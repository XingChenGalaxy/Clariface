package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.capture.ScreenCaptureService
import com.example.myapplication.overlay.OverlaySettings
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView

class SettingsActivity : AppCompatActivity() {
    private lateinit var fpsDropdown: MaterialAutoCompleteTextView
    private lateinit var modeDropdown: MaterialAutoCompleteTextView
    private lateinit var overlayToggleButton: MaterialButton
    private lateinit var overlayCompactToggleButton: MaterialButton

    private var overlayVisible = true
    private var overlayCompact = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        fpsDropdown = findViewById(R.id.fpsDropdown)
        modeDropdown = findViewById(R.id.modeDropdown)
        overlayToggleButton = findViewById(R.id.overlayToggleButton)
        overlayCompactToggleButton = findViewById(R.id.overlayCompactToggleButton)

        overlayVisible = OverlaySettings.isOverlayVisible(this)
        overlayCompact = OverlaySettings.isOverlayCompact(this)

        setupFpsSelector()
        setupTrackingModeSelector()
        setupOverlayButtons()

        findViewById<MaterialButton>(R.id.backButton).setOnClickListener {
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.slide_out_right)
        }
        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            val fps = parseFps(fpsDropdown.text?.toString().orEmpty())
            val mode = parseTrackingMode(modeDropdown.text?.toString().orEmpty())
            OverlaySettings.setRequestedFps(this, fps)
            OverlaySettings.setTrackingMode(this, mode)
            notifyServiceSettingsChanged()
            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.slide_out_right)
        }
    }

    private fun setupFpsSelector() {
        val options = resources.getStringArray(R.array.fps_options)
        val adapter = ArrayAdapter(this, R.layout.item_settings_dropdown, options)
        fpsDropdown.setAdapter(adapter)

        val currentFps = OverlaySettings.getRequestedFps(this)
        val selectedIndex = options.indexOfFirst { parseFps(it) == currentFps }
        fpsDropdown.setText(options.getOrElse(selectedIndex.takeIf { it >= 0 } ?: 2) { options[2] }, false)
        fpsDropdown.setOnItemClickListener { _, view, _, _ ->
            animateSelection(view)
        }
    }

    private fun setupTrackingModeSelector() {
        val options = resources.getStringArray(R.array.tracking_mode_options)
        val adapter = ArrayAdapter(this, R.layout.item_settings_dropdown, options)
        modeDropdown.setAdapter(adapter)
        val trackingMode = OverlaySettings.getTrackingMode(this)
        val selectedIndex = options.indexOfFirst { parseTrackingMode(it) == trackingMode }
        modeDropdown.setText(options.getOrElse(selectedIndex.takeIf { it >= 0 } ?: 0) { options[0] }, false)
        modeDropdown.setOnItemClickListener { _, view, _, _ ->
            animateSelection(view)
        }
    }

    private fun setupOverlayButtons() {
        bindOverlayButtonText()
        bindOverlayCompactButtonText()

        overlayToggleButton.setOnClickListener {
            overlayVisible = !overlayVisible
            OverlaySettings.setOverlayVisible(this, overlayVisible)
            bindOverlayButtonText()
            notifyServiceOverlayVisibilityChanged()
        }

        overlayCompactToggleButton.setOnClickListener {
            overlayCompact = !overlayCompact
            OverlaySettings.setOverlayCompact(this, overlayCompact)
            bindOverlayCompactButtonText()
            notifyServiceOverlayCompactChanged()
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

    private fun notifyServiceSettingsChanged() {
        notifyServiceOverlayVisibilityChanged()
        notifyServiceOverlayCompactChanged()
    }

    private fun notifyServiceOverlayVisibilityChanged() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_SET_OVERLAY_VISIBILITY
            putExtra(ScreenCaptureService.EXTRA_OVERLAY_VISIBLE, overlayVisible)
        }
        startService(intent)
    }

    private fun notifyServiceOverlayCompactChanged() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_SET_OVERLAY_COMPACT
            putExtra(ScreenCaptureService.EXTRA_OVERLAY_COMPACT, overlayCompact)
        }
        startService(intent)
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

    private fun animateSelection(target: View?) {
        target ?: return
        target.alpha = 0.75f
        target.scaleX = 0.98f
        target.scaleY = 0.98f
        target.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180L)
            .start()
    }
}

