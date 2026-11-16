package com.myplayer.app.data.recognize

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class RecognizedSong(
    val title: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    val shazamUrl: String?,
    val recognizedAtMs: Long = System.currentTimeMillis()
)

/**
 * Shazam audio fingerprinting ("What's This Song?") using SongRec / OpenTune's
 * 16 kHz mono 2048-point FFT constellation signature and non-blocking async lookups.
 */
@Singleton
class ShazamRecognizer @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "ShazamRecognizer"
        private const val SHAZAM_ENDPOINT = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag"
        private const val SAMPLE_RATE = 16_000
        private val ASK_AT_SEC = intArrayOf(4, 7, 10, 13)
    }

    @SuppressLint("MissingPermission")
    suspend fun recognizeFromMicrophone(
        maxDurationSeconds: Int = 14,
        onProgress: ((level: Float, seconds: Float) -> Unit)? = null
    ): RecognizedSong? = withContext(Dispatchers.Default) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            throw IllegalStateException("Audio input buffer unavailable ($minBuf)")
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("Couldn't initialize microphone")
        }

        val signature = ShazamBinarySignature()
        val readBuf = ShortArray(SAMPLE_RATE / 10)
        val askSchedule = ASK_AT_SEC.filter { it <= maxDurationSeconds }.ifEmpty { listOf(maxDurationSeconds.coerceAtLeast(3)) }
        var askIdx = 0
        var activeLookup: Deferred<RecognizedSong?>? = null
        var lastLookupError: Throwable? = null

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Microphone is busy or blocked")
            }

            coroutineScope {
                while (isActive) {
                    val read = recorder.read(readBuf, 0, readBuf.size)
                    if (read < 0) throw IOException("Microphone stopped responding (error $read)")
                    if (read == 0) continue

                    signature.addSamples(readBuf, read)

                    var sum = 0.0
                    for (i in 0 until read) sum += readBuf[i] * readBuf[i].toDouble()
                    val level = (sqrt(sum / read) / 6000.0).toFloat().coerceIn(0f, 1f)
                    val seconds = signature.durationMs / 1000f
                    onProgress?.invoke(level, seconds)

                    activeLookup?.takeIf { it.isCompleted }?.let { done ->
                        val found = runCatching { done.await() }.getOrElse { err ->
                            lastLookupError = err
                            null
                        }
                        if (found != null) return@coroutineScope found
                        activeLookup = null
                    }

                    if (activeLookup == null && askIdx < askSchedule.size && seconds >= askSchedule[askIdx]) {
                        askIdx++
                        val encodedSig = signature.encode()
                        val sampleMs = signature.durationMs
                        activeLookup = async(Dispatchers.IO) {
                            queryShazamEndpoint(encodedSig, sampleMs)
                        }
                    }

                    if ((askIdx >= askSchedule.size && activeLookup == null) ||
                        seconds >= maxDurationSeconds + 1f
                    ) {
                        break
                    }
                }

                val lastMatch = activeLookup?.let { done ->
                    runCatching { done.await() }.getOrElse { err ->
                        lastLookupError = err
                        null
                    }
                }
                if (lastMatch != null) return@coroutineScope lastMatch
                if (lastLookupError != null) throw lastLookupError!!
                null
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }

    internal suspend fun queryShazamEndpoint(signatureBytes: ByteArray, sampleMs: Long): RecognizedSong? =
        withContext(Dispatchers.IO) {
            val uri = "data:audio/vnd.shazam.sig;base64," + Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
            val payload = JSONObject()
                .put("timezone", TimeZone.getDefault().id)
                .put("signature", JSONObject().put("uri", uri).put("samplems", sampleMs))
                .put("timestamp", System.currentTimeMillis())
                .put("context", JSONObject())
                .put("geolocation", JSONObject())

            val url = "$SHAZAM_ENDPOINT/${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID().toString().uppercase()}" +
                "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&hubv5minorversion=v5.1&hidelb=true&video=v3"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/UQ1A.240205.004)")
                .header("Content-Language", "en_US")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errMsg = "Shazam lookup failed (HTTP ${resp.code})"
                    Log.w(TAG, errMsg)
                    throw IOException(errMsg)
                }
                parseShazamJson(resp.body?.string().orEmpty())
            }
        }

    internal fun parseShazamJson(rawJson: String): RecognizedSong? {
        if (rawJson.isBlank()) return null
        val root = JSONObject(rawJson)
        val track = root.optJSONObject("track") ?: return null
        val title = track.optString("title", "").trim()
        val artist = track.optString("subtitle", "").trim()
        if (title.isBlank()) return null
        val coverUrl = track.optJSONObject("images")?.let {
            it.optString("coverarthq").ifBlank { it.optString("coverart") }
        }?.takeIf { it.isNotBlank() }

        var album: String? = null
        val sections = track.optJSONArray("sections")
        if (sections != null) {
            for (i in 0 until sections.length()) {
                val meta = sections.optJSONObject(i)?.optJSONArray("metadata") ?: continue
                for (j in 0 until meta.length()) {
                    val m = meta.optJSONObject(j) ?: continue
                    if (m.optString("title").equals("Album", ignoreCase = true)) {
                        album = m.optString("text").takeIf { it.isNotBlank() }
                    }
                }
            }
        }
        return RecognizedSong(
            title = title,
            artist = artist,
            album = album,
            coverUrl = coverUrl,
            shazamUrl = track.optString("url").takeIf { it.isNotBlank() }
        )
    }
}

internal class ShazamBinarySignature {
    companion object {
        private const val WINDOW = 2048
        private const val BINS = 1025
        private const val STEP = 128
        private const val RING = 256
        private val FORMER = intArrayOf(-1, -3, -6)
        private val NEIGHBOURS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
        private val OTHER_FRAMES = intArrayOf(-53, -45) + (165..200 step 7).toList() + (214..249 step 7).toList()
        private val HANN = DoubleArray(WINDOW) { 0.5 - 0.5 * cos(2 * PI * (it + 1) / 2049) }

        private operator fun IntArray.plus(other: List<Int>): IntArray = (this.toList() + other).toIntArray()

        private fun ByteArrayOutputStream.le32(v: Int) {
            write(v and 0xFF)
            write((v ushr 8) and 0xFF)
            write((v ushr 16) and 0xFF)
            write((v ushr 24) and 0xFF)
        }

        private fun ByteArrayOutputStream.le16(v: Int) {
            write(v and 0xFF)
            write((v ushr 8) and 0xFF)
        }
    }

    private val samples = ShortArray(WINDOW)
    private var samplesAt = 0
    private val ffts = Array(RING) { FloatArray(BINS) }
    private var fftAt = 0
    private val spread = Array(RING) { FloatArray(BINS) }
    private var spreadAt = 0
    private var spreadWritten = 0
    var sampleCount = 0
        private set

    private data class Peak(val pass: Int, val magnitude: Int, val bin: Int)
    private val peaks = sortedMapOf<Int, MutableList<Peak>>()
    private val re = DoubleArray(WINDOW)
    private val im = DoubleArray(WINDOW)
    private val pending = ShortArray(STEP)
    private var pendingCount = 0

    val peakCount: Int get() = peaks.values.sumOf { it.size }
    val durationMs: Long get() = sampleCount * 1000L / 16_000L

    fun addSamples(pcm: ShortArray, count: Int = pcm.size) {
        for (i in 0 until count) {
            pending[pendingCount++] = pcm[i]
            if (pendingCount == STEP) {
                for (k in 0 until STEP) samples[(samplesAt + k) % WINDOW] = pending[k]
                samplesAt = (samplesAt + STEP) % WINDOW
                sampleCount += STEP
                pendingCount = 0
                runFft()
                spreadPeaks()
                if (spreadWritten >= 46) extractLocalMaxima()
            }
        }
    }

    private fun runFft() {
        for (k in 0 until WINDOW) {
            re[k] = samples[(samplesAt + k) % WINDOW] * HANN[k]
            im[k] = 0.0
        }
        inPlaceRadix2Fft(re, im)
        val out = ffts[fftAt]
        for (b in 0 until BINS) {
            val magSq = (re[b] * re[b] + im[b] * im[b]) / (1 shl 17)
            out[b] = max(magSq, 1e-10).toFloat()
        }
        fftAt = (fftAt + 1) % RING
    }

    private fun spreadPeaks() {
        val origin = ffts[(fftAt - 1 + RING) % RING]
        val s = spread[spreadAt]
        origin.copyInto(s)
        for (p in 0 until BINS) {
            if (p < BINS - 2) s[p] = max(s[p], max(s[p + 1], s[p + 2]))
            var top = s[p]
            for (back in FORMER) {
                val former = spread[(spreadAt + back + RING) % RING]
                top = max(former[p], top)
                former[p] = top
            }
        }
        spreadAt = (spreadAt + 1) % RING
        spreadWritten++
    }

    private fun extractLocalMaxima() {
        val f46 = ffts[(fftAt - 46 + RING) % RING]
        val s49 = spread[(spreadAt - 49 + RING) % RING]
        for (bin in 10 until 1015) {
            val v = f46[bin]
            if (v < 1f / 64f || v < s49[bin - 1]) continue
            var near = 0f
            for (o in NEIGHBOURS) near = max(s49[bin + o], near)
            if (v <= near) continue
            var around = near
            for (o in OTHER_FRAMES) around = max(spread[((spreadAt + o) % RING + RING) % RING][bin - 1], around)
            if (v <= around) continue

            val pass = spreadWritten - 46
            val magnitude = ln(max(1f / 64f, v)) * 1477.3f + 6144f
            val before = ln(max(1f / 64f, f46[bin - 1])) * 1477.3f + 6144f
            val after = ln(max(1f / 64f, f46[bin + 1])) * 1477.3f + 6144f
            val variation1 = magnitude * 2f - before - after
            if (variation1 <= 0f) continue
            val variation2 = (after - before) * 32f / variation1
            val corrected = bin * 64f + variation2
            val hz = corrected * (16_000f / 2f / 1024f / 64f)
            val band = when {
                hz > 250f && hz < 520f -> 0
                hz > 520f && hz < 1450f -> 1
                hz > 1450f && hz < 3500f -> 2
                hz > 3500f && hz <= 5500f -> 3
                else -> continue
            }
            peaks.getOrPut(band) { mutableListOf() }.add(Peak(pass, magnitude.toInt(), corrected.toInt()))
        }
    }

    fun encode(): ByteArray {
        val contents = ByteArrayOutputStream()
        for ((band, list) in peaks) {
            val body = ByteArrayOutputStream()
            var pass = 0
            for (p in list) {
                if (p.pass - pass >= 255) {
                    body.write(0xFF)
                    body.le32(p.pass)
                    pass = p.pass
                }
                body.write(p.pass - pass)
                body.le16(p.magnitude)
                body.le16(p.bin)
                pass = p.pass
            }
            val bytes = body.toByteArray()
            contents.le32(0x60030040 + band)
            contents.le32(bytes.size)
            contents.write(bytes)
            repeat((4 - bytes.size % 4) % 4) { contents.write(0) }
        }
        val payload = contents.toByteArray()
        val out = ByteArrayOutputStream()
        // 56-byte prefix (48-byte Shazam header + 8-byte signature tag header); CRC written after assembly
        out.le32(0xCAFE2580.toInt())
        out.le32(0)
        out.le32(payload.size + 8)
        out.le32(0x94119C00.toInt())
        repeat(3) { out.le32(0) }
        out.le32(3 shl 27)
        repeat(2) { out.le32(0) }
        out.le32((sampleCount + 16_000 * 0.24).toInt())
        out.le32((15 shl 19) + 0x40000)
        out.le32(0x40000000)
        out.le32(payload.size + 8)
        out.write(payload)
        val all = out.toByteArray()
        val crc = CRC32().apply { update(all, 8, all.size - 8) }.value.toInt()
        for (k in 0 until 4) all[4 + k] = (crc ushr (8 * k)).toByte()
        return all
    }

    private fun inPlaceRadix2Fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val ang = -2.0 * PI / len
            val wLenRe = cos(ang)
            val wLenIm = sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until half) {
                    val uRe = re[i + k]; val uIm = im[i + k]
                    val vRe = re[i + k + half] * wRe - im[i + k + half] * wIm
                    val vIm = re[i + k + half] * wIm + im[i + k + half] * wRe
                    re[i + k] = uRe + vRe; im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe; im[i + k + half] = uIm - vIm
                    val nextRe = wRe * wLenRe - wIm * wLenIm
                    wIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
