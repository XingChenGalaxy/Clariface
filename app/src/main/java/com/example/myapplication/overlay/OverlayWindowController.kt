package com.example.myapplication.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.myapplication.R

class OverlayWindowController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = 24
        y = 180
    }

    private var rootView: View? = null
    private var probabilityText: TextView? = null
    private var fpsText: TextView? = null
    private var latencyText: TextView? = null
    private var labelText: TextView? = null
    private var statusText: TextView? = null
    private var previewContainer: View? = null
    private var previewOffText: TextView? = null
    private var previewImage: ImageView? = null
    private var previewRoi: RoiOverlayView? = null
    private var lastPreviewBitmap: Bitmap? = null
    private var previewEnabled = true

    fun show() {
        if (rootView != null) {
            showDetectingState()
            return
        }
        runCatching {
            val view = LayoutInflater.from(context).inflate(R.layout.view_overlay_metrics, null)
            bindViews(view)
            enableDrag(view)
            windowManager.addView(view, layoutParams)
            view
        }
            .onSuccess {
                rootView = it
                showDetectingState()
                setPreviewVisible(previewEnabled)
            }
            .onFailure {
                rootView = null
            }
    }

    fun isShowing(): Boolean = rootView != null

    fun hide() {
        val view = rootView ?: return
        runCatching { windowManager.removeView(view) }
        rootView = null
        previewImage = null
        previewRoi = null
        previewContainer = null
        previewOffText = null
        lastPreviewBitmap?.recycle()
        lastPreviewBitmap = null
    }

    fun setPreviewVisible(visible: Boolean) {
        previewEnabled = visible
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyPreviewVisibility()
        } else {
            mainHandler.post { applyPreviewVisibility() }
        }
    }

    fun showDetectingState() {
        if (rootView == null) return
        statusText?.text = context.getString(R.string.overlay_detecting_now)
        statusText?.setBackgroundResource(R.drawable.bg_status_running)
        statusText?.setTextColor(ContextCompat.getColor(context, R.color.white))
    }

    fun update(probability: Float, fps: Float, latencyMs: Long, label: Int, isAlert: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateUi(probability, fps, latencyMs, label, isAlert)
        } else {
            mainHandler.post { updateUi(probability, fps, latencyMs, label, isAlert) }
        }
    }

    fun updatePreview(bitmap: Bitmap, roiRect: Rect?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updatePreviewUi(bitmap, roiRect)
        } else {
            mainHandler.post { updatePreviewUi(bitmap, roiRect) }
        }
    }

    private fun updateUi(probability: Float, fps: Float, latencyMs: Long, label: Int, isAlert: Boolean) {
        if (rootView == null) return
        probabilityText?.text = context.getString(R.string.overlay_probability_template, probability)
        fpsText?.text = context.getString(R.string.overlay_fps_template, fps)
        latencyText?.text = context.getString(R.string.overlay_latency_template, latencyMs)
        labelText?.text = context.getString(R.string.overlay_label_template, label)
        statusText?.text = if (isAlert) context.getString(R.string.status_alert_short) else context.getString(R.string.status_running_short)
        statusText?.setBackgroundResource(if (isAlert) R.drawable.bg_status_alert else R.drawable.bg_status_running)
        statusText?.setTextColor(ContextCompat.getColor(context, R.color.white))
    }

    private fun updatePreviewUi(bitmap: Bitmap, roiRect: Rect?) {
        if (rootView == null || !previewEnabled) {
            bitmap.recycle()
            return
        }
        val old = lastPreviewBitmap
        previewImage?.setImageBitmap(bitmap)
        previewRoi?.updateRoi(roiRect)
        lastPreviewBitmap = bitmap
        old?.recycle()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag(view: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (downX - event.rawX).toInt()
                    val deltaY = (event.rawY - downY).toInt()
                    layoutParams.x = startX + deltaX
                    layoutParams.y = (startY + deltaY).coerceAtLeast(0)
                    runCatching { windowManager.updateViewLayout(view, layoutParams) }
                    true
                }

                else -> false
            }
        }
    }

    private fun bindViews(view: View) {
        probabilityText = view.findViewById(R.id.overlayProbabilityText)
        fpsText = view.findViewById(R.id.overlayFpsText)
        latencyText = view.findViewById(R.id.overlayLatencyText)
        labelText = view.findViewById(R.id.overlayLabelText)
        statusText = view.findViewById(R.id.overlayStatusPill)
        previewContainer = view.findViewById(R.id.overlayPreviewContainer)
        previewOffText = view.findViewById(R.id.overlayPreviewOffText)
        previewImage = view.findViewById(R.id.overlayPreviewImage)
        previewRoi = view.findViewById(R.id.overlayPreviewRoi)
    }

    private fun applyPreviewVisibility() {
        if (rootView == null) return
        previewContainer?.visibility = if (previewEnabled) View.VISIBLE else View.GONE
        previewOffText?.visibility = if (previewEnabled) View.GONE else View.VISIBLE
        if (!previewEnabled) {
            previewImage?.setImageDrawable(null)
            previewRoi?.updateRoi(null)
            lastPreviewBitmap?.recycle()
            lastPreviewBitmap = null
        }
    }
}

