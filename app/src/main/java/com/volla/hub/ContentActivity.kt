package com.volla.hub

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.volla.hub.databinding.ActivityContentBinding

class ContentActivity : AppCompatActivity() {
    private lateinit var binding: ActivityContentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra("url") ?: ""
        val title = intent.getStringExtra("title") ?: ""

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = title

        setupWebView()
        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            loadContent(url)
        }

        // Back-Button Handler
        onBackPressedDispatcher.addCallback(this) {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)

            // Optimale Mobile-Einstellungen
            useWideViewPort = false  // Wichtig: false für Wiki
            loadWithOverviewMode = false  // Wichtig: false
            layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.NORMAL

            // Bessere Schriftgrößen
            textZoom = 100
            minimumFontSize = 14
            defaultFontSize = 16

            domStorageEnabled = true
        }
    }

    private fun loadContent(url: String) {
        binding.progressBar.visibility = View.VISIBLE

        if (url.contains("wiki.volla.online")) {
            binding.webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    binding.progressBar.visibility = View.GONE

                    view?.evaluateJavascript("""
                    (function() {
                        // Mobile Viewport - BREITER
                        var meta = document.createElement('meta');
                        meta.name = 'viewport';
                        meta.content = 'width=device-width, initial-scale=1.0, user-scalable=yes';
                        var existing = document.querySelector('meta[name="viewport"]');
                        if (existing) {
                            existing.remove();
                        }
                        document.getElementsByTagName('head')[0].appendChild(meta);
                        
                        // CSS für volle Breite
                        var style = document.createElement('style');
                        style.innerHTML = `
                            body { 
                                font-size: 16px !important; 
                                line-height: 1.6 !important;
                                padding: 8px !important;
                                margin: 0 !important;
                                max-width: 100% !important;
                                width: 100% !important;
                            }
                            #content, #mw-content-text, .mw-parser-output {
                                max-width: 100% !important;
                                width: 100% !important;
                                margin: 0 !important;
                                padding: 8px !important;
                            }
                            img { 
                                max-width: 100% !important; 
                                height: auto !important; 
                            }
                            table { 
                                width: 100% !important; 
                                font-size: 14px !important;
                            }
                            pre, code { 
                                font-size: 13px !important; 
                                overflow-x: auto !important;
                                max-width: 100% !important;
                            }
                            #mw-navigation, .mw-jump-link, #mw-head {
                                display: none !important; 
                            }
                            #mw-page-base, #mw-head-base {
                                display: none !important;
                            }
                        `;
                        document.head.appendChild(style);
                        
                        // Scrolle nach oben
                        window.scrollTo(0, 0);
                    })();
                """, null)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    binding.progressBar.visibility = View.VISIBLE
                }
            }
            binding.webView.loadUrl(url)
        } else {
            binding.webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    binding.progressBar.visibility = View.VISIBLE
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    binding.progressBar.visibility = View.GONE
                }
            }
            binding.webView.loadUrl(url)
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
            R.id.action_developer -> {
                showDeveloperInfo()
                true
            }
            R.id.action_theme -> {
                toggleTheme()
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
}