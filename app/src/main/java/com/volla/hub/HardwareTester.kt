package com.volla.hub

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sqrt

data class MicStats(val peak: Int, val rms: Int)

data class GpsOutcome(
    val fixed: Boolean,
    val seconds: Double,
    val accuracyMeters: Float,
    val visibleSatellites: Int,
    val usedSatellites: Int
)

/**
 * Nicht-visuelle Testlogik des Hardware-Selbsttests. Dialoge, Vollbildansichten und
 * Berechtigungsabfragen liegen in [HardwareTestActivity].
 *
 * Datenschutz: Mikrofonaufnahmen werden nur im Arbeitsspeicher auf Pegel ausgewertet
 * und nicht gespeichert; vom GPS-Test werden keine Koordinaten übernommen.
 */
class HardwareTester(private val ctx: Context) {

    // ------------------------------------------------------------------
    // Automatische Tests
    // ------------------------------------------------------------------

    fun testFeatures(): HwResult {
        val pm = ctx.packageManager
        val lines = mutableListOf<String>()

        fun add(labelRes: Int, feature: String, enabled: Boolean?) {
            val label = ctx.getString(labelRes)
            lines += when {
                !pm.hasSystemFeature(feature) -> ctx.getString(R.string.hw_feature_absent, label)
                enabled == null -> ctx.getString(R.string.hw_feature_present, label)
                enabled -> ctx.getString(R.string.hw_feature_enabled, label)
                else -> ctx.getString(R.string.hw_feature_disabled, label)
            }
        }

        add(R.string.hw_feat_wifi, PackageManager.FEATURE_WIFI, wifiEnabled())
        add(R.string.hw_feat_bluetooth, PackageManager.FEATURE_BLUETOOTH, bluetoothEnabled())
        add(R.string.hw_feat_ble, PackageManager.FEATURE_BLUETOOTH_LE, null)
        add(R.string.hw_feat_nfc, PackageManager.FEATURE_NFC, nfcEnabled())
        add(R.string.hw_feat_gps, PackageManager.FEATURE_LOCATION_GPS, isGpsEnabled())
        add(R.string.hw_feat_telephony, PackageManager.FEATURE_TELEPHONY, null)
        add(R.string.hw_feat_fingerprint, PackageManager.FEATURE_FINGERPRINT, null)
        add(R.string.hw_feat_usb_host, PackageManager.FEATURE_USB_HOST, null)
        add(R.string.hw_feat_camera_back, PackageManager.FEATURE_CAMERA, null)
        add(R.string.hw_feat_camera_front, PackageManager.FEATURE_CAMERA_FRONT, null)
        add(R.string.hw_feat_flash, PackageManager.FEATURE_CAMERA_FLASH, null)

        return HwResult(HwStatus.PASSED, lines.joinToString("\n"))
    }

    @Suppress("DEPRECATION")
    private fun wifiEnabled(): Boolean? = try {
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled
    } catch (e: SecurityException) {
        null
    }

    private fun bluetoothEnabled(): Boolean? = try {
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled
    } catch (e: SecurityException) {
        null
    }

    private fun nfcEnabled(): Boolean? = try {
        NfcAdapter.getDefaultAdapter(ctx)?.isEnabled
    } catch (e: Exception) {
        null
    }

    fun isGpsEnabled(): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Prüft, ob wichtige Sensoren tatsächlich Messwerte liefern (nicht nur vorhanden sind).
     * Fehlende Sensoren werden aufgelistet, gelten aber nicht als Fehler, da die
     * Ausstattung je Modell unterschiedlich ist.
     */
    suspend fun testSensors(): HwResult {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val checks = listOf(
            Sensor.TYPE_ACCELEROMETER to R.string.hw_sensor_accelerometer,
            Sensor.TYPE_GYROSCOPE to R.string.hw_sensor_gyroscope,
            Sensor.TYPE_MAGNETIC_FIELD to R.string.hw_sensor_magnetometer,
            Sensor.TYPE_PROXIMITY to R.string.hw_sensor_proximity,
            Sensor.TYPE_LIGHT to R.string.hw_sensor_light,
            Sensor.TYPE_PRESSURE to R.string.hw_sensor_pressure
        )
        val lines = mutableListOf<String>()
        var failed = 0
        for ((type, labelRes) in checks) {
            val label = ctx.getString(labelRes)
            val sensor = sm.getDefaultSensor(type)
            if (sensor == null) {
                lines += ctx.getString(R.string.hw_sensor_absent, label)
            } else if (awaitSensorEvent(sm, sensor)) {
                lines += ctx.getString(R.string.hw_sensor_ok, label)
            } else {
                failed++
                lines += ctx.getString(R.string.hw_sensor_no_data, label)
            }
        }
        return HwResult(if (failed > 0) HwStatus.FAILED else HwStatus.PASSED, lines.joinToString("\n"))
    }

    private suspend fun awaitSensorEvent(sm: SensorManager, sensor: Sensor): Boolean =
        withTimeoutOrNull(SENSOR_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        if (cont.isActive) {
                            sm.unregisterListener(this)
                            cont.resume(true)
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
                if (!sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
                    cont.resume(false)
                }
            }
        } ?: false

    fun testBattery(): HwResult {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return HwResult(HwStatus.FAILED, ctx.getString(R.string.hw_battery_unavailable))

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val tempC: Double? = if (rawTemp == Int.MIN_VALUE) null else rawTemp / 10.0
        val millivolt = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

        val healthText = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> ctx.getString(R.string.battery_health_good)
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> ctx.getString(R.string.battery_health_overheat)
            BatteryManager.BATTERY_HEALTH_DEAD -> ctx.getString(R.string.battery_health_dead)
            BatteryManager.BATTERY_HEALTH_UNKNOWN -> ctx.getString(R.string.battery_health_unknown)
            else -> ctx.getString(R.string.hw_battery_health_other)
        }
        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> ctx.getString(R.string.hw_battery_state_charging)
            BatteryManager.BATTERY_STATUS_DISCHARGING -> ctx.getString(R.string.hw_battery_state_discharging)
            BatteryManager.BATTERY_STATUS_FULL -> ctx.getString(R.string.hw_battery_state_full)
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> ctx.getString(R.string.hw_battery_state_not_charging)
            else -> ctx.getString(R.string.hw_battery_state_unknown)
        }

        val lines = mutableListOf<String>()
        if (percent >= 0) lines += ctx.getString(R.string.hw_battery_level, percent)
        lines += ctx.getString(R.string.hw_battery_health, healthText)
        if (tempC != null) lines += ctx.getString(R.string.hw_battery_temp, tempC)
        if (millivolt > 0) lines += ctx.getString(R.string.hw_battery_voltage, millivolt / 1000.0)
        lines += ctx.getString(R.string.hw_battery_status, statusText)
        lines += ctx.getString(R.string.hw_battery_plugged, pluggedText(plugged))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val cycles = intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
            if (cycles >= 0) lines += ctx.getString(R.string.hw_battery_cycles, cycles)
        }

        val critical = health == BatteryManager.BATTERY_HEALTH_OVERHEAT ||
            health == BatteryManager.BATTERY_HEALTH_DEAD ||
            health == BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE ||
            health == BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE ||
            health == BatteryManager.BATTERY_HEALTH_COLD ||
            (tempC != null && tempC > BATTERY_MAX_TEMP_C)
        return HwResult(if (critical) HwStatus.FAILED else HwStatus.PASSED, lines.joinToString("\n"))
    }

    private fun pluggedText(plugged: Int): String {
        val parts = mutableListOf<String>()
        if (plugged and BatteryManager.BATTERY_PLUGGED_AC != 0) parts += ctx.getString(R.string.hw_plug_ac)
        if (plugged and BatteryManager.BATTERY_PLUGGED_USB != 0) parts += ctx.getString(R.string.hw_plug_usb)
        if (plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0) parts += ctx.getString(R.string.hw_plug_wireless)
        return if (parts.isEmpty()) ctx.getString(R.string.hw_plug_none) else parts.joinToString(" + ")
    }

    private fun currentPlugged(): Int =
        ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

    // ------------------------------------------------------------------
    // Ladeanschluss
    // ------------------------------------------------------------------

    /** Wartet, bis ein Ladekabel erkannt wird. Ist bereits eines angeschlossen, endet der Test sofort. */
    suspend fun waitForCharger(timeoutMs: Long): HwResult {
        val already = currentPlugged()
        if (already != 0) {
            return HwResult(
                HwStatus.PASSED,
                ctx.getString(R.string.hw_charger_already, pluggedText(already))
            )
        }
        val plugged = withTimeoutOrNull(timeoutMs) {
            var p = currentPlugged()
            while (p == 0) {
                delay(500)
                p = currentPlugged()
            }
            p
        } ?: 0
        return if (plugged != 0) {
            HwResult(HwStatus.PASSED, ctx.getString(R.string.hw_charger_detected, pluggedText(plugged)))
        } else {
            HwResult(HwStatus.FAILED, ctx.getString(R.string.hw_charger_timeout, (timeoutMs / 1000).toInt()))
        }
    }

    // ------------------------------------------------------------------
    // Lautsprecher
    // ------------------------------------------------------------------

    /** Spielt 1,5 s einen 440-Hz-Ton über den Medienkanal (mit kurzem Ein-/Ausblenden). */
    suspend fun playTestTone() {
        val sampleRate = 44_100
        val samples = sampleRate * 3 / 2
        val fadeSamples = sampleRate * 0.05
        val buffer = ShortArray(samples) { i ->
            val t = i / sampleRate.toDouble()
            val fade = minOf(1.0, minOf(i, samples - i) / fadeSamples)
            (Math.sin(2.0 * Math.PI * 440.0 * t) * 0.6 * fade * Short.MAX_VALUE).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(buffer, 0, buffer.size)
            track.play()
            delay(1_700)
        } finally {
            track.release()
        }
    }

    // ------------------------------------------------------------------
    // Mikrofon
    // ------------------------------------------------------------------

    /**
     * Nimmt [durationMs] Millisekunden auf und liefert Spitzenpegel und Effektivwert
     * (Skala 0..32767). Erfordert RECORD_AUDIO; die Aufnahme wird nicht gespeichert.
     */
    @SuppressLint("MissingPermission")
    suspend fun recordMic(durationMs: Long): MicStats? = withContext(Dispatchers.IO) {
        val rate = 44_100
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return@withContext null
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext null
        }
        val buffer = ShortArray(minBuffer)
        var peak = 0
        var sumSquares = 0.0
        var count = 0L
        try {
            recorder.startRecording()
            val end = SystemClock.elapsedRealtime() + durationMs
            while (SystemClock.elapsedRealtime() < end) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) break
                for (i in 0 until read) {
                    val v = abs(buffer[i].toInt())
                    if (v > peak) peak = v
                    sumSquares += v.toDouble() * v
                }
                count += read
            }
        } finally {
            try {
                recorder.stop()
            } catch (e: IllegalStateException) {
                // war nicht gestartet
            }
            recorder.release()
        }
        if (count == 0L) null else MicStats(peak, sqrt(sumSquares / count).toInt())
    }

    // ------------------------------------------------------------------
    // Vibration
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    fun hasVibrator(): Boolean = vibrator()?.hasVibrator() == true

    @Suppress("DEPRECATION")
    fun vibrate() {
        val v = vibrator() ?: return
        val pattern = longArrayOf(0, 500, 300, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            v.vibrate(pattern, -1)
        }
    }

    // ------------------------------------------------------------------
    // Kamera (Auflistung; die Aufnahme übernimmt die System-Kamera-App)
    // ------------------------------------------------------------------

    fun describeCameras(): String {
        return try {
            val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = manager.cameraIdList
            if (ids.isEmpty()) return ctx.getString(R.string.hw_camera_none)
            ids.joinToString("\n") { id ->
                val ch = manager.getCameraCharacteristics(id)
                val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> ctx.getString(R.string.hw_camera_back)
                    CameraCharacteristics.LENS_FACING_FRONT -> ctx.getString(R.string.hw_camera_front)
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> ctx.getString(R.string.hw_camera_external)
                    else -> ctx.getString(R.string.hw_camera_other)
                }
                val size = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val megapixel = if (size != null) size.width.toDouble() * size.height / 1_000_000.0 else 0.0
                val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                ctx.getString(
                    R.string.hw_camera_line, id, facing, megapixel,
                    ctx.getString(if (flash) R.string.yes else R.string.no)
                )
            }
        } catch (e: Exception) {
            ctx.getString(R.string.hw_camera_list_error)
        }
    }

    // ------------------------------------------------------------------
    // GPS
    // ------------------------------------------------------------------

    /**
     * Wartet auf den ersten GPS-Fix. [onStatus] meldet (sichtbare, genutzte) Satelliten.
     * Erfordert ACCESS_FINE_LOCATION und aktiviertes GPS. Koordinaten werden nicht
     * weitergegeben, nur Zeit bis zum Fix und Genauigkeit.
     */
    @SuppressLint("MissingPermission")
    suspend fun waitForGpsFix(timeoutMs: Long, onStatus: (visible: Int, used: Int) -> Unit): GpsOutcome {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var visible = 0
        var used = 0
        val start = SystemClock.elapsedRealtime()

        val statusCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                visible = status.satelliteCount
                var count = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) count++
                }
                used = count
                onStatus(visible, used)
            }
        }
        lm.registerGnssStatusCallback(statusCallback, Handler(Looper.getMainLooper()))

        var listener: LocationListener? = null
        try {
            val location = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Location> { cont ->
                    val l = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (cont.isActive) cont.resume(location)
                        }

                        // Auf API < 30 sind die folgenden Methoden nicht als default definiert
                        // und müssen implementiert sein, sonst droht ein AbstractMethodError.
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

                        override fun onProviderEnabled(provider: String) {}

                        override fun onProviderDisabled(provider: String) {}
                    }
                    listener = l
                    cont.invokeOnCancellation { lm.removeUpdates(l) }
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, l, Looper.getMainLooper())
                }
            }
            val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
            return if (location != null) {
                GpsOutcome(true, seconds, if (location.hasAccuracy()) location.accuracy else -1f, visible, used)
            } else {
                GpsOutcome(false, seconds, -1f, visible, used)
            }
        } finally {
            listener?.let { lm.removeUpdates(it) }
            lm.unregisterGnssStatusCallback(statusCallback)
        }
    }

    companion object {
        private const val SENSOR_TIMEOUT_MS = 1_500L
        private const val BATTERY_MAX_TEMP_C = 45.0
    }
}
