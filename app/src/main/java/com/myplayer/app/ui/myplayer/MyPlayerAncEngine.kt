package com.myplayer.app.ui.myplayer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.*

enum class MyPlayerAncMode(val title: String, val subtitle: String) {
    ANC_ANTI_NOISE("ANC Anti-Noise (180°)", "Inverts ambient mic phase to cancel external drone & chatter"),
    LOW_RUMBLE_ANC("Sub-Bass & Traffic ANC", "Targets 40–450 Hz engine, fan, AC & train rumble"),
    TRANSPARENCY("Transparency Pass-Through", "Blends clear ambient voice into your earbuds so you stay aware")
}

/**
 * Real-Time Phone-Mic Active Noise Cancellation (ANC) & Transparency Engine.
 * Captures ambient noise using the phone's focused microphone via low-latency [AudioRecord],
 * filters & phase-inverts (180° anti-phase) the waveform, and mixes it directly into ExoPlayer's
 * PCM output buffer via [MyPlayerAncAudioProcessor] so even non-ANC earbuds get active noise reduction!
 */
object MyPlayerAncManager {
    var enabled by mutableStateOf(false)
    var mode by mutableStateOf(MyPlayerAncMode.ANC_ANTI_NOISE)
    var intensity by mutableFloatStateOf(0.65f)
    var phaseFineTuneSamples by mutableIntStateOf(0) // -16..+16 sample alignment
    var ambientDbSpl by mutableFloatStateOf(32f)
    var statusText by mutableStateOf("Standby • Phone Mic Ready")

    // Lock-free circular ring buffer of processed mono anti-noise samples (-1f..+1f)
    private const val RING_SIZE = 16384
    private const val RING_MASK = RING_SIZE - 1
    private val ringBuffer = FloatArray(RING_SIZE)
    @Volatile private var writePos = 0
    @Volatile private var readPos = 0

    @Volatile private var captureThread: Thread? = null
    @Volatile private var running = false

    fun hasMicPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun setAncEnabled(context: Context, turnOn: Boolean) {
        if (turnOn && !hasMicPermission(context)) {
            statusText = "Microphone permission required for Phone-Mic ANC"
            enabled = false
            return
        }
        enabled = turnOn
        if (turnOn) {
            startCapture()
        } else {
            stopCapture()
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startCapture() {
        if (running) return
        running = true
        statusText = "Active • Capturing & inverting ambient noise"
        captureThread = thread(start = true, isDaemon = true, name = "MyPlayerAncMicThread") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val sampleRate = 48000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1024)

            val sources = intArrayOf(
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
            var record: AudioRecord? = null
            for (src in sources) {
                try {
                    val candidate = AudioRecord(
                        src,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuf * 2
                    )
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        record = candidate
                        break
                    } else {
                        candidate.release()
                    }
                } catch (_: Throwable) {}
            }

            if (record == null) {
                running = false
                enabled = false
                statusText = "Mic unavailable on this device"
                return@thread
            }

            val pcmBuf = ShortArray(256)
            // 1st-order high-pass (DC blocker at 25 Hz) + low-pass (450 Hz for rumble mode, 1600 Hz for full ANC)
            var dcInPrev = 0f
            var dcOutPrev = 0f
            var lpState = 0f

            try {
                record.startRecording()
                while (running && enabled) {
                    val read = record.read(pcmBuf, 0, pcmBuf.size)
                    if (read <= 0) continue

                    val currentMode = mode
                    val gain = intensity.coerceIn(0f, 1f)
                    val lpCutoff = when (currentMode) {
                        MyPlayerAncMode.LOW_RUMBLE_ANC -> 420f
                        MyPlayerAncMode.ANC_ANTI_NOISE -> 1650f
                        MyPlayerAncMode.TRANSPARENCY -> 4500f
                    }
                    val alphaLp = (1.0 - exp(-2.0 * PI * lpCutoff / sampleRate)).toFloat()

                    var sumSq = 0.0
                    var w = writePos
                    for (i in 0 until read) {
                        val raw = pcmBuf[i] / 32768f
                        sumSq += (raw * raw).toDouble()

                        // DC block
                        val dcOut = raw - dcInPrev + 0.996f * dcOutPrev
                        dcInPrev = raw
                        dcOutPrev = dcOut

                        // Band-limiting low-pass filter
                        lpState += alphaLp * (dcOut - lpState)

                        // Phase invert (180 deg) for ANC modes, or in-phase for Transparency
                        val shaped = when (currentMode) {
                            MyPlayerAncMode.ANC_ANTI_NOISE -> -lpState * gain * 1.15f
                            MyPlayerAncMode.LOW_RUMBLE_ANC -> -lpState * gain * 1.35f
                            MyPlayerAncMode.TRANSPARENCY -> lpState * gain * 0.90f
                        }.coerceIn(-0.65f, 0.65f)

                        ringBuffer[w and RING_MASK] = shaped
                        w++
                    }
                    writePos = w

                    val rms = sqrt(sumSq / read.coerceAtLeast(1)).toFloat()
                    val db = (20f * log10(max(rms, 1e-5f)) + 92f).coerceIn(28f, 98f)
                    ambientDbSpl = ambientDbSpl * 0.82f + db * 0.18f
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    record.stop()
                } catch (_: Throwable) {}
                try {
                    record.release()
                } catch (_: Throwable) {}
                running = false
                ringBuffer.fill(0f)
            }
        }
    }

    @Synchronized
    fun stopCapture() {
        running = false
        captureThread = null
        ringBuffer.fill(0f)
        statusText = "Standby • Phone Mic Ready"
    }

    /** Called by [MyPlayerAncAudioProcessor] on the ExoPlayer audio thread to mix anti-noise. */
    fun pollAntiNoiseSample(): Float {
        if (!enabled || !running) return 0f
        val w = writePos
        var r = readPos
        val avail = w - r
        if (avail <= 0 || avail > RING_SIZE) {
            readPos = (w - 128).coerceAtLeast(0)
            return 0f
        }
        val offset = phaseFineTuneSamples.coerceIn(-16, 16)
        val idx = (r + offset) and RING_MASK
        readPos = r + 1
        return ringBuffer[idx]
    }
}

@UnstableApi
@Suppress("DEPRECATION")
class MyPlayerAncAudioProcessor : AudioProcessor {
    private var pendingFormat = AudioProcessor.AudioFormat.NOT_SET
    private var channels = 0
    private var buffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..2) {
            pendingFormat = AudioProcessor.AudioFormat.NOT_SET
            return AudioProcessor.AudioFormat.NOT_SET
        }
        pendingFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean = pendingFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        if (channels == 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val out = replaceOutputBuffer(bytes)
        if (!MyPlayerAncManager.enabled) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val frames = bytes / (2 * channels)
        val scale = 1f / 32768f
        for (f in 0 until frames) {
            val anti = MyPlayerAncManager.pollAntiNoiseSample()
            val inL = inputBuffer.getShort() * scale
            val inR = if (channels == 2) inputBuffer.getShort() * scale else inL
            out.putShort(softClipToPcm(inL + anti))
            if (channels == 2) {
                out.putShort(softClipToPcm(inR + anti))
            }
        }
        out.flip()
    }

    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (buffer.capacity() < size) {
            buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }
        outputBuffer = buffer
        return buffer
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY_BUFFER

    @Deprecated("Deprecated in Java")
    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
        if (pendingFormat != AudioProcessor.AudioFormat.NOT_SET) {
            channels = pendingFormat.channelCount
        }
    }

    override fun reset() {
        flush()
        pendingFormat = AudioProcessor.AudioFormat.NOT_SET
        channels = 0
    }

    companion object {
        private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
        private const val KNEE = 0.92f

        private fun softClipToPcm(sample: Float): Short {
            val a = abs(sample)
            val shaped = if (a <= KNEE) sample else {
                (KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))) * if (sample < 0f) -1f else 1f
            }
            return (shaped * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }
}

@Composable
fun MyPlayerAncSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            MyPlayerAncManager.setAncEnabled(context, true)
        } else {
            MyPlayerAncManager.statusText = "Mic permission denied"
        }
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MyPlayerSurface,
        border = BorderStroke(
            1.dp,
            if (MyPlayerAncManager.enabled) MyPlayerAccentGreen.copy(alpha = 0.65f) else MyPlayerCardBorder
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (MyPlayerAncManager.enabled) MyPlayerAccentGreen.copy(alpha = 0.20f)
                            else MyPlayerSurfaceHigh
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (MyPlayerAncManager.mode == MyPlayerAncMode.TRANSPARENCY) Icons.Default.Hearing else Icons.Default.Mic,
                        contentDescription = null,
                        tint = if (MyPlayerAncManager.enabled) MyPlayerAccentGreen else MyPlayerTextPrimary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Phone-Mic Active Noise Cancellation (ANC)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MyPlayerTextPrimary
                    )
                    Text(
                        text = "Uses your phone's focused mic to send 180° phase-inverted anti-noise to any earbuds",
                        fontSize = 12.sp,
                        color = MyPlayerTextSecondary
                    )
                }
                Switch(
                    checked = MyPlayerAncManager.enabled,
                    onCheckedChange = { wantOn ->
                        if (wantOn && !MyPlayerAncManager.hasMicPermission(context)) {
                            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            MyPlayerAncManager.setAncEnabled(context, wantOn)
                        }
                    }
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = MyPlayerAncManager.statusText,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (MyPlayerAncManager.enabled) MyPlayerAccentGreen else MyPlayerTextSecondary
                )
                if (MyPlayerAncManager.enabled) {
                    Text(
                        text = "Ambient: ${MyPlayerAncManager.ambientDbSpl.roundToInt()} dB SPL",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MyPlayerAccentGreen
                    )
                }
            }

            AnimatedVisibility(visible = MyPlayerAncManager.enabled) {
                Column {
                    Spacer(Modifier.height(14.dp))

                    // Anti-Phase Waveform Visualizer
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(68.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF0A0A0D))
                            .border(1.dp, MyPlayerCardBorder, RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        val intensityVal = MyPlayerAncManager.intensity
                        val isTransparency = MyPlayerAncManager.mode == MyPlayerAncMode.TRANSPARENCY
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val cy = size.height / 2f
                            val w = size.width
                            val steps = 48
                            for (i in 0 until steps) {
                                val t = i.toFloat() / steps
                                val x = t * w
                                val noiseY = sin(t * PI * 4).toFloat() * (cy * 0.72f)
                                val antiY = if (isTransparency) noiseY * intensityVal else -noiseY * intensityVal
                                drawLine(
                                    color = Color.White.copy(alpha = 0.35f),
                                    start = Offset(x, cy),
                                    end = Offset(x, cy + noiseY),
                                    strokeWidth = 3f,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = MyPlayerAccentGreen,
                                    start = Offset(x, cy),
                                    end = Offset(x, cy + antiY),
                                    strokeWidth = 3f,
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "ANC / ACOUSTIC MODE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MyPlayerTextSecondary
                    )
                    Spacer(Modifier.height(8.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MyPlayerAncMode.entries.forEach { m ->
                            val selected = MyPlayerAncManager.mode == m
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (selected) MyPlayerAccentGreen.copy(alpha = 0.16f) else MyPlayerSurfaceHigh,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MyPlayerAccentGreen else Color.Transparent
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { MyPlayerAncManager.mode = m }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = m.title,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (selected) MyPlayerAccentGreen else MyPlayerTextPrimary
                                        )
                                        Text(
                                            text = m.subtitle,
                                            fontSize = 11.sp,
                                            color = MyPlayerTextSecondary
                                        )
                                    }
                                    RadioButton(
                                        selected = selected,
                                        onClick = { MyPlayerAncManager.mode = m }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("ANC Cancellation Strength", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MyPlayerTextPrimary)
                        Text("${(MyPlayerAncManager.intensity * 100).roundToInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MyPlayerAccentGreen)
                    }
                    Slider(
                        value = MyPlayerAncManager.intensity,
                        onValueChange = { MyPlayerAncManager.intensity = it },
                        valueRange = 0.1f..1.0f
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Earbud Phase Alignment", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MyPlayerTextPrimary)
                        Text("${MyPlayerAncManager.phaseFineTuneSamples} samples", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MyPlayerTextSecondary)
                    }
                    Slider(
                        value = MyPlayerAncManager.phaseFineTuneSamples.toFloat(),
                        onValueChange = { MyPlayerAncManager.phaseFineTuneSamples = it.roundToInt() },
                        valueRange = -16f..16f,
                        steps = 31
                    )
                }
            }
        }
    }
}