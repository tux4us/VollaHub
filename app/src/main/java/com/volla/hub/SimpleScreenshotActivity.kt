package com.volla.hub

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SimpleScreenshotActivity : AppCompatActivity() {
    private lateinit var projectionManager: MediaProjectionManager

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Kein Layout nötig, da wir nur die Berechtigung anfragen und sofort starten
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        // Erst kurz warten, damit die Activity sicher im Vordergrund ist, bevor der System-Dialog kommt
        handler.postDelayed({
            startMediaProjection()
        }, 100)
    }

    private val handler = Handler(Looper.getMainLooper())

    private fun startMediaProjection() {
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val serviceIntent = Intent(this, SimpleScreenshotService::class.java).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                Toast.makeText(this, "Berechtigung verweigert", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }
}
