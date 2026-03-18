package com.example.myapplication.inference

import android.graphics.Bitmap

class FallbackHeuristicInferenceEngine : DeepfakeInferenceEngine {
    override fun infer(bitmap: Bitmap): InferenceOutput {
        val start = System.nanoTime()
        val scaled = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
        val pixels = IntArray(256 * 256)
        scaled.getPixels(pixels, 0, 256, 0, 0, 256, 256)

        var sum = 0.0
        var sumSquare = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            sum += luma
            sumSquare += luma * luma
        }

        val n = pixels.size.toDouble()
        val mean = sum / n
        val variance = (sumSquare / n) - (mean * mean)
        val normalized = (variance / (255.0 * 255.0)).coerceIn(0.0, 1.0)

        // Deterministic fallback score to keep realtime flow available without model file.
        val probability = (0.5 + (normalized - 0.15)).toFloat().coerceIn(0f, 1f)
        val elapsed = (System.nanoTime() - start) / 1_000_000L
        return InferenceOutput(probability = probability, elapsedMs = elapsed, roiUsed = false)
    }

    override fun close() = Unit
}


