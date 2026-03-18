package com.example.myapplication.pipeline

import kotlin.math.roundToInt

/**
 * Mirrors the desktop logic:
 * - smooth over latest 5 window scores
 * - fake threshold = 0.3
 * - trigger alert after 6 consecutive fake windows
 */
class RealtimeDecisionEngine(
    private val fakeThreshold: Float = 0.3f,
    private val smoothingSize: Int = 5,
    private val requiredConsecutiveFake: Int = 6
) {
    private val history = ArrayDeque<Float>()
    private var consecutiveFake = 0

    fun update(windowScore: Float): Decision {
        if (history.size == smoothingSize) {
            history.removeFirst()
        }
        history.addLast(windowScore)
        val smoothed = history.average().toFloat()

        val isFake = smoothed <= fakeThreshold
        if (isFake) {
            consecutiveFake += 1
        } else {
            consecutiveFake = 0
        }

        val alert = isFake && consecutiveFake >= requiredConsecutiveFake
        if (alert) {
            consecutiveFake = 0
        }

        return Decision(
            rawProbability = windowScore,
            smoothedProbability = smoothed,
            label = if (smoothed > fakeThreshold) 1 else 0,
            isAlert = alert,
            consecutiveFakeCount = consecutiveFake
        )
    }

    fun reset() {
        history.clear()
        consecutiveFake = 0
    }
}

data class Decision(
    val rawProbability: Float,
    val smoothedProbability: Float,
    val label: Int,
    val isAlert: Boolean,
    val consecutiveFakeCount: Int
) {
    fun prettyProbability(): String {
        val p = (smoothedProbability * 10000f).roundToInt() / 10000f
        return "%.4f".format(p)
    }
}

