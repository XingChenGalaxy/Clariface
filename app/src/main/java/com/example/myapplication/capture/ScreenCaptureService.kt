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
import android.graphics.Rect
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
import com.example.myapplication.inference.FallbackHeuristicInferenceEngine
import com.example.myapplication.inference.InferenceEngineFactory
import com.example.myapplication.overlay.OverlaySettings
import com.example.myapplication.overlay.OverlayWindowController
import com.example.myapplication.pipeline.RealtimeDecisionEngine
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {

    private val frameWindow = ArrayDeque<Float>()
    private val frameWindowSize = 10

    private val decisionEngine = RealtimeDecisionEngine()
    private val inferenceEngine by lazy { InferenceEngineFactory.create(this) }

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
    private var previewVisible = true
    private var lastInferenceNs = 0L
    private var fpsWindowStartNs = 0L
    private var processedFramesInWindow = 0
    private var analyzerFps = 0f
    private var lastPreviewAtMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        overlayVisible = OverlaySettings.isOverlayVisible(this)
        previewVisible = OverlaySettings.isPreviewVisible(this)
        when (intent?.action) {
            ACTION_START -> {
                startTypedForeground()

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = intent.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
                requestedFps = intent.getIntExtra(EXTRA_REQUESTED_FPS, 30).coerceAtLeast(1)

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    sendDiagnostic(DIAG_PROJECTION_FAILED, "resultData is null or resultCode invalid")
                    stopForeground(STOP_FOREGROUND_REMOVE)
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
                if (inferenceEngine is FallbackHeuristicInferenceEngine) {
                    sendDiagnostic(DIAG_MODEL_FALLBACK)
                } else {
                    sendDiagnostic(DIAG_RUNNING)
                }
                sendStateBroadcast(true)
            }

            ACTION_SET_OVERLAY_VISIBILITY -> {
                overlayVisible = intent.getBooleanExtra(EXTRA_OVERLAY_VISIBLE, true)
                OverlaySettings.setOverlayVisible(this, overlayVisible)
                if (overlayVisible) {
                    showOverlayIfPermitted()
                } else {
                    hideOverlay()
                }
                if (mediaProjection == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }

            ACTION_SET_PREVIEW_VISIBILITY -> {
                previewVisible = intent.getBooleanExtra(EXTRA_PREVIEW_VISIBLE, true)
                OverlaySettings.setPreviewVisible(this, previewVisible)
                overlayController?.setPreviewVisible(previewVisible)
                if (mediaProjection == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }

            ACTION_STOP -> {
                stopCapture()
                hideOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
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
        inferenceEngine.close()
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
        val now = System.nanoTime()
        val frameIntervalNs = 1_000_000_000L / requestedFps.toLong()
        if (now - lastInferenceNs < frameIntervalNs) {
            return
        }
        lastInferenceNs = now

        val bitmap = imageToBitmap(image) ?: return
        val output = inferenceEngine.infer(bitmap)

        maybeSendPreview(bitmap, output.roiUsed, output.roiRect)
        bitmap.recycle()

        pushFrameProbability(output.probability)
        updateAnalyzerFps(now)

        if (frameWindow.size < frameWindowSize) {
            return
        }

        val windowAvg = frameWindow.average().toFloat()
        val decision = decisionEngine.update(windowAvg)

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
            isAlert = decision.isAlert
        )
    }

    private fun pushFrameProbability(probability: Float) {
        if (frameWindow.size == frameWindowSize) {
            frameWindow.removeFirst()
        }
        frameWindow.addLast(probability)
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

    private fun maybeSendPreview(bitmap: Bitmap, roiUsed: Boolean, roiRect: Rect?) {
        if (!previewVisible) {
            return
        }
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastPreviewAtMs < 1_000L) {
            return
        }
        lastPreviewAtMs = nowMs

        val targetWidth = 320
        val scale = targetWidth.toFloat() / bitmap.width.toFloat()
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val previewBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        val previewRoi = mapRoiToPreview(roiRect, bitmap.width, bitmap.height, targetWidth, targetHeight)

        val stream = ByteArrayOutputStream()
        previewBitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)

        overlayController?.updatePreview(previewBitmap, previewRoi)
            ?: previewBitmap.recycle()

        val intent = Intent(ACTION_PREVIEW).apply {
            setPackage(packageName)
            putExtra(EXTRA_PREVIEW_JPEG, stream.toByteArray())
            putExtra(EXTRA_ROI_USED, roiUsed)
            putExtra(EXTRA_PREVIEW_ROI_LEFT, previewRoi?.left ?: -1)
            putExtra(EXTRA_PREVIEW_ROI_TOP, previewRoi?.top ?: -1)
            putExtra(EXTRA_PREVIEW_ROI_RIGHT, previewRoi?.right ?: -1)
            putExtra(EXTRA_PREVIEW_ROI_BOTTOM, previewRoi?.bottom ?: -1)
        }
        sendBroadcast(intent)
    }

    private fun mapRoiToPreview(
        roi: Rect?,
        sourceW: Int,
        sourceH: Int,
        previewW: Int,
        previewH: Int
    ): Rect? {
        roi ?: return null
        val scaleX = previewW.toFloat() / sourceW.toFloat()
        val scaleY = previewH.toFloat() / sourceH.toFloat()
        val mapped = Rect(
            (roi.left * scaleX).toInt(),
            (roi.top * scaleY).toInt(),
            (roi.right * scaleX).toInt(),
            (roi.bottom * scaleY).toInt()
        )
        val clamped = Rect(
            mapped.left.coerceIn(0, previewW - 1),
            mapped.top.coerceIn(0, previewH - 1),
            mapped.right.coerceIn(1, previewW),
            mapped.bottom.coerceIn(1, previewH)
        )
        return if (clamped.width() > 1 && clamped.height() > 1) clamped else null
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    private fun stopCapture() {
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
        frameWindow.clear()
        decisionEngine.reset()
        lastInferenceNs = 0L
        fpsWindowStartNs = 0L
        processedFramesInWindow = 0
        analyzerFps = 0f
        lastPreviewAtMs = 0L
    }

    private fun showOverlayIfPermitted() {
        if (!overlayVisible) {
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            sendDiagnostic(DIAG_NO_OVERLAY_PERMISSION)
            return
        }
        mainHandler.post {
            if (overlayController == null) {
                overlayController = OverlayWindowController(this)
            }
            overlayController?.show()
            overlayController?.showDetectingState()
            overlayController?.setPreviewVisible(previewVisible)
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
        const val ACTION_SET_PREVIEW_VISIBILITY = "com.example.myapplication.capture.SET_PREVIEW_VISIBILITY"

        const val ACTION_METRICS = "com.example.myapplication.capture.METRICS"
        const val ACTION_STATE = "com.example.myapplication.capture.STATE"
        const val ACTION_DIAGNOSTIC = "com.example.myapplication.capture.DIAGNOSTIC"
        const val ACTION_PREVIEW = "com.example.myapplication.capture.PREVIEW"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_REQUESTED_FPS = "extra_requested_fps"
        const val EXTRA_OVERLAY_VISIBLE = "extra_overlay_visible"
        const val EXTRA_PREVIEW_VISIBLE = "extra_preview_visible"

        const val EXTRA_PROBABILITY = "extra_probability"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_DETECTION_MS = "extra_detection_ms"
        const val EXTRA_ANALYZER_FPS = "extra_analyzer_fps"
        const val EXTRA_IS_ALERT = "extra_is_alert"
        const val EXTRA_ROI_USED = "extra_roi_used"
        const val EXTRA_RUNNING = "extra_running"
        const val EXTRA_DIAGNOSTIC_REASON = "extra_diagnostic_reason"
        const val EXTRA_DIAGNOSTIC_DETAIL = "extra_diagnostic_detail"
        const val EXTRA_PREVIEW_JPEG = "extra_preview_jpeg"
        const val EXTRA_PREVIEW_ROI_LEFT = "extra_preview_roi_left"
        const val EXTRA_PREVIEW_ROI_TOP = "extra_preview_roi_top"
        const val EXTRA_PREVIEW_ROI_RIGHT = "extra_preview_roi_right"
        const val EXTRA_PREVIEW_ROI_BOTTOM = "extra_preview_roi_bottom"

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

