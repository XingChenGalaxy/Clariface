package com.example.myapplication.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect

interface DeepfakeInferenceEngine {
    fun infer(bitmap: Bitmap): InferenceOutput
    fun close()
}

data class InferenceOutput(
    val probability: Float,
    val elapsedMs: Long,
    val roiUsed: Boolean = false,
    val roiRect: Rect? = null,
    val faceRect: Rect? = null,
    val validForDecision: Boolean = true,
    val isFake: Boolean = false
)

/**
 * 追踪模式枚举，保留供 UI 选择器和 OverlaySettings 使用。
 * 服务器端推理不依赖此值，仅作为用户偏好设置保存。
 */
enum class TrackingMode(val raw: String) {
    BALANCED("balanced"),
    FAST("fast");

    companion object {
        fun fromRaw(raw: String?): TrackingMode {
            return entries.firstOrNull { it.raw == raw } ?: BALANCED
        }
    }
}

object InferenceEngineFactory {
    fun create(context: Context, trackingMode: TrackingMode): DeepfakeInferenceEngine {
        android.util.Log.i("InferenceEngine", "[OK] 使用服务器推理引擎，地址: ${ServerInferenceEngine.SERVER_URL}")
        return ServerInferenceEngine()
    }
}
