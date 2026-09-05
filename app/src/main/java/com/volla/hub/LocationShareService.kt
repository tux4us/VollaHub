package com.volla.hub

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * LocationShareService – Foreground Service für gegenseitige Standortfreigabe via WebDAV.
 */
class LocationShareService : Service(), LocationListener {

    companion object {
        private const val TAG                = "LocationShareService"
        private const val NOTIFICATION_ID    = 2003
        private const val CHANNEL_ID         = "volla_location_share_channel"
        const  val LOCATION_FILE_PREFIX      = "location_"
        const  val LOCATION_FILE_SUFFIX      = ".json"
        const  val REMOTE_DIR                = "VollaHub"
        private const val UPLOAD_INTERVAL_MS = 60_000L
        private const val GPS_MIN_TIME_MS    = 30_000L
        private const val GPS_MIN_DISTANCE_M = 0f
        const  val PREF_LOCATION_LABEL       = "volla_location_share_label"

        var isServiceRunning = false

        fun getDeviceId(context: Context): String {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
            val model = Build.MODEL.replace(" ", "_").take(20)
            return "${model}_${androidId.take(8)}"
        }

        fun remoteFileName(context: Context): String =
            "$LOCATION_FILE_PREFIX${getDeviceId(context)}$LOCATION_FILE_SUFFIX"
    }

    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    @Volatile private var lastLocation: Location? = null
    private val gson = Gson()
    private val prefs by lazy { WebDavPreferences(this) }

    override fun onCreate() {
        super.onCreate()
        locationManager    = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification("Standortfreigabe aktiv"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Standortfreigabe aktiv"))
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Standortberechtigung fehlt")
            stopSelf()
            return START_NOT_STICKY
        }

        if (isServiceRunning) return START_STICKY

        isServiceRunning = true
        startGpsUpdates()
        startUploadLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        isServiceRunning = false
        locationManager.removeUpdates(this)
        serviceScope.launch { deleteOwnLocationFile() }
        Thread.sleep(2000)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startGpsUpdates() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, GPS_MIN_TIME_MS, GPS_MIN_DISTANCE_M, this
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "GPS-Berechtigung entzogen", e)
            stopSelf()
        }
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
    }

    @Deprecated("Deprecated in API 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun startUploadLoop() {
        serviceScope.launch {
            // Erstes Mal kurz warten, bis GPS evtl. einen Fix hat, dann sofort erster Upload
            delay(5000) 
            while (isActive) {
                val loc = lastLocation
                if (loc != null) {
                    uploadLocation(loc)
                } else {
                    Log.d(TAG, "Kein Standort zum Hochladen verfügbar")
                }
                delay(UPLOAD_INTERVAL_MS)
            }
        }
    }

    private fun uploadLocation(location: Location) {
        val config = prefs.loadConfig() ?: return
        val label = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString(PREF_LOCATION_LABEL, Build.MODEL) ?: Build.MODEL

        val payload = LocationPayload(
            deviceId     = getDeviceId(this),
            label        = label,
            lat          = location.latitude,
            lon          = location.longitude,
            accuracy     = if (location.hasAccuracy()) location.accuracy else null,
            timestamp    = System.currentTimeMillis(),
            batteryLevel = getBatteryLevel()
        )

        val json     = gson.toJson(payload)
        val tempFile = File(cacheDir, remoteFileName(this))
        tempFile.writeText(json)

        val client     = WebDavClient(config.serverUrl, config.username, config.password)
        val remotePath = "$REMOTE_DIR/${remoteFileName(this)}"
        client.createDirectory(REMOTE_DIR)
        val result = client.uploadFile(remotePath, tempFile)
        tempFile.delete()

        if (result.isSuccess) {
            updateNotification("Zuletzt gesendet: ${formatTime(payload.timestamp)}")
        }
    }

    private fun deleteOwnLocationFile() {
        val config = prefs.loadConfig() ?: return
        val client = WebDavClient(config.serverUrl, config.username, config.password)
        client.deleteFile("$REMOTE_DIR/${remoteFileName(this)}")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Standortfreigabe", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aktive Standortfreigabe im Hintergrund"
                setShowBadge(false); setSound(null, null); enableVibration(false)
            }.also { notificationManager.createNotificationChannel(it) }
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📍 Ortung aktiv")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, LocationShareActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            ).build()

    private fun updateNotification(text: String) =
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))

    private fun formatTime(ts: Long) =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(ts))

    private fun getBatteryLevel(): Int {
        val intent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }
}

data class LocationPayload(
    val deviceId: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val accuracy: Float?,
    val timestamp: Long,
    val batteryLevel: Int? = null
)
