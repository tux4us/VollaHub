package com.volla.hub

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.volla.hub.databinding.ActivityContentBinding

class ContentActivity : AppCompatActivity() {
    private lateinit var binding: ActivityContentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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
            useWideViewPort = true
            loadWithOverviewMode = true
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING

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
                            * { 
                                box-sizing: border-box !important; 
                            }
                            html, body { 
                                margin: 0 !important;
                                padding: 0 !important;
                                width: 100% !important;
                                max-width: 100% !important;
                                overflow-x: hidden !important;
                            }
                            #content {
                                margin: 0 !important;
                                padding: 12px !important;
                                width: 100% !important;
                                max-width: 100% !important;
                                display: block !important;
                            }
                            #mw-navigation, #mw-head, #mw-panel, #footer, .mw-jump-link {
                                display: none !important; 
                            }
                            img { 
                                max-width: 100% !important; 
                                height: auto !important; 
                            }
                            table { 
                                display: block !important;
                                width: 100% !important; 
                                overflow-x: auto !important;
                            }
                            pre, code { 
                                white-space: pre-wrap !important;
                                word-wrap: break-word !important;
                                overflow-x: auto !important;
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
            else -> super.onOptionsItemSelected(item)
        }
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