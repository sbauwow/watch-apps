package com.vescwatch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Circular gauge that fills clockwise to show duty cycle percentage.
 * Scaled down for 360x360 watch display (100dp target).
 */
class DutyCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val STROKE_WIDTH = 8f
        private const val START_ANGLE = -90f // 12 o'clock
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
        color = Color.parseColor("#333333")
        strokeCap = Paint.Cap.ROUND
    }

    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        textSize = 72f
        isFakeBoldText = true
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#9E9E9E")
        textSize = 14f
    }

    private val arcRect = RectF()
    private var duty = 0f // 0–100
    private var fgColor = Color.WHITE

    fun setDuty(pct: Float, color: Int) {
        duty = pct.coerceIn(0f, 100f)
        fgColor = color
        fgPaint.color = color
        textPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val pad = STROKE_WIDTH / 2f + 3f
        val size = minOf(w, h)
        val left = (w - size) / 2f + pad
        val top = (h - size) / 2f + pad
        arcRect.set(left, top, left + size - 2 * pad, top + size - 2 * pad)

        // Background ring
        canvas.drawArc(arcRect, 0f, 360f, false, bgPaint)

        // Foreground arc
        val sweep = duty / 100f * 360f
        if (sweep > 0) {
            canvas.drawArc(arcRect, START_ANGLE, sweep, false, fgPaint)
        }

        // Percentage text centered
        val cx = w / 2f
        val cy = h / 2f
        val txt = String.format("%.0f", duty)
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(txt, cx, textY, textPaint)

        // "%" label below
        canvas.drawText("%", cx, textY + textPaint.textSize * 0.55f, labelPaint)
    }
}
