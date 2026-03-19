package com.example.myapplication

import android.Manifest
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.AnimationUtils
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.example.myapplication.capture.ScreenCaptureService
import com.example.myapplication.overlay.OverlaySettings
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
class MainActivity : AppCompatActivity() {
    private lateinit var statusPill: TextView
    private lateinit var instructionText: TextView
    private lateinit var toggleButton: MaterialButton
    private lateinit var overlayToggleButton: MaterialButton
    private lateinit var overlayCompactToggleButton: MaterialButton
    private lateinit var fpsSwipeValue: TextView
    private lateinit var modeSwipeValue: TextView
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var startButtonRing: View
    private lateinit var startButtonGlow: View
    private lateinit var startButtonHost: View
    private lateinit var tipsCard: MaterialCardView
    private lateinit var homeContainer: View
    private lateinit var settingsContainer: View
    private lateinit var metricsSection: View
    private lateinit var probabilityText: TextView
    private lateinit var detectionTimeText: TextView
    private lateinit var fpsText: TextView
    private lateinit var labelText: TextView
    private lateinit var roiStatusText: TextView
    private lateinit var errorReasonText: TextView
    private lateinit var alertCard: MaterialCardView
    private lateinit var diagCard: MaterialCardView

    private var isRunning = false
    private var pendingFps = 30
    private var pendingTrackingMode = "balanced"
    private var overlayVisible = true
    private var overlayCompact = true
    private var pendingStartAfterOverlayGrant = false
    private var isAlertActive = false
    private lateinit var fpsOptions: Array<String>
    private lateinit var modeOptions: Array<String>
    private var fpsOptionIndex = 0
    private var modeOptionIndex = 0
    private var startButtonIdleWidth = 0
    private var startButtonIdleHeight = 0

    private val metricsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenCaptureService.ACTION_METRICS) return
            val probability = intent.getFloatExtra(ScreenCaptureService.EXTRA_PROBABILITY, 0f)
            val elapsed = intent.getLongExtra(ScreenCaptureService.EXTRA_DETECTION_MS, 0L)
            val fps = intent.getFloatExtra(ScreenCaptureService.EXTRA_ANALYZER_FPS, 0f)
            val label = intent.getIntExtra(ScreenCaptureService.EXTRA_LABEL, 0)
            val isAlert = intent.getBooleanExtra(ScreenCaptureService.EXTRA_IS_ALERT, false)
            val roiUsed = intent.getBooleanExtra(ScreenCaptureService.EXTRA_ROI_USED, false)

            probabilityText.text = getString(R.string.probability_template, probability)
            detectionTimeText.text = getString(R.string.detime_template, elapsed)
            fpsText.text = getString(R.string.fps_template, fps)
            labelText.text = getString(R.string.label_template, label)
            roiStatusText.text = if (roiUsed) getString(R.string.roi_hit) else getString(R.string.roi_miss)
            bindAlertState(isAlert)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenCaptureService.ACTION_STATE) return
            val running = intent.getBooleanExtra(ScreenCaptureService.EXTRA_RUNNING, false)
            bindRunningState(running)
            if (running) {
                errorReasonText.text = getString(R.string.diag_running)
            }
        }
    }

    private val diagnosticReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenCaptureService.ACTION_DIAGNOSTIC) return
            val reason = intent.getStringExtra(ScreenCaptureService.EXTRA_DIAGNOSTIC_REASON)
            val detail = intent.getStringExtra(ScreenCaptureService.EXTRA_DIAGNOSTIC_DETAIL)
            errorReasonText.text = when (reason) {
                ScreenCaptureService.DIAG_NO_OVERLAY_PERMISSION -> getString(R.string.diag_no_overlay_permission)
                ScreenCaptureService.DIAG_OVERLAY_BLOCKED -> getString(R.string.diag_overlay_blocked)
                ScreenCaptureService.DIAG_PROJECTION_FAILED -> {
                    if (detail.isNullOrBlank()) {
                        getString(R.string.diag_projection_failed)
                    } else {
                        getString(R.string.diag_projection_failed_with_detail, detail)
                    }
                }
                ScreenCaptureService.DIAG_MODEL_FALLBACK -> getString(R.string.diag_model_fallback)
                ScreenCaptureService.DIAG_RUNNING -> getString(R.string.diag_running)
                else -> getString(R.string.diag_default)
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, getString(R.string.notification_permission_hint), Toast.LENGTH_SHORT).show()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_REQUESTED_FPS, pendingFps)
                putExtra(ScreenCaptureService.EXTRA_TRACKING_MODE, pendingTrackingMode)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            instructionText.text = getString(R.string.diag_running)
        } else {
            Toast.makeText(this, getString(R.string.screen_permission_required), Toast.LENGTH_LONG).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            if (pendingStartAfterOverlayGrant) {
                pendingStartAfterOverlayGrant = false
                requestScreenCapturePermission()
            }
        } else {
            pendingStartAfterOverlayGrant = false
            Toast.makeText(this, getString(R.string.overlay_permission_required), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusPill = findViewById(R.id.statusPill)
        instructionText = findViewById(R.id.instructionText)
        toggleButton = findViewById(R.id.toggleButton)
        overlayToggleButton = findViewById(R.id.overlayToggleButton)
        overlayCompactToggleButton = findViewById(R.id.overlayCompactToggleButton)
        fpsSwipeValue = findViewById(R.id.fpsSwipeValue)
        modeSwipeValue = findViewById(R.id.modeSwipeValue)
        bottomNav = findViewById(R.id.bottomNav)
        startButtonRing = findViewById(R.id.startButtonRing)
        startButtonGlow = findViewById(R.id.startButtonGlow)
        startButtonHost = findViewById(R.id.startButtonHost)
        tipsCard = findViewById(R.id.tipsCard)
        homeContainer = findViewById(R.id.homeContainer)
        settingsContainer = findViewById(R.id.settingsContainer)
        metricsSection = findViewById(R.id.metricsSection)
        probabilityText = findViewById(R.id.probabilityText)
        detectionTimeText = findViewById(R.id.detectionTimeText)
        fpsText = findViewById(R.id.fpsText)
        labelText = findViewById(R.id.labelText)
        roiStatusText = findViewById(R.id.roiStatusText)
        errorReasonText = findViewById(R.id.errorReasonText)
        alertCard = findViewById(R.id.alertCard)
        diagCard = findViewById(R.id.diagCard)
        overlayVisible = OverlaySettings.isOverlayVisible(this)
        overlayCompact = OverlaySettings.isOverlayCompact(this)
        pendingTrackingMode = OverlaySettings.getTrackingMode(this)
        pendingFps = OverlaySettings.getRequestedFps(this)
        startButtonIdleWidth = toggleButton.layoutParams.width
        startButtonIdleHeight = toggleButton.layoutParams.height

        setupToggleButton()
        setupSettingsControls()
        setupBottomNav()
        maybeRequestNotificationPermission()
        bindRunningState(false)
        errorReasonText.text = getString(R.string.diag_default)
        roiStatusText.text = getString(R.string.roi_waiting)
        updateDetailCardsVisibility(visible = false, animate = false)
        tipsCard.post { updateTipsCardPosition(isRunning = false, animate = false) }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            metricsReceiver,
            IntentFilter(ScreenCaptureService.ACTION_METRICS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(ScreenCaptureService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            diagnosticReceiver,
            IntentFilter(ScreenCaptureService.ACTION_DIAGNOSTIC),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(metricsReceiver) }
        runCatching { unregisterReceiver(stateReceiver) }
        runCatching { unregisterReceiver(diagnosticReceiver) }
    }


    private fun setupToggleButton() {
        toggleButton.setOnClickListener {
            if (isRunning) {
                sendStopCommand()
                bindRunningState(false)
                instructionText.text = getString(R.string.instruction_idle)
            } else {
                pendingFps = OverlaySettings.getRequestedFps(this)
                pendingTrackingMode = OverlaySettings.getTrackingMode(this)
                ensureOverlayPermissionThenStart()
            }
        }
    }

    private fun setupSettingsControls() {
        fpsOptions = resources.getStringArray(R.array.fps_options)
        modeOptions = resources.getStringArray(R.array.tracking_mode_options)

        fpsOptionIndex = fpsOptions.indexOfFirst { parseFps(it) == pendingFps }.takeIf { it >= 0 } ?: 2
        modeOptionIndex = modeOptions.indexOfFirst { parseTrackingMode(it) == pendingTrackingMode }.takeIf { it >= 0 } ?: 0
        fpsSwipeValue.text = fpsOptions[fpsOptionIndex]
        modeSwipeValue.text = modeOptions[modeOptionIndex]

        bindOverlayButtonText()
        bindOverlayCompactButtonText()

        attachSwipeCycler(fpsSwipeValue) { direction ->
            fpsOptionIndex = (fpsOptionIndex + direction).mod(fpsOptions.size)
            pendingFps = parseFps(fpsOptions[fpsOptionIndex])
            OverlaySettings.setRequestedFps(this, pendingFps)
            fpsOptions[fpsOptionIndex]
        }

        attachSwipeCycler(modeSwipeValue) { direction ->
            modeOptionIndex = (modeOptionIndex + direction).mod(modeOptions.size)
            pendingTrackingMode = parseTrackingMode(modeOptions[modeOptionIndex])
            OverlaySettings.setTrackingMode(this, pendingTrackingMode)
            modeOptions[modeOptionIndex]
        }

        overlayToggleButton.setOnClickListener {
            overlayVisible = !overlayVisible
            OverlaySettings.setOverlayVisible(this, overlayVisible)
            bindOverlayButtonText()
            if (isRunning) {
                startService(Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_SET_OVERLAY_VISIBILITY
                    putExtra(ScreenCaptureService.EXTRA_OVERLAY_VISIBLE, overlayVisible)
                })
            }
        }

        overlayCompactToggleButton.setOnClickListener {
            overlayCompact = !overlayCompact
            OverlaySettings.setOverlayCompact(this, overlayCompact)
            bindOverlayCompactButtonText()
            if (isRunning) {
                startService(Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_SET_OVERLAY_COMPACT
                    putExtra(ScreenCaptureService.EXTRA_OVERLAY_COMPACT, overlayCompact)
                })
            }
        }
    }

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    switchToPage(showHome = true)
                    true
                }
                R.id.nav_settings -> {
                    switchToPage(showHome = false)
                    true
                }
                else -> false
            }
        }
        bottomNav.selectedItemId = R.id.nav_home
        switchToPage(showHome = true)
    }

    private fun switchToPage(showHome: Boolean) {
        val showView = if (showHome) homeContainer else settingsContainer
        val hideView = if (showHome) settingsContainer else homeContainer
        hideView.animate().alpha(0f).setDuration(140L).withEndAction {
            hideView.visibility = View.GONE
            showView.visibility = View.VISIBLE
            showView.alpha = 0f
            showView.animate().alpha(1f).setDuration(180L).start()
        }.start()
    }

    private fun ensureOverlayPermissionThenStart() {
        if (Settings.canDrawOverlays(this)) {
            requestScreenCapturePermission()
            return
        }
        pendingStartAfterOverlayGrant = true
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
        Toast.makeText(this, getString(R.string.overlay_permission_hint), Toast.LENGTH_LONG).show()
    }

    private fun maybeRequestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestScreenCapturePermission() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun sendStopCommand() {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    private fun bindRunningState(running: Boolean) {
        if (isFinishing || isDestroyed) return
        val stateChanged = isRunning != running
        isRunning = running
        statusPill.text = if (running) getString(R.string.status_running_short) else getString(R.string.status_idle_short)
        statusPill.setBackgroundResource(if (running) R.drawable.bg_status_running else R.drawable.bg_status_idle)
        toggleButton.text = if (running) getString(R.string.stop_detection) else getString(R.string.start_detection)
        updateToggleButtonIcon(running, animate = stateChanged)
        instructionText.text = if (running) getString(R.string.instruction_running) else getString(R.string.instruction_idle)
        animateStartButton(running)
        updateDetailCardsVisibility(visible = running, animate = true)
        if (running) {
            tipsCard.animate().cancel()
            tipsCard.translationY = 0f
            tipsCard.animate()
                .alpha(0f)
                .setDuration(140L)
                .withEndAction { tipsCard.visibility = View.INVISIBLE }
                .start()
        } else {
            tipsCard.animate().cancel()
            tipsCard.visibility = View.VISIBLE
            tipsCard.alpha = 0f
            tipsCard.translationY = resources.getDimension(R.dimen.tips_restore_offset_y)
            tipsCard.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
            updateTipsCardPosition(isRunning = false, animate = true)
        }
        if (!running) {
            isAlertActive = false
        }
        bindAmbientAnimations()
    }

    private fun updateTipsCardPosition(isRunning: Boolean, animate: Boolean) {
        if (tipsCard.top == 0 || startButtonHost.bottom == 0 || bottomNav.top == 0) {
            tipsCard.post { updateTipsCardPosition(isRunning, animate) }
            return
        }

        val targetTranslation = if (isRunning) {
            if (metricsSection.visibility != View.VISIBLE || metricsSection.height == 0 || diagCard.height == 0) {
                tipsCard.postDelayed({ updateTipsCardPosition(isRunning = true, animate = false) }, 140L)
                return
            }
            val metricsBottom = metricsSection.bottom + metricsSection.translationY
            val minGap = resources.getDimension(R.dimen.tips_running_min_gap)
            val available = (bottomNav.top - metricsBottom - tipsCard.height).coerceAtLeast(0f)
            val targetTop = metricsBottom + maxOf(minGap, available / 2f)
            targetTop - tipsCard.top
        } else {
            val available = (bottomNav.top - startButtonHost.bottom - tipsCard.height).coerceAtLeast(0)
            val targetTop = startButtonHost.bottom + (available / 2f)
            targetTop - tipsCard.top
        }

        if (!animate) {
            tipsCard.translationY = targetTranslation
            return
        }

        tipsCard.animate()
            .translationY(targetTranslation)
            .setDuration(280L)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    private fun animateStartButton(running: Boolean) {
        val targetTranslationY = if (running) -resources.getDimension(R.dimen.start_button_shift_up) else 0f
        val maxRunningWidth = resources.displayMetrics.widthPixels - resources.getDimensionPixelSize(R.dimen.start_button_horizontal_padding_total)
        val targetWidth = if (running) {
            resources.getDimensionPixelSize(R.dimen.start_button_width_running).coerceAtMost(maxRunningWidth)
        } else {
            startButtonIdleWidth
        }
        val targetHeight = if (running) {
            resources.getDimensionPixelSize(R.dimen.start_button_height_running)
        } else {
            startButtonIdleHeight
        }
        val targetCorner = resources.getDimensionPixelSize(
            if (running) R.dimen.start_button_corner_running else R.dimen.start_button_corner_idle
        )

        val shapeInterpolator = if (running) FastOutSlowInInterpolator() else DecelerateInterpolator(1.35f)
        val animationDuration = if (running) 320L else 360L
        val ringScaleX = targetWidth.toFloat() / startButtonIdleWidth.toFloat()
        val ringScaleY = targetHeight.toFloat() / startButtonIdleHeight.toFloat()
        val ringAlpha = if (running) 0f else 1f
        val glowScale = if (running) 1.06f else 1f
        val glowAlpha = if (running) 0.08f else 0.22f

        toggleButton.animate().cancel()
        startButtonRing.animate().cancel()
        startButtonGlow.animate().cancel()

        animateButtonSize(targetWidth, targetHeight, animationDuration, shapeInterpolator)
        toggleButton.animate()
            .translationY(targetTranslationY)
            .setDuration(animationDuration)
            .setInterpolator(shapeInterpolator)
            .start()

        startButtonRing.animate()
            .scaleX(ringScaleX)
            .scaleY(ringScaleY)
            .translationY(targetTranslationY)
            .alpha(ringAlpha)
            .setDuration(animationDuration)
            .setInterpolator(shapeInterpolator)
            .start()

        startButtonGlow.animate()
            .scaleX(glowScale)
            .scaleY(glowScale)
            .translationY(targetTranslationY)
            .alpha(glowAlpha)
            .setDuration(animationDuration)
            .setInterpolator(shapeInterpolator)
            .start()

        val currentCorner = toggleButton.cornerRadius
        if (currentCorner != targetCorner) {
            android.animation.ValueAnimator.ofInt(currentCorner, targetCorner).apply {
                this.duration = animationDuration
                interpolator = shapeInterpolator
                addUpdateListener { animator ->
                    toggleButton.cornerRadius = animator.animatedValue as Int
                }
                start()
            }
        }

        animateCardsOffset(running, animationDuration, targetHeight, targetTranslationY)
    }

    private fun animateButtonSize(
        targetWidth: Int,
        targetHeight: Int,
        duration: Long,
        interpolator: android.animation.TimeInterpolator
    ) {
        val params = toggleButton.layoutParams
        val startWidth = params.width
        val startHeight = params.height
        if (startWidth == targetWidth && startHeight == targetHeight) return

        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                params.width = (startWidth + (targetWidth - startWidth) * fraction).toInt()
                params.height = (startHeight + (targetHeight - startHeight) * fraction).toInt()
                toggleButton.layoutParams = params
            }
            start()
        }
    }

    private fun animateCardsOffset(running: Boolean, duration: Long, targetButtonHeight: Int, targetButtonTranslationY: Float) {
        val target = if (running) {
            val hostTop = startButtonHost.top.toFloat()
            val buttonTopInsideHost = (startButtonHost.height - targetButtonHeight) / 2f
            val buttonBottom = hostTop + buttonTopInsideHost + targetButtonTranslationY + targetButtonHeight
            val desiredMetricsTop = buttonBottom + resources.getDimension(R.dimen.running_metrics_gap)
            desiredMetricsTop - metricsSection.top
        } else {
            0f
        }
        metricsSection.animate()
            .translationY(target)
            .setDuration(duration)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    private fun computeRunningMetricsShift(targetButtonHeight: Int, targetButtonTranslationY: Float): Float {
        val balancedGap = resources.getDimension(R.dimen.running_balanced_gap)
        val hostTop = startButtonHost.top.toFloat()
        val buttonTopInsideHost = (startButtonHost.height - targetButtonHeight) / 2f
        val buttonBottomTarget = hostTop + buttonTopInsideHost + targetButtonTranslationY + targetButtonHeight
        val desiredMetricsTop = buttonBottomTarget + balancedGap
        val baseMetricsTop = metricsSection.top.toFloat()
        return desiredMetricsTop - baseMetricsTop
    }

    private fun bindAlertState(isAlert: Boolean) {
        alertCard.strokeWidth = if (isAlert) {
            resources.getDimensionPixelSize(R.dimen.alert_stroke_width)
        } else {
            resources.getDimensionPixelSize(R.dimen.card_stroke_width)
        }
        alertCard.strokeColor = if (isAlert) getColor(R.color.status_alert) else getColor(R.color.card_stroke)
        if (isAlert == isAlertActive) {
            return
        }
        isAlertActive = isAlert
        if (isAlertActive) {
            statusPill.text = getString(R.string.status_alert_short)
            statusPill.setBackgroundResource(R.drawable.bg_status_alert)
        } else {
            statusPill.text = if (isRunning) getString(R.string.status_running_short) else getString(R.string.status_idle_short)
            statusPill.setBackgroundResource(if (isRunning) R.drawable.bg_status_running else R.drawable.bg_status_idle)
        }
        bindAmbientAnimations()
    }

    private fun bindAmbientAnimations() {
        statusPill.clearAnimation()
        alertCard.clearAnimation()
        when {
            isAlertActive -> {
                statusPill.startAnimation(AnimationUtils.loadAnimation(this, R.anim.alert_flash))
                alertCard.startAnimation(AnimationUtils.loadAnimation(this, R.anim.alert_flash))
            }
            isRunning -> {
                statusPill.startAnimation(AnimationUtils.loadAnimation(this, R.anim.status_breathe))
            }
        }
    }

    private fun bindOverlayButtonText() {
        overlayToggleButton.text = if (overlayVisible) {
            getString(R.string.overlay_hide_button)
        } else {
            getString(R.string.overlay_show_button)
        }
    }

    private fun bindOverlayCompactButtonText() {
        overlayCompactToggleButton.text = if (overlayCompact) {
            getString(R.string.overlay_compact_disable)
        } else {
            getString(R.string.overlay_compact_enable)
        }
    }

    private fun updateToggleButtonIcon(running: Boolean, animate: Boolean) {
        val targetRes = if (running) R.drawable.ic_stop_detect else R.drawable.ic_start_detect
        if (!animate) {
            toggleButton.setIconResource(targetRes)
            return
        }

        val current = toggleButton.icon
        if (current == null) {
            toggleButton.setIconResource(targetRes)
            return
        }

        ValueAnimator.ofInt(255, 0).apply {
            duration = 120L
            addUpdateListener { animator ->
                current.alpha = animator.animatedValue as Int
                toggleButton.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    val next = AppCompatResources.getDrawable(this@MainActivity, targetRes)?.mutate()
                    if (next == null) {
                        toggleButton.setIconResource(targetRes)
                        return
                    }
                    next.alpha = 0
                    toggleButton.icon = next
                    ValueAnimator.ofInt(0, 255).apply {
                        duration = 140L
                        addUpdateListener { fadeIn ->
                            next.alpha = fadeIn.animatedValue as Int
                            toggleButton.invalidate()
                        }
                        start()
                    }
                }
            })
            start()
        }
    }


    private fun parseFps(raw: String): Int {
        return when {
            raw.startsWith("fps10") -> 10
            raw.startsWith("fps20") -> 20
            raw.startsWith("fps60") -> 60
            else -> 30
        }
    }

    private fun parseTrackingMode(raw: String): String {
        return if (raw.startsWith("极速")) "fast" else "balanced"
    }

    private fun attachSwipeCycler(target: TextView, nextText: (direction: Int) -> String) {
        val detector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent): Boolean = true

            override fun onFling(
                e1: android.view.MotionEvent?,
                e2: android.view.MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val startEvent = e1 ?: return false
                val dx = e2.x - startEvent.x
                val dy = e2.y - startEvent.y
                if (kotlin.math.abs(dx) <= kotlin.math.abs(dy) || kotlin.math.abs(dx) < 60f) {
                    return false
                }
                val direction = if (dx < 0f) 1 else -1
                val updated = nextText(direction)
                target.animate().translationX(if (direction > 0) -28f else 28f).alpha(0f).setDuration(90L)
                    .withEndAction {
                        target.text = updated
                        target.translationX = if (direction > 0) 28f else -28f
                        target.animate().translationX(0f).alpha(1f).setDuration(130L).start()
                    }.start()
                return true
            }
        })
        target.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    private fun updateDetailCardsVisibility(visible: Boolean, animate: Boolean) {
        val cards = listOf<View>(alertCard, diagCard)
        if (!animate) {
            cards.forEach {
                it.visibility = if (visible) View.VISIBLE else View.GONE
                it.alpha = if (visible) 1f else 0f
            }
            return
        }

        cards.forEach { card ->
            if (visible) {
                if (card.visibility != View.VISIBLE) {
                    card.visibility = View.VISIBLE
                    card.alpha = 0f
                    card.startAnimation(AnimationUtils.loadAnimation(this, R.anim.card_reveal_up))
                    card.animate().alpha(1f).setDuration(260L).start()
                }
            } else if (card.visibility == View.VISIBLE) {
                card.animate().alpha(0f).setDuration(180L).withEndAction {
                    card.visibility = View.GONE
                }.start()
            }
        }
    }

}