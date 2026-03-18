package com.example.myapplication.overlay

import android.content.Context

object OverlaySettings {
    private const val PREFS_NAME = "overlay_settings"
    private const val KEY_OVERLAY_VISIBLE = "overlay_visible"
    private const val KEY_PREVIEW_VISIBLE = "preview_visible"

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

    fun isPreviewVisible(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREVIEW_VISIBLE, true)
    }

    fun setPreviewVisible(context: Context, visible: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PREVIEW_VISIBLE, visible)
            .apply()
    }
}

