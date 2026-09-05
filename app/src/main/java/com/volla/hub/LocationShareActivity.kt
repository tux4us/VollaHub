package com.volla.hub

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.volla.hub.databinding.ActivityLocationShareBinding
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.net.URL
import kotlin.math.*

data class RouteData(val points: List<GeoPoint>, val distance: Double, val duration: Double)

/**
 * LocationShareActivity – Kartenbasierte Standortfreigabe für VollaHub.
 * Portiert von HundeWildnis LocationShareActivity.
 */
class LocationShareActivity : AppCompatActivity(), LocationListener {

    companion object {
        private const val TAG                  = "LocationShareActivity"
        private const val MAP_REFRESH_MS       = 60_000L
        private const val STALE_THRESHOLD_MS   = 10 * 60 * 1000L
    }

    private lateinit var binding: ActivityLocationShareBinding
    private lateinit var prefs: WebDavPreferences
    private lateinit var locationManager: LocationManager
    private var currentLocation: Location? = null
    private val gson = Gson()

    private lateinit var myLocationOverlay: MyLocationNewOverlay
    private val locationMarkers = mutableMapOf<String, Marker>()
    private var currentRoutePolyline: Polyline? = null
    private var currentRouteData: RouteData? = null
    private var finalDestination: GeoPoint? = null

    private var refreshJob: Job? = null
    private var routingProfile = "foot-hiking"
    private var isNavigating = false
    private var isRerouting  = false
    private var isFirstLocation = true

    private lateinit var deviceAdapter: DeviceAdapter
    private val deviceList = mutableListOf<LocationPayload>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startShareService()
        } else {
            binding.switchLocationShare.isChecked = false
            Toast.makeText(this, "Standortberechtigung benötigt", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OsmConfig.getInstance().userAgentValue = packageName
        binding = ActivityLocationShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "📍 Ortung"
            setDisplayHomeAsUpEnabled(true)
        }

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        prefs = WebDavPreferences(this)

        setupMap()
        setupDeviceList()
        setupListeners()
        loadSavedState()
        checkWebDavConfig()
        centerOnLastKnownLocation()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        myLocationOverlay.enableMyLocation()
        updateShareStatus()
        startMapRefreshLoop()
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        myLocationOverlay.disableMyLocation()
        refreshJob?.cancel()
        stopLocationUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.cancel()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_location_share, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_webdav_settings -> {
                showWebDavConfigDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    private fun setupMap() {
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
        }
        val scaleBar = ScaleBarOverlay(binding.mapView).apply {
            setAlignRight(true); setAlignBottom(true)
            setUnitsOfMeasure(ScaleBarOverlay.UnitsOfMeasure.metric)
        }
        binding.mapView.overlays.add(scaleBar)

        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.mapView)
        myLocationOverlay.enableMyLocation()
        myLocationOverlay.runOnFirstFix {
            runOnUiThread {
                if (isFirstLocation) {
                    myLocationOverlay.myLocation?.let {
                        binding.mapView.controller.animateTo(it)
                        isFirstLocation = false
                    }
                }
            }
        }
        binding.mapView.overlays.add(myLocationOverlay)
    }

    private fun setupDeviceList() {
        deviceAdapter = DeviceAdapter(deviceList) { payload ->
            binding.mapView.controller.animateTo(GeoPoint(payload.lat, payload.lon))
            showPointInfoDialog(payload)
        }
        binding.rvDevices.adapter = deviceAdapter
    }

    private fun setupListeners() {
        binding.btnWebDavSettings.setOnClickListener { showWebDavConfigDialog() }
        binding.switchLocationShare.setOnClickListener {
            if (binding.switchLocationShare.isChecked) onShareEnabled() else onShareDisabled()
        }
        binding.etLocationLabel.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveLabelFromInput()
        }
        binding.btnRefreshMap.setOnClickListener { refreshMap() }
        binding.osmCopyrightText.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.openstreetmap.org/copyright")))
        }
        binding.btnCenterLocation.setOnClickListener {
            if (isNavigating) stopNavigation()
            else {
                if (binding.btnCenterLocation.isChecked) myLocationOverlay.enableFollowLocation()
                else myLocationOverlay.disableFollowLocation()
            }
        }
        binding.toggleRoutingProfile.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                routingProfile = when (checkedId) {
                    R.id.btnProfileFoot -> "foot-hiking"
                    R.id.btnProfileBike -> "cycling-regular"
                    else -> "foot-hiking"
                }
                if (isNavigating) recalculateRoute()
            }
        }
    }

    private fun loadSavedState() {
        binding.switchLocationShare.isChecked = LocationShareService.isServiceRunning
        val label = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString(LocationShareService.PREF_LOCATION_LABEL, android.os.Build.MODEL) ?: ""
        binding.etLocationLabel.setText(label)
    }

    // ── Location & Navigation ─────────────────────────────────────────────

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 5f, this)
        }
    }

    private fun stopLocationUpdates() = locationManager.removeUpdates(this)

    override fun onLocationChanged(location: Location) {
        currentLocation = location
        if (isFirstLocation) {
            binding.mapView.controller.animateTo(GeoPoint(location.latitude, location.longitude))
            isFirstLocation = false
        }
        if (isNavigating && !isRerouting) { checkRerouting(location); updateNavigationInfo() }
    }

    private fun centerOnLastKnownLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                last?.let {
                    currentLocation = it
                    binding.mapView.controller.setCenter(GeoPoint(it.latitude, it.longitude))
                    isFirstLocation = false
                }
            } catch (e: Exception) { Log.w(TAG, "Letzten Standort nicht abrufbar: ${e.message}") }
        }
    }

    private fun checkRerouting(location: Location) {
        val route = currentRouteData ?: return
        val minDist = route.points.minOfOrNull { calculateDistance(location.latitude, location.longitude, it.latitude, it.longitude) } ?: 100.0
        if (minDist > 40.0) recalculateRoute()
    }

    private fun startNavigationTo(target: GeoPoint, label: String) {
        if (prefs.getOrsKey().isBlank()) {
            Toast.makeText(this, "Bitte OpenRouteService API-Key in den Einstellungen hinterlegen", Toast.LENGTH_LONG).show()
        }
        isNavigating = true
        finalDestination = target
        binding.btnCenterLocation.text = "🧭 Stopp"
        binding.btnCenterLocation.isChecked = true
        myLocationOverlay.enableFollowLocation()
        recalculateRoute()
    }

    private fun stopNavigation() {
        isNavigating = false; finalDestination = null; currentRouteData = null
        currentRoutePolyline?.let { binding.mapView.overlays.remove(it) }
        currentRoutePolyline = null
        binding.btnCenterLocation.text = "📍 Standort"
        binding.btnCenterLocation.isChecked = false
        binding.navigationInfoText.visibility = View.GONE
        binding.mapView.invalidate()
    }

    private fun recalculateRoute() {
        val from = currentLocation ?: myLocationOverlay.myLocation?.let {
            Location("osm").apply { latitude = it.latitude; longitude = it.longitude }
        } ?: return
        val to = finalDestination ?: return
        drawRouteORS(from.latitude, from.longitude, to.latitude, to.longitude)
    }

    private fun drawRouteORS(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) {
        isRerouting = true
        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { fetchORS(fromLat, fromLon, toLat, toLon) }
                currentRouteData = data
                currentRoutePolyline?.let { binding.mapView.overlays.remove(it) }
                if (data.points.isNotEmpty()) {
                    currentRoutePolyline = Polyline().apply {
                        setPoints(data.points)
                        outlinePaint.color = Color.parseColor("#E53935")
                        outlinePaint.strokeWidth = 12f
                    }
                    binding.mapView.overlays.add(currentRoutePolyline)
                    updateNavigationInfo()
                } else {
                    drawStraightLine(fromLat, fromLon, toLat, toLon)
                }
            } catch (e: Exception) {
                drawStraightLine(fromLat, fromLon, toLat, toLon)
            } finally {
                isRerouting = false
                binding.mapView.invalidate()
            }
        }
    }

    private fun drawStraightLine(fLat: Double, fLon: Double, tLat: Double, tLon: Double) {
        currentRoutePolyline?.let { binding.mapView.overlays.remove(it) }
        currentRoutePolyline = Polyline().apply {
            setPoints(listOf(GeoPoint(fLat, fLon), GeoPoint(tLat, tLon)))
            outlinePaint.color = Color.parseColor("#FF9800")
            outlinePaint.strokeWidth = 10f
            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
        }
        binding.mapView.overlays.add(currentRoutePolyline)
    }

    private fun updateNavigationInfo() {
        val data = currentRouteData ?: return
        val km   = data.distance / 1000.0
        val mins = (data.duration / 60.0).toInt()
        val dText = if (km < 1.0) "${data.distance.toInt()} m" else "%.1f km".format(km)
        val tText = if (mins < 60) "$mins Min" else "${mins/60}h ${mins%60}m"
        binding.navigationInfoText.text = "🧭 $dText • $tText"
        binding.navigationInfoText.visibility = View.VISIBLE
    }

    // ── ORS Backend ───────────────────────────────────────────────────────

    private fun fetchORS(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): RouteData {
        val apiKey = prefs.getOrsKey()
        if (apiKey.isBlank()) return RouteData(emptyList(), 0.0, 0.0)
        
        val url  = "https://api.openrouteservice.org/v2/directions/$routingProfile/geojson"
        val body = JSONObject().apply {
            put("coordinates", JSONArray().apply {
                put(JSONArray().apply { put(fromLon); put(fromLat) })
                put(JSONArray().apply { put(toLon);   put(toLat)   })
            })
        }
        val conn = URL(url).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", apiKey)
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode != 200) return RouteData(emptyList(), 0.0, 0.0)
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val feat = json.getJSONArray("features").getJSONObject(0)
        val prop = feat.getJSONObject("properties").getJSONObject("summary")
        val geom = feat.getJSONObject("geometry").getJSONArray("coordinates")
        val pts  = mutableListOf<GeoPoint>()
        for (i in 0 until geom.length()) {
            val c = geom.getJSONArray(i)
            pts.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
        }
        return RouteData(pts, prop.getDouble("distance"), prop.getDouble("duration"))
    }

    // ── Markers ───────────────────────────────────────────────────────────

    private fun updateMarkersOnMap(payloads: List<LocationPayload>) {
        val now = System.currentTimeMillis()
        val seenIds = mutableSetOf<String>()
        deviceList.clear()
        deviceList.addAll(payloads.sortedByDescending { it.timestamp })
        deviceAdapter.notifyDataSetChanged()
        binding.tvMapStatus.text = if (payloads.isEmpty()) "Keine Geräte gefunden" else "Gefundene Geräte (${payloads.size}):"

        for (payload in payloads) {
            seenIds.add(payload.deviceId)
            val isStale = (now - payload.timestamp) > STALE_THRESHOLD_MS
            val pos = GeoPoint(payload.lat, payload.lon)
            val marker = locationMarkers[payload.deviceId] ?: Marker(binding.mapView).also {
                it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                binding.mapView.overlays.add(it)
                locationMarkers[payload.deviceId] = it
            }
            marker.position = pos
            marker.title   = if (isStale) "⏸ ${payload.label}" else "📍 ${payload.label}"
            marker.snippet = "Zuletzt: ${formatTime(payload.timestamp)}"
            marker.icon    = buildMarkerIcon(isStale, payload.label)
            marker.setOnMarkerClickListener { _, _ -> showPointInfoDialog(payload); true }
        }
        val toRemove = locationMarkers.keys.filter { it !in seenIds }
        toRemove.forEach { id -> binding.mapView.overlays.remove(locationMarkers[id]); locationMarkers.remove(id) }
        binding.mapView.invalidate()
    }

    private fun showPointInfoDialog(payload: LocationPayload) {
        val dist = currentLocation?.let { calculateDistance(it.latitude, it.longitude, payload.lat, payload.lon) } ?: 0.0
        AlertDialog.Builder(this)
            .setTitle("📍 ${payload.label}")
            .setMessage("Entfernung: ${formatDistance(dist)}\nZuletzt gesehen: ${formatTime(payload.timestamp)}")
            .setPositiveButton("🧭 Navigation") { _, _ ->
                startNavigationTo(GeoPoint(payload.lat, payload.lon), payload.label)
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun buildMarkerIcon(isStale: Boolean, label: String): Drawable {
        val baseIcon = ContextCompat.getDrawable(this, org.osmdroid.library.R.drawable.marker_default)!!.mutate()
        val color = if (isStale) Color.GRAY else Color.parseColor("#E53935")
        baseIcon.setTint(color)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 28f
            this.color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        val textBounds = Rect()
        paint.getTextBounds(label, 0, label.length, textBounds)
        val textWidth = textBounds.width()
        val textHeight = textBounds.height()

        val iconWidth = baseIcon.intrinsicWidth
        val iconHeight = baseIcon.intrinsicHeight
        
        val width = maxOf(iconWidth, textWidth + 30)
        val height = iconHeight + textHeight + 25
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Text-Hintergrund (Pille)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#CC000000")
            style = Paint.Style.FILL
        }
        val rect = RectF(
            (width - textWidth) / 2f - 12f,
            0f,
            (width + textWidth) / 2f + 12f,
            textHeight + 14f
        )
        canvas.drawRoundRect(rect, 10f, 10f, bgPaint)
        
        // Text zeichnen
        canvas.drawText(label, width / 2f, textHeight + 5f, paint)

        // Icon darunter zeichnen
        baseIcon.setBounds((width - iconWidth) / 2, textHeight + 20, (width + iconWidth) / 2, height)
        baseIcon.draw(canvas)

        return BitmapDrawable(resources, bitmap)
    }

    // ── Share Logic ───────────────────────────────────────────────────────

    private fun onShareEnabled() {
        saveLabelFromInput()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        startShareService()
    }

    private fun onShareDisabled() {
        stopService(Intent(this, LocationShareService::class.java))
        binding.root.postDelayed({ updateShareStatus() }, 500)
    }

    private fun startShareService() {
        ContextCompat.startForegroundService(this, Intent(this, LocationShareService::class.java))
        binding.root.postDelayed({ updateShareStatus() }, 500)
    }

    private fun updateShareStatus() {
        val running = LocationShareService.isServiceRunning
        if (binding.switchLocationShare.isChecked != running) binding.switchLocationShare.isChecked = running
        binding.tvShareStatus.text = if (running) "✅ Freigabe aktiv" else "Freigabe inaktiv"
    }

    private fun saveLabelFromInput() {
        val label = binding.etLocationLabel.text?.toString()?.trim()
        if (!label.isNullOrBlank()) {
            getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putString(LocationShareService.PREF_LOCATION_LABEL, label).apply()
        }
    }

    private fun checkWebDavConfig() {
        val has = prefs.hasCredentials()
        binding.cardNoWebDav.isVisible = !has
        binding.switchLocationShare.isEnabled = has
        binding.cardNoWebDav.findViewById<View>(R.id.btnConfigureWebDav)?.setOnClickListener {
            showWebDavConfigDialog()
        }
    }

    private fun showWebDavConfigDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_webdav_config, null)
        val etUrl  = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etWebDavUrl)
        val etUser = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etWebDavUser)
        val etPass = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etWebDavPass)
        val etOrs  = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etOrsKey)
        val btnTest = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTestWebDav)
        val btnClear = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClearWebDav)
        val tvResult = dialogView.findViewById<android.widget.TextView>(R.id.tvTestResult)

        // Pre-fill if already configured
        prefs.loadConfig()?.let {
            etUrl.setText(it.serverUrl)
            etUser.setText(it.username)
            etPass.setText(it.password)
        }
        etOrs?.setText(prefs.getOrsKey())

        btnClear?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Zugangsdaten löschen?")
                .setMessage("Möchtest du die WebDAV-Einstellungen wirklich zurücksetzen?")
                .setPositiveButton("Löschen") { _, _ ->
                    prefs.clearCredentials()
                    etUrl.setText("")
                    etUser.setText("")
                    etPass.setText("")
                    etOrs?.setText("")
                    checkWebDavConfig()
                    tvResult.text = "✅ Daten gelöscht"
                    tvResult.setTextColor(Color.GRAY)
                    tvResult.visibility = View.VISIBLE
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }

        btnTest?.setOnClickListener {
            val url  = etUrl.text?.toString()?.trim() ?: ""
            val user = etUser.text?.toString()?.trim() ?: ""
            val pass = etPass.text?.toString()?.trim() ?: ""

            if (url.isBlank() || user.isBlank()) {
                tvResult.text = "❌ URL und Benutzername fehlen"
                tvResult.setTextColor(Color.RED)
                tvResult.visibility = View.VISIBLE
                return@setOnClickListener
            }

            tvResult.text = "⌛ Teste Verbindung..."
            tvResult.setTextColor(Color.GRAY)
            tvResult.visibility = View.VISIBLE
            btnTest.isEnabled = false

            lifecycleScope.launch {
                val client = WebDavClient(url, user, pass)
                val success = withContext(Dispatchers.IO) {
                    // Test connection by trying to list the base directory
                    client.listFiles("").isSuccess
                }
                
                if (success) {
                    tvResult.text = "✅ Verbindung erfolgreich!"
                    tvResult.setTextColor(Color.parseColor("#4CAF50"))
                } else {
                    tvResult.text = "❌ Verbindung fehlgeschlagen"
                    tvResult.setTextColor(Color.RED)
                }
                btnTest.isEnabled = true
            }
        }

        AlertDialog.Builder(this)
            .setTitle("WebDAV einrichten")
            .setView(dialogView)
            .setPositiveButton("Speichern") { _, _ ->
                val url  = etUrl.text?.toString()?.trim() ?: ""
                val user = etUser.text?.toString()?.trim() ?: ""
                val pass = etPass.text?.toString()?.trim() ?: ""
                val ors  = etOrs?.text?.toString()?.trim() ?: ""

                if (url.isNotBlank() && user.isNotBlank()) {
                    prefs.saveCredentials(url, user, pass)
                    prefs.saveOrsKey(ors)
                    checkWebDavConfig()
                    Toast.makeText(this, "✅ Einstellungen gespeichert", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "URL und Benutzername sind Pflichtfelder", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ── Map Refresh ───────────────────────────────────────────────────────

    private fun startMapRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            // Sofort aktualisieren beim Start, dann alle 60s
            while (isActive) { 
                fetchAndUpdateMarkers()
                delay(MAP_REFRESH_MS) 
            }
        }
    }

    private fun refreshMap() = lifecycleScope.launch { fetchAndUpdateMarkers() }

    private suspend fun fetchAndUpdateMarkers() {
        val config = prefs.loadConfig() ?: return
        val client = WebDavClient(config.serverUrl, config.username, config.password)
        val own    = LocationShareService.remoteFileName(this@LocationShareActivity)
        
        withContext(Dispatchers.IO) {
            val filesResult = client.listFiles(LocationShareService.REMOTE_DIR)
            if (filesResult.isFailure) {
                withContext(Dispatchers.Main) {
                    binding.tvMapStatus.text = "❌ Fehler beim Abrufen: ${filesResult.exceptionOrNull()?.message}"
                }
                return@withContext
            }
            
            val files = filesResult.getOrDefault(emptyList()).filter { it != own && it.startsWith("location_") }
            val payloads = mutableListOf<LocationPayload>()
            val tempDir  = File(cacheDir, "loc_dl").apply { mkdirs() }
            
            for (f in files) {
                val file = File(tempDir, f)
                if (client.downloadFile("${LocationShareService.REMOTE_DIR}/$f", file).isSuccess) {
                    try { 
                        val text = file.readText()
                        val payload = gson.fromJson(text, LocationPayload::class.java)
                        if (payload != null) payloads.add(payload)
                    } catch (e: Exception) {
                        Log.e(TAG, "Fehler beim Parsen von $f: ${e.message}")
                    }
                    file.delete()
                }
            }
            withContext(Dispatchers.Main) { updateMarkersOnMap(payloads) }
        }
    }

    // ── Device List Adapter ───────────────────────────────────────────────

    private inner class DeviceAdapter(
        private val items: List<LocationPayload>,
        private val onClick: (LocationPayload) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<DeviceAdapter.VH>() {

        inner class VH(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val label:    android.widget.TextView = view.findViewById(R.id.tvDeviceLabel)
            val status:   android.widget.TextView = view.findViewById(R.id.tvDeviceStatus)
            val distance: android.widget.TextView = view.findViewById(R.id.tvDeviceDistance)
            val icon:     android.widget.TextView = view.findViewById(R.id.tvDeviceIcon)
            val coords:   android.widget.TextView = view.findViewById(R.id.tvDeviceCoords)
            val battery:  android.widget.TextView = view.findViewById(R.id.tvDeviceBattery)
            val btnRoute: View = view.findViewById(R.id.btnItemRoute)
            val btnShare: View = view.findViewById(R.id.btnItemShare)
            init {
                view.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) onClick(items[pos])
                }
                btnRoute.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        val item = items[pos]
                        startNavigationTo(GeoPoint(item.lat, item.lon), item.label)
                    }
                }
                btnShare.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) shareDeviceLocation(items[pos])
                }
            }
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val view = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_location_device, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item    = items[position]
            val isStale = (System.currentTimeMillis() - item.timestamp) > STALE_THRESHOLD_MS
            holder.label.text    = item.label
            holder.status.text   = if (isStale) "⏸ Inaktiv (${formatTime(item.timestamp)})" else "Vor ${formatTimeDiff(item.timestamp)}"
            holder.icon.text     = if (isStale) "⏸" else "📍"
            holder.coords.text   = "Lat: %.6f, Lon: %.6f".format(item.lat, item.lon)
            holder.label.setTextColor(if (isStale) Color.GRAY else Color.WHITE)
            if (item.batteryLevel != null && item.batteryLevel != -1) {
                holder.battery.visibility = View.VISIBLE
                holder.battery.text = "🔋 ${item.batteryLevel}%"
                holder.battery.setTextColor(when {
                    item.batteryLevel > 50 -> Color.parseColor("#4CAF50")
                    item.batteryLevel > 20 -> Color.parseColor("#FFC107")
                    else -> Color.parseColor("#F44336")
                })
            } else {
                holder.battery.visibility = View.GONE
            }
            val dist = currentLocation?.let { calculateDistance(it.latitude, it.longitude, item.lat, item.lon) } ?: 0.0
            holder.distance.text = formatDistance(dist)
        }

        override fun getItemCount() = items.size

        private fun formatTimeDiff(t: Long): String {
            val diff = (System.currentTimeMillis() - t) / 1000
            return when {
                diff < 60   -> "$diff sek"
                diff < 3600 -> "${diff / 60} min"
                else        -> "${diff / 3600} std"
            }
        }

        private fun shareDeviceLocation(payload: LocationPayload) {
            val osmPreview = "https://www.openstreetmap.org/?mlat=${payload.lat}&mlon=${payload.lon}#map=17/${payload.lat}/${payload.lon}"
            val geoUri = "geo:${payload.lat},${payload.lon}?q=${payload.lat},${payload.lon}(${payload.label})"
            val text = "📍 Standort von ${payload.label}:\n\nKarte (OSM): $osmPreview\n\nNavigations-Link:\n$geoUri\n\nKoordinaten:\nLat: ${payload.lat}\nLon: ${payload.lon}"
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) },
                "Standort teilen via"
            ))
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun formatDistance(m: Double) = if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000)
    private fun formatTime(t: Long) = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(t))
}
