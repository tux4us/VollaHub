package com.volla.hub

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.volla.hub.databinding.ActivityStorageAnalysisBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque

/**
 * Zeigt die Speicherbelegung des Geräts als Sunburst-Diagramm (Filelight-Stil)
 * mit begleitenden Detail-Cards. Erfordert MANAGE_EXTERNAL_STORAGE für eine
 * echte, ordnerbasierte Aufschlüsselung; ohne Berechtigung wird ein Hinweis
 * mit Direktlink zu den Systemeinstellungen angezeigt.
 */
class StorageAnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorageAnalysisBinding
    private val scanner = StorageScanner(this)
    private val appCacheScanner = AppCacheScanner(this)
    private lateinit var adapter: StorageNodeAdapter
    private lateinit var appCacheAdapter: AppCacheAdapter

    private var rootNode: StorageNode? = null
    private var currentDisplayedNode: StorageNode? = null
    private var totalDeviceBytes: Long = 1L
    private val navigationStack = ArrayDeque<StorageNode>()

    private var appCachesLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityStorageAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.storage_analysis_toolbar_title)

        adapter = StorageNodeAdapter(
            onClick = { node -> drillInto(node) },
            onLongClick = { node -> confirmDeleteNode(node) }
        )
        binding.rvStorageNodes.layoutManager = LinearLayoutManager(this)
        binding.rvStorageNodes.adapter = adapter

        appCacheAdapter = AppCacheAdapter { app -> openAppInfoSettings(app.packageName) }
        binding.rvAppCaches.layoutManager = LinearLayoutManager(this)
        binding.rvAppCaches.adapter = appCacheAdapter

        binding.sunburstChart.onSegmentTapped = { node -> drillInto(node) }
        binding.sunburstChart.onCenterTapped = { navigateUpOneLevel() }
        binding.sunburstChart.onSegmentLongPressed = { node -> confirmDeleteNode(node) }
        binding.btnUpOneLevel.setOnClickListener { navigateUpOneLevel() }
        binding.btnRescan.setOnClickListener { startScan() }
        binding.btnGrantPermission.setOnClickListener { requestAllFilesPermission() }

        binding.btnGrantUsageAccess.setOnClickListener { requestUsageAccessPermission() }
        binding.btnClearOwnCache.setOnClickListener { confirmClearOwnCache() }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> showFolderTab()
                    1 -> showAppCacheTab()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        showOverallStorageSummary()
        updateOwnCacheSize()
    }

    override fun onResume() {
        super.onResume()
        if (hasAllFilesPermission()) {
            binding.permissionLayout.visibility = View.GONE
            binding.contentScroll.visibility = View.VISIBLE
            if (rootNode == null) startScan()
        } else {
            binding.permissionLayout.visibility = View.VISIBLE
            binding.contentScroll.visibility = View.GONE
        }

        // Nutzungszugriff kann der Nutzer jederzeit in den Einstellungen
        // erteilt/entzogen haben – bei jedem Rückkehr aus den Settings neu prüfen.
        if (binding.appCacheTabContent.visibility == View.VISIBLE) {
            refreshAppCacheTabState()
        }
        updateOwnCacheSize()
    }

    private fun showFolderTab() {
        binding.folderTabContent.visibility = View.VISIBLE
        binding.appCacheTabContent.visibility = View.GONE
    }

    private fun showAppCacheTab() {
        binding.folderTabContent.visibility = View.GONE
        binding.appCacheTabContent.visibility = View.VISIBLE
        refreshAppCacheTabState()
    }

    private fun refreshAppCacheTabState() {
        if (appCacheScanner.hasUsageStatsPermission()) {
            binding.usageAccessPermissionLayout.visibility = View.GONE
            if (!appCachesLoaded) {
                startAppCacheScan()
            } else {
                binding.appCacheListLayout.visibility = View.VISIBLE
            }
        } else {
            binding.usageAccessPermissionLayout.visibility = View.VISIBLE
            binding.appCacheListLayout.visibility = View.GONE
            appCachesLoaded = false
        }
    }

    /**
     * Zeigt vor dem Sprung in die Systemeinstellungen einen erklärenden Hinweis,
     * falls die App per Sideload (z.B. APK von GitHub statt aus dem Play Store)
     * installiert wurde. Ab Android 13 blockiert das System dann per "Restricted
     * Settings" zunächst den Umschalter für sensible Berechtigungen wie den
     * Nutzungsdatenzugriff und zeigt stattdessen "Zugriff verweigert" – das ist
     * kein Fehler dieser App, sondern ein bewusster Sicherheitsmechanismus, der
     * sich nur manuell über die App-Info-Seite aufheben lässt.
     */
    private fun requestUsageAccessPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isInstalledFromTrustedInstaller()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_restricted_settings_title)
                .setMessage(R.string.dialog_restricted_settings_message)
                .setNegativeButton(R.string.btn_open_app_info) { _, _ -> openAppInfoSettings(packageName) }
                .setPositiveButton(R.string.btn_continue_to_settings) { _, _ -> openUsageAccessSettings() }
                .show()
        } else {
            openUsageAccessSettings()
        }
    }

    /**
     * Prüft, ob die App über einen vertrauenswürdigen Installer (Play Store)
     * installiert wurde. Bei null/unbekanntem Installer (Sideload per APK,
     * Dateimanager, Browser-Download etc.) ist die Restricted-Settings-Sperre
     * wahrscheinlich, daher wird dann der erklärende Hinweis-Dialog gezeigt.
     */
    private fun isInstalledFromTrustedInstaller(): Boolean {
        val installerPackage = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
        } catch (e: Exception) {
            null
        }
        return installerPackage == "com.android.vending"
    }

    private fun openUsageAccessSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun startAppCacheScan() {
        binding.appCacheScanSpinner.visibility = View.VISIBLE
        binding.tvAppCacheScanStatus.visibility = View.VISIBLE
        binding.appCacheListLayout.visibility = View.GONE

        lifecycleScope.launch {
            val apps = appCacheScanner.scanAppCaches()
            appCachesLoaded = true
            binding.appCacheScanSpinner.visibility = View.GONE
            binding.tvAppCacheScanStatus.visibility = View.GONE

            if (apps.isEmpty()) {
                binding.appCacheListLayout.visibility = View.GONE
                binding.tvAppCacheScanStatus.apply {
                    text = getString(R.string.app_cache_empty)
                    visibility = View.VISIBLE
                }
            } else {
                binding.appCacheListLayout.visibility = View.VISIBLE
                val total = appCacheScanner.totalCacheBytes(apps)
                binding.tvAppCacheTotal.text =
                    getString(R.string.app_cache_total_summary, formatBytes(total), apps.size)
                appCacheAdapter.submitList(apps)
            }
        }
    }

    /**
     * Öffnet die System-"App-Info"-Seite der angegebenen App. VollaHub kann
     * den Cache einer fremden App nicht selbst löschen (Android erlaubt das
     * seit Scoped Storage keiner regulären App ohne Root) – der Nutzer muss
     * dort selbst auf "Cache leeren" tippen. Dieser Link verkürzt nur den Weg.
     */
    private fun openAppInfoSettings(packageName: String) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Seite für dieses Paket nicht erreichbar (z.B. exotisches OEM-Verhalten) – ignorieren
        }
    }

    private fun updateOwnCacheSize() {
        val size = ownCacheSizeBytes()
        binding.tvOwnCacheSize.text = getString(R.string.own_cache_card_size, formatBytes(size))
    }

    private fun ownCacheSizeBytes(): Long {
        var total = folderSizeRecursive(cacheDir)
        externalCacheDir?.let { total += folderSizeRecursive(it) }
        return total
    }

    private fun folderSizeRecursive(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.listFiles()?.forEach { entry ->
            total += if (entry.isDirectory) folderSizeRecursive(entry) else entry.length()
        }
        return total
    }

    private fun confirmClearOwnCache() {
        val size = ownCacheSizeBytes()
        if (size <= 0L) {
            android.widget.Toast.makeText(
                this, getString(R.string.own_cache_already_empty), android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_clear_own_cache_title)
            .setMessage(getString(R.string.dialog_clear_own_cache_message, formatBytes(size)))
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_confirm_delete) { _, _ -> clearOwnCache(size) }
            .show()
    }

    private fun clearOwnCache(previousSize: Long) {
        cacheDir.deleteRecursively()
        externalCacheDir?.deleteRecursively()
        updateOwnCacheSize()
        android.widget.Toast.makeText(
            this, getString(R.string.own_cache_cleared, formatBytes(previousSize)), android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun confirmDeleteNode(node: StorageNode) {
        val isProtected = isProtectedSystemPath(node.path)
        val message = buildString {
            append(
                if (node.isDirectory) {
                    getString(R.string.dialog_delete_folder_message, node.name, formatBytes(node.sizeBytes))
                } else {
                    getString(R.string.dialog_delete_file_message, node.name, formatBytes(node.sizeBytes))
                }
            )
            if (isProtected) {
                append("\n\n")
                append(getString(R.string.dialog_delete_protected_warning))
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (node.isDirectory) R.string.dialog_delete_folder_title else R.string.dialog_delete_file_title)
            .setMessage(message)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_confirm_delete) { _, _ -> performDelete(node) }
            .show()
    }

    /**
     * Android/data und Android/obb gehören anderen Apps bzw. deren Sandbox.
     * Seit Scoped Storage (API 30+) verweigert das System das Löschen dort
     * i.d.R. auch mit MANAGE_EXTERNAL_STORAGE – der Nutzer wird vorab gewarnt,
     * damit ein Fehlschlag nicht überrascht.
     */
    private fun isProtectedSystemPath(path: String): Boolean {
        return path.contains("/Android/data") || path.contains("/Android/obb")
    }

    private fun performDelete(node: StorageNode) {
        val parentPath = currentDisplayedNode?.path
        val wasAtRootLevel = navigationStack.isEmpty()

        lifecycleScope.launch {
            binding.scanProgressSpinner.visibility = View.VISIBLE

            val success = withContext(Dispatchers.IO) {
                val file = File(node.path)
                try {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                } catch (e: Exception) {
                    false
                }
            }

            // Statt den Baum manuell (fehleranfällig) zu aktualisieren, wird die
            // betroffene Ebene neu eingelesen – das ist robust auch bei teilweise
            // fehlgeschlagenem Löschen (z.B. geschützte Pfade) und spiegelt immer
            // den tatsächlichen Dateisystemzustand.
            if (wasAtRootLevel) {
                startScan()
            } else if (parentPath != null) {
                val refreshed = scanner.scanFolder(parentPath)
                binding.scanProgressSpinner.visibility = View.GONE
                displayNode(refreshed)
                showOverallStorageSummary()
            } else {
                binding.scanProgressSpinner.visibility = View.GONE
            }

            val messageRes = if (success) R.string.node_deleted_toast else R.string.node_delete_failed_toast
            android.widget.Toast.makeText(
                this@StorageAnalysisActivity,
                getString(messageRes, node.name),
                if (success) android.widget.Toast.LENGTH_SHORT else android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun hasAllFilesPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // Vor Android 11 reicht klassischer Speicherzugriff
        }
    }

    private fun requestAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun showOverallStorageSummary() {
        val (total, used, free) = scanner.getStatFsSummary()
        totalDeviceBytes = total.coerceAtLeast(1L)
        val percent = (used.toFloat() / total.toFloat() * 100f).toInt()
        binding.tvSummary.text = getString(R.string.storage_usage_summary, formatBytes(used), formatBytes(total), percent.toString(), formatBytes(free))
        binding.progressOverall.progress = percent.coerceIn(0, 100)
    }

    private fun startScan() {
        navigationStack.clear()
        currentDisplayedNode = null
        binding.scanProgressSpinner.visibility = View.VISIBLE
        binding.tvScanStatus.visibility = View.VISIBLE
        binding.btnRescan.isEnabled = false

        lifecycleScope.launch {
            val result = scanner.scanFull { progress ->
                runOnUiThread {
                    binding.tvScanStatus.text = getString(R.string.storage_scan_status, progress.currentPath)
                }
            }
            rootNode = result
            showOverallStorageSummary()
            binding.scanProgressSpinner.visibility = View.GONE
            binding.tvScanStatus.visibility = View.GONE
            binding.btnRescan.isEnabled = true
            displayNode(result)
        }
    }

    private fun drillInto(node: StorageNode) {
        if (!node.isDirectory) return
        val current = currentDisplayedNode ?: return
        navigationStack.addLast(current)

        if (node.children.isEmpty()) {
            // Noch nicht tief gescannter Ordner: gezielt nachladen
            lifecycleScope.launch {
                binding.scanProgressSpinner.visibility = View.VISIBLE
                val expanded = scanner.scanFolder(node.path)
                binding.scanProgressSpinner.visibility = View.GONE
                displayNode(expanded)
            }
        } else {
            displayNode(node)
        }
    }

    private fun navigateUpOneLevel() {
        val previous = navigationStack.pollLast() ?: return
        displayNode(previous)
    }

    private fun displayNode(node: StorageNode) {
        currentDisplayedNode = node
        binding.sunburstChart.setRootNode(node)
        binding.tvBreadcrumb.text = buildBreadcrumb(node)
        binding.btnUpOneLevel.visibility = if (navigationStack.isEmpty()) View.GONE else View.VISIBLE

        val children = node.sortedChildren()
        adapter.submitList(children, node.sizeBytes)
    }

    private fun buildBreadcrumb(node: StorageNode): String {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val relative = node.path.removePrefix(root)
        val rootLabel = getString(R.string.storage_root_label)
        return if (relative.isEmpty()) rootLabel else "$rootLabel${relative.replace("/", " / ")}"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}