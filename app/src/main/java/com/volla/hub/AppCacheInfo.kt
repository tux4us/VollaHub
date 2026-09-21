package com.volla.hub

import android.graphics.drawable.Drawable

/**
 * Cache-Information einer installierten App, ermittelt über StorageStatsManager.
 *
 * Wichtig: Android erlaubt seit API 30 (Scoped Storage) keiner regulären App mehr,
 * den Cache EINER ANDEREN App direkt zu löschen (weder mit MANAGE_EXTERNAL_STORAGE
 * noch mit Root-losen Mitteln). Diese Klasse dient daher nur der Anzeige/Sortierung;
 * das eigentliche Leeren erfolgt durch den Nutzer selbst über die System-App-Info-Seite,
 * zu der VollaHub gezielt verlinkt (siehe StorageAnalysisActivity.openAppInfoSettings).
 */
data class AppCacheInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val cacheBytes: Long,
    val isSystemApp: Boolean
)
