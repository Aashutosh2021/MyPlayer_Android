package com.myplayer.app.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Which side of a MyPlayer Crossmix / Crossfade transition this processor is rendering.
 */
enum class MyPlayerCrossmixRole { Outgoing, Incoming }

/**
 * MyPlayer transition styles ported from MyPlayer (`CrossmixStyles.kt`):
 * - [SilkBlend]: Smooth equal-power blend with gentle bass hand-off and warm high-cut on outgoing tail.
 * - [BassSwap]: Keeps both faders strong until the bass swap point, then cleanly hands over low-end kick/bass.
 * - [FilterSweep]: Resonant low-pass / high-pass filter ride that prevents vocal & key clashes.
 * - [Dampen]: Apple Music-style spatial room recession on the outgoing track while incoming enters clear in front.
 * - [EqualPower]: Pure constant-power cosine/sine curve (`cos^2 + sin^2 = 1.0`) with zero tone coloration.
 */
enum class MyPlayerCrossmixStyle(val label: String) {
    SilkBlend("Silk Blend"),
    BassSwap("Bass Swap"),
    FilterSweep("Filter Sweep"),
    Dampen("Apple Dampen"),
    EqualPower("Pure Equal-Power")
}

/**
 * Per-block DSP parameters for [MyPlayerCrossmixAudioProcessor], evaluated from normalized progress `0f..1f`
 * and interpolated sample-by-sample across 32-frame blocks so nothing ever steps, clicks, or drops quality.
 */
class MyPlayerFxParams {
    var gain = 1f
    var lowCutHz = 0f
    var highCutHz = OpenHz
    var resonance = 0.707f
    var lowBand = 1f
    var midBand = 1f
    var highBand = 1f
    var bassCut = 0f

    fun reset() {
        gain = 1f
        lowCutHz = 0f
        highCutHz = OpenHz
        resonance = 0.707f
        lowBand = 1f
        midBand = 1f
        highBand = 1f
        bassCut = 0f
    }

    companion object {
        const val OpenHz = 20_000f
    }
}

/**
 * Ported from MyPlayer's `CrossmixTransitionAudioProcessor.kt` & `CrossmixStyles.kt`.
 *
 * Runs inside each ExoPlayer's AudioSink processor chain (one instance per player deck).
 * - When idle (`active == false`), copies input buffer straight through with zero CPU or tone change.
 * - During a transition, smoothly interpolates gain, equal-power headroom (`out^2 + in^2 <= 1.0`),
 *   4th-order Butterworth 200Hz bass hand-off, and state-variable high/low-pass filters every 32 frames,
 *   gliding sample-by-sample so there is never a click, pop, or sudden quality shift.
 */
@UnstableApi
@Suppress("DEPRECATION")
class MyPlayerCrossmixAudioProcessor : AudioProcessor {

    @Volatile var role: MyPlayerCrossmixRole = MyPlayerCrossmixRole.Outgoing
        private set
    @Volatile var style: MyPlayerCrossmixStyle = MyPlayerCrossmixStyle.SilkBlend
    @Volatile var progress: Float = 0f
    @Volatile var active: Boolean = false
        private set
    @Volatile var finishing: Boolean = false
        private set
    @Volatile var fadingOut: Boolean = false
        private set
    @Volatile var silenced: Boolean = false
        private set

    @Volatile private var pendingTimestampUs = Long.MIN_VALUE
    @Volatile private var streamOffsetUs = 0L
    @Volatile private var supported = true

    private var sampleRate = 0
    private var channels = 0
    private var outputBuffer: ByteBuffer = EmptyBuffer
    private var inputEnded = false

    private val params = MyPlayerFxParams()
    private val otherParams = MyPlayerFxParams()
    private val dsp = Dsp()

    val configured: Boolean get() = sampleRate > 0 || !supported
    val canRender: Boolean get() = supported && sampleRate > 0

    fun onInputTimestamp(presentationTimeUs: Long) {
        pendingTimestampUs = presentationTimeUs
    }

    fun onStreamOffset(offsetUs: Long) {
        streamOffsetUs = offsetUs
    }

    /**
     * Arms this player's processor for a crossfade transition before or at transition start.
     * For [MyPlayerCrossmixRole.Incoming], arming at `progress = 0f` before `prepare()` guarantees
     * the very first decoded audio frames start at `gain = 0f` (never a loud blip at start).
     */
    fun arm(
        role: MyPlayerCrossmixRole,
        style: MyPlayerCrossmixStyle = this.style,
        initialProgress: Float = 0f
    ) {
        if (sampleRate > 0) dsp.ensure(sampleRate)
        this.role = role
        this.style = style
        this.progress = initialProgress.coerceIn(0f, 1f)
        this.finishing = false
        this.fadingOut = false
        this.silenced = false
        dsp.prepareForArm(role, style, this.progress)
        this.active = true
    }

    fun updateProgress(newProgress: Float) {
        this.progress = newProgress.coerceIn(0f, 1f)
    }

    /**
     * Smoothly glides the incoming track out of transition filtering over ~50ms back to bit-exact dry PCM.
     */
    fun finish() {
        if (!active) return
        finishing = true
    }

    /**
     * Quickly fades the outgoing track to silence over ~60ms (used when user manually skips or seeks mid-fade).
     */
    fun fadeOutNow() {
        if (!active) {
            arm(MyPlayerCrossmixRole.Outgoing, style, progress)
        }
        fadingOut = true
    }

    fun clear() {
        active = false
        finishing = false
        fadingOut = false
        silenced = false
        progress = 0f
        dsp.clearState()
    }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount in 1..2
        if (!supported) {
            sampleRate = 0
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        dsp.ensure(sampleRate)
        return inputAudioFormat
    }

    override fun isActive(): Boolean = supported

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val frames = size / (channels * 2)

        if (!active || sampleRate <= 0) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        if (silenced) {
            for (i in 0 until frames * channels) output.putShort(0)
            inputBuffer.position(inputBuffer.limit())
            output.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        dsp.render(inputBuffer, output, frames, channels)
        output.flip()
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer = outputBuffer.also { outputBuffer = EmptyBuffer }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === EmptyBuffer

    @Deprecated("Deprecated in Java")
    override fun flush() {
        outputBuffer = EmptyBuffer
        inputEnded = false
        dsp.clearFilterStatesOnly()
    }

    @Deprecated("Deprecated in Java")
    override fun reset() {
        flush()
        clear()
        sampleRate = 0
        channels = 0
    }

    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        return outputBuffer
    }

    private inner class Dsp {
        private var rate = 0
        private val highPassL = Svf(); private val highPassR = Svf()
        private val lowPassL = Svf(); private val lowPassR = Svf()
        private val bandLowL = Svf(); private val bandLowR = Svf()
        private val bandHighL = Svf(); private val bandHighR = Svf()
        private val bassCutL1 = Svf(); private val bassCutL2 = Svf()
        private val bassCutR1 = Svf(); private val bassCutR2 = Svf()

        private var bassCutOn = false
        private var bassCutMix = 0f
        private var finishMix = 0f
        private var outFade = 1f

        private var gain = 1f
        private var lowBand = 1f
        private var midBand = 1f
        private var highBand = 1f
        private var started = false

        fun ensure(sampleRate: Int) {
            if (rate == sampleRate) return
            rate = sampleRate
            clearFilterStatesOnly()
        }

        fun prepareForArm(role: MyPlayerCrossmixRole, style: MyPlayerCrossmixStyle, initialProgress: Float) {
            clearFilterStatesOnly()
            finishMix = 0f
            outFade = 1f
            evaluateStyle(style, role, initialProgress, params)
            val otherRole = if (role == MyPlayerCrossmixRole.Outgoing) MyPlayerCrossmixRole.Incoming else MyPlayerCrossmixRole.Outgoing
            evaluateStyle(style, otherRole, initialProgress, otherParams)
            params.gain *= headroom(params.gain, otherParams.gain)
            gain = params.gain
            lowBand = params.lowBand
            midBand = params.midBand
            highBand = params.highBand
            started = true
        }

        fun clearFilterStatesOnly() {
            for (f in arrayOf(highPassL, highPassR, lowPassL, lowPassR, bandLowL, bandLowR, bandHighL, bandHighR)) f.clear()
            for (f in arrayOf(bassCutL1, bassCutL2, bassCutR1, bassCutR2)) f.clear()
            bassCutOn = false
            bassCutMix = 0f
        }

        fun clearState() {
            clearFilterStatesOnly()
            finishMix = 0f
            outFade = 1f
            gain = 1f
            lowBand = 1f
            midBand = 1f
            highBand = 1f
            started = false
        }

        fun render(input: ByteBuffer, output: ByteBuffer, frames: Int, channelCount: Int) {
            val sr = rate.toFloat().coerceAtLeast(44_100f)
            var processed = 0
            val currentRole = this@MyPlayerCrossmixAudioProcessor.role
            val currentStyle = this@MyPlayerCrossmixAudioProcessor.style
            val p = this@MyPlayerCrossmixAudioProcessor.progress.coerceIn(0f, 1f)

            while (processed < frames) {
                val block = min(BlockFrames, frames - processed)
                evaluateStyle(currentStyle, currentRole, p, params)

                if (!started) {
                    gain = params.gain
                    lowBand = params.lowBand
                    midBand = params.midBand
                    highBand = params.highBand
                    started = true
                }

                renderBlock(input, output, block, channelCount, sr, currentRole)
                processed += block
            }

            if (currentRole == MyPlayerCrossmixRole.Outgoing && (p >= 0.999f || (fadingOut && outFade <= 0.0005f))) {
                silenced = true
            }
            if (currentRole == MyPlayerCrossmixRole.Incoming && ((finishing && finishMix >= 0.999f) || p >= 0.999f)) {
                if (finishing && finishMix >= 0.999f) {
                    this@MyPlayerCrossmixAudioProcessor.active = false
                    this@MyPlayerCrossmixAudioProcessor.finishing = false
                } else if (!finishing && p >= 0.999f) {
                    this@MyPlayerCrossmixAudioProcessor.finishing = true
                }
            }
        }

        private fun renderBlock(
            input: ByteBuffer,
            output: ByteBuffer,
            block: Int,
            channelCount: Int,
            sr: Float,
            currentRole: MyPlayerCrossmixRole
        ) {
            val inv = 1f / block
            val gainT = params.gain
            val lowT = params.lowBand
            val midT = params.midBand
            val highT = params.highBand

            val hpOn = params.lowCutHz > 21f
            val lpOn = params.highCutHz < 19_000f
            if (hpOn) {
                highPassL.set(params.lowCutHz, params.resonance, sr)
                highPassR.copyCoefficients(highPassL)
            }
            if (lpOn) {
                lowPassL.set(params.highCutHz, params.resonance, sr)
                lowPassR.copyCoefficients(lowPassL)
            }

            val isolate = lowT != 1f || midT != 1f || highT != 1f || lowBand != 1f || midBand != 1f || highBand != 1f
            if (isolate) {
                bandLowL.set(220f, 0.6f, sr); bandLowR.copyCoefficients(bandLowL)
                bandHighL.set(2_600f, 0.6f, sr); bandHighR.copyCoefficients(bandHighL)
            }

            val bassCutWanted = params.bassCut > 0.001f
            if (bassCutWanted) bassCutOn = true
            val bassCutStep = 1f / (0.03f * sr)
            if (bassCutOn) {
                val hz = BassCutFromHz * (BassCutToHz / BassCutFromHz).pow(params.bassCut.coerceIn(0f, 1f))
                bassCutL1.set(hz, ButterworthQ1, sr); bassCutR1.copyCoefficients(bassCutL1)
                bassCutL2.set(hz, ButterworthQ2, sr); bassCutR2.copyCoefficients(bassCutL2)
            }

            for (i in 0 until block) {
                var l = input.getShort() / 32_768f
                var r = if (channelCount == 2) input.getShort() / 32_768f else l
                val plainL = l
                val plainR = r

                gain += (gainT - gain) * inv * 4f
                lowBand += (lowT - lowBand) * inv * 4f
                midBand += (midT - midBand) * inv * 4f
                highBand += (highT - highBand) * inv * 4f

                if (isolate) {
                    val lowL = bandLowL.lowPass(l); val lowR = bandLowR.lowPass(r)
                    val highL = bandHighL.highPass(l); val highR = bandHighR.highPass(r)
                    l = lowL * lowBand + (l - lowL - highL) * midBand + highL * highBand
                    r = lowR * lowBand + (r - lowR - highR) * midBand + highR * highBand
                }
                if (hpOn) {
                    l = highPassL.highPass(l)
                    r = highPassR.highPass(r)
                }
                if (lpOn) {
                    l = lowPassL.lowPass(l)
                    r = lowPassR.lowPass(r)
                }
                if (bassCutOn) {
                    bassCutMix = if (bassCutWanted) min(1f, bassCutMix + bassCutStep) else max(0f, bassCutMix - bassCutStep)
                    val cutL = bassCutL2.highPass(bassCutL1.highPass(l))
                    val cutR = bassCutR2.highPass(bassCutR1.highPass(r))
                    l += (cutL - l) * bassCutMix
                    r += (cutR - r) * bassCutMix
                }

                var mixL = l * gain
                var mixR = r * gain

                if (finishing && currentRole == MyPlayerCrossmixRole.Incoming) {
                    finishMix = min(1f, finishMix + 1f / (0.05f * sr))
                    mixL += (plainL - mixL) * finishMix
                    mixR += (plainR - mixR) * finishMix
                }
                if (fadingOut) {
                    outFade = max(0f, outFade - 1f / (0.06f * sr))
                    mixL *= outFade
                    mixR *= outFade
                }

                mixL = limit(mixL)
                mixR = limit(mixR)
                if (mixL.isNaN() || mixR.isNaN()) {
                    mixL = 0f
                    mixR = 0f
                    clearFilterStatesOnly()
                }
                output.putShort((mixL * 32_767f).roundToInt().coerceIn(-32_768, 32_767).toShort())
                if (channelCount == 2) {
                    output.putShort((mixR * 32_767f).roundToInt().coerceIn(-32_768, 32_767).toShort())
                }
            }

            if (bassCutOn && !bassCutWanted && bassCutMix <= 0f) {
                bassCutOn = false
                for (f in arrayOf(bassCutL1, bassCutL2, bassCutR1, bassCutR2)) f.clear()
            }
        }

        private fun limit(x: Float): Float {
            val a = abs(x)
            if (a <= Knee) return x
            val over = (a - Knee) / (1f - Knee)
            val shaped = Knee + (1f - Knee) * (over / (1f + over))
            return if (x < 0f) -shaped else shaped
        }
    }

    /**
     * Topology-preserving state-variable filter from MyPlayer (`CrossmixTransitionAudioProcessor.Svf`).
     */
    private class Svf {
        private var g = 0f; private var k = 1.414f; private var a1 = 0f; private var a2 = 0f; private var a3 = 0f
        private var ic1 = 0f; private var ic2 = 0f
        private var low = 0f; private var band = 0f; private var high = 0f

        fun set(cutoffHz: Float, q: Float, sampleRate: Float) {
            val fc = cutoffHz.coerceIn(10f, sampleRate * 0.45f)
            g = tan(PI.toFloat() * fc / sampleRate)
            k = 1f / q.coerceIn(0.3f, 8f)
            a1 = 1f / (1f + g * (g + k))
            a2 = g * a1
            a3 = g * a2
        }

        fun copyCoefficients(other: Svf) {
            g = other.g; k = other.k; a1 = other.a1; a2 = other.a2; a3 = other.a3
        }

        private fun tick(x: Float) {
            val v3 = x - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2f * v1 - ic1 + Denormal
            ic2 = 2f * v2 - ic2 + Denormal
            low = v2
            band = v1
            high = x - k * v1 - v2
        }

        fun lowPass(x: Float): Float { tick(x); return low }
        fun highPass(x: Float): Float { tick(x); return high }
        fun clear() { ic1 = 0f; ic2 = 0f; low = 0f; band = 0f; high = 0f }
    }

    companion object {
        private val EmptyBuffer: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
        private const val BlockFrames = 32
        private const val Knee = 0.89f
        private const val BassCutFromHz = 20f
        private const val BassCutToHz = 200f
        private const val ButterworthQ1 = 0.5412f
        private const val ButterworthQ2 = 1.3066f
        private const val Denormal = 1e-20f

        private fun ramp(x: Float, a: Float, b: Float): Float =
            if (b <= a) (if (x >= b) 1f else 0f) else ((x - a) / (b - a)).coerceIn(0f, 1f)

        private fun smooth(x: Float): Float {
            val c = x.coerceIn(0f, 1f)
            return c * c * (3f - 2f * c)
        }

        private fun fadeOut(x: Float): Float = cos(x.coerceIn(0f, 1f) * PI.toFloat() / 2f)
        private fun fadeIn(x: Float): Float = sin(x.coerceIn(0f, 1f) * PI.toFloat() / 2f)

        private fun sweep(t: Float, from: Float, to: Float): Float {
            if (t <= 0f) return from
            if (t >= 1f) return to
            val a = from.coerceAtLeast(20f)
            val b = to.coerceAtLeast(20f)
            val value = a * (b / a).pow(t)
            return if (to <= 0f && t >= 0.98f) 0f else value
        }

        private fun entryHighPass(p: Float, topHz: Float, openBy: Float): Float {
            val remaining = 1f - ramp(p, 0f, openBy)
            if (remaining <= 0f) return 0f
            return 20f * (topHz / 20f).pow(remaining.pow(0.35f))
        }

        internal fun headroom(own: Float, other: Float): Float {
            val power = own * own + other * other
            return if (power > 1f) 1f / sqrt(power) else 1f
        }

        /**
         * Evaluates MyPlayer exact DJ transition curves (`SilkBlend`, `BassSwap`, `FilterSweep`,
         * `Dampen`, or `EqualPower`) for normalized transition progress [p] in `0f..1f`.
         * Note: keep tone-shaping subtle on default SilkBlend/EqualPower so perceived audio quality
         * stays 100% consistent with normal playback while preventing double-bass mud.
         */
        internal fun evaluateStyle(
            style: MyPlayerCrossmixStyle,
            role: MyPlayerCrossmixRole,
            progress: Float,
            out: MyPlayerFxParams
        ) {
            out.reset()
            val p = progress.coerceIn(0f, 1f)
            val outgoing = role == MyPlayerCrossmixRole.Outgoing
            val swap = 0.55f

            when (style) {
                MyPlayerCrossmixStyle.SilkBlend -> {
                    out.gain = 1f
                    if (outgoing) {
                        out.highCutHz = sweep(ramp(p, 0.65f, 1f).pow(0.9f), MyPlayerFxParams.OpenHz, 4_200f)
                    } else {
                        out.lowCutHz = entryHighPass(p, 260f, 0.45f)
                    }
                    val handed = ramp(p, swap - 0.12f, swap + 0.08f)
                    out.bassCut = if (outgoing) handed * 0.65f else (1f - handed) * 0.55f
                }

                MyPlayerCrossmixStyle.BassSwap -> {
                    out.gain = 1f
                    if (outgoing) {
                        out.highCutHz = sweep(ramp(p, 0.5f, 1f), MyPlayerFxParams.OpenHz, 3_200f)
                    } else {
                        out.lowCutHz = entryHighPass(p, 420f, 0.55f)
                    }
                    val handed = ramp(p, swap - 0.06f, swap)
                    out.bassCut = if (outgoing) handed else 1f - handed
                }

                MyPlayerCrossmixStyle.FilterSweep -> {
                    out.gain = 1f
                    if (outgoing) {
                        out.highCutHz = sweep(p.pow(0.75f), MyPlayerFxParams.OpenHz, 650f)
                        out.resonance = 0.707f + 0.12f * sin(PI.toFloat() * p)
                    } else {
                        out.lowCutHz = entryHighPass(p, 950f, 0.55f)
                    }
                    val handed = ramp(p, 0.45f, 0.55f)
                    out.bassCut = if (outgoing) handed else 1f - handed
                }

                MyPlayerCrossmixStyle.Dampen -> {
                    if (outgoing) {
                        val d = 0.75f * smooth(ramp(p, 0.15f, 0.75f))
                        out.gain = 1f - 0.18f * d
                        out.highCutHz = sweep(d.pow(0.85f), MyPlayerFxParams.OpenHz, 1_600f)
                        out.midBand = 1f - 0.3f * d
                    } else {
                        out.gain = 1f
                        out.lowCutHz = entryHighPass(p, 380f, 0.4f)
                    }
                }

                MyPlayerCrossmixStyle.EqualPower -> {
                    out.gain = 1f
                }
            }
        }
    }
}
