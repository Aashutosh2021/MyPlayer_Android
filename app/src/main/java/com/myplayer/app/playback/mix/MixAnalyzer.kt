package com.myplayer.app.playback.mix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

data class TrackMixPoint(
    val outroStartMs: Long,
    val introEndMs: Long,
    val estimatedBpm: Float,
    val rmsDb: Float,
    val recommendedOverlapMs: Long,
    val tempoRatio: Float
)

/**
 * OpenTune DJ Automix & Equal-Power Crossfade Analyzer ported to MyPlayer.
 */
@Singleton
class MixAnalyzer @Inject constructor() {

    fun equalPowerGains(progress: Float): Pair<Float, Float> {
        val clamped = progress.coerceIn(0f, 1f)
        val angle = (clamped * (PI / 2.0)).toFloat()
        return cos(angle) to sin(angle)
    }

    fun analyzeTransition(
        outgoingTailPcm: ShortArray,
        incomingHeadPcm: ShortArray,
        sampleRateHz: Int = 44100,
        channels: Int = 2,
        outgoingDurationMs: Long
    ): TrackMixPoint {
        val frameSamples = max(1, (sampleRateHz / 20) * channels)
        val outEnv = computeRmsEnvelopeDb(outgoingTailPcm, frameSamples)
        val inEnv = computeRmsEnvelopeDb(incomingHeadPcm, frameSamples)

        val outPeakDb = outEnv.maxOrNull() ?: -20f
        val thresholdDb = outPeakDb - 10f
        var tailFadeFrames = outEnv.size
        for (i in outEnv.indices.reversed()) {
            if (outEnv[i] >= thresholdDb) {
                tailFadeFrames = i
                break
            }
        }
        val tailSecondsFromEnd = ((outEnv.size - tailFadeFrames) * 50L).coerceIn(1_500L, 12_000L)
        val outroStartMs = (outgoingDurationMs - tailSecondsFromEnd).coerceAtLeast(0L)

        val inPeakDb = inEnv.maxOrNull() ?: -20f
        val inThresholdDb = inPeakDb - 12f
        var introSkipFrames = 0
        for (i in inEnv.indices) {
            if (inEnv[i] >= inThresholdDb) {
                introSkipFrames = i
                break
            }
        }
        val introEndMs = (introSkipFrames * 50L).coerceIn(0L, 6_000L)

        val outBpm = estimateBpmFromEnvelope(outEnv)
        val inBpm = estimateBpmFromEnvelope(inEnv)
        val rawRatio = if (outBpm > 60f && inBpm > 60f) outBpm / inBpm else 1.0f
        val tempoRatio = if (abs(1f - rawRatio) <= 0.06f) rawRatio else 1.0f

        val overlapMs = when {
            outBpm >= 120f -> 4_500L
            outBpm >= 90f -> 6_000L
            else -> 7_500L
        }

        return TrackMixPoint(
            outroStartMs = outroStartMs,
            introEndMs = introEndMs,
            estimatedBpm = outBpm,
            rmsDb = outPeakDb,
            recommendedOverlapMs = overlapMs,
            tempoRatio = tempoRatio
        )
    }

    private fun computeRmsEnvelopeDb(pcm: ShortArray, windowSamples: Int): FloatArray {
        if (pcm.isEmpty() || windowSamples <= 0) return floatArrayOf(-60f)
        val windows = pcm.size / windowSamples
        if (windows == 0) return floatArrayOf(-60f)
        val out = FloatArray(windows)
        for (w in 0 until windows) {
            var sumSq = 0.0
            val base = w * windowSamples
            for (i in 0 until windowSamples) {
                val sample = pcm[base + i] / 32768.0
                sumSq += sample * sample
            }
            val rms = sqrt(sumSq / windowSamples).coerceAtLeast(1e-6)
            out[w] = (20.0 * log10(rms)).toFloat()
        }
        return out
    }

    private fun estimateBpmFromEnvelope(envDb: FloatArray): Float {
        if (envDb.size < 40) return 120f
        val onset = FloatArray(envDb.size - 1) { i -> max(0f, envDb[i + 1] - envDb[i]) }
        var bestLag = 10
        var bestCorr = -1f
        for (lag in 7..min(20, onset.size / 2)) {
            var corr = 0f
            for (i in 0 until onset.size - lag) {
                corr += onset[i] * onset[i + lag]
            }
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }
        return (60f / (bestLag * 0.05f)).coerceIn(65f, 175f)
    }
}
