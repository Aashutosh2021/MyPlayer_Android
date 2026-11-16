package com.myplayer.app.myplayerfx.audio

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.myplayer.app.myplayerfx.AudioFxService
import com.myplayer.app.playback.AudioOutputRouterManager
import java.nio.ByteBuffer

/**
 * MyPlayer's volume stage: the exponential volume curve, the song's ReplayGain, and
 * Multi-Output Studio's independent app volume multiplier shape every volume the player sets.
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

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val currentIndVol = AudioOutputRouterManager.currentIndependentVolumeMultiplier
        // curve, ReplayGain, or Independent App Volume changed: update sink gain live
        if (audioFx.volumeCurve !== appliedCurve ||
            replayGainFactor() != appliedReplayGain ||
            currentIndVol != appliedIndependentVolume
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
        super.setVolume(curve.gain(effectiveInput) * appliedReplayGain)
    }

    private fun replayGainFactor() = replayGain.linear(audioFx.replayGain, audioFx.normalize)
}
