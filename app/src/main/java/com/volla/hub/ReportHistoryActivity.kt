package com.volla.hub

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.volla.hub.databinding.ActivityReportHistoryBinding
import com.volla.hub.databinding.ItemReportHistoryBinding
import java.text.SimpleDateFormat
import java.util.*

class ReportHistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReportHistoryBinding
    private lateinit var reportStorage: ReportStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        reportStorage = ReportStorage(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Bericht-Historie"

        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        val reports = reportStorage.getAllReports()
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = HistoryAdapter(reports.toMutableList()) { reportId ->
            reportStorage.deleteReport(reportId)
            setupRecyclerView() // Refresh
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
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
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
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

    inner class HistoryAdapter(
        private val reports: MutableList<DeviceReport>,
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemReportHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val report = reports[position]
            val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            
            holder.binding.tvDate.text = sdf.format(Date(report.timestamp))
            holder.binding.tvTitle.text = report.title.ifEmpty { "Unbenannter Bericht" }
            holder.binding.tvSummary.text = report.note.ifEmpty { "Keine Notiz vorhanden." }
            holder.binding.tvImageCount.text = "${report.imagePaths.size} Bilder angehängt"
            
            holder.binding.root.setOnClickListener {
                val intent = Intent(this@ReportHistoryActivity, DeviceReportActivity::class.java).apply {
                    putExtra("report_json", com.google.gson.Gson().toJson(report))
                }
                startActivity(intent)
            }

            holder.binding.btnDelete.setOnClickListener {
                onDelete(report.id)
            }
        }

        override fun getItemCount() = reports.size
        inner class ViewHolder(val binding: ItemReportHistoryBinding) : RecyclerView.ViewHolder(binding.root)
    }
}