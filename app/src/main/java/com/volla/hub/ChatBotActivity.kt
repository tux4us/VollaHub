package com.volla.hub

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityChatbotBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.inputLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = if (ime.bottom > systemBars.bottom) ime.bottom else systemBars.bottom
            
            val density = resources.displayMetrics.density
            val p8 = (8 * density).toInt()
            
            v.updatePadding(
                left = p8,
                top = p8,
                right = p8,
                bottom = bottomInset + p8
            )
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.helpbot_toolbar_title)

        adapter = ChatAdapter(
            onContentClick = { item ->
                val intent = Intent(this, ContentActivity::class.java).apply {
                    putExtra("url", item.url)
                    putExtra("title", item.title)
                }
                startActivity(intent)
            },
            onActionClick = { action ->
                handleChatAction(action)
            }
        )

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
                AppInfoDialog.show(this)
                true
            }
            R.id.action_theme -> {
                toggleTheme()
                true
            }
            R.id.action_lang_de -> {
                LanguageHelper.setLanguage(this, LanguageHelper.LANG_DE)
                true
            }
            R.id.action_lang_en -> {
                LanguageHelper.setLanguage(this, LanguageHelper.LANG_EN)
                true
            }
            R.id.action_report -> {
                startActivity(Intent(this, DeviceReportActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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

    private fun handleChatAction(action: ChatAction) {
        when(action) {
            ChatAction.OPEN_LOCATION -> startActivity(Intent(this, LocationShareActivity::class.java))
            ChatAction.TAKE_SCREENSHOT -> startActivity(Intent(this, ScreenshotActivity::class.java))
            ChatAction.OPEN_STORAGE_ANALYSIS -> startActivity(Intent(this, StorageAnalysisActivity::class.java))
            ChatAction.CREATE_REPORT -> startActivity(Intent(this, DeviceReportActivity::class.java))
            ChatAction.OPEN_WIKI -> {
                val intent = Intent(this, MainActivity::class.java)
                intent.putExtra("view_type", MainActivity.VIEW_WIKI)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
                finish()
            }
            ChatAction.OPEN_FORUM -> {
                val intent = Intent(this, MainActivity::class.java)
                intent.putExtra("view_type", MainActivity.VIEW_FORUM)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
                finish()
            }
            ChatAction.OPEN_BLOG -> {
                val intent = Intent(this, MainActivity::class.java)
                intent.putExtra("view_type", MainActivity.VIEW_BLOG)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
                finish()
            }
        }
    }

    private fun sendMessage(query: String) {
        adapter.addMessage(ChatMessage(query, true))
        binding.etMessage.text.clear()
        binding.progressBar.visibility = View.VISIBLE

        val lang = LanguageHelper.getCurrentLanguage(this)
        
        lifecycleScope.launch {
            try {
                // Intent Erkennung (einfach)
                val detectedAction = detectIntent(query)
                
                val wikiResults = withContext(Dispatchers.IO) { vollaParser.searchWiki(query, lang) }
                val forumResults = withContext(Dispatchers.IO) { vollaParser.searchForum(query, lang) }
                val onlineResults = withContext(Dispatchers.IO) { vollaParser.searchOnline(query, lang) }
                val faqResults = withContext(Dispatchers.IO) { vollaParser.searchFaqs(query, lang) }
                val downloadResults = withContext(Dispatchers.IO) { vollaParser.searchDownloads(query, lang) }
                val ubportsResults = withContext(Dispatchers.IO) { vollaParser.searchUbports(query) }
                
                val combined = (faqResults + wikiResults + forumResults + onlineResults + ubportsResults + downloadResults)
                    .sortedByDescending { it.second }
                    .distinctBy { it.first.url }
                    .map { it.first }
                
                if (combined.isEmpty() && detectedAction == null) {
                    adapter.addMessage(ChatMessage(getString(R.string.chat_no_results), false))
                } else {
                    val responseText = if (detectedAction != null) {
                        if (lang == "de") "Ich habe eine passende Funktion in der App für dich gefunden:"
                        else "I found a matching feature in the app for you:"
                    } else {
                        getString(R.string.chat_results_found)
                    }
                    adapter.addMessage(ChatMessage(responseText, false, combined.take(15), detectedAction))
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

    private fun detectIntent(query: String): ChatAction? {
        val q = query.lowercase()
        return when {
            // Ortung / Location
            q.contains("ortung") || q.contains("location") || q.contains("karte") || q.contains("map") || 
            q.contains("teilen") || q.contains("share") || q.contains("gps") || q.contains("tracking") || 
            q.contains("verfolgen") || q.contains("finden") -> ChatAction.OPEN_LOCATION
            
            // Screenshot
            q.contains("screenshot") || q.contains("bildschirmfoto") || q.contains("abfotografieren") || 
            q.contains("bildschirm") || q.contains("screen") || q.contains("aufnehmen") -> ChatAction.TAKE_SCREENSHOT
            
            // Speicher-Analyse
            q.contains("speicher") || q.contains("storage") || q.contains("platz") || q.contains("voll") || 
            q.contains("bereinigen") || q.contains("aufräumen") || q.contains("dateien") || q.contains("files") || 
            q.contains("analysieren") || q.contains("platzmangel") -> ChatAction.OPEN_STORAGE_ANALYSIS
            
            // Geräte-Report / Support
            q.contains("report") || q.contains("bericht") || q.contains("support") || q.contains("hilfe") || 
            q.contains("problem") || q.contains("fehler") || q.contains("bug") || q.contains("kaputt") || 
            q.contains("geht nicht") || q.contains("protokoll") || q.contains("log") || q.contains("anfrage") -> ChatAction.CREATE_REPORT
            
            // Wiki
            q.contains("wiki") || q.contains("lexikon") || q.contains("nachschlagen") || q.contains("begriff") -> ChatAction.OPEN_WIKI
            
            // Forum
            q.contains("forum") || q.contains("community") || q.contains("diskussion") || q.contains("austausch") || q.contains("fragen") -> ChatAction.OPEN_FORUM
            
            // Blog
            q.contains("blog") || q.contains("news") || q.contains("neuigkeiten") || q.contains("artikel") || q.contains("aktuell") -> ChatAction.OPEN_BLOG
            
            else -> null
        }
    }
}