package com.myplayer.app.data.recognize

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

data class RecognizedSong(
    val title: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    val shazamUrl: String?,
    val recognizedAtMs: Long = System.currentTimeMillis()
)

/**
 * OpenTune Shazam Audio Fingerprinting ("What's This Song?") ported to MyPlayer.
 */
@Singleton
class ShazamRecognizer @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val SHAZAM_ENDPOINT = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag"
        private const val SAMPLE_RATE = 16_000
    }

    @SuppressLint("MissingPermission")
    suspend fun recognizeFromMicrophone(maxDurationSeconds: Int = 9): RecognizedSong? = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext null
        }

        val signature = ShazamBinarySignature()
        val readBuf = ShortArray(2048)
        val startMs = System.currentTimeMillis()
        var lastLookupSec = 2

        try {
            recorder.startRecording()
            while (isActive && (System.currentTimeMillis() - startMs) < maxDurationSeconds * 1000L) {
                val read = recorder.read(readBuf, 0, readBuf.size)
                if (read > 0) {
                    signature.addSamples(readBuf, read)
                }
                val elapsedSec = ((System.currentTimeMillis() - startMs) / 1000L).toInt()
                if (elapsedSec >= lastLookupSec && signature.peakCount >= 24) {
                    lastLookupSec = elapsedSec + 2
                    val encodedSig = signature.encode()
                    val match = queryShazamEndpoint(encodedSig, elapsedSec * 1000L)
                    if (match != null) return@withContext match
                }
            }
            if (signature.peakCount > 12) {
                return@withContext queryShazamEndpoint(signature.encode(), (System.currentTimeMillis() - startMs))
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        null
    }

    private suspend fun queryShazamEndpoint(signatureBytes: ByteArray, sampleMs: Long): RecognizedSong? =
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

            runCatching {
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    parseShazamJson(resp.body?.string().orEmpty())
                }
            }.getOrNull()
        }

    private fun parseShazamJson(rawJson: String): RecognizedSong? {
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
        private val BANDS = intArrayOf(250, 520, 1450, 3500, 5500).map { it * WINDOW / 16_000 }.toIntArray()
        private val HANN = DoubleArray(WINDOW) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / (WINDOW - 1)) }
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

    fun addSamples(pcm: ShortArray, count: Int) {
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
        fftAt = (fftAt + 1) % RING
        for (b in 0 until BINS) {
            val magSq = (re[b] * re[b] + im[b] * im[b]) / (1 shl 17)
            out[b] = max(magSq, 1e-10).toFloat()
        }
    }

    private fun spreadPeaks() {
        val src = ffts[(fftAt - 1 + RING) % RING]
        val dst = spread[spreadAt]
        System.arraycopy(src, 0, dst, 0, BINS)
        for (b in 0 until BINS - 2) {
            dst[b] = maxOf(dst[b], dst[b + 1], dst[b + 2])
        }
        for (past in intArrayOf(1, 3, 6)) {
            val prev = spread[(spreadAt - past + RING) % RING]
            for (b in 0 until BINS) prev[b] = max(prev[b], dst[b])
        }
        spreadAt = (spreadAt + 1) % RING
        spreadWritten++
    }

    private fun extractLocalMaxima() {
        val centerIdx = (fftAt - 46 + RING) % RING
        val spec = ffts[centerIdx]
        val passNum = spreadWritten - 46
        for (b in BANDS[0] until BANDS.last()) {
            val valMag = spec[b]
            if (valMag < 1f / 64f) continue
            if (valMag <= spread[(centerIdx - 1 + RING) % RING][b]) continue
            val magScaled = (max(ln(valMag.toDouble()) - ln(1.0 / 64.0), 0.0) * 1477.3).toInt()
            val bandIdx = (0 until BANDS.size - 1).firstOrNull { b in BANDS[it] until BANDS[it + 1] } ?: continue
            peaks.getOrPut(bandIdx) { mutableListOf() }.add(Peak(passNum, magScaled, b))
        }
    }

    fun encode(): ByteArray {
        val bodyBos = ByteArrayOutputStream()
        for ((band, list) in peaks) {
            val bandBos = ByteArrayOutputStream()
            var lastPass = 0
            for (p in list) {
                val delta = p.pass - lastPass
                if (delta >= 255) {
                    bandBos.write(0xFF)
                    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(p.pass).array()
                    bandBos.write(bb)
                } else {
                    bandBos.write(delta)
                }
                val magBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(p.magnitude.toShort()).array()
                val binBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(p.bin.toShort()).array()
                bandBos.write(magBytes)
                bandBos.write(binBytes)
                lastPass = p.pass
            }
            val rawBand = bandBos.toByteArray()
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x60030040 + band)
                .putInt(rawBand.size)
                .array()
            bodyBos.write(header)
            bodyBos.write(rawBand)
            while (bodyBos.size() % 4 != 0) bodyBos.write(0)
        }
        val payload = bodyBos.toByteArray()
        val totalLen = payload.size + 48
        val full = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        full.putInt(0xCAFE2580.toInt())
        full.putInt(0)
        full.putInt(totalLen - 48)
        full.putInt(0x94119C00.toInt())
        full.putInt(0); full.putInt(0); full.putInt(0)
        full.putInt(3 shl 27)
        full.putInt(0); full.putInt(0)
        full.putInt(sampleCount + (sampleCount * 0.24).toInt())
        full.putInt((15 shl 19) + 0x40000)
        full.putInt(0x40000000)
        full.putInt(totalLen - 40)
        full.put(payload)
        val arr = full.array()
        val crc = CRC32().apply { update(arr, 8, arr.size - 8) }.value.toInt()
        ByteBuffer.wrap(arr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc)
        return arr
    }

    private fun inPlaceRadix2Fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
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
