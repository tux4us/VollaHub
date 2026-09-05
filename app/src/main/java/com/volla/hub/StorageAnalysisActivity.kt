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
import com.volla.hub.databinding.ActivityStorageAnalysisBinding
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Zeigt die Speicherbelegung des Geräts als Sunburst-Diagramm (Filelight-Stil)
 * mit begleitenden Detail-Cards. Erfordert MANAGE_EXTERNAL_STORAGE für eine
 * echte, ordnerbasierte Aufschlüsselung; ohne Berechtigung wird ein Hinweis
 * mit Direktlink zu den Systemeinstellungen angezeigt.
 */
class StorageAnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorageAnalysisBinding
    private val scanner = StorageScanner()
    private lateinit var adapter: StorageNodeAdapter

    private var rootNode: StorageNode? = null
    private var currentDisplayedNode: StorageNode? = null
    private var totalDeviceBytes: Long = 1L
    private val navigationStack = ArrayDeque<StorageNode>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityStorageAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Speicherbelegung"

        adapter = StorageNodeAdapter { node -> drillInto(node) }
        binding.rvStorageNodes.layoutManager = LinearLayoutManager(this)
        binding.rvStorageNodes.adapter = adapter

        binding.sunburstChart.onSegmentTapped = { node -> drillInto(node) }
        binding.sunburstChart.onCenterTapped = { navigateUpOneLevel() }
        binding.btnUpOneLevel.setOnClickListener { navigateUpOneLevel() }
        binding.btnRescan.setOnClickListener { startScan() }
        binding.btnGrantPermission.setOnClickListener { requestAllFilesPermission() }

        showOverallStorageSummary()
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
        binding.tvSummary.text = "${formatBytes(used)} von ${formatBytes(total)} belegt ($percent%) · ${formatBytes(free)} frei"
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
                    binding.tvScanStatus.text = "Scanne: ${progress.currentPath}"
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
        return if (relative.isEmpty()) "Speicher" else "Speicher${relative.replace("/", " / ")}"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
