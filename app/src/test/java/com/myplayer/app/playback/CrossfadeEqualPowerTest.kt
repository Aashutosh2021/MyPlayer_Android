package com.myplayer.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeEqualPowerTest {
    @Test
    fun equalPowerGainsPreserveConstantPowerAcrossTransition() {
        val (outStart, inStart) = AudioFeatureManagers.equalPowerGains(0f)
        assertEquals(1f, outStart, 0.0001f)
        assertEquals(0f, inStart, 0.0001f)

        val (outEnd, inEnd) = AudioFeatureManagers.equalPowerGains(1f)
        assertEquals(0f, outEnd, 0.0001f)
        assertEquals(1f, inEnd, 0.0001f)

        for (step in 0..10) {
            val p = step / 10f
            val (o, i) = AudioFeatureManagers.equalPowerGains(p)
            val powerSum = o * o + i * i
            assertEquals("power sum at p=$p", 1.0f, powerSum, 0.001f)
            assertTrue("outgoing monotonic at p=$p", o in 0f..1f)
            assertTrue("incoming monotonic at p=$p", i in 0f..1f)
        }
    }
}
