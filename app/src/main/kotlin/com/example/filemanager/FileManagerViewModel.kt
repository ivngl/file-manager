package com.example.filemanager

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FileItem(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val isHidden: Boolean,
    val size: Long,
    val lastModified: Long
) {
    val file: File get() = File(path)
}

enum class SortOrder(val label: String) {
    NAME_ASC("Name (A–Z)"), NAME_DESC("Name (Z–A)"),
    SIZE_ASC("Size (smallest)"), SIZE_DESC("Size (largest)"),
    DATE_ASC("Date (oldest)"), DATE_DESC("Date (newest)");

    val comparator: Comparator<FileItem>
        get() {
            val byField = when (this) {
                NAME_ASC -> compareBy<FileItem> { it.name.lowercase() }
                NAME_DESC -> compareByDescending<FileItem> { it.name.lowercase() }
                SIZE_ASC -> compareBy<FileItem> { it.size }
                SIZE_DESC -> compareByDescending<FileItem> { it.size }
                DATE_ASC -> compareBy<FileItem> { it.lastModified }
                DATE_DESC -> compareByDescending<FileItem> { it.lastModified }
            }
            return compareByDescending<FileItem> { it.isDirectory }.then(byField)
        }
}

data class FileClipboard(val files: List<File>, val isCut: Boolean)

data class UiState(
    val currentDir: File = Environment.getExternalStorageDirectory(),
    val allFiles: List<FileItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selected: Set<String> = emptySet(),
    val clipboard: FileClipboard? = null,
    val sort: SortOrder = SortOrder.NAME_ASC,
    val showHidden: Boolean = false,
    val searchActive: Boolean = false,
    val searchQuery: String = ""
) {
    val visibleFiles: List<FileItem>
        get() = allFiles
            .filter { showHidden || !it.isHidden }
            .filter { !searchActive || searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }
            .sortedWith(sort.comparator)

    val selectionMode: Boolean get() = selected.isNotEmpty()
    val canGoUp: Boolean get() = currentDir.parentFile != null
}

class FileManagerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val state get() = _uiState.value
    private fun notify(msg: String) { _messages.tryEmit(msg) }

    // ---------- Navigation ----------

    fun loadDirectory(path: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, error = null, selected = emptySet(),
                    searchActive = false, searchQuery = "")
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(path)
                    if (!dir.isDirectory) error("Not a folder: $path")
                    val children = dir.listFiles() ?: error("Access denied: $path")
                    dir to children.map { f ->
                        FileItem(
                            path = f.absolutePath,
                            name = f.name,
                            isDirectory = f.isDirectory,
                            isHidden = f.isHidden,
                            size = if (f.isDirectory) 0L else f.length(),
                            lastModified = f.lastModified()
                        )
                    }
                }
            }
            result.onSuccess { (dir, items) ->
                _uiState.update { it.copy(currentDir = dir, allFiles = items, isLoading = false) }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(currentDir = File(path), allFiles = emptyList(),
                        isLoading = false, error = e.message ?: "Failed to load folder")
                }
            }
        }
    }

    fun refresh() { loadDirectory(state.currentDir.absolutePath) }
    fun navigateUp() { state.currentDir.parentFile?.let { loadDirectory(it.absolutePath) } }
    fun navigateTo(dir: File) { loadDirectory(dir.absolutePath) }

    // ---------- Selection & view options ----------

    fun toggleSelection(path: String) {
        _uiState.update {
            it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path)
        }
    }
    fun clearSelection() = _uiState.update { it.copy(selected = emptySet()) }
    fun selectAll() = _uiState.update { it.copy(selected = it.visibleFiles.map { f -> f.path }.toSet()) }
    fun setSort(sort: SortOrder) = _uiState.update { it.copy(sort = sort) }
    fun toggleHidden() = _uiState.update { it.copy(showHidden = !it.showHidden) }
    fun setSearchActive(active: Boolean) =
        _uiState.update { it.copy(searchActive = active, searchQuery = if (active) it.searchQuery else "") }
    fun setSearchQuery(q: String) = _uiState.update { it.copy(searchQuery = q) }

    // ---------- File operations ----------

    fun createFolder(name: String) = ioOp("Folder \"$name\" created") {
        val target = File(state.currentDir, name)
        if (target.exists()) error(" \"$name\" already exists")
        if (!target.mkdirs()) error("Could not create folder")
    }

    fun rename(item: FileItem, newName: String) = ioOp("Renamed to \"$newName\"") {
        val target = File(item.file.parentFile, newName)
        if (target.exists()) error("\"$newName\" already exists")
        if (!item.file.renameTo(target)) error("Rename failed")
    }

    fun delete(items: List<FileItem>) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { items.count { it.file.deleteRecursively() } }
            notify(if (ok == items.size) "Deleted $ok item(s)"
                   else "Deleted $ok of ${items.size} items (some failed)")
            clearSelection()
            refresh()
        }
    }

    fun copySelected(cut: Boolean) {
        val files = state.selected.map { File(it) }
        if (files.isEmpty()) return
        _uiState.update { it.copy(clipboard = FileClipboard(files, cut), selected = emptySet()) }
        notify("${files.size} item(s) ready to ${if (cut) "move" else "copy"} — navigate and paste")
    }

    fun clearClipboard() = _uiState.update { it.copy(clipboard = null) }

    fun paste() {
        val clip = state.clipboard ?: return
        val dest = state.currentDir
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val (done, failed) = withContext(Dispatchers.IO) {
                var done = 0; var failed = 0
                clip.files.forEach { src ->
                    try {
                        if (!src.exists()) { failed++; return@forEach }
                        // Never copy/move a folder into itself or its own subtree
                        if (src.isDirectory &&
                            (dest == src || dest.absolutePath.startsWith(src.absolutePath + File.separator))) {
                            failed++; return@forEach
                        }
                        // Cutting into the same folder is a no-op
                        if (clip.isCut && src.parentFile?.absolutePath == dest.absolutePath) {
                            done++; return@forEach
                        }
                        val target = uniqueTarget(dest, src)
                        if (clip.isCut) {
                            if (!src.renameTo(target)) {          // fast path fails across filesystems
                                src.copyRecursively(target)
                                src.deleteRecursively()
                            }
                        } else {
                            src.copyRecursively(target)
                        }
                        done++
                    } catch (e: Exception) { failed++ }
                }
                done to failed
            }
            _uiState.update { it.copy(clipboard = if (clip.isCut) null else it.clipboard) }
            notify("Pasted $done item(s)" + if (failed > 0) ", $failed failed" else "")
            refresh()
        }
    }

    /** "file.txt" -> "file (1).txt" -> "file (2).txt" ... on name conflicts */
    private fun uniqueTarget(dir: File, src: File): File {
        var target = File(dir, src.name)
        if (!target.exists()) return target
        val base = if (src.isFile) src.nameWithoutExtension else src.name
        val ext = if (src.isFile && src.extension.isNotEmpty()) ".${src.extension}" else ""
        var i = 1
        while (target.exists()) { target = File(dir, "$base ($i)$ext"); i++ }
        return target
    }

    private fun ioOp(successMsg: String, block: () -> Unit) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { runCatching(block).exceptionOrNull() }
            if (error == null) { notify(successMsg); clearSelection(); refresh() }
            else notify(error.message ?: "Operation failed")
        }
    }
}