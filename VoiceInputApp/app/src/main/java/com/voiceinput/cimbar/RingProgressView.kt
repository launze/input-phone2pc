package com.voiceinput.cimbar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A3A32")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9F4A")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val textBounds = Rect()
    private val oval = RectF()

    private var percent = 0

    fun setProgressPercent(value: Int) {
        val next = value.coerceIn(0, 100)
        if (percent != next) {
            percent = next
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val stroke = size * 0.09f
        val inset = stroke / 2f + 2f
        val left = (width - size) / 2f + inset
        val top = (height - size) / 2f + inset
        oval.set(left, top, left + size - inset * 2f, top + size - inset * 2f)
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke
        canvas.drawArc(oval, -90f, 360f, false, trackPaint)
        canvas.drawArc(oval, -90f, percent * 3.6f, false, progressPaint)

        val label = "$percent%"
        textPaint.textSize = size * 0.23f
        textPaint.getTextBounds(label, 0, label.length, textBounds)
        val baseline = height / 2f - (textBounds.top + textBounds.bottom) / 2f
        canvas.drawText(label, width / 2f, baseline, textPaint)
    }
}
