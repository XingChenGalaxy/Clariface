package com.example.myapplication.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R
import com.example.myapplication.inference.DeepfakeInferenceEngine
import com.example.myapplication.inference.InferenceEngineFactory
import com.example.myapplication.inference.TrackingMode
import com.example.myapplication.overlay.OverlaySettings
import com.example.myapplication.overlay.OverlayWindowController
import com.example.myapplication.pipeline.RealtimeDecisionEngine

class ScreenCaptureService : Service() {

    private val decisionEngine = RealtimeDecisionEngine()
    private var inferenceEngine: DeepfakeInferenceEngine? = null

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayController: OverlayWindowController? = null

    private var requestedFps = 30
    private var overlayVisible = true
    private var overlayCompact = false
    private var trackingMode: TrackingMode = TrackingMode.BALANCED
    private var lastInferenceNs = 0L
    private var fpsWindowStartNs = 0L
    private var processedFramesInWindow = 0
    private var analyzerFps = 0f
    private var lastSmoothedProbability = 0.5f
    private var lastLabel = 1
    private var stableOverlayState = OVERLAY_STATE_PENDING
    private var consecutiveNoFaceFrames = 0
    private var pendingDetectedLabel = 1
    private var consecutiveDetectedLabelFrames = 0
    private var isStopping = false
    private var isProcessingFrame = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        overlayVisible = OverlaySettings.isOverlayVisible(this)
        overlayCompact = OverlaySettings.isOverlayCompact(this)
        trackingMode = TrackingMode.fromRaw(OverlaySettings.getTrackingMode(this))
        when (intent?.action) {
            ACTION_START -> {
                startTypedForeground()

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = intent.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
                requestedFps = intent.getIntExtra(EXTRA_REQUESTED_FPS, 30).coerceAtLeast(1)
                trackingMode = TrackingMode.fromRaw(intent.getStringExtra(EXTRA_TRACKING_MODE))

                // 强制开启悬浮窗显示（避免因历史设置为隐藏导致“前端不显示”）
                overlayVisible = true
                overlayCompact = false
                OverlaySettings.setOverlayVisible(this, true)
                OverlaySettings.setOverlayCompact(this, false)

                inferenceEngine?.close()
                inferenceEngine = InferenceEngineFactory.create(this, trackingMode)

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    sendDiagnostic(DIAG_PROJECTION_FAILED, "resultData is null or resultCode invalid")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    inferenceEngine?.close()
                    inferenceEngine = null
                    stopSelf()
                    return START_NOT_STICKY
                }

                showOverlayIfPermitted()
                val projectionError = startProjection(resultCode, resultData)
                if (projectionError != null) {
                    sendDiagnostic(DIAG_PROJECTION_FAILED, projectionError)
                    stopCapture()
                    hideOverlay()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                sendDiagnostic(DIAG_RUNNING, "服务器推理引擎已就绪，地址: ${com.example.myapplication.inference.ServerInferenceEngine.SERVER_URL}")
                sendStateBroadcast(true)
            }

            ACTION_SET_OVERLAY_VISIBILITY -> {
                overlayVisible = intent.getBooleanExtra(EXTRA_OVERLAY_VISIBLE, true)
                OverlaySettings.setOverlayVisible(this, overlayVisible)
                showOverlayIfPermitted()
                if (mediaProjection == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }

            ACTION_SET_OVERLAY_COMPACT -> {
                overlayCompact = intent.getBooleanExtra(EXTRA_OVERLAY_COMPACT, false)
                OverlaySettings.setOverlayCompact(this, overlayCompact)
                overlayController?.setCompactMode(overlayCompact)
                if (mediaProjection == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }

            ACTION_STOP -> {
                stopCapture()
                hideOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                inferenceEngine?.close()
                inferenceEngine = null
                stopSelf()
            }

            else -> {
                if (mediaProjection == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        hideOverlay()
        inferenceEngine?.close()
        inferenceEngine = null
        super.onDestroy()
    }

    private fun startProjection(resultCode: Int, resultData: Intent): String? {
        return runCatching {
            stopCapture()
            resetRuntimeState()
            ensureCaptureThread()

            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                    hideOverlay()
                    sendStateBroadcast(false)
                }
            }
            projectionCallback = callback
            mediaProjection?.registerCallback(callback, captureHandler)

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val densityDpi = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    processImage(image)
                } finally {
                    image.close()
                }
            }, captureHandler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "mask-screen-capture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                captureHandler
            )
            if (virtualDisplay == null) {
                error("createVirtualDisplay returned null")
            }
        }.exceptionOrNull()?.let { throwable ->
            Log.e(TAG, "startProjection failed", throwable)
            "${throwable.javaClass.simpleName}: ${throwable.message ?: "unknown"}"
        }
    }

    private fun processImage(image: Image) {
        if (isStopping || isProcessingFrame) {
            return
        }
        isProcessingFrame = true
        try {
            val now = System.nanoTime()
            val frameIntervalNs = 1_000_000_000L / requestedFps.toLong()
        if (now - lastInferenceNs < frameIntervalNs) {
            return
        }
        lastInferenceNs = now

        val engine = inferenceEngine ?: return
        val bitmap = imageToBitmap(image) ?: return
        // 每30帧打印一次引擎类型，便于调试
        if (processedFramesInWindow % 30 == 0) {
            Log.d(TAG, "推理引擎: ${engine.javaClass.simpleName}")
        }
        val output = engine.infer(bitmap)
        if (isStopping) {
            bitmap.recycle()
            return
        }
        Log.d(TAG, "推理结果: prob=${output.probability} roiUsed=${output.roiUsed} valid=${output.validForDecision} elapsedMs=${output.elapsedMs}")

        val trackedRect = output.faceRect ?: output.roiRect
        bitmap.recycle()

        if (!output.validForDecision) {
            updateOverlayStateForNoFace()
            overlayController?.updateTrackedFaceRect(trackedRect, stableOverlayState == OVERLAY_STATE_FAKE)
            if (stableOverlayState == OVERLAY_STATE_PENDING) {
                overlayController?.showWaitingFaceState()
            }
            val metricsIntent = Intent(ACTION_METRICS).apply {
                setPackage(packageName)
                putExtra(EXTRA_PROBABILITY, if (stableOverlayState == OVERLAY_STATE_PENDING) -1f else lastSmoothedProbability)
                putExtra(EXTRA_LABEL, if (stableOverlayState == OVERLAY_STATE_PENDING) -1 else lastLabel)
                putExtra(EXTRA_DETECTION_MS, output.elapsedMs)
                putExtra(EXTRA_ANALYZER_FPS, analyzerFps)
                putExtra(EXTRA_IS_ALERT, false)
                putExtra(EXTRA_ROI_USED, false)
            }
            sendBroadcast(metricsIntent)
            return
        }

        // 直接将每帧 probability 送入决策引擎（内部做5帧滑动平均）
        // 与 PC 端逻辑一致：每帧 sigmoid → 决策引擎平滑 → 阈值判断
        updateAnalyzerFps(now)
        val decision = decisionEngine.update(output.probability)
        lastSmoothedProbability = decision.smoothedProbability
        lastLabel = decision.label
        updateOverlayStateForDetection(lastLabel)
        overlayController?.updateTrackedFaceRect(trackedRect, stableOverlayState == OVERLAY_STATE_FAKE)

        val metricsIntent = Intent(ACTION_METRICS).apply {
            setPackage(packageName)
            putExtra(EXTRA_PROBABILITY, decision.smoothedProbability)
            putExtra(EXTRA_LABEL, decision.label)
            putExtra(EXTRA_DETECTION_MS, output.elapsedMs)
            putExtra(EXTRA_ANALYZER_FPS, analyzerFps)
            putExtra(EXTRA_IS_ALERT, decision.isAlert)
            putExtra(EXTRA_ROI_USED, output.roiUsed)
        }
        sendBroadcast(metricsIntent)

        overlayController?.update(
            probability = decision.smoothedProbability,
            fps = analyzerFps,
            latencyMs = output.elapsedMs,
            label = decision.label,
            isAlert = decision.isAlert,
            stableDisplayLabel = stableOverlayState
        )
        } catch (t: Throwable) {
            Log.e(TAG, "processImage failed", t)
            // 不中断采集线程，避免一次异常后前端持续无更新
        } finally {
            isProcessingFrame = false
        }
    }

    private fun updateAnalyzerFps(now: Long) {
        if (fpsWindowStartNs == 0L) {
            fpsWindowStartNs = now
        }
        processedFramesInWindow += 1
        val elapsedNs = now - fpsWindowStartNs
        if (elapsedNs >= 1_000_000_000L) {
            analyzerFps = processedFramesInWindow * 1_000_000_000f / elapsedNs
            fpsWindowStartNs = now
            processedFramesInWindow = 0
        }
    }

    private fun updateOverlayStateForNoFace() {
        consecutiveNoFaceFrames += 1
        pendingDetectedLabel = lastLabel
        consecutiveDetectedLabelFrames = 0
        if (consecutiveNoFaceFrames >= 10) {
            stableOverlayState = OVERLAY_STATE_PENDING
        }
    }

    private fun updateOverlayStateForDetection(detectedLabel: Int) {
        consecutiveNoFaceFrames = 0
        if (detectedLabel != pendingDetectedLabel) {
            pendingDetectedLabel = detectedLabel
            consecutiveDetectedLabelFrames = 1
        } else {
            consecutiveDetectedLabelFrames += 1
        }
        if (consecutiveDetectedLabelFrames >= 5) {
            stableOverlayState = if (detectedLabel == 0) OVERLAY_STATE_FAKE else OVERLAY_STATE_REAL
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val tempBitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        tempBitmap.copyPixelsFromBuffer(buffer)

        val cropped = Bitmap.createBitmap(tempBitmap, 0, 0, image.width, image.height)
        tempBitmap.recycle()
        return cropped
    }

    private fun stopCapture() {
        isStopping = true
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        virtualDisplay?.release()
        virtualDisplay = null

        projectionCallback?.let { callback ->
            mediaProjection?.unregisterCallback(callback)
        }
        projectionCallback = null

        mediaProjection?.stop()
        mediaProjection = null

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        sendStateBroadcast(false)
    }

    private fun ensureCaptureThread() {
        if (captureThread != null) {
            return
        }
        captureThread = HandlerThread("screen-capture-worker").apply { start() }
        captureHandler = Handler(captureThread!!.looper)
    }

    private fun resetRuntimeState() {
        decisionEngine.reset()
        lastInferenceNs = 0L
        fpsWindowStartNs = 0L
        processedFramesInWindow = 0
        analyzerFps = 0f
        lastSmoothedProbability = 0.5f
        lastLabel = 1
        stableOverlayState = OVERLAY_STATE_PENDING
        consecutiveNoFaceFrames = 0
        pendingDetectedLabel = 1
        consecutiveDetectedLabelFrames = 0
        isStopping = false
        isProcessingFrame = false
    }

    private fun showOverlayIfPermitted() {
        if (!Settings.canDrawOverlays(this)) {
            sendDiagnostic(DIAG_NO_OVERLAY_PERMISSION)
            return
        }
        mainHandler.post {
            if (overlayController == null) {
                overlayController = OverlayWindowController(this)
            }
            overlayController?.show()
            overlayController?.setMetricsVisible(overlayVisible)
            overlayController?.setCompactMode(overlayCompact)
            overlayController?.showDetectingState()
            if (overlayController?.isShowing() != true) {
                sendDiagnostic(DIAG_OVERLAY_BLOCKED)
            }
        }
    }

    private fun hideOverlay() {
        mainHandler.post {
            overlayController?.hide()
            overlayController = null
        }
    }

    private fun sendStateBroadcast(running: Boolean) {
        val stateIntent = Intent(ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_RUNNING, running)
        }
        sendBroadcast(stateIntent)
    }

    private fun sendDiagnostic(reason: String, detail: String? = null) {
        val intent = Intent(ACTION_DIAGNOSTIC).apply {
            setPackage(packageName)
            putExtra(EXTRA_DIAGNOSTIC_REASON, reason)
            putExtra(EXTRA_DIAGNOSTIC_DETAIL, detail)
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(): Notification {
        createNotificationChannelIfNeeded()

        val openIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content))
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop_detection), stopPendingIntent)
            .build()
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun startTypedForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private inline fun <reified T> Intent.getParcelableExtraCompat(key: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }
    }

    companion object {
        const val ACTION_START = "com.example.myapplication.capture.START"
        const val ACTION_STOP = "com.example.myapplication.capture.STOP"
        const val ACTION_SET_OVERLAY_VISIBILITY = "com.example.myapplication.capture.SET_OVERLAY_VISIBILITY"
        const val ACTION_SET_OVERLAY_COMPACT = "com.example.myapplication.capture.SET_OVERLAY_COMPACT"

        private const val OVERLAY_STATE_PENDING = -1
        private const val OVERLAY_STATE_FAKE = 0
        private const val OVERLAY_STATE_REAL = 1

        const val ACTION_METRICS = "com.example.myapplication.capture.METRICS"
        const val ACTION_STATE = "com.example.myapplication.capture.STATE"
        const val ACTION_DIAGNOSTIC = "com.example.myapplication.capture.DIAGNOSTIC"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_REQUESTED_FPS = "extra_requested_fps"
        const val EXTRA_TRACKING_MODE = "extra_tracking_mode"
        const val EXTRA_OVERLAY_VISIBLE = "extra_overlay_visible"
        const val EXTRA_OVERLAY_COMPACT = "extra_overlay_compact"

        const val EXTRA_PROBABILITY = "extra_probability"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_DETECTION_MS = "extra_detection_ms"
        const val EXTRA_ANALYZER_FPS = "extra_analyzer_fps"
        const val EXTRA_IS_ALERT = "extra_is_alert"
        const val EXTRA_ROI_USED = "extra_roi_used"
        const val EXTRA_RUNNING = "extra_running"
        const val EXTRA_DIAGNOSTIC_REASON = "extra_diagnostic_reason"
        const val EXTRA_DIAGNOSTIC_DETAIL = "extra_diagnostic_detail"

        const val DIAG_NO_OVERLAY_PERMISSION = "diag_no_overlay_permission"
        const val DIAG_OVERLAY_BLOCKED = "diag_overlay_blocked"
        const val DIAG_PROJECTION_FAILED = "diag_projection_failed"
        const val DIAG_MODEL_FALLBACK = "diag_model_fallback"
        const val DIAG_RUNNING = "diag_running"

        private const val CHANNEL_ID = "mask_detector_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "ScreenCaptureService"
    }
}

