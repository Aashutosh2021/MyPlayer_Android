package com.myplayer.app.playback

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
    private var tailFadeJob: Job? = null
    private var tailPlayer: ExoPlayer? = null

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

    private fun getOrCreateTailPlayer(dataSourceFactory: DataSource.Factory?): ExoPlayer? {
        tailPlayer?.let { return it }
        return runCatching {
            val dsFactory = dataSourceFactory ?: DefaultDataSource.Factory(context)
            val audioAttrs = AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build()
            ExoPlayer.Builder(context)
                .setAudioAttributes(audioAttrs, false)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
                .build().apply {
                    volume = 0f
                    playWhenReady = false
                    tailPlayer = this
                }
        }.getOrNull()
    }

    private fun stopTailPlayer() {
        tailFadeJob?.cancel()
        tailFadeJob = null
        runCatching {
            tailPlayer?.pause()
            tailPlayer?.volume = 0f
            tailPlayer?.clearMediaItems()
        }
    }

    /**
     * 2. APPLE MUSIC STYLE DUAL-PLAYER EQUAL-POWER S-CURVE CROSSFADE ENGINE (Android)
     * - Works seamlessly across BOTH local multi-item queues AND online 1-item queues!
     * - Uses an auxiliary [tailPlayer] (fed from SimpleCache / local disk) to keep the outgoing
     *   track's tail playing and fading out via cos(t * PI/2) while [exoPlayer] immediately
     *   starts the incoming track and fades it in via sin(t * PI/2)!
     * - If the outgoing stream is uncached, gracefully falls back to single-player S-curve
     *   cosine fade-out + sine fade-in with online stream readiness detection.
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
        dataSourceFactory: DataSource.Factory? = null,
        onTriggerNextTrack: (() -> Unit)? = null
    ) {
        crossfadeJob?.cancel()
        crossfadeJob = scope.launch(Dispatchers.Main) {
            var lastPrewarmedMediaId: String? = null
            var lastTriggeredMediaId: String? = null
            var isRampingInNewTrack = false

            while (isActive) {
                delay(75L)
                val xfadeActive = isEnabled() || MyPlayerAudioFxDspBridge.crossfadeEnabled
                if (!xfadeActive) {
                    if (exoPlayer.volume < 1f) exoPlayer.volume = 1f
                    stopTailPlayer()
                    isRampingInNewTrack = false
                    continue
                }

                if (!exoPlayer.isPlaying || exoPlayer.repeatMode == Player.REPEAT_MODE_ONE) {
                    if (!exoPlayer.playWhenReady && !isRampingInNewTrack) {
                        stopTailPlayer()
                    }
                    continue
                }

                val duration = exoPlayer.duration
                val position = exoPlayer.currentPosition
                val currentItem = exoPlayer.currentMediaItem
                val currentMediaId = currentItem?.mediaId ?: ""

                if (duration > 10_000L && position > 1500L && currentItem != null) {
                    val fadeSec = crossfadeSecondsProvider().coerceIn(1f, 12f)
                    val fadeWindowMs = (fadeSec * 1000f).toLong().coerceIn(1000L, (duration / 2).coerceAtLeast(1500L))
                    val remainingMs = duration - position

                    // Pre-warm tailPlayer ~2.5s before the crossfade window so overlapping handoff is 0ms instant
                    if (remainingMs in (fadeWindowMs + 1)..(fadeWindowMs + 2800L) && lastPrewarmedMediaId != currentMediaId) {
                        lastPrewarmedMediaId = currentMediaId
                        runCatching {
                            val tp = getOrCreateTailPlayer(dataSourceFactory)
                            if (tp != null) {
                                tp.pause()
                                tp.volume = 0f
                                tp.setMediaItem(currentItem, (duration - fadeWindowMs).coerceAtLeast(0L))
                                tp.prepare()
                            }
                        }
                    }

                    if (remainingMs in 1..fadeWindowMs) {
                        val tp = tailPlayer
                        val canOverlapWithTail = tp != null &&
                            tp.currentMediaItem?.mediaId == currentMediaId &&
                            tp.playbackState == Player.STATE_READY

                        if (canOverlapWithTail && lastTriggeredMediaId != currentMediaId && remainingMs >= 800L) {
                            // TRUE DUAL-PLAYER OVERLAPPING CROSSFADE:
                            // 1. Sync tailPlayer to exoPlayer's exact position and start tail playback at current volume
                            lastTriggeredMediaId = currentMediaId
                            isRampingInNewTrack = true
                            val overlapMs = remainingMs.coerceIn(1000L, fadeWindowMs)

                            runCatching {
                                tp!!.seekTo(exoPlayer.currentPosition)
                                tp.volume = exoPlayer.volume.coerceIn(0.2f, 1.0f)
                                tp.play()
                            }
                            delay(45L)

                            // 2. Fade out tailPlayer smoothly in background using Equal-Power Cosine curve
                            tailFadeJob?.cancel()
                            tailFadeJob = scope.launch(Dispatchers.Main) {
                                val steps = 30
                                val stepDelay = (overlapMs / steps).coerceAtLeast(30L)
                                val startVol = tp?.volume ?: 1f
                                for (s in 1..steps) {
                                    if (!isActive) break
                                    val p = s.toFloat() / steps.toFloat()
                                    val env = (startVol * cos(p * (PI / 2.0)).toFloat()).coerceIn(0f, 1f)
                                    runCatching { tp?.volume = env }
                                    delay(stepDelay)
                                }
                                stopTailPlayer()
                            }

                            // 3. Trigger next track immediately on main exoPlayer at low volume and ramp in via Sine curve
                            exoPlayer.volume = 0.04f
                            if (exoPlayer.mediaItemCount > 1 && exoPlayer.hasNextMediaItem()) {
                                exoPlayer.seekToNextMediaItem()
                            } else if (onTriggerNextTrack != null) {
                                onTriggerNextTrack.invoke()
                            }

                            var waitedMs = 0L
                            while (isActive && waitedMs < 3500L) {
                                val newId = exoPlayer.currentMediaItem?.mediaId ?: ""
                                if (newId != currentMediaId && exoPlayer.isPlaying) break
                                delay(60L)
                                waitedMs += 60L
                            }

                            val rampInSteps = 28
                            val stepDelayMs = (overlapMs / rampInSteps).coerceIn(35L, 180L)
                            for (step in 1..rampInSteps) {
                                if (!isActive) break
                                val inProgress = step.toFloat() / rampInSteps.toFloat()
                                val equalPowerIn = sin(inProgress * (PI / 2.0)).toFloat().coerceIn(0.04f, 1.0f)
                                exoPlayer.volume = equalPowerIn
                                delay(stepDelayMs)
                            }
                            exoPlayer.volume = 1.0f
                            isRampingInNewTrack = false
                        } else if (!isRampingInNewTrack) {
                            // Single-player Equal-Power Cosine Fade-Out fallback
                            val progress = (1f - (remainingMs.toFloat() / fadeWindowMs.toFloat())).coerceIn(0f, 1f)
                            val equalPowerOut = cos(progress * (PI / 2.0)).toFloat().coerceIn(0.04f, 1.0f)
                            exoPlayer.volume = equalPowerOut

                            // Trigger at 900ms remaining (before STATE_ENDED can fire prematurely)
                            if (remainingMs <= 900L && lastTriggeredMediaId != currentMediaId) {
                                lastTriggeredMediaId = currentMediaId
                                isRampingInNewTrack = true

                                exoPlayer.volume = 0.04f
                                if (exoPlayer.mediaItemCount > 1 && exoPlayer.hasNextMediaItem()) {
                                    exoPlayer.seekToNextMediaItem()
                                } else if (onTriggerNextTrack != null) {
                                    onTriggerNextTrack.invoke()
                                }

                                var waitedMs = 0L
                                while (isActive && waitedMs < 3500L) {
                                    val newId = exoPlayer.currentMediaItem?.mediaId ?: ""
                                    if (newId != currentMediaId && exoPlayer.isPlaying) break
                                    delay(60L)
                                    waitedMs += 60L
                                }

                                val rampInSteps = 24
                                val stepDelayMs = (fadeWindowMs / (rampInSteps * 2)).coerceIn(40L, 150L)
                                for (step in 1..rampInSteps) {
                                    if (!isActive) break
                                    val inProgress = step.toFloat() / rampInSteps.toFloat()
                                    val equalPowerIn = sin(inProgress * (PI / 2.0)).toFloat().coerceIn(0.04f, 1.0f)
                                    exoPlayer.volume = equalPowerIn
                                    delay(stepDelayMs)
                                }
                                exoPlayer.volume = 1.0f
                                isRampingInNewTrack = false
                            }
                        }
                    } else if (!isRampingInNewTrack && exoPlayer.volume < 1f && remainingMs > fadeWindowMs) {
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
            val req = Request.Builder().url(url).header("User-Agent", "MyPlayer/6.5").build()

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

    fun release() {
        tailFadeJob?.cancel()
        tailFadeJob = null
        crossfadeJob?.cancel()
        crossfadeJob = null
        runCatching {
            tailPlayer?.release()
        }
        tailPlayer = null
    }
}
