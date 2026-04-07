package com.example.myapplication.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class RoiOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val COLOR_REAL = "#00E676"
        private const val COLOR_FAKE = "#FF3D57"
        private const val ALPHA_REAL = "#2200E676"
        private const val ALPHA_FAKE = "#22FF3D57"
        private const val LABEL_BG_REAL = "#CC00E676"
        private const val LABEL_BG_FAKE = "#CCFF3D57"
        private const val LABEL_TEXT = "#F7FFF9"
        private const val CORNER_RADIUS = 28f
        private const val STROKE_WIDTH = 6f
        private const val LABEL_TEXT_SIZE = 34f
        private const val LABEL_PADDING_H = 22f
        private const val LABEL_PADDING_V = 14f
        private const val SMOOTHING_FACTOR = 0.28f
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(COLOR_REAL)
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(ALPHA_REAL)
        style = Paint.Style.FILL
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(LABEL_BG_REAL)
        style = Paint.Style.FILL
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(LABEL_TEXT)
        textSize = LABEL_TEXT_SIZE
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
    }

    private var targetRect: RectF? = null
    private var displayRect: RectF? = null
    private var fillEnabled: Boolean = false
    private var isFakeFace: Boolean = false

    fun setFillEnabled(enabled: Boolean) {
        fillEnabled = enabled
        invalidate()
    }

    fun updateRoi(rect: Rect?, isFake: Boolean = false) {
        targetRect = rect?.let { RectF(it) }
        isFakeFace = isFake

        val strokeColor = if (isFakeFace) COLOR_FAKE else COLOR_REAL
        val fillColor = if (isFakeFace) ALPHA_FAKE else ALPHA_REAL
        val labelBgColor = if (isFakeFace) LABEL_BG_FAKE else LABEL_BG_REAL
        strokePaint.color = Color.parseColor(strokeColor)
        backgroundPaint.color = Color.parseColor(fillColor)
        labelBgPaint.color = Color.parseColor(labelBgColor)

        if (targetRect == null) {
            displayRect = null
            invalidate()
            return
        }

        if (displayRect == null) {
            displayRect = RectF(targetRect)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val destination = targetRect ?: return
        val current = displayRect ?: RectF(destination).also { displayRect = it }

        smoothRectTowards(current, destination)

        if (current.width() <= 0f || current.height() <= 0f) {
            return
        }

        if (fillEnabled) {
            canvas.drawRoundRect(current, CORNER_RADIUS, CORNER_RADIUS, backgroundPaint)
        }
        canvas.drawRoundRect(current, CORNER_RADIUS, CORNER_RADIUS, strokePaint)
        drawLabel(canvas, current)

        if (!isRectCloseEnough(current, destination)) {
            postInvalidateOnAnimation()
        }
    }

    private fun smoothRectTowards(current: RectF, target: RectF) {
        current.left = lerp(current.left, target.left)
        current.top = lerp(current.top, target.top)
        current.right = lerp(current.right, target.right)
        current.bottom = lerp(current.bottom, target.bottom)
    }

    private fun lerp(current: Float, target: Float): Float {
        return current + (target - current) * SMOOTHING_FACTOR
    }

    private fun isRectCloseEnough(current: RectF, target: RectF): Boolean {
        return max(
            max(kotlin.math.abs(current.left - target.left), kotlin.math.abs(current.top - target.top)),
            max(kotlin.math.abs(current.right - target.right), kotlin.math.abs(current.bottom - target.bottom))
        ) < 1.5f
    }

    private fun drawLabel(canvas: Canvas, rect: RectF) {
        val label = if (isFakeFace) "FAKE" else "REAL"
        val textWidth = labelTextPaint.measureText(label)
        val textHeight = labelTextPaint.fontMetrics.run { bottom - top }
        val labelWidth = textWidth + LABEL_PADDING_H * 2f
        val labelHeight = textHeight + LABEL_PADDING_V * 2f

        val left = rect.left
        val top = (rect.top - labelHeight - 12f).coerceAtLeast(12f)
        val labelRect = RectF(left, top, left + labelWidth, top + labelHeight)

        canvas.drawRoundRect(labelRect, CORNER_RADIUS, CORNER_RADIUS, labelBgPaint)
        val baseline = labelRect.top + LABEL_PADDING_V - labelTextPaint.fontMetrics.top
        canvas.drawText(label, labelRect.left + LABEL_PADDING_H, baseline, labelTextPaint)
    }
}
