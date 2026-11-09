package com.myplayer.app.playback

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(UnstableApi::class)
@Singleton
class AudioFeatureManagers @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private var crossfadeJob: Job? = null

    /**
     * 1. SYSTEM AUDIO EFFECTS (Let Dolby / equalizer apps process MyPlayer audio)
     */
    fun toggleSystemAudioEffects(audioSessionId: Int, enable: Boolean) {
        if (audioSessionId <= 0) return
        val action = if (enable) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
        } else {
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
        }
        val intent = Intent(action).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }
        context.sendBroadcast(intent)
    }

    /**
     * 2. APPLE MUSIC STYLE EQUAL-POWER S-CURVE CROSSFADE ENGINE
     * Works seamlessly across BOTH local multi-item queues AND online 1-item queues!
     * Uses equal-power cosine/sine loudness curves (cos(t * PI/2) fade-out, sin(t * PI/2) fade-in)
     * so perceived loudness never dips in the middle of the transition.
     */
    fun attachCrossfadeMonitor(
        scope: CoroutineScope,
        exoPlayer: ExoPlayer,
        isEnabled: () -> Boolean,
        crossfadeDurationSec: Int = 5,
        crossfadeSecondsProvider: () -> Float = {
            if (MyPlayerAudioFxDspBridge.crossfadeEnabled) MyPlayerAudioFxDspBridge.crossfadeSeconds
            else crossfadeDurationSec.toFloat()
        },
        onTriggerNextTrack: (() -> Unit)? = null
    ) {
        crossfadeJob?.cancel()
        crossfadeJob = scope.launch(Dispatchers.Main) {
            var lastTriggeredMediaId: String? = null
            var isRampingInNewTrack = false

            while (isActive) {
                delay(80L)
                val xfadeActive = isEnabled() || MyPlayerAudioFxDspBridge.crossfadeEnabled
                if (!xfadeActive) {
                    if (exoPlayer.volume < 1f) exoPlayer.volume = 1f
                    isRampingInNewTrack = false
                    continue
                }

                if (!exoPlayer.isPlaying || exoPlayer.repeatMode == Player.REPEAT_MODE_ONE) {
                    continue
                }

                val duration = exoPlayer.duration
                val position = exoPlayer.currentPosition
                val currentMediaId = exoPlayer.currentMediaItem?.mediaId ?: ""

                if (duration > 12_000L && position > 0L) {
                    val fadeSec = crossfadeSecondsProvider().coerceIn(1f, 12f)
                    val fadeWindowMs = (fadeSec * 1000f).toLong().coerceIn(1000L, (duration / 2).coerceAtLeast(1500L))
                    val remainingMs = duration - position

                    if (remainingMs in 1..fadeWindowMs) {
                        // Apple Music Equal-Power Cosine Fade-Out: progress goes 0.0 (start of fade) -> 1.0 (end of track)
                        val progress = (1f - (remainingMs.toFloat() / fadeWindowMs.toFloat())).coerceIn(0f, 1f)
                        val equalPowerOut = cos(progress * (PI / 2.0)).toFloat().coerceIn(0.04f, 1.0f)
                        exoPlayer.volume = equalPowerOut

                        // Trigger next track smoothly right before track end (works for BOTH local and online queues!)
                        if (remainingMs <= 320L && lastTriggeredMediaId != currentMediaId) {
                            lastTriggeredMediaId = currentMediaId
                            isRampingInNewTrack = true

                            if (exoPlayer.mediaItemCount > 1 && exoPlayer.hasNextMediaItem()) {
                                exoPlayer.seekToNextMediaItem()
                            } else if (onTriggerNextTrack != null) {
                                onTriggerNextTrack.invoke()
                            }

                            // Apple Music Equal-Power Sine Fade-In on the incoming track
                            val rampInSteps = 18
                            val stepDelayMs = (fadeWindowMs / (rampInSteps * 2)).coerceIn(45L, 140L)
                            for (step in 1..rampInSteps) {
                                if (!isActive) break
                                val inProgress = step.toFloat() / rampInSteps.toFloat()
                                val equalPowerIn = sin(inProgress * (PI / 2.0)).toFloat().coerceIn(0.06f, 1.0f)
                                exoPlayer.volume = equalPowerIn
                                delay(stepDelayMs)
                            }
                            exoPlayer.volume = 1.0f
                            isRampingInNewTrack = false
                        }
                    } else if (!isRampingInNewTrack && exoPlayer.volume < 1f && position < duration - fadeWindowMs) {
                        exoPlayer.volume = 1f
                    }
                }
            }
        }
    }

    /**
     * 3. DOWNLOAD SYNCED LYRICS (.lrc companion file from LRCLIB next to downloaded audio)
     */
    suspend fun downloadLrcCompanionFile(
        trackTitle: String,
        artistName: String,
        durationSeconds: Long,
        targetAudioFile: File
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val t = URLEncoder.encode(trackTitle, "UTF-8")
            val a = URLEncoder.encode(artistName, "UTF-8")
            val url = "https://lrclib.net/api/get?track_name=$t&artist_name=$a&duration=$durationSeconds"
            val req = Request.Builder().url(url).header("User-Agent", "MyPlayer/6.2").build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string() ?: return@withContext null)
                val syncedLyrics = json.optString("syncedLyrics").takeIf { it.isNotBlank() }
                    ?: json.optString("plainLyrics").takeIf { it.isNotBlank() }
                    ?: return@withContext null

                val lrcFile = File(targetAudioFile.parentFile, "${targetAudioFile.nameWithoutExtension}.lrc")
                lrcFile.writeText(syncedLyrics)
                lrcFile
            }
        }.getOrNull()
    }

    /**
     * 4. BACKGROUND PLAYBACK (Check & Request Battery Optimization Exemption)
     */
    fun isBatteryUnrestricted(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    @SuppressLint("BatteryLife")
    fun buildBatteryExemptionIntent(): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}