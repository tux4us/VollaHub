package com.volla.hub

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * Ring-/Sunburst-Diagramm im Stil von KDE Filelight:
 * - Innerer Ring: aktueller Ordner, aufgeteilt in seine direkten Kinder
 * - Äußerer Ring: jeweils die Kinder der inneren Segmente (eine Ebene tiefer)
 * - Tap auf ein Segment zoomt eine Ebene rein (Callback an Activity)
 * - Tap in die Mitte zoomt eine Ebene raus
 */
class SunburstChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentNode: StorageNode? = null
    var onSegmentTapped: ((StorageNode) -> Unit)? = null
    var onCenterTapped: (() -> Unit)? = null

    private data class Segment(val node: StorageNode, val startAngle: Float, val sweepAngle: Float, val ring: Int)
    private val innerSegments = mutableListOf<Segment>()
    private val outerSegments = mutableListOf<Segment>()

    private val innerOval = RectF()
    private val outerOval = RectF()
    private val centerOval = RectF()

    private val segmentPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val strokePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#33000000")
    }
    private val centerTextPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val centerSubTextPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        alpha = 160
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e.x, e.y)
            return true
        }
    })

    fun setRootNode(node: StorageNode) {
        currentNode = node
        computeSegments()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val dx = x - cx
        val dy = y - cy
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < -90f) angle += 360f // Start bei 12 Uhr = -90°

        val innerRadius = innerOval.width() / 2f
        val outerRadius = outerOval.width() / 2f
        val centerRadius = centerOval.width() / 2f

        when {
            dist <= centerRadius -> onCenterTapped?.invoke()
            dist <= innerRadius -> findSegmentAt(innerSegments, angle)?.let { onSegmentTapped?.invoke(it.node) }
            dist <= outerRadius -> findSegmentAt(outerSegments, angle)?.let { onSegmentTapped?.invoke(it.node) }
        }
    }

    private fun findSegmentAt(segments: List<Segment>, angleDeg: Float): Segment? {
        val normalized = if (angleDeg < -90f) angleDeg + 360f else angleDeg
        return segments.firstOrNull {
            val end = it.startAngle + it.sweepAngle
            normalized >= it.startAngle && normalized < end
        }
    }

    private fun computeSegments() {
        innerSegments.clear()
        outerSegments.clear()
        val node = currentNode ?: return
        val children = node.sortedChildren().filter { it.sizeBytes > 0 }
        val total = children.sumOf { it.sizeBytes }.coerceAtLeast(1L)

        var angle = -90f
        for (child in children) {
            val sweep = 360f * (child.sizeBytes.toFloat() / total.toFloat())
            if (sweep < 0.5f) { angle += sweep; continue }
            innerSegments.add(Segment(child, angle, sweep, 0))

            // Äußerer Ring: Kinder von child (falls vorhanden), sonst leer lassen
            val grandChildren = child.sortedChildren().filter { it.sizeBytes > 0 }
            if (grandChildren.isNotEmpty()) {
                val childTotal = grandChildren.sumOf { it.sizeBytes }.coerceAtLeast(1L)
                var gAngle = angle
                for (gc in grandChildren) {
                    val gSweep = sweep * (gc.sizeBytes.toFloat() / childTotal.toFloat())
                    if (gSweep < 0.5f) { gAngle += gSweep; continue }
                    outerSegments.add(Segment(gc, gAngle, gSweep, 1))
                    gAngle += gSweep
                }
            }
            angle += sweep
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = max(0, minOf(w, h)).toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val outerR = size / 2f * 0.94f
        val innerR = outerR * 0.62f
        val centerR = innerR * 0.55f

        outerOval.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        innerOval.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR)
        centerOval.set(cx - centerR, cy - centerR, cx + centerR, cy + centerR)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val node = currentNode ?: return

        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        val textColor = typedValue.data
        centerTextPaint.color = textColor
        centerSubTextPaint.color = textColor

        // Äußerer Ring
        for (seg in outerSegments) {
            segmentPaint.color = Color.parseColor(seg.node.category.colorHex)
            segmentPaint.alpha = 210
            canvas.drawArc(outerOval, seg.startAngle, seg.sweepAngle, true, segmentPaint)
        }
        // Loch für den äußeren Ring ausschneiden (nur Ring, kein Kreis) durch Übermalen mit Hintergrund
        val bgPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL; color = resolveWindowBackground() }
        canvas.drawOval(innerOval, bgPaint)

        // Innerer Ring
        for (seg in innerSegments) {
            segmentPaint.color = Color.parseColor(seg.node.category.colorHex)
            canvas.drawArc(innerOval, seg.startAngle, seg.sweepAngle, true, segmentPaint)
            canvas.drawArc(innerOval, seg.startAngle, seg.sweepAngle, true, strokePaint)
        }
        for (seg in outerSegments) {
            canvas.drawArc(outerOval, seg.startAngle, seg.sweepAngle, true, strokePaint)
        }

        // Zentrum
        canvas.drawOval(centerOval, bgPaint)
        centerTextPaint.textSize = centerOval.width() * 0.16f
        centerSubTextPaint.textSize = centerOval.width() * 0.11f
        val cx = centerOval.centerX()
        val cy = centerOval.centerY()
        canvas.drawText(node.name.ifEmpty { context.getString(R.string.storage_root_label) }, cx, cy - 6f, centerTextPaint)
        canvas.drawText(formatBytes(node.sizeBytes), cx, cy + centerSubTextPaint.textSize + 2f, centerSubTextPaint)
    }

    private fun resolveWindowBackground(): Int {
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.windowBackground, typedValue, true)
        return if (typedValue.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT &&
            typedValue.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
            typedValue.data
        } else {
            // Fallback, falls windowBackground ein Drawable statt Farbe ist
            val nightModeFlags = context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            if (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES) Color.parseColor("#121212")
            else Color.WHITE
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.size - 1) {
        value /= 1024.0
        unitIndex++
    }
    return if (unitIndex == 0) "${value.toInt()} ${units[unitIndex]}"
    else String.format("%.1f %s", value, units[unitIndex])
}
