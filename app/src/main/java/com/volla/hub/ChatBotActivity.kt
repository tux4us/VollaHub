package com.volla.hub

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.volla.hub.databinding.ActivityChatbotBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatBotActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChatbotBinding
    private lateinit var adapter: ChatAdapter
    private val vollaParser = VollaParser()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatbotBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "VollaHub HelpBot"

        adapter = ChatAdapter { item ->
            val intent = Intent(this, ContentActivity::class.java).apply {
                putExtra("url", item.url)
                putExtra("title", item.title)
            }
            startActivity(intent)
        }

        binding.chatRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.chatRecyclerView.adapter = adapter

        binding.btnSend.setOnClickListener {
            val query = binding.etMessage.text.toString()
            if (query.isNotBlank()) {
                sendMessage(query)
            }
        }

        // Willkommensnachricht
        adapter.addMessage(ChatMessage(getString(R.string.chat_welcome), false))
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
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                finish()
                true
            }
            R.id.action_developer -> {
                showDeveloperInfo()
                true
            }
            R.id.action_theme -> {
                toggleTheme()
                true
            }
            R.id.action_report -> {
                startActivity(Intent(this, DeviceReportActivity::class.java))
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
        val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val currentDark = prefs.getBoolean("dark_theme", false)
        prefs.edit().putBoolean("dark_theme", !currentDark).apply()
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            if (!currentDark) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
        recreate()
    }

    private fun sendMessage(query: String) {
        adapter.addMessage(ChatMessage(query, true))
        binding.etMessage.text.clear()
        binding.progressBar.visibility = View.VISIBLE

        // "Training" / System-Definition für die Zukunft
        // Der Bot agiert als freundlicher, rein deutschsprachiger Volla-Experte
        val botSystemPrompt = "Name: Volla HelpBot. Sprache: Deutsch. Mission: Hilfe zum Volla Phone."

        lifecycleScope.launch {
            try {
                val wikiResults = withContext(Dispatchers.IO) { vollaParser.searchWiki(query) }
                val forumResults = withContext(Dispatchers.IO) { vollaParser.searchForum(query) }
                val onlineResults = withContext(Dispatchers.IO) { vollaParser.searchOnline(query) }
                
                val combined = wikiResults + forumResults + onlineResults
                
                if (combined.isEmpty()) {
                    adapter.addMessage(ChatMessage(getString(R.string.chat_no_results), false))
                } else {
                    adapter.addMessage(ChatMessage(getString(R.string.chat_results_found), false, combined))
                }
            } catch (e: Exception) {
                adapter.addMessage(ChatMessage(getString(R.string.chat_error), false))
                android.util.Log.e("ChatBot", "Fehler: ${e.message}")
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.chatRecyclerView.scrollToPosition(adapter.itemCount - 1)
            }
        }
    }
}