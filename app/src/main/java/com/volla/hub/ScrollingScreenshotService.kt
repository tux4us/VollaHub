package com.volla.hub

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * AccessibilityService für automatisches Scrollen beim Long-Screenshot.
 *
 * Ablauf:
 *  1. ScreenshotActivity startet MediaProjection → ScreenshotService
 *  2. ScreenshotActivity sendet Broadcast ACTION_START_LONG
 *  3. Dieser Service empfängt ihn, beginnt zu scrollen + signalisiert dem
 *     ScreenshotService wann ein Frame aufgenommen werden soll
 *  4. Nach N Schritten ohne neue Inhalte → ACTION_STOP_LONG
 */
class ScrollingScreenshotService : AccessibilityService() {

    companion object {
        const val ACTION_START_LONG = "com.volla.hub.START_LONG_SCREENSHOT"
        const val ACTION_STOP_LONG  = "com.volla.hub.STOP_LONG_SCREENSHOT"
        const val ACTION_CAPTURE_FRAME = "com.volla.hub.CAPTURE_FRAME"
        const val ACTION_FRAME_READY  = "com.volla.hub.FRAME_READY"

        // Scroll-Parameter — kurze Zyklen für maximale Frame-Dichte
        private const val SCROLL_STEP_PX   = 500   // Kleiner = mehr Überlappung = robusteres Stitching
        private const val SCROLL_DURATION  = 200L  // Kurze schnelle Geste
        private const val SETTLE_DELAY     = 250L  // Warten bis Rendering fertig
        private const val CAPTURE_DELAY    = 80L   // Frame direkt nach Settle
        private const val MAX_IDENTICAL    = 3     // Seitenende-Erkennung
        private const val MAX_STEPS        = 150   // Für sehr lange Seiten
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var stepCount = 0
    private var identicalCount = 0

    // Letzter aufgenommener Frame für Duplikat-Erkennung (skaliertes Thumbnail)
    private var lastThumb: Bitmap? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_START_LONG -> startScrollCapture()
                ACTION_STOP_LONG  -> stopScrollCapture()
                ACTION_FRAME_READY -> {
                    // ScreenshotService hat Frame aufgenommen → nächsten Schritt
                    val bitmapBytes = intent.getByteArrayExtra("thumb")
                    if (bitmapBytes != null) {
                        onFrameReceived(bitmapBytes)
                    } else {
                        scheduleNextScroll()
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter().apply {
            addAction(ACTION_START_LONG)
            addAction(ACTION_STOP_LONG)
            addAction(ACTION_FRAME_READY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopScrollCapture() }

    override fun onUnbind(intent: Intent?): Boolean {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        return super.onUnbind(intent)
    }

    // ── Steuerung ────────────────────────────────────────────────────────────

    private fun startScrollCapture() {
        if (isRunning) return
        isRunning = true
        stepCount = 0
        identicalCount = 0
        lastThumb = null

        // Ersten Frame sofort aufnehmen (Seitenanfang)
        requestCapture()
    }

    private fun stopScrollCapture() {
        if (!isRunning) return
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        // handler.removeCallbacksAndMessages(null) verhindert weitere Scroll-Schritte.
        // Die laufende Geste läuft maximal noch SCROLL_DURATION ms aus — akzeptabel.
        lastThumb?.recycle()
        lastThumb = null
        // ScreenshotService informieren dass Scrollen abgeschlossen → Speichern starten
        sendBroadcast(Intent(ScreenshotService.ACTION_SCROLLING_DONE).also { it.setPackage(packageName) })
    }

    // ── Frame-Logik ──────────────────────────────────────────────────────────

    private fun requestCapture() {
        sendBroadcast(Intent(ACTION_CAPTURE_FRAME).also { it.setPackage(packageName) })
    }

    private fun onFrameReceived(thumbBytes: ByteArray) {
        if (!isRunning) return

        val newThumb = decodeThumbnail(thumbBytes)

        if (newThumb != null && lastThumb != null) {
            val similarity = computeSimilarity(lastThumb!!, newThumb)
            if (similarity > 0.97f) {
                // Kaum Änderung → wahrscheinlich Ende der Seite
                identicalCount++
                if (identicalCount >= MAX_IDENTICAL) {
                    newThumb.recycle()
                    stopScrollCapture()
                    return
                }
            } else {
                identicalCount = 0
            }
        }

        lastThumb?.recycle()
        lastThumb = newThumb

        scheduleNextScroll()
    }

    private fun scheduleNextScroll() {
        if (!isRunning) return
        stepCount++
        if (stepCount >= MAX_STEPS) {
            stopScrollCapture()
            return
        }
        handler.postDelayed({ performScroll() }, SETTLE_DELAY)
    }

    // ── Scroll-Geste ─────────────────────────────────────────────────────────

    private fun performScroll() {
        if (!isRunning) return

        val metrics = resources.displayMetrics
        val screenWidth  = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        val startX = screenWidth / 2f
        val startY = (screenHeight * 0.75f)         // Start im unteren Viertel
        val endY   = (startY - SCROLL_STEP_PX).coerceAtLeast(50f)

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, SCROLL_DURATION)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                // Nach Scroll warten bis Seite fertig gerendert, dann Frame anfordern
                handler.postDelayed({
                    if (isRunning) requestCapture()
                }, CAPTURE_DELAY)
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                // Geste abgebrochen (z.B. anderer Touch) → kurz warten, nochmal
                handler.postDelayed({
                    if (isRunning) performScroll()
                }, 300)
            }
        }, handler)
    }

    // ── Ähnlichkeitsberechnung (schnelles Thumbnail-Downscale) ───────────────

    /**
     * Dekodiert das rohe ARGB-ByteArray in ein kleines Thumbnail-Bitmap.
     * Das ByteArray wird vom ScreenshotService als ARGB_8888 auf 64×64 vorskaliert.
     */
    private fun decodeThumbnail(bytes: ByteArray): Bitmap? {
        return try {
            val side = 64
            val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val buf = java.nio.ByteBuffer.wrap(bytes)
            bmp.copyPixelsFromBuffer(buf)
            bmp
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Normalisierte Kreuzkorrelation auf Thumbnail-Ebene.
     * Gibt 0.0 (komplett verschieden) bis 1.0 (identisch) zurück.
     */
    private fun computeSimilarity(a: Bitmap, b: Bitmap): Float {
        val w = a.width
        val h = a.height
        if (b.width != w || b.height != h) return 0f

        val pixA = IntArray(w * h)
        val pixB = IntArray(w * h)
        a.getPixels(pixA, 0, w, 0, 0, w, h)
        b.getPixels(pixB, 0, w, 0, 0, w, h)

        var sumA = 0L; var sumB = 0L
        for (i in pixA.indices) {
            sumA += (pixA[i] and 0xFF)  // nur Blaukanal reicht für Grob-Vergleich
            sumB += (pixB[i] and 0xFF)
        }
        val meanA = sumA.toFloat() / pixA.size
        val meanB = sumB.toFloat() / pixB.size

        var num = 0.0; var denA = 0.0; var denB = 0.0
        for (i in pixA.indices) {
            val da = (pixA[i] and 0xFF) - meanA
            val db = (pixB[i] and 0xFF) - meanB
            num  += da * db
            denA += da * da
            denB += db * db
        }

        val den = Math.sqrt(denA * denB)
        return if (den < 1e-6) 1f else (num / den).toFloat().coerceIn(0f, 1f)
    }
}
