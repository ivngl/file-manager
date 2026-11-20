package com.example.filemanager.ui

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.filemanager.*
import java.io.File
import kotlinx.coroutines.launch

private sealed interface DialogState {
    data object NewFolder : DialogState
    data class Rename(val item: FileItem) : DialogState
    data class Delete(val items: List<FileItem>) : DialogState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileScreen(viewModel: FileManagerViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<DialogState?>(null) }

    LaunchedEffect(Unit) {
        if (state.allFiles.isEmpty() && state.error == null) viewModel.refresh()
    }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    BackHandler(enabled = state.selectionMode || state.searchActive || state.canGoUp) {
        when {
            state.selectionMode -> viewModel.clearSelection()
            state.searchActive -> viewModel.setSearchActive(false)
            else -> viewModel.navigateUp()
        }
    }

    val selectedItems = state.allFiles.filter { it.path in state.selected }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (state.selectionMode) {
                SelectionTopBar(
                    count = state.selected.size,
                    single = state.selected.size == 1,
                    onClose = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                    onCopy = { viewModel.copySelected(cut = false) },
                    onCut = { viewModel.copySelected(cut = true) },
                    onDelete = { dialog = DialogState.Delete(selectedItems) },
                    onRename = { selectedItems.firstOrNull()?.let { dialog = DialogState.Rename(it) } }
                )
            } else {
                BrowseTopBar(
                    state = state,
                    onUp = viewModel::navigateUp,
                    onToggleSearch = { viewModel.setSearchActive(!state.searchActive) },
                    onQueryChange = viewModel::setSearchQuery,
                    onSort = viewModel::setSort,
                    onToggleHidden = viewModel::toggleHidden,
                    onRefresh = viewModel::refresh
                )
            }
        },
        floatingActionButton = {
            if (!state.selectionMode && state.error == null) {
                ExtendedFloatingActionButton(
                    onClick = { dialog = DialogState.NewFolder },
                    icon = { Icon(Icons.Filled.CreateNewFolder, null) },
                    text = { Text("New folder") }
                )
            }
        },
        bottomBar = {
            state.clipboard?.let { clip ->
                BottomAppBar(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                    Icon(
                        if (clip.isCut) Icons.Filled.ContentCut else Icons.Filled.ContentCopy,
                        null, Modifier.padding(start = 16.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${clip.files.size} item(s) to ${if (clip.isCut) "move" else "copy"}",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = viewModel::clearClipboard) { Text("Cancel") }
                    Button(onClick = viewModel::paste, modifier = Modifier.padding(end = 16.dp)) {
                        Text("Paste here")
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Breadcrumbs(state.currentDir, viewModel::navigateTo)
            HorizontalDivider()
            Text(
                "${state.visibleFiles.size} items • ${FileUtils.formatSize(state.currentDir.freeSpace)} free",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> MessageState(
                    Icons.Filled.ErrorOutline,
                    state.error ?: "Failed to load folder",
                    showRetry = true, onRetry = viewModel::refresh
                )
                state.visibleFiles.isEmpty() -> MessageState(
                    Icons.Filled.FolderOpen,
                    if (state.searchActive) "No matching files" else "This folder is empty"
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    items(state.visibleFiles, key = { it.path }) { item ->
                        FileRow(
                            item = item,
                            selected = item.path in state.selected,
                            selectionMode = state.selectionMode,
                            onClick = {
                                when {
                                    state.selectionMode -> viewModel.toggleSelection(item.path)
                                    item.isDirectory -> viewModel.navigateTo(item.file)
                                    else -> openFile(context, item.file) { msg ->
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    }
                                }
                            },
                            onLongClick = { viewModel.toggleSelection(item.path) }
                        )
                    }
                }
            }
        }
    }

    // ---------- Dialogs ----------
    when (val d = dialog) {
        DialogState.NewFolder -> NameDialog(
            title = "New folder", initial = "", label = "Folder name",
            onConfirm = { viewModel.createFolder(it); dialog = null },
            onDismiss = { dialog = null }
        )
        is DialogState.Rename -> NameDialog(
            title = "Rename", initial = d.item.name, label = "New name",
            onConfirm = { viewModel.rename(d.item, it); dialog = null },
            onDismiss = { dialog = null }
        )
        is DialogState.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text("Delete ${d.items.size} item(s)?") },
            text = {
                val preview = d.items.take(5).joinToString("\n") { it.name }
                Text(preview + (if (d.items.size > 5) "\n…" else "") + "\n\nThis cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(d.items); dialog = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } }
        )
        null -> Unit
    }
}

// ---------- Top bars ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseTopBar(
    state: UiState,
    onUp: () -> Unit,
    onToggleSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSort: (SortOrder) -> Unit,
    onToggleHidden: () -> Unit,
    onRefresh: () -> Unit
) {
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val title =
        if (state.currentDir.absolutePath == Environment.getExternalStorageDirectory().absolutePath)
            "Internal storage"
        else state.currentDir.name.ifBlank { "/" }

    TopAppBar(
        title = {
            if (state.searchActive) {
                TextField(
                    value = state.searchQuery,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Search this folder…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
            } else {
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        state.currentDir.absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        navigationIcon = {
            if (state.canGoUp) IconButton(onClick = onUp) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
            }
        },
        actions = {
            IconButton(onClick = onToggleSearch) {
                Icon(if (state.searchActive) Icons.Filled.Close else Icons.Filled.Search, "Search")
            }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Filled.Sort, "Sort") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            text = { Text(order.label) },
                            trailingIcon = { if (state.sort == order) Icon(Icons.Filled.Check, null) },
                            onClick = { onSort(order); sortMenu = false }
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { moreMenu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Show hidden files") },
                        trailingIcon = { if (state.showHidden) Icon(Icons.Filled.Check, null) },
                        onClick = { onToggleHidden(); moreMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Refresh") },
                        onClick = { onRefresh(); moreMenu = false }
                    )
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int, single: Boolean,
    onClose: () -> Unit, onSelectAll: () -> Unit,
    onCopy: () -> Unit, onCut: () -> Unit, onDelete: () -> Unit, onRename: () -> Unit
) {
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cancel") } },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Filled.SelectAll, "Select all") }
            if (single) IconButton(onClick = onRename) { Icon(Icons.Filled.Edit, "Rename") }
            IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, "Copy") }
            IconButton(onClick = onCut) { Icon(Icons.Filled.ContentCut, "Cut") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    )
}

// ---------- List items & navigation ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: FileItem, selected: Boolean, selectionMode: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit
) {
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        label = "selection"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            FileUtils.iconFor(item), null,
            modifier = Modifier.size(36.dp),
            tint = if (item.isDirectory) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (item.isDirectory) FileUtils.formatDate(item.lastModified)
                else "${FileUtils.formatSize(item.size)} • ${FileUtils.formatDate(item.lastModified)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when {
            selectionMode -> Checkbox(checked = selected, onCheckedChange = { onClick() })
            item.isDirectory -> Icon(Icons.Filled.ChevronRight, null,
                tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun Breadcrumbs(dir: File, onNavigate: (File) -> Unit) {
    val segments = remember(dir) { generateSequence(dir) { it.parentFile }.toList().asReversed() }
    val listState = rememberLazyListState()
    LaunchedEffect(dir) { listState.animateScrollToItem(segments.lastIndex) }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        itemsIndexed(segments) { index, f ->
            if (index > 0) Icon(Icons.Filled.ChevronRight, null,
                Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
            TextButton(
                onClick = { onNavigate(f) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    when {
                        f.absolutePath == Environment.getExternalStorageDirectory().absolutePath -> "Internal"
                        f.parentFile == null -> "/"
                        else -> f.name
                    },
                    style = MaterialTheme.typography.labelLarge, maxLines = 1
                )
            }
        }
    }
}

// ---------- States & dialogs ----------

@Composable
private fun MessageState(
    icon: ImageVector, message: String,
    showRetry: Boolean = false, onRetry: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text(message, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showRetry) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun NameDialog(
    title: String, initial: String, label: String,
    onConfirm: (String) -> Unit, onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val valid = text.isNotBlank() && !text.contains('/') && text.trim() !in setOf(".", "..")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text(label) }, singleLine = true,
                isError = text.isNotEmpty() && !valid,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(text.trim()) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------- Open file in external app ----------

private fun openFile(context: Context, file: File, onError: (String) -> Unit) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, FileUtils.mimeType(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open with"))
    } catch (e: Exception) {
        onError("No app found to open \"${file.name}\"")
    }
}