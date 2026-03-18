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
    val roiRect: Rect? = null
)

object InferenceEngineFactory {
    fun create(context: Context): DeepfakeInferenceEngine {
        return try {
            OnnxDeepfakeInferenceEngine(context)
        } catch (_: Exception) {
            // Keep app runnable even before model conversion is ready.
            FallbackHeuristicInferenceEngine()
        }
    }
}

