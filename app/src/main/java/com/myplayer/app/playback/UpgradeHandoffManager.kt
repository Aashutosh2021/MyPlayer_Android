package com.myplayer.app.playback

import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

enum class UpgradeAlignment { BLEND, SHORT_FADE, SEEK }

data class UpgradedStreamInfo(
    val streamUrl: String,
    val codecLabel: String,
    val bitrateKbps: Int,
    val isLossless: Boolean
)

/**
 * OpenTune Mid-Stream Upgrade Handoff + JioSaavn 320kbps Direct Decryption Engine for MyPlayer.
 * Starts playback fast on YouTube/NewPipe streams, resolves 320kbps AAC in the background,
 * and performs a sample-aligned hot-swap at the exact playback position when higher bitrate is verified.
 */
@Singleton
class UpgradeHandoffManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val losslessStreamResolver: LosslessStreamResolver
) {
    companion object {
        private const val TAG = "UpgradeHandoff"
        private const val SAAVN_API = "https://www.jiosaavn.com/api.php"
        private val SAAVN_DES_KEY = "38346591".toByteArray(StandardCharsets.UTF_8)
    }

    private var activeUpgradeJob: Job? = null

    private val _upgradedBadge = MutableStateFlow<UpgradedStreamInfo?>(null)
    val upgradedBadge: StateFlow<UpgradedStreamInfo?> = _upgradedBadge.asStateFlow()

    fun determineAlignment(driftMs: Long): UpgradeAlignment = when (driftMs) {
        in -20L..20L -> UpgradeAlignment.BLEND
        in -250L..250L -> UpgradeAlignment.SHORT_FADE
        else -> UpgradeAlignment.SEEK
    }

    fun scheduleMidStreamUpgrade(
        scope: CoroutineScope,
        player: ExoPlayer,
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        preferLossless: Boolean = true
    ) {
        activeUpgradeJob?.cancel()
        _upgradedBadge.value = null

        activeUpgradeJob = scope.launch(Dispatchers.IO) {
            delay(800L)
            val cleanTitle = losslessStreamResolver.cleanTrackTitle(title).ifBlank { title }
            val candidate = resolveJioSaavn320(cleanTitle, artist, durationMs) ?: return@launch

            withContext(Dispatchers.Main) {
                if (!player.isPlaying) return@withContext
                val currentPos = player.currentPosition
                val currentMediaItem = player.currentMediaItem ?: return@withContext

                Log.d(TAG, "Hot-swapping $title ($videoId) to ${candidate.codecLabel} at ${currentPos}ms")
                val upgradedItem = currentMediaItem.buildUpon()
                    .setUri(Uri.parse(candidate.streamUrl))
                    .build()

                val originalVolume = player.volume
                player.volume = (originalVolume * 0.35f).coerceAtLeast(0.05f)
                player.setMediaItem(upgradedItem, currentPos)
                player.prepare()
                player.play()
                delay(90L)
                player.volume = originalVolume
                _upgradedBadge.value = candidate
            }
        }
    }

    suspend fun resolveJioSaavn320(
        title: String,
        artist: String,
        durationMs: Long
    ): UpgradedStreamInfo? = withContext(Dispatchers.IO) {
        try {
            val url = SAAVN_API.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("__call", "search.getResults")
                ?.addQueryParameter("_format", "json")
                ?.addQueryParameter("_marker", "0")
                ?.addQueryParameter("api_version", "4")
                ?.addQueryParameter("ctx", "web6dot0")
                ?.addQueryParameter("n", "5")
                ?.addQueryParameter("p", "1")
                ?.addQueryParameter("q", "$title $artist".trim())
                ?.build() ?: return@withContext null

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val root = JSONObject(resp.body?.string() ?: return@withContext null)
                val results = root.optJSONArray("results") ?: return@withContext null
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val candTitle = item.optString("title", item.optString("song", ""))
                    if (candTitle.isNotBlank() && !losslessStreamResolver.isVariantCompatible(title, candTitle)) continue
                    val more = item.optJSONObject("more_info") ?: continue
                    val encUrl = more.optString("encrypted_media_url", "")
                    if (encUrl.isBlank()) continue
                    val durSec = more.optString("duration", "0").toLongOrNull() ?: 0L
                    if (!losslessStreamResolver.isDurationAcceptable(durationMs, durSec * 1000L)) continue

                    val has320 = more.optString("320kbps", "false") == "true"
                    val decrypted = decryptSaavnUrl(encUrl, has320) ?: continue
                    return@withContext UpgradedStreamInfo(
                        streamUrl = decrypted,
                        codecLabel = if (has320) "AAC • 320 kbps" else "AAC • 160 kbps",
                        bitrateKbps = if (has320) 320 else 160,
                        isLossless = false
                    )
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "JioSaavn 320kbps lookup skipped: ${e.message}")
        }
        null
    }

    private fun decryptSaavnUrl(encryptedBase64: String, upgradeTo320: Boolean): String? {
        return try {
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(SAAVN_DES_KEY, "DES"))
            val decoded = Base64.decode(encryptedBase64.trim(), Base64.DEFAULT)
            val plainUrl = String(cipher.doFinal(decoded), StandardCharsets.UTF_8).trim()
            if (!plainUrl.startsWith("http")) return null
            val targetSuffix = if (upgradeTo320) "_320.mp4" else "_160.mp4"
            plainUrl.replace("_96.mp4", targetSuffix).replace("_160.mp4", targetSuffix)
        } catch (_: Exception) {
            null
        }
    }
}
