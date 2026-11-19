package com.myplayer.app.playback.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.myplayer.app.data.preferences.AudioPlaybackState
import com.myplayer.app.data.preferences.Eq15Preset
import com.myplayer.app.data.preferences.LoudnessMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Live health of the master DSP. Android release builds strip android.util.Log
 * (see proguard-rules.pro), so this flow is the only reliable way to see whether
 * the native engine is really running or the Kotlin fallback took over.
 */
data class DspStatus(
    val nativeLoaded: Boolean = false,
    val usingKotlinFallback: Boolean = true,
    val lastError: String? = null,
    val sampleRate: Int = 0,
    val eqEnabled: Boolean = false,
    val activeBands: Int = 0,
    val clarityActive: Boolean = false,
    val clarityNeedsNative: Boolean = false
)

@OptIn(UnstableApi::class)
@Singleton
class MasterAudioDspProcessor @Inject constructor() : BaseAudioProcessor() {

    /**
     * If `true`, this instance is a secondary/crossfade deck clone created via [createDeckProcessor].
     * Secondary decks run their own isolated 64-bit Kotlin 15-Band EQ + Studio Master Clarity + Preamp
     * biquad state so two overlapping Crossfade ExoPlayers NEVER interleave buffers into the single
     * C++ static `gDsp` instance (which previously caused filter state corruption or forced raw
     * unprocessed audio on the secondary player!).
     */
    private var isSecondaryDeckInstance: Boolean = false

    private val childDeckProcessors = CopyOnWriteArrayList<MasterAudioDspProcessor>()

    /**
     * Creates an isolated per-player [MasterAudioDspProcessor] with identical EQ, AutoEQ, Preamp,
     * Studio Master Clarity, and LUFS loudness settings, kept synced in real time with this singleton.
     */
    fun createDeckProcessor(): MasterAudioDspProcessor {
        val child = MasterAudioDspProcessor().also {
            it.isSecondaryDeckInstance = true
            it.syncFromParent(this)
        }
        childDeckProcessors.add(child)
        return child
    }

    fun releaseDeckProcessor(deckProcessor: MasterAudioDspProcessor?) {
        if (deckProcessor != null) {
            childDeckProcessors.remove(deckProcessor)
        }
    }

    private fun syncFromParent(parent: MasterAudioDspProcessor) {
        this.currentState = parent.currentState
        this.currentTrackLufs = parent.currentTrackLufs
        this.isSpatialStream = parent.isSpatialStream
        this.audioLabEqEnabled = parent.audioLabEqEnabled
        this.audioLabGains15 = parent.audioLabGains15?.clone()
        this.audioLabPreampDb = parent.audioLabPreampDb
        this.audioLabAutoPreamp = parent.audioLabAutoPreamp
        System.arraycopy(parent.autoEqBands10, 0, this.autoEqBands10, 0, this.autoEqBands10.size)
        val sr = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate else parent.lastSampleRate
        pushConfig(sr)
    }

    private fun propagateToChildren() {
        for (child in childDeckProcessors) {
            child.syncFromParent(this)
        }
    }

    companion object {
        @Volatile private var libLoaded = false
        @Volatile private var libError: String? = null

        init {
            try {
                System.loadLibrary("lastwave_audio_engine")
                libLoaded = true
            } catch (t: Throwable) {
                libLoaded = false
                if (com.myplayer.app.BuildConfig.DEBUG) {
                    libError = "${t.javaClass.simpleName}: ${t.message}"
                }
            }
        }

        val BAND_FREQUENCIES = floatArrayOf(
            25f, 40f, 63f, 100f, 160f, 250f, 400f, 630f,
            1000f, 1600f, 2500f, 4000f, 6300f, 10000f, 16000f
        )

        val BAND_10_FREQUENCIES = floatArrayOf(
            31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
        )

        val BAND_LABELS = listOf(
            "25\nSUB", "40\nSUB", "63\nBASS", "100\nBASS", "160\nLOW-MID",
            "250\nLOW-MID", "400\nMID", "630\nMID", "1k\nUPPER-MID", "1.6k\nPRESENCE",
            "2.5k\nPRESENCE", "4k\nCLARITY", "6.3k\nTREBLE", "10k\nAIR", "16k\nSPARKLE"
        )

        private const val EQ_Q = 1.41421356
        private const val SOFT_KNEE = 0.841395141
        private const val CEILING = 0.944060876

        /**
         * Log-frequency interpolation from 10-Band Rainbow EQ (31 Hz..16 kHz)
         * onto the 15 ISO 2/3-octave center frequencies (25 Hz..16 kHz).
         */
        fun map10BandsTo15Bands(gains10: FloatArray): FloatArray {
            if (gains10.size < 10) return FloatArray(15)
            val out15 = FloatArray(15)
            val log10Freqs = FloatArray(10) { ln(BAND_10_FREQUENCIES[it].toDouble()).toFloat() }
            for (i in 0 until 15) {
                val f = BAND_FREQUENCIES[i]
                val lf = ln(f.toDouble()).toFloat()
                when {
                    lf <= log10Freqs[0] -> out15[i] = gains10[0]
                    lf >= log10Freqs[9] -> out15[i] = gains10[9]
                    else -> {
                        var k = 0
                        while (k < 8 && log10Freqs[k + 1] < lf) {
                            k++
                        }
                        val t = ((lf - log10Freqs[k]) / (log10Freqs[k + 1] - log10Freqs[k])).coerceIn(0f, 1f)
                        out15[i] = gains10[k] + (gains10[k + 1] - gains10[k]) * t
                    }
                }
            }
            return out15
        }
    }

    private external fun nativeConfigure(
        sampleRate: Int,
        bitPerfect: Boolean,
        eqEnabled: Boolean,
        eqGains: FloatArray,
        studioClarity: Boolean,
        clarityPresetOrd: Int,
        loudnessGainDb: Float
    )

    private external fun nativeProcessPcmFloat(
        directBuffer: ByteBuffer,
        byteOffset: Int,
        frameCount: Int,
        channelCount: Int
    )

    private external fun nativeProcessPcm16(
        directBuffer: ByteBuffer,
        byteOffset: Int,
        frameCount: Int,
        channelCount: Int
    )

    @Volatile private var currentState = AudioPlaybackState()
    @Volatile private var currentTrackLufs = -14.0f
    @Volatile private var isSpatialStream = false
    @Volatile private var nativeReady = libLoaded
    @Volatile private var nativeError: String? = libError
    @Volatile private var lastSampleRate = 48000

    // Live 10-Band Rainbow Equalizer & Preamp override from MyPlayerAudioFxDspBridge
    @Volatile private var audioLabEqEnabled: Boolean = false
    @Volatile private var audioLabGains15: FloatArray? = null
    @Volatile private var audioLabPreampDb: Float = 0f
    @Volatile private var audioLabAutoPreamp: Boolean = true
    private val autoEqBands10 = FloatArray(10)

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in autoEqBands10.indices) return
        autoEqBands10[bandIndex] = gainDb.coerceIn(-12f, 12f)
        audioLabGains15 = map10BandsTo15Bands(autoEqBands10)
        val sr = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate else lastSampleRate
        pushConfig(sr)
        propagateToChildren()
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        audioLabEqEnabled = enabled
        val sr = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate else lastSampleRate
        pushConfig(sr)
        propagateToChildren()
    }

    private val eqLock = Any()
    private val kotlinEq = KotlinEq()

    private val _status = MutableStateFlow(
        DspStatus(nativeLoaded = libLoaded, usingKotlinFallback = !libLoaded, lastError = libError)
    )
    val status: StateFlow<DspStatus> = _status.asStateFlow()

    fun updateState(newState: AudioPlaybackState, trackLufs: Float = currentTrackLufs, isSpatial: Boolean = isSpatialStream) {
        this.currentState = newState
        this.currentTrackLufs = trackLufs
        this.isSpatialStream = isSpatial
        val sr = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate else lastSampleRate
        pushConfig(sr)
        propagateToChildren()
    }

    /**
     * Called live from MyPlayerAudioFxDspBridge when the user toggles or drags sliders in
     * the 10-Band Rainbow Equalizer, Preamp, or AutoEQ headphone profiles.
     */
    fun updateFromAudioLab10Band(
        enabled: Boolean,
        gains10: FloatArray,
        preampDb: Float,
        autoPreamp: Boolean
    ) {
        this.audioLabEqEnabled = enabled
        this.audioLabGains15 = map10BandsTo15Bands(gains10)
        this.audioLabPreampDb = preampDb.coerceIn(-12f, 12f)
        this.audioLabAutoPreamp = autoPreamp
        val sr = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate else lastSampleRate
        pushConfig(sr)
        propagateToChildren()
    }

    fun setTrackFormat(format: androidx.media3.common.Format?) {
        val mime = format?.sampleMimeType?.lowercase().orEmpty()
        val isSpatial = mime.contains("eac3") || mime.contains("ec-3") ||
            mime.contains("ac-3") || mime.contains("ac3") || mime.contains("ac4") ||
            mime.contains("mha1") || mime.contains("mhm1")
        val tags = com.myplayer.app.playback.LoudnessNormalizer.parseFromFormat(format)
        val trackGain = tags.trackGainDb ?: if (isSpatial) 6.0f else 0.0f
        val trackLufs = -14.0f - trackGain
        this.currentTrackLufs = trackLufs
        this.isSpatialStream = isSpatial
        val sr = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate else lastSampleRate
        pushConfig(sr)
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val isSupportedEncoding = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT ||
                inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        if (!isSupportedEncoding || inputAudioFormat.channelCount !in 1..2) {
            return AudioFormat.NOT_SET
        }
        pushConfig(inputAudioFormat.sampleRate)
        return inputAudioFormat
    }

    private fun pushConfig(sampleRate: Int) {
        val sr = if (sampleRate > 0) sampleRate else 48000
        lastSampleRate = sr
        val st = currentState

        // Combine 15-band ISO EQ (AudioPlaybackStore) and 10-band Rainbow EQ (AudioLab)
        val storeEqActive = st.eqEnabled && st.eqBandGains.any { abs(it) >= 0.01f }
        val labGains = audioLabGains15
        val labEqActive = audioLabEqEnabled && labGains != null && labGains.any { abs(it) >= 0.01f }
        val effectiveEqEnabled = st.eqEnabled || audioLabEqEnabled

        val gains = FloatArray(15) { i ->
            val g15 = if (st.eqEnabled) st.eqBandGains.getOrElse(i) { 0f } else 0f
            val g10 = if (audioLabEqEnabled && labGains != null) labGains.getOrElse(i) { 0f } else 0f
            when {
                storeEqActive && labEqActive -> (g15 + g10).coerceIn(-12f, 12f)
                labEqActive -> g10.coerceIn(-12f, 12f)
                else -> g15.coerceIn(-12f, 12f)
            }
        }

        val isStudioPreset = st.eqPreset == Eq15Preset.STUDIO_MASTER
        val effectiveClarity = (st.studioMasterClarity || isStudioPreset) &&
                !(st.claritySpatialBypass && isSpatialStream)

        val loudnessDb = when (st.loudnessMode) {
            LoudnessMode.OFF -> 0f
            LoudnessMode.TRACK -> (-14.0f - currentTrackLufs).coerceIn(-6f, 6f)
            LoudnessMode.ALBUM -> (-14.0f - (currentTrackLufs - 1.0f)).coerceIn(-6f, 5f)
        }

        val userPreampOffsetDb = if (audioLabEqEnabled && !audioLabAutoPreamp) audioLabPreampDb else 0f
        val totalLoudnessAndPreampDb = (loudnessDb + userPreampOffsetDb).coerceIn(-12f, 12f)
        val bitPerfect = st.bitPerfectMode || st.usbExclusiveOutput

        // If Studio Master Clarity is active on a Kotlin-processed deck, fold the exact
        // Studio Master sub-warmth (+1.8 dB @ 63Hz) and air-presence (+2.4 dB @ 10kHz..16kHz)
        // curves into the 64-bit Kotlin biquad cascade so both native and secondary decks match 1:1!
        val kotlinGains = gains.clone()
        if (effectiveClarity) {
            val clarityBoostScale = when (st.clarityOutputPreset.ordinal) {
                1 -> 1.15f
                2 -> 1.3f
                else -> 1.0f
            }
            kotlinGains[1] = (kotlinGains[1] + 1.2f * clarityBoostScale).coerceIn(-12f, 12f)
            kotlinGains[2] = (kotlinGains[2] + 1.6f * clarityBoostScale).coerceIn(-12f, 12f)
            kotlinGains[10] = (kotlinGains[10] + 1.4f * clarityBoostScale).coerceIn(-12f, 12f)
            kotlinGains[11] = (kotlinGains[11] + 2.0f * clarityBoostScale).coerceIn(-12f, 12f)
            kotlinGains[12] = (kotlinGains[12] + 2.4f * clarityBoostScale).coerceIn(-12f, 12f)
            kotlinGains[13] = (kotlinGains[13] + 2.6f * clarityBoostScale).coerceIn(-12f, 12f)
            kotlinGains[14] = (kotlinGains[14] + 2.2f * clarityBoostScale).coerceIn(-12f, 12f)
        }
        val kotlinActive = effectiveEqEnabled || effectiveClarity

        val maxBoost = if (kotlinActive) (kotlinGains.maxOrNull() ?: 0f).coerceAtLeast(0f) else 0f
        val autoHeadroomDb = if (kotlinActive && audioLabAutoPreamp) {
            -maxOf(0f, maxBoost - 1.0f)
        } else {
            0f
        }
        val kotlinPreampDb = totalLoudnessAndPreampDb + autoHeadroomDb + if (kotlinActive) 1.0f else 0.0f
        synchronized(eqLock) { kotlinEq.configure(sr, kotlinGains, kotlinActive, kotlinPreampDb) }

        if (!isSecondaryDeckInstance && nativeReady) {
            try {
                nativeConfigure(
                    sampleRate = sr,
                    bitPerfect = bitPerfect,
                    eqEnabled = effectiveEqEnabled,
                    eqGains = gains,
                    studioClarity = effectiveClarity,
                    clarityPresetOrd = st.clarityOutputPreset.ordinal,
                    loudnessGainDb = totalLoudnessAndPreampDb
                )
            } catch (t: Throwable) {
                nativeReady = false
                if (com.myplayer.app.BuildConfig.DEBUG) {
                    nativeError = "${t.javaClass.simpleName}: ${t.message}"
                }
            }
        }

        _status.value = DspStatus(
            nativeLoaded = libLoaded,
            usingKotlinFallback = !nativeReady || isSecondaryDeckInstance,
            lastError = nativeError,
            sampleRate = sr,
            eqEnabled = effectiveEqEnabled,
            activeBands = if (effectiveEqEnabled) gains.count { abs(it) >= 0.005f } else 0,
            clarityActive = effectiveClarity,
            clarityNeedsNative = false
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outputBuffer = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        val st = currentState

        if (st.bitPerfectMode || st.usbExclusiveOutput) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val positionBefore = outputBuffer.position()
        outputBuffer.put(inputBuffer)

        val channelCount = inputAudioFormat.channelCount
        val frameCount = remaining / inputAudioFormat.bytesPerFrame
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT

        var processed = false
        // Only the primary singleton uses the single static C++ gDsp instance when no secondary
        // crossfade deck is concurrently active; secondary crossfade decks use their own isolated
        // 64-bit KotlinEq instance with identical coefficients so two streams never corrupt gDsp's delay lines!
        if (!isSecondaryDeckInstance && nativeReady && childDeckProcessors.isEmpty()) {
            try {
                if (isFloat) {
                    nativeProcessPcmFloat(outputBuffer, positionBefore, frameCount, channelCount)
                } else {
                    nativeProcessPcm16(outputBuffer, positionBefore, frameCount, channelCount)
                }
                processed = true
            } catch (t: Throwable) {
                nativeReady = false
                if (com.myplayer.app.BuildConfig.DEBUG) {
                    nativeError = "${t.javaClass.simpleName}: ${t.message}"
                }
                _status.value = _status.value.copy(
                    usingKotlinFallback = true,
                    lastError = nativeError,
                    clarityNeedsNative = false
                )
            }
        }
        if (!processed) {
            synchronized(eqLock) {
                kotlinEq.process(outputBuffer, positionBefore, frameCount, channelCount, isFloat)
            }
        }

        outputBuffer.position(positionBefore + remaining)
        outputBuffer.flip()
    }

    /** 15-band peaking EQ (Q = sqrt(2)) + Studio Clarity + preamp + soft limiter. Same 64-bit maths as the native engine. */
    private class KotlinEq {
        private val n = 15
        private val b0 = DoubleArray(n) { 1.0 }
        private val b1 = DoubleArray(n)
        private val b2 = DoubleArray(n)
        private val a1 = DoubleArray(n)
        private val a2 = DoubleArray(n)
        private val z1 = Array(2) { DoubleArray(n) }
        private val z2 = Array(2) { DoubleArray(n) }
        private var activeIdx = IntArray(0)
        private var preamp = 1.0
        private var sampleRate = 48000.0

        fun configure(sr: Int, gainsDb: FloatArray, eqEnabled: Boolean, preampDb: Float) {
            if (sr.toDouble() != sampleRate) {
                for (c in 0..1) { z1[c].fill(0.0); z2[c].fill(0.0) }
            }
            sampleRate = sr.toDouble()
            preamp = 10.0.pow(preampDb / 20.0)

            val previouslyActive = BooleanArray(n)
            for (i in activeIdx) previouslyActive[i] = true

            val active = ArrayList<Int>(n)
            for (i in 0 until n) {
                val g = if (eqEnabled) gainsDb[i].toDouble() else 0.0
                val f = BAND_FREQUENCIES[i].toDouble()
                if (abs(g) < 0.005 || f >= sampleRate * 0.45) continue

                val omega = 2.0 * PI * f / sampleRate
                val cosw = cos(omega)
                val alpha = sin(omega) / (2.0 * EQ_Q)
                val amp = 10.0.pow(g / 40.0)
                val a0 = 1.0 + alpha / amp
                b0[i] = (1.0 + alpha * amp) / a0
                b1[i] = (-2.0 * cosw) / a0
                b2[i] = (1.0 - alpha * amp) / a0
                a1[i] = (-2.0 * cosw) / a0
                a2[i] = (1.0 - alpha / amp) / a0
                if (!previouslyActive[i]) {
                    z1[0][i] = 0.0; z2[0][i] = 0.0
                    z1[1][i] = 0.0; z2[1][i] = 0.0
                }
                active.add(i)
            }
            activeIdx = active.toIntArray()
        }

        fun process(buf: ByteBuffer, byteOffset: Int, frames: Int, channels: Int, isFloat: Boolean) {
            val idxs = activeIdx
            if (idxs.isEmpty() && abs(preamp - 1.0) < 0.0005) return

            val bytesPer = if (isFloat) 4 else 2
            val ch = if (channels >= 2) 2 else 1
            var p = byteOffset
            for (f in 0 until frames) {
                for (c in 0 until ch) {
                    val at = p + c * bytesPer
                    var x = if (isFloat) buf.getFloat(at).toDouble() else buf.getShort(at) / 32768.0
                    x *= preamp
                    val zc1 = z1[c]
                    val zc2 = z2[c]
                    for (k in idxs.indices) {
                        val b = idxs[k]
                        val out = b0[b] * x + zc1[b]
                        var n1 = b1[b] * x - a1[b] * out + zc2[b]
                        var n2 = b2[b] * x - a2[b] * out
                        if (abs(n1) < 1.0e-18) n1 = 0.0
                        if (abs(n2) < 1.0e-18) n2 = 0.0
                        zc1[b] = n1
                        zc2[b] = n2
                        x = out
                    }
                    x = softLimit(x)
                    if (isFloat) {
                        buf.putFloat(at, x.toFloat())
                    } else {
                        buf.putShort(at, (x * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort())
                    }
                }
                p += channels * bytesPer
            }
        }

        private fun softLimit(x: Double): Double {
            val a = abs(x)
            if (a <= SOFT_KNEE) return x
            val range = CEILING - SOFT_KNEE
            val s = SOFT_KNEE + range * tanh((a - SOFT_KNEE) / range)
            return if (x >= 0.0) s else -s
        }
    }
}