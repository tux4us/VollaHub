package com.volla.hub

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ScreenshotService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    // Long-Screenshot Zustand
    private val capturedBitmaps = mutableListOf<Bitmap>()
    private var isLongMode = false
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        // Signal: ScrollingScreenshotService → ScreenshotService (Scrollen fertig)
        const val ACTION_SCROLLING_DONE = "com.volla.hub.SCROLLING_DONE"
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ScrollingScreenshotService.ACTION_CAPTURE_FRAME -> {
                    captureFrameForLong()
                }
                ACTION_SCROLLING_DONE -> {
                    // AccessibilityService hat Scrollen beendet → jetzt speichern
                    finalizeLongScreenshot()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mediaProjection != null) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                startForegroundService()
                setupMediaProjection(resultCode, data)
                registerFrameReceiver()
                showFloatingButton()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Fehler beim Starten des Screenshot-Dienstes", Toast.LENGTH_LONG).show()
                stopSelf()
            }
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private fun startForegroundService() {
        val channelId = "screenshot_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Screenshot Service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Screenshot-Dienst aktiv")
            .setContentText("Schwebender Button für Screenshots verfügbar")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!isLongMode) stopSelf()
            }
        }, null)

        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 3
        )

        // Frames im Idle-Modus sofort schließen damit der Buffer nicht voll läuft.
        // Android beendet die MediaProjection wenn acquireLatestImage() zu lange
        // nicht aufgerufen wird oder der Buffer überläuft.
        imageReader?.setOnImageAvailableListener({ reader ->
            if (!isLongMode) {
                // Im Idle: Frame sofort verwerfen
                reader.acquireLatestImage()?.close()
            }
            // Im Long-Modus übernimmt captureFrameForLong() das Lesen
        }, handler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "Screenshot",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun registerFrameReceiver() {
        val filter = IntentFilter().apply {
            addAction(ScrollingScreenshotService.ACTION_CAPTURE_FRAME)
            addAction(ACTION_SCROLLING_DONE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, filter)
        }
    }

    // ── Floating Button ──────────────────────────────────────────────────────

    private fun showFloatingButton() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val contextWrapper = android.view.ContextThemeWrapper(this, R.style.Theme_VollaHub)
        floatingView = LayoutInflater.from(contextWrapper)
            .inflate(R.layout.layout_floating_screenshot, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        windowManager?.addView(floatingView, params)

        // Drag auf dem Container-View mit korrektem Event-Dispatching.
        // Buttons sind Kind-Views und erhalten ihre Clicks unabhängig.
        val dragHandle: View = floatingView!!

        dragHandle.setOnTouchListener(object : View.OnTouchListener {
            private var initX = 0; private var initY = 0
            private var initTx = 0f; private var initTy = 0f
            private var isDrag = false

            override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        initX = params.x; initY = params.y
                        initTx = e.rawX; initTy = e.rawY
                        isDrag = false
                        return true  // true: wir wollen MOVE-Events empfangen
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - initTx).toInt()
                        val dy = (e.rawY - initTy).toInt()
                        if (!isDrag && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                            isDrag = true
                        }
                        if (isDrag) {
                            params.x = initX + dx
                            params.y = initY + dy
                            windowManager?.updateViewLayout(floatingView, params)
                        }
                        return isDrag
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!isDrag) v.performClick()
                        return isDrag
                    }
                }
                return false
            }
        })

        // Buttons direkt verdrahten — unabhängig vom Drag-Listener
        floatingView?.findViewById<ImageButton>(R.id.btnFloatingLong)
            ?.setOnClickListener { toggleLongScreenshot() }

        floatingView?.findViewById<ImageButton>(R.id.btnFloatingClose)
            ?.setOnClickListener { stopSelf() }
    }

    // ── Long-Screenshot ──────────────────────────────────────────────────────

    private fun toggleLongScreenshot() {
        if (isLongMode) {
            // Manueller Abbruch → ScrollingService stoppt, sendet ACTION_SCROLLING_DONE zurück
            sendBroadcast(Intent(ScrollingScreenshotService.ACTION_STOP_LONG).also { it.setPackage(packageName) })
        } else {
            isLongMode = true
            // Idle-Listener deaktivieren damit captureFrameForLong() den Frame bekommt
            imageReader?.setOnImageAvailableListener(null, null)
            capturedBitmaps.clear()
            updateLongButton(active = true)
            Toast.makeText(this, "Langer Screenshot startet…", Toast.LENGTH_SHORT).show()
            // Kurz warten bis der Listener sicher deregistriert ist, dann ersten Frame holen
            handler.postDelayed({
                captureFrameForLong()
                handler.postDelayed({
                    sendBroadcast(Intent(ScrollingScreenshotService.ACTION_START_LONG).also { it.setPackage(packageName) })
                }, 300)
            }, 100)
        }
    }

    /**
     * Wird vom AccessibilityService per Broadcast angefordert.
     * Nimmt einen Frame auf, erstellt ein 64×64 Thumbnail als ARGB-Bytes
     * und sendet es zurück damit der Service Duplikate erkennen kann.
     */
    private fun captureFrameForLong() {
        // Retry falls der Buffer kurz leer ist (z.B. nach Listener-Wechsel)
        val bitmap = acquireCurrentFrame()
            ?: run {
                handler.postDelayed({ captureFrameForLong() }, 80)
                return
            }
        capturedBitmaps.add(bitmap)

        // Thumbnail für Ähnlichkeitsvergleich erstellen und zurücksenden
        val thumb = createThumbnail(bitmap, 64, 64)
        val buf = java.nio.ByteBuffer.allocate(thumb.byteCount)
        thumb.copyPixelsToBuffer(buf)
        thumb.recycle()

        val reply = Intent(ScrollingScreenshotService.ACTION_FRAME_READY).apply {
            putExtra("thumb", buf.array())
        }
        sendBroadcast(reply.also { it.setPackage(packageName) })
    }

    private fun finalizeLongScreenshot() {
        // isLongMode bleibt true bis processAndSaveLong() fertig ist,
        // damit der MediaProjection-Callback den Service nicht vorzeitig killt
        updateLongButton(active = false)
        Toast.makeText(this, "Verarbeite ${capturedBitmaps.size} Frames…", Toast.LENGTH_SHORT).show()
        handler.post {
            processAndSaveLong()
            isLongMode = false  // erst jetzt freigeben
            // Idle-Listener wieder aktivieren damit MediaProjection nicht abbricht
            restoreIdleListener()
        }
    }

    private fun restoreIdleListener() {
        imageReader?.setOnImageAvailableListener({ reader ->
            if (!isLongMode) {
                reader.acquireLatestImage()?.close()
            }
        }, handler)
    }

    private fun updateLongButton(active: Boolean) {
        val btn = floatingView?.findViewById<ImageButton>(R.id.btnFloatingLong)
        btn?.setImageResource(
            if (active) android.R.drawable.checkbox_on_background
            else android.R.drawable.ic_menu_sort_by_size
        )
    }

    // ── Frame-Erfassung ──────────────────────────────────────────────────────

    /**
     * Liest den aktuellen Frame aus dem ImageReader.
     * Gibt ein bereinigtes (ohne Row-Padding) Bitmap zurück oder null.
     */
    private fun acquireCurrentFrame(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * image.width
            val paddedWidth = image.width + rowPadding / plane.pixelStride

            val raw = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(plane.buffer)

            // Padding abschneiden
            val clean = Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
            if (raw != clean) raw.recycle()
            clean
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            image.close()
        }
    }

    private fun createThumbnail(src: Bitmap, w: Int, h: Int): Bitmap {
        val thumb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(thumb)
        val srcRect = android.graphics.Rect(0, 0, src.width, src.height)
        val dstRect = android.graphics.Rect(0, 0, w, h)
        canvas.drawBitmap(src, srcRect, dstRect, null)
        return thumb
    }

    // ── Stitching ────────────────────────────────────────────────────────────

    private fun processAndSaveLong() {
        if (capturedBitmaps.isEmpty()) return

        try {
            val result = stitchBitmaps(capturedBitmaps)
            saveBitmap(result)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Fehler beim Zusammensetzen: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            capturedBitmaps.forEach { it.recycle() }
            capturedBitmaps.clear()
        }
    }

    /**
     * Erkennt fixe Bereiche (Header/Footer) die sich zwischen Frames nicht ändern.
     * Gibt Pair(headerHeight, footerHeight) in Pixeln zurück.
     * Strategie: Zeilenweise NCC zwischen erstem und zweitem Frame —
     * Zeilen mit Score > 0.99 sind "statisch" (Toolbar, Statusbar, Navbar).
     */
    private fun detectFixedRegions(first: Bitmap, second: Bitmap): Pair<Int, Int> {
        if (first.width != second.width || first.height != second.height) return Pair(0, 0)
        val w = first.width
        val h = first.height
        val lineA = IntArray(w)
        val lineB = IntArray(w)

        // Header: von oben scannen, aber maximal 20% der Framehöhe
        // und nur wenn KONSEKUTIV identisch (ein Ausreißer bricht ab)
        val maxHeaderH = h / 5
        var headerEnd = 0
        for (y in 0 until maxHeaderH) {
            first.getPixels(lineA, 0, w, 0, y, w, 1)
            second.getPixels(lineB, 0, w, 0, y, w, 1)
            if (ncc(lineA, lineB) > 0.995f) {
                headerEnd = y + 1
            } else {
                break  // erste nicht-identische Zeile → Header endet hier
            }
        }

        // Footer: von unten scannen, maximal 15% der Framehöhe
        val maxFooterH = h * 15 / 100
        var footerStart = h
        for (y in h - 1 downTo h - maxFooterH) {
            first.getPixels(lineA, 0, w, 0, y, w, 1)
            second.getPixels(lineB, 0, w, 0, y, w, 1)
            if (ncc(lineA, lineB) > 0.995f) {
                footerStart = y
            } else {
                break
            }
        }

        val footerHeight = h - footerStart

        // Sicherheitscheck: Inhaltsbereich muss mind. 60% des Frames betragen
        val contentH = h - headerEnd - footerHeight
        if (contentH < h * 6 / 10) {
            // Zu aggressiv erkannt → kein Cropping
            return Pair(0, 0)
        }
        return Pair(headerEnd, footerHeight)
    }

    /**
     * Setzt eine Liste von Bitmaps vertikal zusammen.
     * Header wird einmalig oben behalten, Footer einmalig unten.
     * Der scrollende Inhaltsbereich wird nahtlos zusammengesetzt.
     */
    private fun stitchBitmaps(frames: List<Bitmap>): Bitmap {
        val cfg = frames[0].config ?: Bitmap.Config.ARGB_8888

        if (frames.size == 1) return frames[0].copy(cfg, false)

        // Deduplizierung
        val unique = mutableListOf(frames[0])
        for (i in 1 until frames.size) {
            if (!areSimilar(unique.last(), frames[i], threshold = 0.95f)) {
                unique.add(frames[i])
            }
        }
        if (unique.size == 1) return unique[0].copy(cfg, false)

        // Fixe Regionen anhand der ersten zwei Frames bestimmen
        val (headerH, footerH) = detectFixedRegions(unique[0], unique[1])
        val frameH = unique[0].height
        val contentH = frameH - headerH - footerH

        if (contentH <= 0) {
            // Fallback: kein Cropping, normales Stitching
            var result = unique[0].copy(cfg, false)
            for (i in 1 until unique.size) result = appendBelow(result, unique[i], 0, 0)
            return result
        }

        // Gesamthöhe: Header + alle Inhalts-Streifen + Footer
        // Jeder Frame trägt nur seinen Inhaltsstreifen bei
        // Überlappung zwischen Inhaltsstreifen via TM bestimmen
        val contentStrips = mutableListOf<Bitmap>()
        for (frame in unique) {
            val strip = Bitmap.createBitmap(frame, 0, headerH, frame.width, contentH)
            contentStrips.add(strip)
        }

        var stitchedContent = contentStrips[0].copy(cfg, false)
        for (i in 1 until contentStrips.size) {
            stitchedContent = appendBelow(stitchedContent, contentStrips[i], 0, 0)
        }

        // Header + Content + Footer zusammensetzen
        val totalH = headerH + stitchedContent.height + footerH
        val result = Bitmap.createBitmap(unique[0].width, totalH, cfg)
        val canvas = Canvas(result)

        // Header aus erstem Frame
        if (headerH > 0) {
            canvas.drawBitmap(
                unique[0],
                android.graphics.Rect(0, 0, unique[0].width, headerH),
                android.graphics.Rect(0, 0, result.width, headerH),
                null
            )
        }

        // Gescrollter Inhalt
        canvas.drawBitmap(stitchedContent, 0f, headerH.toFloat(), null)

        // Footer aus letztem Frame
        if (footerH > 0) {
            val lastFrame = unique.last()
            canvas.drawBitmap(
                lastFrame,
                android.graphics.Rect(0, frameH - footerH, lastFrame.width, frameH),
                android.graphics.Rect(0, headerH + stitchedContent.height, result.width, totalH),
                null
            )
        }

        contentStrips.forEach { it.recycle() }
        stitchedContent.recycle()
        return result
    }

    /**
     * Hängt `bottom`-Inhaltsstreifen unterhalb von `top` an.
     * headerH/footerH werden beim Aufruf aus contentStrips bereits
     * herausgeschnitten — dieser Aufruf arbeitet nur auf reinen Inhalts-Bitmaps.
     */
    private fun appendBelow(top: Bitmap, bottom: Bitmap, @Suppress("UNUSED_PARAMETER") hh: Int, @Suppress("UNUSED_PARAMETER") fh: Int): Bitmap {
        val overlap = findOverlapTM(top, bottom)
        val usedBottom = (bottom.height - overlap).coerceAtLeast(1)
        val newHeight = top.height + usedBottom
        val combined = Bitmap.createBitmap(top.width, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)
        canvas.drawBitmap(top, 0f, 0f, null)
        canvas.drawBitmap(
            bottom,
            android.graphics.Rect(0, overlap, bottom.width, bottom.height),
            android.graphics.Rect(0, top.height, top.width, newHeight),
            null
        )
        top.recycle()
        return combined
    }

    /**
     * Template Matching auf reinen Inhaltsstreifen (kein Header/Footer mehr drin).
     * Sucht einen Block von matchLines Zeilen vom Anfang von `bottom` in `top`.
     */
    private fun findOverlapTM(top: Bitmap, bottom: Bitmap): Int {
        val w = top.width
        val topH = top.height
        val botH = bottom.height

        // Template aus dem oberen Bereich von bottom (10% Abstand vom Rand)
        val matchLines = minOf(16, botH / 6, topH / 6).coerceAtLeast(2)
        val templateStartY = (botH / 10).coerceAtLeast(0)
        if (templateStartY + matchLines > botH) return 0

        val template = IntArray(matchLines * w)
        bottom.getPixels(template, 0, w, 0, templateStartY, w, matchLines)

        // Suchbereich: untere Hälfte von top
        val searchEnd = (topH - matchLines).coerceAtLeast(0)
        val searchStart = (topH / 2).coerceAtMost(searchEnd)

        var bestScore = -1f
        var bestY = searchStart

        val candidate = IntArray(matchLines * w)
        for (y in searchEnd downTo searchStart) {
            top.getPixels(candidate, 0, w, 0, y, w, matchLines)
            val score = ncc(template, candidate)
            if (score > bestScore) {
                bestScore = score
                bestY = y
            }
            if (bestScore > 0.998f) break
        }

        if (bestScore < 0.88f) {
            return 0
        }

        val overlapStart = (bestY - templateStartY).coerceAtLeast(0)
        val overlap = topH - overlapStart
        return overlap
    }

    /**
     * Normalisierte Kreuzkorrelation (NCC) für zwei Pixel-Arrays.
     * Arbeitet nur auf dem Grünkanal (schnell, ausreichend für Texterkennung).
     */
    private fun ncc(a: IntArray, b: IntArray): Float {
        var meanA = 0.0; var meanB = 0.0
        for (i in a.indices) {
            meanA += (a[i] shr 8) and 0xFF
            meanB += (b[i] shr 8) and 0xFF
        }
        meanA /= a.size; meanB /= b.size

        var num = 0.0; var denA = 0.0; var denB = 0.0
        for (i in a.indices) {
            val da = ((a[i] shr 8) and 0xFF) - meanA
            val db = ((b[i] shr 8) and 0xFF) - meanB
            num  += da * db
            denA += da * da
            denB += db * db
        }
        val den = Math.sqrt(denA * denB)
        return if (den < 1e-6) 1f else (num / den).toFloat().coerceIn(0f, 1f)
    }

    /** Schneller Ähnlichkeitscheck via Thumbnail-Downscale */
    private fun areSimilar(a: Bitmap, b: Bitmap, threshold: Float): Boolean {
        val ta = createThumbnail(a, 32, 32)
        val tb = createThumbnail(b, 32, 32)
        val pa = IntArray(32 * 32); val pb = IntArray(32 * 32)
        ta.getPixels(pa, 0, 32, 0, 0, 32, 32)
        tb.getPixels(pb, 0, 32, 0, 0, 32, 32)
        ta.recycle(); tb.recycle()
        return ncc(pa, pb) > threshold
    }

    // ── Speichern ────────────────────────────────────────────────────────────

    private fun saveBitmap(bitmap: Bitmap) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "Screenshot_$timeStamp.png"
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + File.separator + "VollaHub"
                )
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        )
        try {
            uri?.let {
                contentResolver.openOutputStream(it)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(it, contentValues, null, null)
                }
                Toast.makeText(this, "Screenshot gespeichert", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Speicherfehler: ${e.message}", Toast.LENGTH_SHORT).show()
            uri?.let { contentResolver.delete(it, null, null) }
        } finally {
            bitmap.recycle()
        }
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        try {
            isLongMode = false
            handler.removeCallbacksAndMessages(null)
            unregisterReceiver(broadcastReceiver)
            floatingView?.let { windowManager?.removeViewImmediate(it) }
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            capturedBitmaps.forEach { it.recycle() }
            capturedBitmaps.clear()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            floatingView = null
            virtualDisplay = null
            imageReader = null
            mediaProjection = null
        }
    }
}
