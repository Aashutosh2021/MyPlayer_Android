package com.myplayer.app.playback

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@OptIn(UnstableApi::class)
@Singleton
class AudioFeatureManagers @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private var crossfadeJob: Job? = null
    private var rampInJob: Job? = null
    private var attachedPlayer: ExoPlayer? = null
    private var transitionListener: Player.Listener? = null

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
     * 2. PERCEPTUAL SMOOTH FADE-OUT & FADE-IN ENGINE (Zero-Spike Sequential Envelope)
     * - Keeps the song at normal 1.0f volume throughout playback.
     * - During the last [fadeSec] seconds of the outgoing song, monotonically eases volume down
     *   from 1.0f to 0.0f on the main [exoPlayer] using a perceptual smoothstep curve
     *   (no un-equalized second player, no double-audio summing spike, no early track cut).
     * - On track transition (`onMediaItemTransition`), immediately locks [exoPlayer.volume] at 0.0f
     *   before the first audio frame plays, waits until the new track is actually playing and
     *   advancing, and then monotonically ramps volume from 0.0f up to 1.0f over [fadeSec] seconds.
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
        rampInJob?.cancel()
        transitionListener?.let { oldListener ->
            runCatching { attachedPlayer?.removeListener(oldListener) }
        }
        attachedPlayer = exoPlayer

        var isRampingInNewTrack = false
        var fadingOutMediaId: String? = null
        var lastFadeOutVol = 1.0f

        fun isCrossfadeActive(): Boolean {
            return (isEnabled() || MyPlayerAudioFxDspBridge.crossfadeEnabled) &&
                exoPlayer.repeatMode != Player.REPEAT_MODE_ONE
        }

        fun resolveFadeWindowMs(trackDurationMs: Long = C.TIME_UNSET): Long {
            val fadeSec = crossfadeSecondsProvider().coerceIn(1f, 12f)
            val requestedMs = (fadeSec * 1000f).toLong().coerceIn(1500L, 12_000L)
            return if (trackDurationMs > 12_000L) {
                requestedMs.coerceAtMost((trackDurationMs / 3L).coerceAtLeast(1500L))
            } else {
                requestedMs
            }
        }

        fun startSmoothFadeInForNewTrack(targetMediaId: String) {
            rampInJob?.cancel()
            if (!isCrossfadeActive()) {
                isRampingInNewTrack = false
                exoPlayer.volume = 1.0f
                return
            }

            // Lock volume at 0.0f immediately before the first sample of the incoming track is heard
            isRampingInNewTrack = true
            fadingOutMediaId = null
            lastFadeOutVol = 1.0f
            exoPlayer.volume = 0.0f

            rampInJob = scope.launch(Dispatchers.Main.immediate) {
                try {
                    // 1. Wait while the incoming track resolves/buffers until sound actually starts advancing
                    var waitElapsedMs = 0L
                    var initialPos = -1L
                    while (isActive && waitElapsedMs < 8_000L) {
                        if (!isCrossfadeActive()) {
                            exoPlayer.volume = 1.0f
                            isRampingInNewTrack = false
                            return@launch
                        }
                        // Keep locked at 0.0f while waiting for playback to begin
                        if (exoPlayer.volume > 0.01f) {
                            exoPlayer.volume = 0.0f
                        }
                        if (exoPlayer.playbackState == Player.STATE_READY && exoPlayer.isPlaying) {
                            val pos = exoPlayer.currentPosition
                            if (initialPos < 0L) {
                                initialPos = pos
                            } else if (pos > initialPos || pos > 40L) {
                                break
                            }
                        }
                        delay(25L)
                        waitElapsedMs += 25L
                    }

                    // 2. Smoothly ramp volume from 0.0f ("niche se") up to 1.0f ("normal sound")
                    val dur = exoPlayer.duration.takeIf { it > 0L } ?: C.TIME_UNSET
                    val fadeInMs = resolveFadeWindowMs(dur)
                    var accumulatedPlayMs = 0L
                    var lastTickMs = SystemClock.elapsedRealtime()
                    var currentRampVol = 0.0f

                    while (isActive && accumulatedPlayMs < fadeInMs) {
                        if (!isCrossfadeActive()) {
                            exoPlayer.volume = 1.0f
                            isRampingInNewTrack = false
                            return@launch
                        }
                        // Stop ramp-in early if the track already entered its own fade-out window
                        val trackDur = exoPlayer.duration
                        val trackPos = exoPlayer.currentPosition
                        if (trackDur > 12_000L && (trackDur - trackPos) in 1..resolveFadeWindowMs(trackDur)) {
                            break
                        }

                        val nowMs = SystemClock.elapsedRealtime()
                        val deltaMs = (nowMs - lastTickMs).coerceIn(0L, 120L)
                        lastTickMs = nowMs

                        if (exoPlayer.isPlaying && exoPlayer.playbackState == Player.STATE_READY) {
                            accumulatedPlayMs += deltaMs
                            val p = (accumulatedPlayMs.toFloat() / fadeInMs.toFloat()).coerceIn(0f, 1f)
                            // Perceptual smoothstep * p curve: starts very gently from 0 with zero sudden jump
                            val smooth = p * p * (3f - 2f * p)
                            val targetInVol = (smooth * p).coerceIn(0f, 1f)
                            if (targetInVol > currentRampVol) {
                                currentRampVol = targetInVol
                            }
                            exoPlayer.volume = currentRampVol
                        }
                        delay(30L)
                    }

                    exoPlayer.volume = 1.0f
                } finally {
                    isRampingInNewTrack = false
                }
            }
        }

        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val newId = mediaItem?.mediaId ?: ""
                if (isCrossfadeActive() && newId.isNotBlank()) {
                    startSmoothFadeInForNewTrack(newId)
                } else {
                    rampInJob?.cancel()
                    isRampingInNewTrack = false
                    fadingOutMediaId = null
                    lastFadeOutVol = 1.0f
                    exoPlayer.volume = 1.0f
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // If user manually seeks back into the middle of the current track, restore normal volume
                if (reason == Player.DISCONTINUITY_REASON_SEEK &&
                    oldPosition.mediaItemIndex == newPosition.mediaItemIndex
                ) {
                    val dur = exoPlayer.duration
                    val fadeWin = resolveFadeWindowMs(dur)
                    if (dur > 0L && (dur - newPosition.positionMs) > fadeWin) {
                        rampInJob?.cancel()
                        isRampingInNewTrack = false
                        fadingOutMediaId = null
                        lastFadeOutVol = 1.0f
                        exoPlayer.volume = 1.0f
                    }
                }
            }
        }
        transitionListener = listener
        exoPlayer.addListener(listener)

        crossfadeJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(35L)

                if (!isCrossfadeActive()) {
                    if (!isRampingInNewTrack && exoPlayer.volume < 1f) {
                        exoPlayer.volume = 1f
                    }
                    fadingOutMediaId = null
                    lastFadeOutVol = 1.0f
                    continue
                }

                // While ramping in the new song, let rampInJob own exoPlayer.volume exclusively
                if (isRampingInNewTrack) {
                    continue
                }

                if (!exoPlayer.isPlaying || exoPlayer.playbackState != Player.STATE_READY) {
                    continue
                }

                val duration = exoPlayer.duration
                val position = exoPlayer.currentPosition
                val currentItem = exoPlayer.currentMediaItem
                val currentMediaId = currentItem?.mediaId ?: ""

                if (duration > 12_000L && position > 1_000L && currentItem != null) {
                    val fadeOutWindowMs = resolveFadeWindowMs(duration)
                    val remainingMs = duration - position

                    if (remainingMs in 1..fadeOutWindowMs) {
                        if (fadingOutMediaId != currentMediaId) {
                            fadingOutMediaId = currentMediaId
                            lastFadeOutVol = exoPlayer.volume.coerceIn(0f, 1f)
                        }

                        // Perceptual smooth fade-out: stays at 1.0 entering the window, then eases down to 0.0
                        val linearRem = (remainingMs.toFloat() / fadeOutWindowMs.toFloat()).coerceIn(0f, 1f)
                        val smooth = linearRem * linearRem * (3f - 2f * linearRem)
                        val targetOutVol = if (remainingMs <= 120L) {
                            0.0f
                        } else {
                            (smooth * linearRem).coerceIn(0f, 1f)
                        }

                        // Strict monotonic decrease so position jitter can NEVER cause a volume spike
                        val appliedVol = minOf(lastFadeOutVol, targetOutVol)
                        lastFadeOutVol = appliedVol
                        exoPlayer.volume = appliedVol
                    } else if (remainingMs > fadeOutWindowMs + 400L) {
                        fadingOutMediaId = null
                        lastFadeOutVol = 1.0f
                        if (exoPlayer.volume < 1.0f) {
                            exoPlayer.volume = 1.0f
                        }
                    }
                } else if (exoPlayer.volume < 1.0f && fadingOutMediaId == null) {
                    exoPlayer.volume = 1.0f
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
            val req = Request.Builder().url(url).header("User-Agent", "MyPlayer/6.6").build()

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
        rampInJob?.cancel()
        rampInJob = null
        crossfadeJob?.cancel()
        crossfadeJob = null
        transitionListener?.let { oldListener ->
            runCatching { attachedPlayer?.removeListener(oldListener) }
        }
        transitionListener = null
        attachedPlayer = null
    }
}
