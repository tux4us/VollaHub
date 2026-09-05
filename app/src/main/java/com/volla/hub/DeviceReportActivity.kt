package com.volla.hub

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.*
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.volla.hub.databinding.ActivityDeviceReportBinding
import java.io.File
import java.io.FileOutputStream
import java.util.*

class DeviceReportActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDeviceReportBinding
    private val selectedImages = mutableListOf<Uri>()
    private val selectedApps = mutableListOf<AppInfo>()
    private lateinit var imageAdapter: SelectedImageAdapter
    private lateinit var appAdapter: SelectedAppAdapter
    private lateinit var reportStorage: ReportStorage
    private val handler = Handler(Looper.getMainLooper())
    private val ramSampler = object : Runnable {
        override fun run() {
            updateRamStatus()
            handler.postDelayed(this, 5000)
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        selectedImages.addAll(uris)
        imageAdapter.setImages(selectedImages)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        reportStorage = ReportStorage(this)
        imageAdapter = SelectedImageAdapter { uri ->
            selectedImages.remove(uri)
            imageAdapter.setImages(selectedImages)
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Geräte-Report"

        binding.rvImages.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvImages.adapter = imageAdapter

        appAdapter = SelectedAppAdapter(packageManager) { app ->
            selectedApps.remove(app)
            appAdapter.setApps(selectedApps)
        }
        binding.rvSelectedApps.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvSelectedApps.adapter = appAdapter

        displayDeviceSpecs()
        autoDetectStatus()
        handler.post(ramSampler)

        binding.btnAddPhoto.setOnClickListener { pickImage.launch("image/*") }
        binding.btnAddApp.setOnClickListener { showAppSelectionDialog() }
        binding.btnShare.setOnClickListener { shareReport() }
        binding.btnSavePdf.setOnClickListener { saveAndStoreReport() }
        binding.btnCopySpecs.setOnClickListener { copySpecsToClipboard() }
        binding.btnViewHistory.setOnClickListener { 
            startActivity(Intent(this, ReportHistoryActivity::class.java))
        }

        binding.btnNewReport.setOnClickListener { 
            resetToNewReport()
        }

        // Check if we are viewing an existing report
        intent.getStringExtra("report_json")?.let { json ->
            val report = com.google.gson.Gson().fromJson(json, DeviceReport::class.java)
            loadReportIntoFields(report)
            binding.btnNewReport.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ramSampler)
    }

    private fun updateRamStatus() {
        val mi = ActivityManager.MemoryInfo()
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.getMemoryInfo(mi)
        
        val totalRam = mi.totalMem / (1024 * 1024)
        val availableRam = mi.availMem / (1024 * 1024)
        val usedRam = totalRam - availableRam
        val usedPercent = (usedRam.toFloat() / totalRam.toFloat()) * 100
        
        binding.ramChart.addSample(usedPercent)
        binding.tvRamStats.text = "RAM: ${usedRam}MB / ${totalRam}MB (${usedPercent.toInt()}%)"
    }

    private fun resetToNewReport() {
        binding.etNoteTitle.setText("")
        binding.etNoteContent.setText("")
        selectedImages.clear()
        imageAdapter.setImages(selectedImages)
        selectedApps.clear()
        appAdapter.setApps(selectedApps)
        displayDeviceSpecs()
        autoDetectStatus()
        binding.btnNewReport.visibility = View.GONE
        Toast.makeText(this, "Bereit für neuen Bericht", Toast.LENGTH_SHORT).show()
    }

    private fun showAppSelectionDialog() {
        val dialog = AppSelectionDialog.newInstance()
        dialog.setInitiallySelectedApps(selectedApps)
        dialog.setOnAppsSelectedListener { apps ->
            selectedApps.clear()
            selectedApps.addAll(apps)
            appAdapter.setApps(selectedApps)
        }
        dialog.show(supportFragmentManager, AppSelectionDialog.TAG)
    }

    private fun loadReportIntoFields(report: DeviceReport) {
        binding.etNoteTitle.setText(report.title)
        binding.etNoteContent.setText(report.note)
        binding.tvDeviceSpecs.text = report.specs
        binding.swShelter.isChecked = report.shelterActive
        binding.swSecurityMode.isChecked = report.securityModeActive
        binding.swVpn.isChecked = report.vpnActive
        
        // Load images if they still exist
        selectedImages.clear()
        report.imagePaths.forEach { path ->
            val file = File(path)
            if (file.exists()) {
                selectedImages.add(Uri.fromFile(file))
            }
        }
        imageAdapter.setImages(selectedImages)

        // Load apps
        selectedApps.clear()
        selectedApps.addAll(report.selectedApps)
        appAdapter.setApps(selectedApps)
    }

    private fun autoDetectStatus() {
        binding.swVpn.isChecked = isVpnActive()
        val userManager = getSystemService(Context.USER_SERVICE) as UserManager
        binding.swShelter.isChecked = userManager.userProfiles.size > 1
    }

    private fun isVpnActive(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun displayDeviceSpecs() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "3.5" }
        val specs = StringBuilder()
        specs.append("Erstellt mit: VollaHub #$version\n\n")
        specs.append("--- BASIS DATEN ---\n")
        specs.append("HERSTELLER: ${Build.MANUFACTURER}\n")
        specs.append("MODELL: ${Build.MODEL}\n")
        specs.append("GERÄT: ${Build.DEVICE}\n")
        specs.append("BOARD: ${Build.BOARD}\n")
        specs.append("HARDWARE: ${Build.HARDWARE}\n")
        specs.append("ANDROID VERSION: ${Build.VERSION.RELEASE}\n")
        specs.append("SDK VERSION: ${Build.VERSION.SDK_INT}\n")
        specs.append("BUILD ID: ${Build.ID}\n")
        
        // Batterie Status
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            baseContext.registerReceiver(null, filter)
        }
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = level * 100 / scale.toFloat()
        val health = when(batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Gut"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Überhitzt"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Defekt"
            else -> "Unbekannt"
        }
        
        specs.append("\n--- ENERGIE ---\n")
        specs.append("BATTERIE LADESTAND: $batteryPct%\n")
        specs.append("BATTERIE ZUSTAND: $health\n")
        
        // RAM Stats
        val mi = ActivityManager.MemoryInfo()
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.getMemoryInfo(mi)
        val totalRam = mi.totalMem / (1024 * 1024)
        val availableRam = mi.availMem / (1024 * 1024)
        
        specs.append("\n--- SPEICHER (RAM) ---\n")
        specs.append("TOTAL RAM: ${totalRam}MB\n")
        specs.append("VERFÜGBAR: ${availableRam}MB\n")
        specs.append("LOW MEMORY: ${if (mi.lowMemory) "JA" else "NEIN"}\n")
        specs.append("SCHWELLENWERT: ${mi.threshold / (1024 * 1024)}MB\n")
        
        specs.append("\n--- SYSTEM ---\n")
        specs.append("FINGERPRINT: ${Build.FINGERPRINT}\n")
        
        // Launcher Information
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0L))
            } else {
                packageManager.resolveActivity(intent, 0)
            }
            
            val launcherPackage = resolveInfo?.activityInfo?.packageName
            if (launcherPackage != null) {
                val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(launcherPackage, android.content.pm.PackageManager.PackageInfoFlags.of(0L))
                } else {
                    packageManager.getPackageInfo(launcherPackage, 0)
                }
                val appInfo = pInfo.applicationInfo
                if (appInfo != null) {
                    val launcherName = packageManager.getApplicationLabel(appInfo).toString()
                    val launcherVersion = pInfo.versionName ?: "Unbekannt"
                    specs.append("LAUNCHER: $launcherName ($launcherPackage)\n")
                    specs.append("LAUNCHER VERSION: $launcherVersion\n")
                }
            }
        } catch (e: Exception) {
            specs.append("LAUNCHER: Fehler beim Auslesen\n")
        }
        
        binding.tvDeviceSpecs.text = specs.toString()
    }

    private fun getFullReportText(): String {
        val title = binding.etNoteTitle.text.toString()
        val note = binding.etNoteContent.text.toString()
        val specs = binding.tvDeviceSpecs.text.toString()
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "3.5" }

        val appsText = if (selectedApps.isNotEmpty()) {
            val sb = StringBuilder("\nBETROFFENE APPS:\n")
            selectedApps.forEach { app ->
                sb.append("- ${app.name} (${app.packageName}) V: ${app.version}\n")
            }
            sb.toString()
        } else ""

        return "VOLLA GERÄTE REPORT\n" +
                "Erstellt mit: VollaHub #$version\n\n" +
                "TITEL: $title\n\n" +
                "NOTIZ:\n$note\n\n" +
                "ZUSÄTZLICHE INFOS:\n" +
                "- Shelter/Profil aktiv: ${if (binding.swShelter.isChecked) "Ja" else "Nein"}\n" +
                "- Sicherheitsmodus aktiv: ${if (binding.swSecurityMode.isChecked) "Ja" else "Nein"}\n" +
                "- VPN aktiv: ${if (binding.swVpn.isChecked) "Ja" else "Nein"}\n" +
                appsText +
                "\nSYSTEM-SPEZIFIKATIONEN:\n$specs"
    }

    private fun shareReport() {
        val reportText = getFullReportText()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Volla Support Report: ${binding.etNoteTitle.text}")
            putExtra(Intent.EXTRA_TEXT, reportText)
        }
        startActivity(Intent.createChooser(intent, "Report teilen via..."))
    }

    private fun saveAndStoreReport() {
        val internalImagePaths = mutableListOf<String>()
        selectedImages.forEachIndexed { index, uri ->
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val file = File(filesDir, "report_img_${System.currentTimeMillis()}_$index.jpg")
                val outputStream = FileOutputStream(file)
                inputStream?.copyTo(outputStream)
                inputStream?.close()
                outputStream.close()
                internalImagePaths.add(file.absolutePath)
            } catch (e: Exception) { e.printStackTrace() }
        }

        val report = DeviceReport(
            title = binding.etNoteTitle.text.toString(),
            note = binding.etNoteContent.text.toString(),
            specs = binding.tvDeviceSpecs.text.toString(),
            shelterActive = binding.swShelter.isChecked,
            securityModeActive = binding.swSecurityMode.isChecked,
            vpnActive = binding.swVpn.isChecked,
            imagePaths = internalImagePaths,
            selectedApps = ArrayList(selectedApps)
        )
        reportStorage.saveReport(report)
        saveAsPdf(report)
    }

    private fun saveAsPdf(report: DeviceReport) {
        val pdfDocument = PdfDocument()
        val paint = Paint()
        
        // --- SEITE 1: TEXT ---
        var pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas

        var y = 50f
        paint.textSize = 18f
        paint.isFakeBoldText = true
        canvas.drawText("Volla Geräte Report", 50f, y, paint)
        
        y += 40f
        paint.textSize = 12f
        paint.isFakeBoldText = false
        
        val reportText = getFullReportText()
        val lines = reportText.split("\n")
        for (line in lines) {
            if (y > 780) {
                drawFooter(canvas)
                pdfDocument.finishPage(page)
                pageInfo = PdfDocument.PageInfo.Builder(595, 842, pdfDocument.pages.size + 1).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas
                y = 50f
            }
            canvas.drawText(line, 50f, y, paint)
            y += 20f
        }
        drawFooter(canvas)
        pdfDocument.finishPage(page)

        // --- SEITE: RAM CHART ---
        pageInfo = PdfDocument.PageInfo.Builder(595, 842, pdfDocument.pages.size + 1).create()
        page = pdfDocument.startPage(pageInfo)
        canvas = page.canvas
        
        paint.textSize = 14f
        paint.isFakeBoldText = true
        canvas.drawText("RAM Auslastungsverlauf (letzte 5 Minuten)", 50f, 50f, paint)
        
        val chartBitmap = viewToBitmap(binding.ramChart)
        val chartScale = 495f / chartBitmap.width
        val ch = chartBitmap.height * chartScale
        canvas.drawBitmap(chartBitmap, null, RectF(50f, 80f, 50f + 495f, 80f + ch), null)
        
        drawFooter(canvas)
        pdfDocument.finishPage(page)

        // --- BILDER ---
        report.imagePaths.forEach { path ->
            val bitmap = android.graphics.BitmapFactory.decodeFile(path)
            if (bitmap != null) {
                pageInfo = PdfDocument.PageInfo.Builder(595, 842, pdfDocument.pages.size + 1).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas
                
                val scale = Math.min(495f / bitmap.width, 700f / bitmap.height)
                val dw = bitmap.width * scale
                val dh = bitmap.height * scale
                canvas.drawBitmap(bitmap, null, RectF(50f, 50f, 50f + dw, 50f + dh), null)
                
                drawFooter(canvas)
                pdfDocument.finishPage(page)
                bitmap.recycle()
            }
        }

        val fileName = "VollaReport_${System.currentTimeMillis()}.pdf"
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Volla")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            pdfDocument.writeTo(FileOutputStream(file))
            Toast.makeText(this, "PDF gespeichert: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            pdfDocument.close()
        }
    }

    private fun viewToBitmap(view: View): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        view.draw(canvas)
        return bitmap
    }

    private fun drawFooter(canvas: android.graphics.Canvas) {
        val footerPaint = Paint().apply {
            textSize = 10f
            color = android.graphics.Color.GRAY
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Erstellt mit VollaHub für Android. Eine App entwickelt von https://github.com/tux4us für die Community.", 297f, 820f, footerPaint)
    }

    private fun copySpecsToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Volla Device Specs", binding.tvDeviceSpecs.text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Spezifikationen kopiert", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_home -> {
                val intent = Intent(this, StartActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                true
            }
            R.id.action_theme -> {
                toggleTheme()
                true
            }
            R.id.action_developer -> {
                showDeveloperInfo()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showDeveloperInfo() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "3.5" }
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Appinfo")
        builder.setMessage("App-Version: $version\n\nEntwickler der App: tux4us\nGitHub: https://github.com/tux4us/VollaHubAndroidApp")
        builder.setPositiveButton("GitHub öffnen") { _, _ ->
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/tux4us/VollaHubAndroidApp"))
            startActivity(intent)
        }
        builder.setNegativeButton("Schließen", null)
        builder.show()
    }

    private fun toggleTheme() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val currentDark = prefs.getBoolean("dark_theme", false)
        prefs.edit().putBoolean("dark_theme", !currentDark).apply()
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            if (!currentDark) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
        recreate()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}