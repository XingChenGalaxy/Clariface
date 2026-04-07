package com.example.myapplication.pipeline

import kotlin.math.roundToInt

/**
 * Mirrors the desktop logic while making the visible label stable in both directions:
 * - smooth over latest 5 window scores
 * - fake threshold = 0.3
 * - visible label switches only after several consecutive frames on the new side
 */
class RealtimeDecisionEngine(
    private val fakeThreshold: Float = 0.3f,
    private val smoothingSize: Int = 5,
    private val requiredConsecutiveSwitch: Int = 3
) {
    private val history = ArrayDeque<Float>()
    private var stableLabel = 1
    private var pendingLabel = 1
    private var consecutivePending = 0

    fun update(windowScore: Float): Decision {
        if (history.size == smoothingSize) {
            history.removeFirst()
        }
        history.addLast(windowScore)
        val smoothed = history.average().toFloat()

        val candidateLabel = if (smoothed > fakeThreshold) 1 else 0
        if (candidateLabel == stableLabel) {
            pendingLabel = candidateLabel
            consecutivePending = 0
        } else if (candidateLabel != pendingLabel) {
            pendingLabel = candidateLabel
            consecutivePending = 1
        } else {
            consecutivePending += 1
            if (consecutivePending >= requiredConsecutiveSwitch) {
                stableLabel = candidateLabel
                consecutivePending = 0
            }
        }

        val isAlert = stableLabel == 0

        return Decision(
            rawProbability = windowScore,
            smoothedProbability = smoothed,
            label = stableLabel,
            isAlert = isAlert,
            consecutiveSwitchCount = consecutivePending,
            candidateLabel = candidateLabel
        )
    }

    fun reset() {
        history.clear()
        stableLabel = 1
        pendingLabel = 1
        consecutivePending = 0
    }
}

data class Decision(
    val rawProbability: Float,
    val smoothedProbability: Float,
    val label: Int,
    val isAlert: Boolean,
    val consecutiveSwitchCount: Int,
    val candidateLabel: Int
) {
    fun prettyProbability(): String {
        val p = (smoothedProbability * 10000f).roundToInt() / 10000f
        return "%.4f".format(p)
    }
}
