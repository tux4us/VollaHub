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
enum class StorageCategory(val displayName: String, val colorHex: String) {
    IMAGES("Bilder", "#E57373"),
    VIDEOS("Videos", "#BA68C8"),
    AUDIO("Audio", "#64B5F6"),
    DOCUMENTS("Dokumente", "#4DB6AC"),
    APPS("Apps & Daten", "#FFB74D"),
    DOWNLOADS("Downloads", "#81C784"),
    SYSTEM("System", "#90A4AE"),
    CACHE("Cache", "#A1887F"),
    OTHER("Sonstiges", "#B0BEC5"),
    FREE("Frei", "#37474F")
}
