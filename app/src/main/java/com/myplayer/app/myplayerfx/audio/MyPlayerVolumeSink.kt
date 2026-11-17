package com.myplayer.app.myplayerfx.audio

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.myplayer.app.myplayerfx.AudioFxService
import com.myplayer.app.playback.AudioOutputRouterManager
import com.myplayer.app.playback.dsp.MyPlayerCrossmixAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * MyPlayer's unified Volume & MyPlayer Clock Sink (merges `MyPlayerVolumeSink` + MyPlayer `CrossmixClockSink`):
 * 1. Forwards presentation timestamps (`onInputTimestamp`) and stream offsets (`onStreamOffset`)
 *    to [crossmixProcessor] so sample-accurate transitions stay locked to the track's clock.
 * 2. Applies the exponential [VolumeCurve], track [ReplayGainTags], and Multi-Output Studio's
 *    independent app volume multiplier identically on both primary and crossfade decks.
 * 3. Deduplicates native `AudioTrack.setVolume` calls to prevent startup buffer jitter.
 */
@UnstableApi
class MyPlayerVolumeSink(
    delegate: AudioSink,
    private val audioFx: AudioFxService,
    private val replayGain: ReplayGainTags,
    private val crossmixProcessor: MyPlayerCrossmixAudioProcessor? = null,
) : ForwardingAudioSink(delegate) {

    private var lastTimestampUs = Long.MIN_VALUE
    private var requestedVolume = 1f
    private var appliedCurve: VolumeCurve? = null
    private var appliedReplayGain = 1f
    private var appliedIndependentVolume = 1f
    private var lastDispatchedGain = -1f

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (presentationTimeUs != lastTimestampUs) {
            lastTimestampUs = presentationTimeUs
            crossmixProcessor?.onInputTimestamp(presentationTimeUs)
        }
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

    override fun flush() {
        lastTimestampUs = Long.MIN_VALUE
        super.flush()
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        crossmixProcessor?.onStreamOffset(outputStreamOffsetUs)
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    private fun applyVolume() {
        val curve = audioFx.volumeCurve
        appliedCurve = curve
        appliedReplayGain = replayGainFactor()
        appliedIndependentVolume = AudioOutputRouterManager.currentIndependentVolumeMultiplier
        val userGain = curve.gain(appliedIndependentVolume.coerceIn(0f, 1f))
        val targetGain = (requestedVolume.coerceIn(0f, 1f) * userGain * appliedReplayGain).coerceIn(0f, 2f)
        if (abs(targetGain - lastDispatchedGain) > 0.0004f) {
            lastDispatchedGain = targetGain
            super.setVolume(targetGain)
        }
    }

    private fun replayGainFactor() = replayGain.linear(audioFx.replayGain, audioFx.normalize)
}
