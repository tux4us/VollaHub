package com.volla.hub

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.volla.hub.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var onlineAdapter: ContentAdapter
    private lateinit var blogAdapter: ContentAdapter
    private lateinit var wikiAdapter: ContentAdapter
    private val vollaParser = VollaParser()

    private var allOnlinePages = listOf<ContentItem>()
    private var allBlogPosts = listOf<ContentItem>()
    private var allWikiArticles = listOf<ContentItem>()
    private var currentView = VIEW_ONLINE
    private var currentWikiLanguage = "de"

    companion object {
        const val VIEW_ONLINE = 0
        const val VIEW_BLOG = 1
        const val VIEW_WIKI = 2
        const val VIEW_FORUM = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentView = intent.getIntExtra("view_type", VIEW_ONLINE)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        updateToolbarTitle()

        setupRecyclerViews()
        setupNavigation()
        loadInitialContent()

        binding.swipeRefresh.setOnRefreshListener {
            refreshContent()
        }
    }

    private fun updateToolbarTitle() {
        supportActionBar?.title = when (currentView) {
            VIEW_ONLINE -> "Volla Online"
            VIEW_BLOG -> "Volla Blog"
            VIEW_WIKI -> "Volla Wiki"
            VIEW_FORUM -> "Volla Forum"
            else -> "Volla Hub"
        }
    }

    private fun setupRecyclerViews() {
        onlineAdapter = ContentAdapter { item -> openContent(item) }
        blogAdapter = ContentAdapter { item -> openContent(item) }
        wikiAdapter = ContentAdapter { item -> openContent(item) }

        binding.onlineRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.onlineRecyclerView.adapter = onlineAdapter

        binding.blogRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.blogRecyclerView.adapter = blogAdapter

        binding.wikiRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.wikiRecyclerView.adapter = wikiAdapter
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    finish()
                }
                R.id.nav_social -> {
                    showSocialMediaDialog()
                }
                R.id.nav_chat -> {
                    startActivity(Intent(this, ChatBotActivity::class.java))
                }
            }
            true
        }

        binding.btnLoadMore.setOnClickListener { loadMoreBlogPosts() }

        binding.btnWikiDe.setOnClickListener { loadWikiLanguage("de", "Hauptseite") }
        binding.btnWikiEn.setOnClickListener { loadWikiLanguage("en", "English") }
        binding.btnWikiCs.setOnClickListener { loadWikiLanguage("cs", "%C4%8Cesky_Slovensk%C3%A1") }
        binding.btnWikiIt.setOnClickListener { loadWikiLanguage("it", "Italiano") }
        binding.btnWikiEs.setOnClickListener { loadWikiLanguage("es", "Espa%C3%B1ol") }

        binding.btnForumDe.setOnClickListener { openForumUrl("https://forum.volla.online/viewforum.php?f=94", "Deutsch Forum") }
        binding.btnForumEn.setOnClickListener { openForumUrl("https://forum.volla.online/viewforum.php?f=26", "English Forum") }
        binding.btnForumEs.setOnClickListener { openForumUrl("https://forum.volla.online/viewforum.php?f=119", "Español Forum") }
    }

    private fun openForumUrl(url: String, title: String) {
        val intent = Intent(this, ContentActivity::class.java).apply {
            putExtra("url", url)
            putExtra("title", title)
        }
        startActivity(intent)
    }

    private fun loadInitialContent() {
        showView(currentView)
        when (currentView) {
            VIEW_ONLINE -> loadOnlineContent()
            VIEW_BLOG -> loadBlogContent()
            VIEW_WIKI -> loadWikiLanguage("de", "Hauptseite")
            VIEW_FORUM -> {} // Nur statische Buttons
        }
    }

    private fun showView(viewType: Int) {
        binding.onlineRecyclerView.visibility = if (viewType == VIEW_ONLINE) View.VISIBLE else View.GONE
        binding.blogRecyclerView.visibility = if (viewType == VIEW_BLOG) View.VISIBLE else View.GONE
        binding.wikiContainer.visibility = if (viewType == VIEW_WIKI) View.VISIBLE else View.GONE
        binding.forumContainer.visibility = if (viewType == VIEW_FORUM) View.VISIBLE else View.GONE
        binding.btnLoadMore.visibility = if (viewType == VIEW_BLOG) View.VISIBLE else View.GONE
    }

    private fun loadOnlineContent() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val online = withContext(Dispatchers.IO) { vollaParser.parseOnlinePages() }
                allOnlinePages = online
                onlineAdapter.submitList(online)
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            } catch (e: Exception) {
                showError(e.message)
            }
        }
    }

    private fun loadBlogContent() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val blog = withContext(Dispatchers.IO) { vollaParser.parseBlog() }
                allBlogPosts = blog
                blogAdapter.submitList(blog.take(20))
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            } catch (e: Exception) {
                showError(e.message)
            }
        }
    }

    private fun loadWikiLanguage(lang: String, title: String) {
        currentWikiLanguage = lang
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val articles = withContext(Dispatchers.IO) { vollaParser.parseWiki(title) }
                allWikiArticles = articles
                wikiAdapter.submitList(articles)
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            } catch (e: Exception) {
                showError(e.message)
            }
        }
    }

    private fun loadMoreBlogPosts() {
        val currentSize = blogAdapter.itemCount
        val nextBatch = allBlogPosts.drop(currentSize).take(20)
        if (nextBatch.isNotEmpty()) {
            blogAdapter.submitList(allBlogPosts.take(currentSize + 20))
        }
    }

    private fun refreshContent() {
        loadInitialContent()
    }

    private fun showError(message: String?) {
        binding.progressBar.visibility = View.GONE
        binding.swipeRefresh.isRefreshing = false
        binding.errorText.text = "Fehler: $message"
        binding.errorText.visibility = View.VISIBLE
    }

    private fun openContent(item: ContentItem) {
        val intent = Intent(this, ContentActivity::class.java).apply {
            putExtra("url", item.url)
            putExtra("title", item.title)
        }
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                filterContent(newText ?: "")
                return true
            }
        })
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
            R.id.action_refresh -> {
                refreshContent()
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
        AppCompatDelegate.setDefaultNightMode(
            if (!currentDark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
        recreate()
    }

    private fun filterContent(query: String) {
        when (currentView) {
            VIEW_ONLINE -> {
                val filtered = if (query.isEmpty()) allOnlinePages
                else allOnlinePages.filter { it.title.contains(query, ignoreCase = true) }
                onlineAdapter.submitList(filtered)
            }
            VIEW_BLOG -> {
                val filtered = if (query.isEmpty()) allBlogPosts.take(blogAdapter.itemCount)
                else allBlogPosts.filter {
                    it.title.contains(query, ignoreCase = true) ||
                            it.excerpt.contains(query, ignoreCase = true)
                }
                blogAdapter.submitList(filtered)
            }
            VIEW_WIKI -> {
                val filtered = if (query.isEmpty()) allWikiArticles
                else allWikiArticles.filter { it.title.contains(query, ignoreCase = true) }
                wikiAdapter.submitList(filtered)
            }
            else -> {}
        }
    }

    private fun openUrl(url: String, title: String) {
        val intent = Intent(this, ContentActivity::class.java).apply {
            putExtra("url", url)
            putExtra("title", title)
        }
        startActivity(intent)
    }

    private fun showSocialMediaDialog() {
        val platforms = arrayOf(
            getString(R.string.social_telegram),
            getString(R.string.social_x),
            getString(R.string.social_facebook),
            getString(R.string.social_instagram),
            getString(R.string.social_mastodon)
        )
        val urls = arrayOf(
            "https://t.me/hello_volla",
            "https://x.com/hello_volla",
            "https://www.facebook.com/hellovolla",
            "https://www.instagram.com/hello_volla",
            "https://mastodon.social/@volla"
        )

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.nav_social)
            .setItems(platforms) { _, which ->
                val url = urls[which]
                try {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    startActivity(intent)
                } catch (e: Exception) {
                    openUrl(url, platforms[which])
                }
            }
            .show()
    }
}
