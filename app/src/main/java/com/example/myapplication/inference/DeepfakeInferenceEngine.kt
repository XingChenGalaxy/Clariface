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
    val validForDecision: Boolean = true
)

object InferenceEngineFactory {
    fun create(context: Context, trackingMode: TrackingMode): DeepfakeInferenceEngine {
        return try {
            OnnxDeepfakeInferenceEngine(context, trackingMode = trackingMode)
        } catch (_: Exception) {
            // Keep app runnable even before model conversion is ready.
            FallbackHeuristicInferenceEngine()
        }
    }
}

