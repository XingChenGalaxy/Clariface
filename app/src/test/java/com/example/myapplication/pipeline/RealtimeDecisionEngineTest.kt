package com.example.myapplication.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeDecisionEngineTest {

    @Test
    fun `label is 1 when smoothed score above threshold`() {
        val engine = RealtimeDecisionEngine(fakeThreshold = 0.3f)
        val decision = engine.update(0.8f)

        assertEquals(1, decision.label)
        assertFalse(decision.isAlert)
    }

    @Test
    fun `alert triggers after required consecutive fake updates`() {
        val engine = RealtimeDecisionEngine(fakeThreshold = 0.3f, requiredConsecutiveFake = 3)

        engine.update(0.1f)
        engine.update(0.1f)
        val decision = engine.update(0.1f)

        assertEquals(0, decision.label)
        assertTrue(decision.isAlert)
    }
}

