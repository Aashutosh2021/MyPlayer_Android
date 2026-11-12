package com.myplayer.app.playback.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.myplayer.app.data.preferences.ClarityPreset
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
@Singleton
class LastWaveDspAudioProcessor @Inject constructor() : BaseAudioProcessor() {

    @Volatile private var clarityPreset: ClarityPreset = ClarityPreset.OFF
    @Volatile private var trueDolbyEnabled: Boolean = false
    @Volatile private var spatialWidth: Float = 0.45f
    @Volatile var isDualBudActive: Boolean = false // DualBud safety flag!

    // Biquad High-Shelf Filter state (Left & Right channels)
    private var x1L = 0f; private var x2L = 0f; private var y1L = 0f; private var y2L = 0f
    private var x1R = 0f; private var x2R = 0f; private var y1R = 0f; private var y2R = 0f
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f

    fun updateDspConfig(preset: ClarityPreset, dolby: Boolean, width: Float) {
        this.clarityPreset = preset
        this.trueDolbyEnabled = dolby
        this.spatialWidth = width.coerceIn(0f, 1f)
        if (inputAudioFormat.sampleRate > 0) {
            calculateHighShelfCoeffs(inputAudioFormat.sampleRate, 3800f, preset.highShelfGainDb)
        }
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioFormat.NOT_SET
        }
        calculateHighShelfCoeffs(inputAudioFormat.sampleRate, 3800f, clarityPreset.highShelfGainDb)
        return inputAudioFormat
    }

    private fun calculateHighShelfCoeffs(sampleRate: Int, freq: Float, gainDb: Float) {
        updateBiquadCoefficients(sampleRate, freq, gainDb)
    }

    private fun updateBiquadCoefficients(sampleRate: Int, freq: Float, gainDb: Float) {
        if (gainDb == 0f) {
            b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
            return
        }
        val safeFreq = if (sampleRate > 0) minOf(3800f, sampleRate * 0.45f) else freq
        val a = 10.0.pow(gainDb / 40.0).toFloat()
        val w0 = (2.0 * Math.PI * safeFreq / sampleRate).toFloat()
        val cosW0 = cos(w0)
        val alpha = sin(w0) / 2f * sqrt(2f)

        val a0 = (a + 1f) - (a - 1f) * cosW0 + 2f * sqrt(a) * alpha
        b0 = (a * ((a + 1f) + (a - 1f) * cosW0 + 2f * sqrt(a) * alpha)) / a0
        b1 = (-2f * a * ((a - 1f) + (a + 1f) * cosW0)) / a0
        b2 = (a * ((a + 1f) + (a - 1f) * cosW0 - 2f * sqrt(a) * alpha)) / a0
        a1 = (2f * ((a - 1f) - (a + 1f) * cosW0)) / a0
        a2 = ((a + 1f) - (a - 1f) * cosW0 - 2f * sqrt(a) * alpha) / a0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outputBuffer = replaceOutputBuffer(remaining)
        val bypassDsp = (clarityPreset == ClarityPreset.OFF && !trueDolbyEnabled)

        if (bypassDsp) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        while (inputBuffer.remaining() >= 4) {
            var left = inputBuffer.short / 32768f
            var right = inputBuffer.short / 32768f

            // 1. Apply Clarity High-Shelf Biquad Filter
            if (clarityPreset != ClarityPreset.OFF) {
                var outL = b0 * left + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
                var outR = b0 * right + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R

                if (outL.isNaN() || outL.isInfinite() || outR.isNaN() || outR.isInfinite()) {
                    x1L = 0f; x2L = 0f; y1L = 0f; y2L = 0f
                    x1R = 0f; x2R = 0f; y1R = 0f; y2R = 0f
                    outL = 0f
                    outR = 0f
                } else {
                    x2L = x1L; x1L = left; y2L = y1L; y1L = outL
                    x2R = x1R; x1R = right; y2R = y1R; y1R = outR
                }
                left = outL
                right = outR
            }

            // 2. Apply True-Dolby Mid-Side Spatial Widening (Bypassed if DualBud Mode is ON)
            if (trueDolbyEnabled && !isDualBudActive) {
                val mid = (left + right) * 0.5f
                val side = (left - right) * 0.5f * (1f + spatialWidth * 0.85f)
                left = mid + side
                right = mid - side
            }

            if (left.isNaN() || left.isInfinite()) left = 0f
            if (right.isNaN() || right.isInfinite()) right = 0f

            // Soft-clipping limiter to prevent digital distortion
            val clampedL = (left.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            val clampedR = (right.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

            outputBuffer.putShort(clampedL)
            outputBuffer.putShort(clampedR)
        }
        if (inputBuffer.hasRemaining()) inputBuffer.position(inputBuffer.limit())
        outputBuffer.flip()
    }
}
