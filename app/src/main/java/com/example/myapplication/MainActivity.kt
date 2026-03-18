package com.example.myapplication

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.myapplication.capture.ScreenCaptureService
import com.example.myapplication.overlay.OverlaySettings
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {
    private lateinit var statusPill: TextView
    private lateinit var instructionText: TextView
    private lateinit var fpsSpinner: Spinner
    private lateinit var toggleButton: MaterialButton
    private lateinit var overlayToggleButton: MaterialButton
    private lateinit var previewToggleButton: MaterialButton
    private lateinit var probabilityText: TextView
    private lateinit var detectionTimeText: TextView
    private lateinit var fpsText: TextView
    private lateinit var labelText: TextView
    private lateinit var roiStatusText: TextView
    private lateinit var errorReasonText: TextView
    private lateinit var alertCard: MaterialCardView

    private var isRunning = false
    private var pendingFps = 30
    private var overlayVisible = true
    private var previewVisible = true
    private var pendingStartAfterOverlayGrant = false

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
        fpsSpinner = findViewById(R.id.fpsSpinner)
        toggleButton = findViewById(R.id.toggleButton)
        overlayToggleButton = findViewById(R.id.overlayToggleButton)
        previewToggleButton = findViewById(R.id.previewToggleButton)
        probabilityText = findViewById(R.id.probabilityText)
        detectionTimeText = findViewById(R.id.detectionTimeText)
        fpsText = findViewById(R.id.fpsText)
        labelText = findViewById(R.id.labelText)
        roiStatusText = findViewById(R.id.roiStatusText)
        errorReasonText = findViewById(R.id.errorReasonText)
        alertCard = findViewById(R.id.alertCard)
        overlayVisible = OverlaySettings.isOverlayVisible(this)
        previewVisible = OverlaySettings.isPreviewVisible(this)

        setupFpsSelector()
        setupToggleButton()
        setupOverlayToggleButton()
        setupPreviewToggleButton()
        maybeRequestNotificationPermission()
        bindRunningState(false)
        bindOverlayButtonText()
        bindPreviewButtonText()
        errorReasonText.text = getString(R.string.diag_default)
        roiStatusText.text = getString(R.string.roi_waiting)
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
        unregisterReceiver(metricsReceiver)
        unregisterReceiver(stateReceiver)
        unregisterReceiver(diagnosticReceiver)
    }

    private fun setupFpsSelector() {
        val options = resources.getStringArray(R.array.fps_options)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        fpsSpinner.adapter = adapter
        fpsSpinner.setSelection(2, false)
        pendingFps = parseFps(options[2])
    }

    private fun setupToggleButton() {
        toggleButton.setOnClickListener {
            if (isRunning) {
                sendStopCommand()
                bindRunningState(false)
                instructionText.text = getString(R.string.instruction_idle)
            } else {
                pendingFps = parseFps(fpsSpinner.selectedItem.toString())
                ensureOverlayVisibleForNewSession()
                ensureOverlayPermissionThenStart()
            }
        }
    }

    private fun ensureOverlayVisibleForNewSession() {
        if (overlayVisible) {
            return
        }
        overlayVisible = true
        OverlaySettings.setOverlayVisible(this, true)
        bindOverlayButtonText()
        Toast.makeText(this, getString(R.string.overlay_auto_enabled_for_start), Toast.LENGTH_SHORT).show()
    }

    private fun setupOverlayToggleButton() {
        overlayToggleButton.setOnClickListener {
            overlayVisible = !overlayVisible
            OverlaySettings.setOverlayVisible(this, overlayVisible)
            bindOverlayButtonText()
            if (isRunning) {
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_SET_OVERLAY_VISIBILITY
                    putExtra(ScreenCaptureService.EXTRA_OVERLAY_VISIBLE, overlayVisible)
                }
                startService(intent)
            }
            val message = if (overlayVisible) {
                getString(R.string.overlay_now_shown)
            } else {
                getString(R.string.overlay_now_hidden)
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPreviewToggleButton() {
        previewToggleButton.setOnClickListener {
            previewVisible = !previewVisible
            OverlaySettings.setPreviewVisible(this, previewVisible)
            bindPreviewButtonText()
            if (isRunning) {
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_SET_PREVIEW_VISIBILITY
                    putExtra(ScreenCaptureService.EXTRA_PREVIEW_VISIBLE, previewVisible)
                }
                startService(intent)
            }
            val message = if (previewVisible) {
                getString(R.string.preview_now_shown)
            } else {
                getString(R.string.preview_now_hidden)
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
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
        isRunning = running
        statusPill.text = if (running) getString(R.string.status_running_short) else getString(R.string.status_idle_short)
        statusPill.setBackgroundResource(if (running) R.drawable.bg_status_running else R.drawable.bg_status_idle)
        toggleButton.text = if (running) getString(R.string.stop_detection) else getString(R.string.start_detection)
        instructionText.text = if (running) getString(R.string.instruction_running) else getString(R.string.instruction_idle)
    }

    private fun bindOverlayButtonText() {
        overlayToggleButton.text = if (overlayVisible) {
            getString(R.string.overlay_hide_button)
        } else {
            getString(R.string.overlay_show_button)
        }
    }

    private fun bindPreviewButtonText() {
        previewToggleButton.text = if (previewVisible) {
            getString(R.string.preview_hide_button)
        } else {
            getString(R.string.preview_show_button)
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

    private fun bindAlertState(isAlert: Boolean) {
        alertCard.strokeWidth = if (isAlert) resources.getDimensionPixelSize(R.dimen.alert_stroke_width) else 0
        alertCard.strokeColor = if (isAlert) getColor(R.color.status_alert) else getColor(R.color.card_stroke)
        if (isAlert) {
            statusPill.text = getString(R.string.status_alert_short)
            statusPill.setBackgroundResource(R.drawable.bg_status_alert)
        } else if (isRunning) {
            statusPill.text = getString(R.string.status_running_short)
            statusPill.setBackgroundResource(R.drawable.bg_status_running)
        }
    }

}