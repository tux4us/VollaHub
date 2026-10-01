package com.volla.hub

import android.content.Context
import androidx.annotation.StringRes
import java.text.DateFormat
import java.util.Date

enum class HwStatus { PENDING, PASSED, FAILED, SKIPPED }

data class HwResult(val status: HwStatus, val detail: String = "")

/**
 * Alle Einzeltests des Hardware-Selbsttests. Reihenfolge = Anzeigereihenfolge:
 * zuerst die automatischen Tests, danach die Tests mit Nutzerinteraktion.
 */
enum class HwTestId(
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val automatic: Boolean
) {
    FEATURES(R.string.hw_features_title, R.string.hw_features_desc, true),
    SENSORS(R.string.hw_sensors_title, R.string.hw_sensors_desc, true),
    BATTERY(R.string.hw_battery_title, R.string.hw_battery_desc, true),
    TOUCH(R.string.hw_touch_title, R.string.hw_touch_desc, false),
    DISPLAY(R.string.hw_display_title, R.string.hw_display_desc, false),
    SPEAKER(R.string.hw_speaker_title, R.string.hw_speaker_desc, false),
    MICROPHONE(R.string.hw_mic_title, R.string.hw_mic_desc, false),
    VIBRATION(R.string.hw_vibration_title, R.string.hw_vibration_desc, false),
    VOLUME_KEYS(R.string.hw_keys_title, R.string.hw_keys_desc, false),
    CAMERA(R.string.hw_camera_title, R.string.hw_camera_desc, false),
    CHARGER(R.string.hw_charger_title, R.string.hw_charger_desc, false),
    GPS(R.string.hw_gps_title, R.string.hw_gps_desc, false)
}

/**
 * Speichert die zuletzt ermittelten Ergebnisse lokal (SharedPreferences), damit der
 * Geräte-Report sie als Abschnitt übernehmen kann. Es werden keine Standortdaten,
 * Aufnahmen oder Kennungen gespeichert, nur Status und ein kurzer Detailtext.
 */
class HardwareTestStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("hardware_tests", Context.MODE_PRIVATE)

    private fun key(id: HwTestId, field: String) = "${id.name}_$field"

    fun get(id: HwTestId): HwResult {
        val name = prefs.getString(key(id, "status"), null) ?: return HwResult(HwStatus.PENDING)
        val status = HwStatus.values().firstOrNull { it.name == name } ?: HwStatus.PENDING
        return HwResult(status, prefs.getString(key(id, "detail"), "") ?: "")
    }

    fun put(id: HwTestId, result: HwResult) {
        prefs.edit()
            .putString(key(id, "status"), result.status.name)
            .putString(key(id, "detail"), result.detail)
            .putLong("last_run", System.currentTimeMillis())
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun lastRun(): Long = prefs.getLong("last_run", 0L)

    /**
     * Textblock für den Geräte-Report im Stil der übrigen Abschnitte ("--- NAME ---").
     * Liefert null, wenn noch kein Test ausgeführt wurde. Nicht ausgeführte Tests
     * werden weggelassen.
     */
    fun formatReportBlock(context: Context): String? {
        val results = HwTestId.values()
            .map { it to get(it) }
            .filter { it.second.status != HwStatus.PENDING }
        if (results.isEmpty()) return null

        val timestamp = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(lastRun()))
        val sb = StringBuilder()
        sb.append(context.getString(R.string.hw_report_section)).append('\n')
        sb.append(context.getString(R.string.hw_report_timestamp, timestamp)).append('\n')
        for ((id, result) in results) {
            val tag = when (result.status) {
                HwStatus.PASSED -> "[OK]"
                HwStatus.FAILED -> "[FAIL]"
                HwStatus.SKIPPED -> "[SKIP]"
                HwStatus.PENDING -> "[-]"
            }
            sb.append(tag).append(' ').append(context.getString(id.titleRes))
            if (result.detail.isNotBlank()) {
                sb.append(": ").append(result.detail.replace("\n", "; "))
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }
}
