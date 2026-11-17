package com.myplayer.app.data.recognize

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
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

sealed interface RecognizerUiState {
    data object Idle : RecognizerUiState
    data class Listening(val level: Float, val seconds: Float, val peaksCount: Int) : RecognizerUiState
    data class Found(val song: RecognizedSong) : RecognizerUiState
    data object NotFound : RecognizerUiState
    data class Failed(val reason: String) : RecognizerUiState
}

/**
 * OpenTune Shazam Audio Fingerprinting ("What's This Song?") for MyPlayer.
 *
 * Uses OpenTune's exact SongRec/Shazam binary signature algorithm:
 * - 16 kHz mono PCM, 2048-point FFT every 128 samples with numpy hanning(2050) window
 * - 2D time-frequency peak spreading (`s49`, `NEIGHBOURS`, `OTHER_FRAMES`)
 * - Parabolic sub-bin interpolation scaled by 64 (`corrected = bin * 64 + variation2`)
 * - `+ 6144` log-magnitude scaling across the 4 Shazam frequency bands (250..5500 Hz)
 * - Non-blocking `async(Dispatchers.IO)` Shazam lookups at 3s, 6s, 9s, and 12s while
 *   `AudioRecord` continues capturing audio uninterrupted.
 */
@Singleton
class ShazamRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "ShazamRecognizer"
        private const val SHAZAM_ENDPOINT = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag"
        private const val SAMPLE_RATE = 16_000
        private val ASK_AT_SECONDS = intArrayOf(3, 6, 9, 12)
        private const val PREFS_NAME = "myplayer_recognized_songs"
        private const val KEY_HISTORY = "history_json"

        private val USER_AGENTS = arrayOf(
            "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/UQ1A.240205.004)",
            "Dalvik/2.1.0 (Linux; U; Android 13; SM-S918B Build/TP1A.220624.014)",
            "Shazam/14.18.0 (Linux; U; Android 14; en_US)"
        )

        internal fun parseShazamJson(rawJson: String): RecognizedSong? {
            if (rawJson.isBlank()) return null
            val root = JSONObject(rawJson)
            val track = root.optJSONObject("track") ?: return null
            val title = track.optString("title").trim().takeIf { it.isNotBlank() } ?: return null
            val artist = track.optString("subtitle").trim().ifBlank { "Unknown Artist" }
            val images = track.optJSONObject("images")
            val coverUrl = images?.optString("coverarthq")?.takeIf { it.isNotBlank() }
                ?: images?.optString("coverart")?.takeIf { it.isNotBlank() }

            val album = track.optJSONArray("sections")?.let { sections ->
                (0 until sections.length()).asSequence()
                    .mapNotNull { sections.optJSONObject(it)?.optJSONArray("metadata") }
                    .flatMap { m -> (0 until m.length()).asSequence().mapNotNull { m.optJSONObject(it) } }
                    .firstOrNull { it.optString("title").equals("Album", ignoreCase = true) }
                    ?.optString("text")
                    ?.takeIf { it.isNotBlank() }
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _state = MutableStateFlow<RecognizerUiState>(RecognizerUiState.Idle)
    val state: StateFlow<RecognizerUiState> = _state.asStateFlow()

    private val _history = MutableStateFlow<List<RecognizedSong>>(emptyList())
    val history: StateFlow<List<RecognizedSong>> = _history.asStateFlow()

    private var activeJob: Job? = null

    init {
        // ponytail: Recognizer disabled in release until stable
        if (com.myplayer.app.BuildConfig.DEBUG) {
            _history.value = loadHistoryFromPrefs()
        }
    }

    fun startListening() {
        if (!com.myplayer.app.BuildConfig.DEBUG) return
        if (activeJob?.isActive == true) return
        _state.value = RecognizerUiState.Listening(level = 0f, seconds = 0f, peaksCount = 0)
        activeJob = scope.launch {
            val result = recognizeInternal(
                onProgress = { level, seconds, peaks ->
                    _state.value = RecognizerUiState.Listening(level, seconds, peaks)
                },
                onError = { msg ->
                    _state.value = RecognizerUiState.Failed(msg)
                }
            )
            if (result != null) {
                saveToHistory(result)
                _state.value = RecognizerUiState.Found(result)
            } else if (_state.value is RecognizerUiState.Listening) {
                _state.value = RecognizerUiState.NotFound
            }
        }
    }

    fun cancelListening() {
        activeJob?.cancel()
        activeJob = null
        _state.value = RecognizerUiState.Idle
    }

    fun resetState() {
        if (activeJob?.isActive != true) {
            _state.value = RecognizerUiState.Idle
        }
    }

    fun removeFromHistory(song: RecognizedSong) {
        if (!com.myplayer.app.BuildConfig.DEBUG) return
        _history.value = _history.value.filterNot {
            it.recognizedAtMs == song.recognizedAtMs && it.title == song.title
        }
        persistHistory(_history.value)
    }

    /**
     * Direct suspend entrypoint (also updates [state] and [history] flows).
     */
    @SuppressLint("MissingPermission")
    suspend fun recognizeFromMicrophone(
        maxDurationSeconds: Int = 13,
        onProgress: ((level: Float, seconds: Float, peaksCount: Int) -> Unit)? = null
    ): RecognizedSong? = withContext(Dispatchers.Default) {
        if (!com.myplayer.app.BuildConfig.DEBUG) return@withContext null
        _state.value = RecognizerUiState.Listening(0f, 0f, 0)
        val found = recognizeInternal(
            maxDurationSeconds = maxDurationSeconds,
            onProgress = { level, sec, peaks ->
                _state.value = RecognizerUiState.Listening(level, sec, peaks)
                onProgress?.invoke(level, sec, peaks)
            },
            onError = { reason ->
                _state.value = RecognizerUiState.Failed(reason)
            }
        )
        if (found != null) {
            saveToHistory(found)
            _state.value = RecognizerUiState.Found(found)
        } else if (_state.value is RecognizerUiState.Listening) {
            _state.value = RecognizerUiState.NotFound
        }
        found
    }

    @SuppressLint("MissingPermission")
    private suspend fun recognizeInternal(
        maxDurationSeconds: Int = 13,
        onProgress: (level: Float, seconds: Float, peaksCount: Int) -> Unit,
        onError: (String) -> Unit
    ): RecognizedSong? = withContext(Dispatchers.Default) {
        if (!com.myplayer.app.BuildConfig.DEBUG) return@withContext null
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE)

        val recorder = createBestAudioRecord(minBuf * 2)
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder?.release() }
            onError("Couldn't access the microphone. Check permission or close other recording apps.")
            return@withContext null
        }

        val signature = OpenTuneShazamSignature()
        val buffer = ShortArray(SAMPLE_RATE / 10) // 100ms chunks (1600 samples)
        val scaledBuffer = ShortArray(buffer.size)
        var askIndex = 0
        var inflightLookup: Deferred<RecognizedSong?>? = null

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                onError("Microphone is currently in use by another app.")
                return@withContext null
            }

            while (isActive) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n < 0) {
                    throw IOException("Microphone read failed (code $n)")
                }
                if (n == 0) continue

                // Compute RMS & peak level; apply gentle digital gain if the ambient mic signal is very soft
                // so songs playing from another phone/laptop or low speaker volume still produce strong FFT peaks.
                var sumSq = 0.0
                var maxAbs = 0
                for (i in 0 until n) {
                    val s = buffer[i].toInt()
                    val a = abs(s)
                    if (a > maxAbs) maxAbs = a
                    sumSq += s.toDouble() * s.toDouble()
                }
                val rms = sqrt(sumSq / n)
                val gain = when {
                    maxAbs in 60..2500 -> (8000f / maxAbs.toFloat()).coerceIn(1f, 8f)
                    maxAbs in 2501..6000 -> (12000f / maxAbs.toFloat()).coerceIn(1f, 3f)
                    else -> 1f
                }
                if (gain > 1.05f) {
                    for (i in 0 until n) {
                        scaledBuffer[i] = (buffer[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    signature.add(scaledBuffer, n)
                } else {
                    signature.add(buffer, n)
                }

                val uiLevel = (rms / 5500.0).toFloat().coerceIn(0f, 1f)
                val seconds = signature.durationMs / 1000f
                onProgress(uiLevel, seconds, signature.peakCount)

                // Check if an asynchronous lookup finished while we kept recording
                inflightLookup?.takeIf { it.isCompleted }?.let { completed ->
                    val match = runCatching { completed.await() }.getOrNull()
                    if (match != null) {
                        return@withContext match
                    }
                    inflightLookup = null
                }

                // Launch next non-blocking lookup at 3s, 6s, 9s, 12s once enough peaks are collected
                if (inflightLookup == null &&
                    askIndex < ASK_AT_SECONDS.size &&
                    seconds >= ASK_AT_SECONDS[askIndex] &&
                    signature.peakCount >= 16
                ) {
                    val currentAttempt = askIndex
                    askIndex++
                    val encodedBytes = signature.encode()
                    val sampleDurationMs = signature.durationMs
                    inflightLookup = async(Dispatchers.IO) {
                        runCatching {
                            queryShazamEndpoint(encodedBytes, sampleDurationMs, currentAttempt)
                        }.onFailure { e ->
                            Log.w(TAG, "Shazam lookup #$currentAttempt failed: ${e.message}")
                        }.getOrNull()
                    }
                }

                if ((askIndex >= ASK_AT_SECONDS.size && inflightLookup == null) ||
                    seconds >= maxDurationSeconds
                ) {
                    break
                }
            }

            // Await any in-flight lookup or perform one final lookup if we have peaks
            val pendingMatch = inflightLookup?.let { runCatching { it.await() }.getOrNull() }
            if (pendingMatch != null) return@withContext pendingMatch

            if (signature.peakCount >= 12) {
                val finalBytes = signature.encode()
                return@withContext runCatching {
                    queryShazamEndpoint(finalBytes, signature.durationMs, askIndex)
                }.getOrNull()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Recognition error", e)
            onError("Couldn't listen: ${e.message ?: "unknown error"}")
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        null
    }

    @SuppressLint("MissingPermission")
    private fun createBestAudioRecord(bufferSizeInBytes: Int): AudioRecord? {
        // Prefer MIC first (standard across all devices), with fallback to VOICE_RECOGNITION / UNPROCESSED
        // in case a vendor ROM blocks MIC or applies aggressive voice-only filtering.
        val sources = buildList {
            add(MediaRecorder.AudioSource.MIC)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                add(MediaRecorder.AudioSource.UNPROCESSED)
            }
            add(MediaRecorder.AudioSource.DEFAULT)
        }
        for (src in sources) {
            val rec = runCatching {
                AudioRecord(
                    src,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSizeInBytes
                )
            }.getOrNull()
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                return rec
            }
            runCatching { rec?.release() }
        }
        return null
    }

    private suspend fun queryShazamEndpoint(
        signatureBytes: ByteArray,
        durationMs: Long,
        attemptIndex: Int = 0
    ): RecognizedSong? = withContext(Dispatchers.IO) {
        val uri = "data:audio/vnd.shazam.sig;base64," + Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
        val now = System.currentTimeMillis()
        val payload = JSONObject()
            .put("timezone", TimeZone.getDefault().id)
            .put("signature", JSONObject().put("uri", uri).put("samplems", durationMs))
            .put("timestamp", now)
            .put("context", JSONObject())
            .put("geolocation", JSONObject())

        val url = "$SHAZAM_ENDPOINT/${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID().toString().uppercase()}" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&hubv5minorversion=v5.1&hidelb=true&video=v3"

        val userAgent = USER_AGENTS[attemptIndex % USER_AGENTS.size]
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Content-Language", "en_US")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "Shazam HTTP ${resp.code}")
                return@use null
            }
            parseShazamJson(resp.body?.string().orEmpty())
        }
    }

    private fun saveToHistory(song: RecognizedSong) {
        val updated = (listOf(song) + _history.value.filterNot {
            it.title.equals(song.title, ignoreCase = true) &&
                it.artist.equals(song.artist, ignoreCase = true)
        }).take(30)
        _history.value = updated
        persistHistory(updated)
    }

    private fun persistHistory(list: List<RecognizedSong>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { s ->
                arr.put(
                    JSONObject()
                        .put("title", s.title)
                        .put("artist", s.artist)
                        .put("album", s.album ?: "")
                        .put("cover", s.coverUrl ?: "")
                        .put("url", s.shazamUrl ?: "")
                        .put("at", s.recognizedAtMs)
                )
            }
            prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
        }
    }

    private fun loadHistoryFromPrefs(): List<RecognizedSong> {
        return runCatching {
            val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = o.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RecognizedSong(
                    title = title,
                    artist = o.optString("artist", "Unknown Artist"),
                    album = o.optString("album").takeIf { it.isNotBlank() && it != "null" },
                    coverUrl = o.optString("cover").takeIf { it.isNotBlank() && it != "null" },
                    shazamUrl = o.optString("url").takeIf { it.isNotBlank() && it != "null" },
                    recognizedAtMs = o.optLong("at", System.currentTimeMillis())
                )
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * Exact port of OpenTune's `com.opentune.data.recognize.Signature`:
 * 16 kHz mono audio, 2048-point FFT every 128 samples, 2D peak spreading across
 * `FORMER`, `NEIGHBOURS`, and `OTHER_FRAMES`, parabolic sub-bin interpolation (`* 64`),
 * and `+ 6144` magnitude offset packed into Shazam's `0xCAFE2580` binary format.
 */
internal class OpenTuneShazamSignature {
    private val samples = ShortArray(WINDOW)
    private var samplesAt = 0
    private val ffts = Array(RING) { FloatArray(BINS) }
    private var fftAt = 0
    private val spread = Array(RING) { FloatArray(BINS) }
    private var spreadAt = 0
    private var spreadWritten = 0

    var sampleCount = 0
        private set

    private val peaks = sortedMapOf<Int, MutableList<Peak>>()

    private class Peak(val pass: Int, val magnitude: Int, val bin: Int)

    private val re = DoubleArray(WINDOW)
    private val im = DoubleArray(WINDOW)

    private val pending = ShortArray(STEP)
    private var pendingCount = 0

    fun add(pcm: ShortArray, count: Int = pcm.size) {
        for (i in 0 until count) {
            pending[pendingCount++] = pcm[i]
            if (pendingCount == STEP) {
                for (k in 0 until STEP) samples[(samplesAt + k) % WINDOW] = pending[k]
                samplesAt = (samplesAt + STEP) % WINDOW
                sampleCount += STEP
                pendingCount = 0
                fft()
                spreadPeaks()
                if (spreadWritten >= 46) findPeaks()
            }
        }
    }

    val peakCount: Int get() = peaks.values.sumOf { it.size }

    val durationMs: Long get() = sampleCount * 1000L / 16000

    private fun fft() {
        for (k in 0 until WINDOW) {
            re[k] = samples[(samplesAt + k) % WINDOW] * HANN[k]
            im[k] = 0.0
        }
        transform(re, im)
        val out = ffts[fftAt]
        for (k in 0 until BINS) {
            out[k] = max(((re[k] * re[k] + im[k] * im[k]) / (1 shl 17)).toFloat(), 1e-10f)
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

    private fun findPeaks() {
        val f46 = ffts[(fftAt - 46 + RING) % RING]
        val s49 = spread[(spreadAt - 49 + RING) % RING]
        for (bin in 10 until 1015) {
            val v = f46[bin]
            if (v < 1f / 64 || v < s49[bin - 1]) continue
            var near = 0f
            for (o in NEIGHBOURS) near = max(s49[bin + o], near)
            if (v <= near) continue
            var around = near
            for (o in OTHER_FRAMES) around = max(spread[((spreadAt + o) % RING + RING) % RING][bin - 1], around)
            if (v <= around) continue

            val pass = spreadWritten - 46
            val magnitude = ln(max(1f / 64, v)) * 1477.3f + 6144
            val before = ln(max(1f / 64, f46[bin - 1])) * 1477.3f + 6144
            val after = ln(max(1f / 64, f46[bin + 1])) * 1477.3f + 6144
            val variation1 = magnitude * 2 - before - after
            if (variation1 <= 0f) continue
            val variation2 = (after - before) * 32 / variation1
            val corrected = bin * 64 + variation2
            val hz = corrected * (16000f / 2 / 1024 / 64)
            val band = when {
                hz > 250 && hz < 520 -> 0
                hz > 520 && hz < 1450 -> 1
                hz > 1450 && hz < 3500 -> 2
                hz > 3500 && hz <= 5500 -> 3
                else -> continue
            }
            peaks.getOrPut(band) { mutableListOf() } += Peak(pass, magnitude.toInt(), corrected.toInt())
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
        val c = contents.toByteArray()
        val out = ByteArrayOutputStream()
        out.le32(0xCAFE2580.toInt())
        out.le32(0)
        out.le32(c.size + 8)
        out.le32(0x94119C00.toInt())
        repeat(3) { out.le32(0) }
        out.le32(3 shl 27) // 16 kHz
        repeat(2) { out.le32(0) }
        out.le32((sampleCount + 16000 * 0.24).toInt())
        out.le32((15 shl 19) + 0x40000)
        out.le32(0x40000000)
        out.le32(c.size + 8)
        out.write(c)
        val all = out.toByteArray()
        val crc = CRC32().apply { update(all, 8, all.size - 8) }.value.toInt()
        for (k in 0 until 4) all[4 + k] = (crc ushr (8 * k)).toByte()
        return all
    }

    companion object {
        private const val WINDOW = 2048
        private const val BINS = 1025
        private const val STEP = 128
        private const val RING = 256
        private val FORMER = intArrayOf(-1, -3, -6)
        private val NEIGHBOURS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
        private val OTHER_FRAMES =
            intArrayOf(-53, -45) + (165..200 step 7).toList() + (214..249 step 7).toList()
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

        private fun transform(re: DoubleArray, im: DoubleArray) {
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
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val angle = -2 * PI / len
                val wr = cos(angle)
                val wi = sin(angle)
                var i = 0
                while (i < n) {
                    var cr = 1.0
                    var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k + len / 2] = re[i + k] - ar
                        im[i + k + len / 2] = im[i + k] - ai
                        re[i + k] += ar
                        im[i + k] += ai
                        val ncr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
