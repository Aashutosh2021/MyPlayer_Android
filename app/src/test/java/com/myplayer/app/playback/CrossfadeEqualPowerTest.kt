package com.myplayer.app.playback

import androidx.media3.common.util.UnstableApi
import com.myplayer.app.playback.dsp.MyPlayerCrossmixAudioProcessor
import com.myplayer.app.playback.dsp.MyPlayerCrossmixRole
import com.myplayer.app.playback.dsp.MyPlayerCrossmixStyle
import com.myplayer.app.playback.dsp.MyPlayerFxParams
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

    @Test
    @OptIn(UnstableApi::class)
    fun evaluateStyleKeepsPassbandGainUnitySoPreDecodedBuffersNeverSilence() {
        val params = MyPlayerFxParams()
        val unityStyles = listOf(
            MyPlayerCrossmixStyle.SilkBlend,
            MyPlayerCrossmixStyle.BassSwap,
            MyPlayerCrossmixStyle.FilterSweep,
            MyPlayerCrossmixStyle.EqualPower
        )
        for (style in unityStyles) {
            for (role in MyPlayerCrossmixRole.entries) {
                for (step in 0..10) {
                    val p = step / 10f
                    MyPlayerCrossmixAudioProcessor.evaluateStyle(style, role, p, params)
                    assertEquals("passband gain at $style/$role p=$p", 1.0f, params.gain, 0.0001f)
                }
            }
        }
    }
}
