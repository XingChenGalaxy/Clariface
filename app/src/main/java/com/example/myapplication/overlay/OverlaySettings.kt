package com.example.myapplication.overlay

import android.content.Context

object OverlaySettings {
    private const val PREFS_NAME = "overlay_settings"
    private const val KEY_OVERLAY_VISIBLE = "overlay_visible"
    private const val KEY_OVERLAY_COMPACT = "overlay_compact"
    private const val KEY_TRACKING_MODE = "tracking_mode"
    private const val KEY_REQUESTED_FPS = "requested_fps"

    fun isOverlayVisible(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OVERLAY_VISIBLE, true)
    }

    fun setOverlayVisible(context: Context, visible: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OVERLAY_VISIBLE, visible)
            .apply()
    }

    fun isOverlayCompact(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OVERLAY_COMPACT, false)
    }

    fun setOverlayCompact(context: Context, compact: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OVERLAY_COMPACT, compact)
            .apply()
    }

    fun getTrackingMode(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TRACKING_MODE, "balanced") ?: "balanced"
    }

    fun setTrackingMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TRACKING_MODE, mode)
            .apply()
    }

    fun getRequestedFps(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_REQUESTED_FPS, 30)
    }

    fun setRequestedFps(context: Context, fps: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_REQUESTED_FPS, fps)
            .apply()
    }
}

