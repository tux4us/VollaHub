package com.volla.hub

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog

/**
 * Zeigt den "Appinfo"-Dialog mit Versionsnummer, Entwicklerhinweis und Link zum
 * GitHub-Repository. Wurde zuvor in sechs Activities identisch dupliziert
 * (ChatBotActivity, ContentActivity, DeviceReportActivity, MainActivity,
 * ReportHistoryActivity, StartActivity) und hier zentralisiert.
 */
object AppInfoDialog {
    fun show(context: Context) {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "3.5"
        }
        val builder = AlertDialog.Builder(context)
        builder.setTitle(R.string.menu_appinfo)
        builder.setMessage(context.getString(R.string.appinfo_message, version))
        builder.setPositiveButton(R.string.appinfo_open_github) { _, _ ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tux4us/VollaHubAndroidApp"))
            context.startActivity(intent)
        }
        builder.setNegativeButton(R.string.close, null)
        builder.show()
    }
}
