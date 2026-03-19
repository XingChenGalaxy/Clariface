package com.example.myapplication.overlay

import android.annotation.SuppressLint
import android.content.Context
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

    private val roiLayerParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
    }

    private var rootView: View? = null
    private var screenRoiView: RoiOverlayView? = null
    private var metricsVisible = true
    private var compactMode = false
    private var probabilityText: TextView? = null
    private var fpsText: TextView? = null
    private var latencyText: TextView? = null
    private var labelText: TextView? = null
    private var statusText: TextView? = null
    private var metricsContainer: View? = null

    fun show() {
        ensureScreenRoiLayer()
        if (!metricsVisible) return
        ensureMetricsCard()
        showDetectingState()
    }

    fun isShowing(): Boolean = rootView != null || screenRoiView != null

    fun hide() {
        rootView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        rootView = null
        screenRoiView?.let { roiView ->
            runCatching { windowManager.removeView(roiView) }
        }
        screenRoiView = null
        metricsContainer = null
    }

    fun setMetricsVisible(visible: Boolean) {
        metricsVisible = visible
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyMetricsVisibility()
        } else {
            mainHandler.post { applyMetricsVisibility() }
        }
    }

    fun setCompactMode(compact: Boolean) {
        compactMode = compact
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyCompactMode()
        } else {
            mainHandler.post { applyCompactMode() }
        }
    }

    fun showDetectingState() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyDetectingState()
        } else {
            mainHandler.post { applyDetectingState() }
        }
    }

    fun showWaitingFaceState() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyWaitingFaceState()
        } else {
            mainHandler.post { applyWaitingFaceState() }
        }
    }

    fun update(probability: Float, fps: Float, latencyMs: Long, label: Int, isAlert: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateUi(probability, fps, latencyMs, label, isAlert)
        } else {
            mainHandler.post { updateUi(probability, fps, latencyMs, label, isAlert) }
        }
    }

    fun updateTrackedFaceRect(rect: Rect?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ensureScreenRoiLayer()
            screenRoiView?.updateRoi(rect)
        } else {
            mainHandler.post {
                ensureScreenRoiLayer()
                screenRoiView?.updateRoi(rect)
            }
        }
    }

    private fun updateUi(probability: Float, fps: Float, latencyMs: Long, label: Int, isAlert: Boolean) {
        if (rootView == null) return
        if (compactMode) return
        probabilityText?.text = context.getString(R.string.overlay_probability_template, probability)
        fpsText?.text = context.getString(R.string.overlay_fps_template, fps)
        latencyText?.text = context.getString(R.string.overlay_latency_template, latencyMs)
        labelText?.text = context.getString(R.string.overlay_label_template, label)
        statusText?.text = if (isAlert) context.getString(R.string.status_alert_short) else context.getString(R.string.status_running_short)
        statusText?.setBackgroundResource(if (isAlert) R.drawable.bg_status_alert else R.drawable.bg_status_running)
        statusText?.setTextColor(ContextCompat.getColor(context, R.color.white))
    }

    private fun applyDetectingState() {
        if (rootView == null) return
        statusText?.text = context.getString(R.string.overlay_detecting_now)
        statusText?.setBackgroundResource(R.drawable.bg_status_running)
        statusText?.setTextColor(ContextCompat.getColor(context, R.color.white))
    }

    private fun applyWaitingFaceState() {
        if (rootView == null) return
        statusText?.text = context.getString(R.string.overlay_waiting_face)
        statusText?.setBackgroundResource(R.drawable.bg_status_idle)
        statusText?.setTextColor(ContextCompat.getColor(context, R.color.white))
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
        metricsContainer = view.findViewById(R.id.overlayMetricsContainer)
    }

    private fun ensureMetricsCard() {
        if (rootView != null) return
        runCatching {
            val view = LayoutInflater.from(context).inflate(R.layout.view_overlay_metrics, null)
            bindViews(view)
            enableDrag(view)
            windowManager.addView(view, layoutParams)
            rootView = view
            applyCompactMode()
        }.onFailure {
            rootView = null
        }
    }

    private fun ensureScreenRoiLayer() {
        if (screenRoiView != null) return
        val roiView = RoiOverlayView(context).apply {
            setFillEnabled(false)
            updateRoi(null)
        }
        runCatching {
            windowManager.addView(roiView, roiLayerParams)
            screenRoiView = roiView
        }
    }

    private fun applyMetricsVisibility() {
        if (metricsVisible) {
            ensureMetricsCard()
            showDetectingState()
        } else {
            rootView?.let { view -> runCatching { windowManager.removeView(view) } }
            rootView = null
        }
    }

    private fun applyCompactMode() {
        if (rootView == null) return
        metricsContainer?.visibility = if (compactMode) View.GONE else View.VISIBLE
    }
}

