package com.myplayer.app.myplayerfx.audio

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.myplayer.app.myplayerfx.AudioFxService
import com.myplayer.app.playback.AudioOutputRouterManager
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * MyPlayer's volume stage: the exponential volume curve, the song's ReplayGain, and
 * Multi-Output Studio's independent app volume multiplier shape every volume the player sets.
 * Deduplicates native AudioTrack.setVolume calls to prevent startup buffer jitter.
 */
@UnstableApi
class MyPlayerVolumeSink(
    delegate: AudioSink,
    private val audioFx: AudioFxService,
    private val replayGain: ReplayGainTags,
) : ForwardingAudioSink(delegate) {

    private var requestedVolume = 1f
    private var appliedCurve: VolumeCurve? = null
    private var appliedReplayGain = 1f
    private var appliedIndependentVolume = 1f
    private var lastDispatchedGain = -1f

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val currentIndVol = AudioOutputRouterManager.currentIndependentVolumeMultiplier
        val currentRg = replayGainFactor()
        if (audioFx.volumeCurve !== appliedCurve ||
            abs(currentRg - appliedReplayGain) > 0.0005f ||
            abs(currentIndVol - appliedIndependentVolume) > 0.0005f
        ) {
            applyVolume()
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun setVolume(volume: Float) {
        requestedVolume = volume
        applyVolume()
    }

    private fun applyVolume() {
        val curve = audioFx.volumeCurve
        appliedCurve = curve
        appliedReplayGain = replayGainFactor()
        appliedIndependentVolume = AudioOutputRouterManager.currentIndependentVolumeMultiplier
        val effectiveInput = (requestedVolume * appliedIndependentVolume).coerceIn(0f, 1f)
        val targetGain = (curve.gain(effectiveInput) * appliedReplayGain).coerceIn(0f, 2f)
        if (abs(targetGain - lastDispatchedGain) > 0.0004f) {
            lastDispatchedGain = targetGain
            super.setVolume(targetGain)
        }
    }

    private fun replayGainFactor() = replayGain.linear(audioFx.replayGain, audioFx.normalize)
}
