package com.volla.hub

/**
 * Repräsentiert einen Knoten im Speicherbaum (Ordner oder Datei-Kategorie).
 * Wird sowohl vom Sunburst-Diagramm als auch von den Detail-Cards verwendet.
 */
data class StorageNode(
    val name: String,
    val path: String,
    var sizeBytes: Long = 0L,
    val isDirectory: Boolean = true,
    val children: MutableList<StorageNode> = mutableListOf(),
    val category: StorageCategory = StorageCategory.OTHER
) {
    /** Liefert die Kinder absteigend nach Größe sortiert. */
    fun sortedChildren(): List<StorageNode> = children.sortedByDescending { it.sizeBytes }

    /** Summiert die Größe aus den Kindern (für Zwischenknoten während des Scans). */
    fun recalculateFromChildren() {
        if (children.isNotEmpty()) {
            sizeBytes = children.sumOf { it.sizeBytes }
        }
    }
}

/**
 * Grobe Kategorisierung für Einfärbung & Icons, angelehnt an die Android
 * MediaStore-Kategorien plus Apps/System, da ohne Root kein 1:1-Filelight
 * möglich ist, aber mit MANAGE_EXTERNAL_STORAGE ein echter Ordnerbaum.
 */
enum class StorageCategory(val displayNameRes: Int, val colorHex: String) {
    IMAGES(R.string.storage_category_images, "#E57373"),
    VIDEOS(R.string.storage_category_videos, "#BA68C8"),
    AUDIO(R.string.storage_category_audio, "#64B5F6"),
    DOCUMENTS(R.string.storage_category_documents, "#4DB6AC"),
    APPS(R.string.storage_category_apps, "#FFB74D"),
    DOWNLOADS(R.string.storage_category_downloads, "#81C784"),
    SYSTEM(R.string.storage_category_system, "#90A4AE"),
    CACHE(R.string.storage_category_cache, "#A1887F"),
    OTHER(R.string.storage_category_other, "#B0BEC5"),
    FREE(R.string.storage_category_free, "#37474F")
}
