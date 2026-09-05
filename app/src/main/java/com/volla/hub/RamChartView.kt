package com.volla.hub

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

class RamChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dataPoints = mutableListOf<Float>()
    private val maxPoints = 60 // 5 minutes at 5s intervals
    
    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val gridPaint = Paint().apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 1f
        alpha = 80
    }

    private val textPaint = Paint().apply {
        textSize = 24f
        isAntiAlias = true
    }

    fun addSample(usedPercent: Float) {
        dataPoints.add(usedPercent)
        if (dataPoints.size > maxPoints) {
            dataPoints.removeAt(0)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val typedValue = TypedValue()
        context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
        val accentColor = typedValue.data

        context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        val textColor = typedValue.data

        linePaint.color = accentColor
        fillPaint.color = accentColor
        fillPaint.alpha = 60
        textPaint.color = textColor

        val w = width.toFloat()
        val h = height.toFloat()
        val paddingRight = 80f
        val paddingBottom = 40f
        val chartW = w - paddingRight
        val chartH = h - paddingBottom
        val stepX = chartW / (maxPoints - 1)

        // Draw Y Axis Labels & Grid
        for (i in 0..4) {
            val y = chartH * i / 4
            canvas.drawLine(0f, y, chartW, y, gridPaint)
            val label = "${(4 - i) * 25}%"
            canvas.drawText(label, chartW + 10f, y + 10f, textPaint)
        }

        // Draw X Axis Labels
        canvas.drawText("-5 Min", 0f, h - 5f, textPaint)
        canvas.drawText("Jetzt", chartW - 60f, h - 5f, textPaint)

        if (dataPoints.isEmpty()) return

        val path = Path()
        val fillPath = Path()
        
        val startIdx = maxPoints - dataPoints.size
        dataPoints.forEachIndexed { index, value ->
            val x = (index + startIdx) * stepX
            val y = chartH - (value / 100f * chartH)
            
            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, chartH)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            
            if (index == dataPoints.size - 1) {
                fillPath.lineTo(x, chartH)
                fillPath.close()
            }
        }

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}