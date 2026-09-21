package com.volla.hub

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ermittelt die Cache-Größe aller installierten Apps über StorageStatsManager
 * (API 26+). Benötigt die Permission PACKAGE_USAGE_STATS, die der Nutzer manuell
 * über die "Nutzungsdatenzugriff"-Systemeinstellung freigeben muss (App-Ops,
 * kein normaler Runtime-Permission-Dialog).
 *
 * Löschen fremder App-Caches ist auf Android ohne Root NICHT möglich – das
 * System erlaubt das nur der App selbst oder dem Nutzer über die Einstellungen.
 * Diese Klasse liefert daher ausschließlich Lesezugriff für die Anzeige.
 */
class AppCacheScanner(private val context: Context) {

    /** Prüft, ob die PACKAGE_USAGE_STATS-Berechtigung (App-Ops) erteilt wurde. */
    fun hasUsageStatsPermission(): Boolean {
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOpsManager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /**
     * Liest Cache-Größe je installierter App aus. Läuft auf Dispatchers.IO, da
     * queryStatsForPackage pro App blockierend ist und bei vielen installierten
     * Apps spürbar Zeit braucht.
     */
    suspend fun scanAppCaches(): List<AppCacheInfo> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@withContext emptyList()

        val packageManager = context.packageManager
        val storageStatsManager =
            context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val uuid = try {
            storageManager.getUuidForPath(context.filesDir)
        } catch (e: Exception) {
            StorageManager.UUID_DEFAULT
        }

        val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        val results = mutableListOf<AppCacheInfo>()

        for (appInfo in installedApps) {
            try {
                val stats = storageStatsManager.queryStatsForPackage(
                    uuid, appInfo.packageName, Process.myUserHandle()
                )
                if (stats.cacheBytes <= 0L) continue

                val isSystemApp =
                    (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                results.add(
                    AppCacheInfo(
                        packageName = appInfo.packageName,
                        label = packageManager.getApplicationLabel(appInfo).toString(),
                        icon = try {
                            packageManager.getApplicationIcon(appInfo)
                        } catch (e: Exception) {
                            null
                        },
                        cacheBytes = stats.cacheBytes,
                        isSystemApp = isSystemApp
                    )
                )
            } catch (e: PackageManager.NameNotFoundException) {
                // App wurde während der Iteration deinstalliert – überspringen
            } catch (e: SecurityException) {
                // Fehlende Berechtigung für dieses spezifische Paket – überspringen
            } catch (e: Exception) {
                // Sonstige, pro-App auftretende Fehler dürfen den gesamten Scan nicht abbrechen
            }
        }

        results.sortedByDescending { it.cacheBytes }
    }

    /** Summe aller Cache-Größen (für eine kompakte Übersichtszeile). */
    fun totalCacheBytes(caches: List<AppCacheInfo>): Long = caches.sumOf { it.cacheBytes }
}
