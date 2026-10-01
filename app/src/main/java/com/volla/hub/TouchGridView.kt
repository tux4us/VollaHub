package com.volla.hub

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import kotlin.math.max

/**
 * Vollbild-Raster für den Touchscreen-Test. Jedes berührte Feld wird grün; sind alle
 * Felder berührt, wird [onAllTouched] einmalig aufgerufen. Multitouch wird über
 * [maxPointers] erfasst.
 */
class TouchGridView(context: Context) : View(context) {

    var onAllTouched: (() -> Unit)? = null
    var hint: String = ""

    var maxPointers = 0
        private set

    private val columns = 6
    private var rows = 0
    private var cell = 0f
    private var touched = BooleanArray(0)
    private var completed = false

    val totalCells: Int get() = columns * rows
    val touchedCells: Int get() = touched.count { it }

    private val fillPaint = Paint().apply {
        color = 0xFF2E7D32.toInt()
        style = Paint.Style.FILL
    }
    private val linePaint = Paint().apply {
        color = 0xFF666666.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
    }

    init {
        setBackgroundColor(0xFF000000.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        cell = w / columns.toFloat()
        rows = max(1, ceil(h / cell).toInt())
        touched = BooleanArray(columns * rows)
        completed = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cell <= 0f) return
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                val left = c * cell
                val top = r * cell
                if (touched[r * columns + c]) {
                    canvas.drawRect(left, top, left + cell, top + cell, fillPaint)
                }
                canvas.drawRect(left, top, left + cell, top + cell, linePaint)
            }
        }
        if (hint.isNotEmpty()) {
            canvas.drawText(hint, width / 2f, height / 2f, textPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (cell <= 0f) return true
        maxPointers = max(maxPointers, event.pointerCount)
        for (h in 0 until event.historySize) {
            for (p in 0 until event.pointerCount) {
                mark(event.getHistoricalX(p, h), event.getHistoricalY(p, h))
            }
        }
        for (p in 0 until event.pointerCount) {
            mark(event.getX(p), event.getY(p))
        }
        invalidate()
        if (!completed && touched.all { it }) {
            completed = true
            post { onAllTouched?.invoke() }
        }
        return true
    }

    private fun mark(x: Float, y: Float) {
        val c = (x / cell).toInt().coerceIn(0, columns - 1)
        val r = (y / cell).toInt().coerceIn(0, rows - 1)
        touched[r * columns + c] = true
    }
}
