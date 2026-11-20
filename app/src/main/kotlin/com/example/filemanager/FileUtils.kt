package com.example.filemanager

import android.webkit.MimeTypeMap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtils {

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
    }

    private val dateFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
    fun formatDate(millis: Long): String = dateFormat.format(Date(millis))

    fun mimeType(file: File): String {
        val ext = file.extension.lowercase(Locale.ROOT)
        return when (ext) {
            "apk" -> "application/vnd.android.package-archive"
            "md", "kt", "java", "py", "js", "ts", "json", "xml", "yml", "yaml",
            "csv", "log", "sh", "html", "css" -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        }
    }

    fun iconFor(item: FileItem): ImageVector {
        if (item.isDirectory) return Icons.Filled.Folder
        return when (File(item.path).extension.lowercase(Locale.ROOT)) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg" -> Icons.Filled.Image
            "mp4", "mkv", "webm", "avi", "mov", "3gp" -> Icons.Filled.Movie
            "mp3", "wav", "ogg", "flac", "m4a", "aac" -> Icons.Filled.MusicNote
            "pdf" -> Icons.Filled.PictureAsPdf
            "zip", "rar", "7z", "tar", "gz" -> Icons.Filled.FolderZip
            "apk" -> Icons.Filled.Android
            "txt", "md", "log", "json", "xml", "csv", "doc", "docx" -> Icons.Filled.Description
            "kt", "java", "py", "js", "ts", "html", "css", "c", "cpp", "sh" -> Icons.Filled.Code
            else -> Icons.Outlined.InsertDriveFile
        }
    }
}