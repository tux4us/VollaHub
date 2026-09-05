package com.volla.hub

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.volla.hub.databinding.ActivityScreenshotBinding

class ScreenshotActivity : AppCompatActivity() {
    private lateinit var binding: ActivityScreenshotBinding
    private lateinit var projectionManager: MediaProjectionManager

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_OVERLAY_PERMISSION = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenshotBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Screenshot"

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        binding.btnStartService.setOnClickListener {
            checkPermissionsAndStart()
        }

        binding.toolbar.title = getString(R.string.btn_screenshot)
        supportActionBar?.title = getString(R.string.btn_screenshot)

        // Hinweis falls AccessibilityService nicht aktiv
        checkAccessibilityServiceHint()
    }

    /**
     * Zeigt einen einmaligen Hinweis wenn der ScrollingScreenshotService
     * noch nicht in den Eingabehilfen aktiviert wurde.
     */
    private fun checkAccessibilityServiceHint() {
        val prefs = getSharedPreferences("screenshot_prefs", Context.MODE_PRIVATE)
        val hintShown = prefs.getBoolean("accessibility_hint_shown", false)
        if (hintShown) return

        val enabled = isAccessibilityServiceEnabled()
        if (!enabled) {
            AlertDialog.Builder(this)
                .setTitle("Langer Screenshot")
                .setMessage(
                    "Für automatisches Scrollen beim langen Screenshot muss einmalig ein " +
                    "Eingabehilfen-Dienst aktiviert werden.\n\n" +
                    "Einstellungen → Eingabehilfen → VollaHub Scroll-Dienst → Aktivieren\n\n" +
                    "Normale Screenshots funktionieren sofort ohne diese Einstellung."
                )
                .setPositiveButton("Zu den Einstellungen") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Später", null)
                .show()
            prefs.edit().putBoolean("accessibility_hint_shown", true).apply()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = packageName + "/" + ScrollingScreenshotService::class.java.name
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any { it.equals(serviceName, ignoreCase = true) }
    }

    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
        } else {
            startMediaProjection()
        }
    }

    private fun startMediaProjection() {
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    Settings.canDrawOverlays(this)
                ) {
                    startMediaProjection()
                } else {
                    Toast.makeText(this, "Overlay-Berechtigung wird benötigt", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val serviceIntent = Intent(this, ScreenshotService::class.java).apply {
                        putExtra("resultCode", resultCode)
                        putExtra("data", data)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    finish()
                } else {
                    Toast.makeText(this, "Berechtigung verweigert", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
