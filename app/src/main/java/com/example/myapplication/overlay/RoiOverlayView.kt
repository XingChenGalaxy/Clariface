package com.example.myapplication.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

class RoiOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5500E676")
        style = Paint.Style.FILL
    }

    private var roiRect: Rect? = null

    fun updateRoi(rect: Rect?) {
        roiRect = rect?.let { Rect(it) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = roiRect ?: return
        if (rect.width() <= 0 || rect.height() <= 0) {
            return
        }
        canvas.drawRect(rect, backgroundPaint)
        canvas.drawRect(rect, strokePaint)
    }
}

