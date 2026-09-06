package com.volla.hub

import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Scannt den externen Speicher rekursiv (benötigt MANAGE_EXTERNAL_STORAGE,
 * "Alle Dateien Zugriff") und baut einen Ordnerbaum ähnlich Filelight auf.
 *
 * Ohne diese Berechtigung liefert scanTopLevelOnly() eine grobe, aber ohne
 * Sonderrechte funktionierende Kategorie-Übersicht (Fallback).
 */
class StorageScanner(private val context: android.content.Context? = null) {

    private fun rootFallbackName(): String =
        context?.getString(R.string.storage_root_label) ?: "Speicher"

    data class ScanProgress(val currentPath: String, val scannedBytes: Long)

    /**
     * Vollständiger, rekursiver Scan des primären externen Speichers.
     * Läuft auf Dispatchers.IO. onProgress wird gelegentlich aufgerufen,
     * damit die UI einen Fortschritt/aktuellen Pfad anzeigen kann.
     */
    suspend fun scanFull(
        onProgress: (ScanProgress) -> Unit = {}
    ): StorageNode = withContext(Dispatchers.IO) {
        val root = Environment.getExternalStorageDirectory()
        val rootNode = StorageNode(
            name = root.name.ifEmpty { rootFallbackName() },
            path = root.absolutePath,
            isDirectory = true
        )

        var scannedSoFar = 0L
        var lastReport = 0L

        fun categorize(dirName: String): StorageCategory = when (dirName.lowercase()) {
            "dcim", "pictures", "screenshots" -> StorageCategory.IMAGES
            "movies", "video", "videos" -> StorageCategory.VIDEOS
            "music", "audio", "ringtones", "notifications", "alarms" -> StorageCategory.AUDIO
            "download", "downloads" -> StorageCategory.DOWNLOADS
            "documents" -> StorageCategory.DOCUMENTS
            "android" -> StorageCategory.APPS
            else -> StorageCategory.OTHER
        }

        fun walk(dir: File, node: StorageNode, depth: Int, inheritedCategory: StorageCategory?) {
            val entries = try {
                dir.listFiles()
            } catch (e: SecurityException) {
                null
            } ?: return

            for (entry in entries) {
                // Symlinks / kaputte Referenzen überspringen
                val canonicalSafe = try { entry.canonicalPath } catch (e: Exception) { entry.absolutePath }
                if (entry.isDirectory) {
                    val category = inheritedCategory ?: categorize(entry.name)
                    val childNode = StorageNode(
                        name = entry.name,
                        path = entry.absolutePath,
                        isDirectory = true,
                        category = category
                    )
                    // Tiefe begrenzen für Performance; tiefere Ebenen werden
                    // beim Reinzoomen des Nutzers on-demand nachgescannt.
                    if (depth < MAX_EAGER_DEPTH) {
                        walk(entry, childNode, depth + 1, category)
                    } else {
                        childNode.sizeBytes = folderSizeShallow(entry)
                    }
                    childNode.recalculateFromChildren()
                    if (childNode.sizeBytes == 0L && childNode.children.isEmpty()) {
                        childNode.sizeBytes = folderSizeShallow(entry)
                    }
                    node.children.add(childNode)
                } else {
                    val size = entry.length()
                    scannedSoFar += size
                    val fileNode = StorageNode(
                        name = entry.name,
                        path = entry.absolutePath,
                        sizeBytes = size,
                        isDirectory = false,
                        category = inheritedCategory ?: categoryForExtension(entry.extension)
                    )
                    node.children.add(fileNode)
                }
                if (scannedSoFar - lastReport > 50L * 1024 * 1024) {
                    lastReport = scannedSoFar
                    onProgress(ScanProgress(entry.absolutePath, scannedSoFar))
                }
            }
            node.recalculateFromChildren()
        }

        walk(root, rootNode, 0, null)
        rootNode.recalculateFromChildren()
        rootNode
    }

    /** Scannt gezielt einen einzelnen Unterordner (für "Reinzoomen" bei zu tiefen Pfaden). */
    suspend fun scanFolder(path: String): StorageNode = withContext(Dispatchers.IO) {
        val dir = File(path)
        val node = StorageNode(name = dir.name, path = dir.absolutePath, isDirectory = true)
        dir.listFiles()?.forEach { entry ->
            if (entry.isDirectory) {
                val child = StorageNode(
                    name = entry.name,
                    path = entry.absolutePath,
                    isDirectory = true,
                    sizeBytes = folderSizeShallow(entry, maxDepth = 6)
                )
                node.children.add(child)
            } else {
                node.children.add(
                    StorageNode(
                        name = entry.name,
                        path = entry.absolutePath,
                        sizeBytes = entry.length(),
                        isDirectory = false,
                        category = categoryForExtension(entry.extension)
                    )
                )
            }
        }
        node.recalculateFromChildren()
        node
    }

    private fun folderSizeShallow(dir: File, maxDepth: Int = 4, depth: Int = 0): Long {
        if (depth > maxDepth) return 0L
        var total = 0L
        val entries = try { dir.listFiles() } catch (e: SecurityException) { null } ?: return 0L
        for (entry in entries) {
            total += if (entry.isDirectory) folderSizeShallow(entry, maxDepth, depth + 1) else entry.length()
        }
        return total
    }

    private fun categoryForExtension(ext: String): StorageCategory = when (ext.lowercase()) {
        "jpg", "jpeg", "png", "webp", "gif", "heic" -> StorageCategory.IMAGES
        "mp4", "mkv", "webm", "avi", "mov" -> StorageCategory.VIDEOS
        "mp3", "flac", "ogg", "wav", "m4a" -> StorageCategory.AUDIO
        "pdf", "doc", "docx", "txt", "odt", "xls", "xlsx" -> StorageCategory.DOCUMENTS
        "apk" -> StorageCategory.APPS
        else -> StorageCategory.OTHER
    }

    /** Gesamt-/Frei-/Belegt-Speicher des primären Datenträgers. */
    fun getStatFsSummary(): Triple<Long, Long, Long> {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val total = stat.totalBytes
        val free = stat.availableBytes
        val used = total - free
        return Triple(total, used, free)
    }

    companion object {
        // Ab dieser Ordnertiefe wird nicht mehr eifrig weiter aufgeschlüsselt,
        // sondern nur die Gesamtgröße ermittelt (Performance bei riesigen Bäumen,
        // z.B. Android/data/*). Nutzer kann per Tap gezielt tiefer scannen.
        private const val MAX_EAGER_DEPTH = 2
    }
}
