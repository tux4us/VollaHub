package com.volla.hub

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.volla.hub.databinding.ActivityNetworkDiagnosticBinding
import com.volla.hub.databinding.ItemDiagRowBinding
import com.volla.hub.databinding.ItemDiagSectionBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Netzwerk-Diagnose: zeigt Verbindungsstatus, Netzwerkkonfiguration, WLAN-/Mobilfunk-
 * Details und führt DNS-, TCP- und HTTPS-Tests gegen die Volla-Server aus. Das
 * Ergebnis lässt sich kopieren oder teilen (Support-Anfragen in Forum/Telegram).
 * Die eigentliche Logik steckt in [NetworkDiagnostics].
 */
class NetworkDiagnosticActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNetworkDiagnosticBinding
    private lateinit var diagnostics: NetworkDiagnostics

    private var sections: List<DiagSection> = emptyList()
    private var running = false

    private val phonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Unabhängig vom Ergebnis neu auswerten; ohne Berechtigung erscheint wieder der Hinweis.
            startDiagnostics()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityNetworkDiagnosticBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.btn_network_diagnostic)

        ViewCompat.setOnApplyWindowInsetsListener(binding.contentScroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom)
            insets
        }

        diagnostics = NetworkDiagnostics(this)

        binding.btnRun.setOnClickListener { startDiagnostics() }
        binding.btnCopy.setOnClickListener { copyToClipboard() }
        binding.btnShare.setOnClickListener { shareResult() }
        binding.btnGrantPhone.setOnClickListener {
            phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }

        startDiagnostics()
    }

    override fun onDestroy() {
        super.onDestroy()
        diagnostics.close()
    }

    private fun startDiagnostics() {
        if (running) return
        running = true
        setUiRunning(true)

        val staticSections = diagnostics.collectStatic()
        sections = staticSections
        render(staticSections)
        binding.cardPhonePermission.visibility =
            if (diagnostics.needsPhonePermission()) View.VISIBLE else View.GONE

        lifecycleScope.launch {
            try {
                sections = staticSections + diagnostics.runTests()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                sections = staticSections + DiagSection(
                    getString(R.string.netdiag_section_hints),
                    listOf(DiagRow(getString(R.string.netdiag_hint_label), getString(R.string.error_generic, message), DiagStatus.ERROR))
                )
            } finally {
                running = false
                setUiRunning(false)
            }
            render(sections)
            updateSummary()
        }
    }

    private fun setUiRunning(isRunning: Boolean) {
        binding.progress.visibility = if (isRunning) View.VISIBLE else View.GONE
        binding.btnRun.isEnabled = !isRunning
        binding.btnCopy.isEnabled = !isRunning
        binding.btnShare.isEnabled = !isRunning
        binding.btnRun.setText(if (isRunning) R.string.netdiag_running else R.string.netdiag_rerun)
        if (isRunning) {
            binding.summaryIcon.text = ""
            binding.tvSummary.setText(R.string.netdiag_running)
        }
    }

    private fun updateSummary() {
        val (errors, warnings) = diagnostics.countIssues(sections)
        val status = when {
            errors > 0 -> DiagStatus.ERROR
            warnings > 0 -> DiagStatus.WARN
            else -> DiagStatus.OK
        }
        binding.summaryIcon.text = symbolFor(status)
        binding.summaryIcon.setTextColor(colorFor(status, binding.summaryIcon))
        binding.tvSummary.text = when (status) {
            DiagStatus.ERROR -> getString(R.string.netdiag_summary_error, errors, warnings)
            DiagStatus.WARN -> getString(R.string.netdiag_summary_warn, warnings)
            else -> getString(R.string.netdiag_summary_ok)
        }
    }

    private fun render(list: List<DiagSection>) {
        val container = binding.sectionsContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (section in list) {
            val sectionBinding = ItemDiagSectionBinding.inflate(inflater, container, false)
            sectionBinding.tvSectionTitle.text = section.title
            for (row in section.rows) {
                val rowBinding = ItemDiagRowBinding.inflate(inflater, sectionBinding.rowsContainer, false)
                rowBinding.tvStatus.text = symbolFor(row.status)
                rowBinding.tvStatus.setTextColor(colorFor(row.status, rowBinding.tvStatus))
                rowBinding.tvLabel.text = row.label
                rowBinding.tvValue.text = row.value
                sectionBinding.rowsContainer.addView(rowBinding.root)
            }
            container.addView(sectionBinding.root)
        }
    }

    private fun symbolFor(status: DiagStatus): String = when (status) {
        DiagStatus.OK -> "✔"
        DiagStatus.WARN -> "▲"
        DiagStatus.ERROR -> "✖"
        DiagStatus.INFO -> "•"
    }

    private fun colorFor(status: DiagStatus, anchor: View): Int = when (status) {
        DiagStatus.OK -> ContextCompat.getColor(this, R.color.diag_ok)
        DiagStatus.WARN -> ContextCompat.getColor(this, R.color.diag_warn)
        DiagStatus.ERROR -> ContextCompat.getColor(this, R.color.diag_error)
        DiagStatus.INFO -> MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOutline)
    }

    private fun copyToClipboard() {
        if (sections.isEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.netdiag_share_subject), diagnostics.toPlainText(sections))
        )
        Toast.makeText(this, R.string.netdiag_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareResult() {
        if (sections.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.netdiag_share_subject))
            putExtra(Intent.EXTRA_TEXT, diagnostics.toPlainText(sections))
        }
        startActivity(Intent.createChooser(intent, getString(R.string.netdiag_share_chooser)))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_network_diagnostic, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_run_diagnostic -> {
                startDiagnostics()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
