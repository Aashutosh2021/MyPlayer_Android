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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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
    private var overlapJob: Job? = null
    private var tailPlayer: ExoPlayer? = null
    private var attachedPlayer: ExoPlayer? = null
    private var transitionListener: Player.Listener? = null

    companion object {
        /**
         * Constant-power equal-loudness gains `(outgoingGain, incomingGain)` for normalized
         * crossfade [progress] in `0f..1f`. `out^2 + in^2 == 1.0` so combined volume never dips.
         */
        internal fun equalPowerGains(progress: Float): Pair<Float, Float> {
            val p = progress.coerceIn(0f, 1f)
            val angle = p * (PI / 2.0)
            return cos(angle).toFloat().coerceIn(0f, 1f) to sin(angle).toFloat().coerceIn(0f, 1f)
        }
    }

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
     * 2. TRUE EQUAL-POWER OVERLAPPING CROSSFADE ENGINE
     * - Keeps both the end of the outgoing song and start of the incoming song at normal 1.0f
     *   combined loudness (`cos^2 + sin^2 == 1.0`) with zero pre-fade dip or silent start.
     * - When the outgoing track enters the last [fadeSec] seconds, warms up a secondary
     *   non-focus tail deck (`tailPlayer`) at the exact current position, hands off seamlessly,
     *   advances the main [exoPlayer] to the incoming track, and runs an equal-power blend once
     *   both streams are actively producing audio.
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
        overlapJob?.cancel()
        cleanupTailDeck()
        transitionListener?.let { oldListener ->
            runCatching { attachedPlayer?.removeListener(oldListener) }
        }
        attachedPlayer = exoPlayer

        var crossfadeInProgress = false
        var handledOutgoingKey: String? = null
        var transitionEpoch = 0L

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

        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                transitionEpoch++
                if (!crossfadeInProgress) {
                    exoPlayer.volume = 1.0f
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK &&
                    oldPosition.mediaItemIndex == newPosition.mediaItemIndex
                ) {
                    val dur = exoPlayer.duration
                    val fadeWin = resolveFadeWindowMs(dur)
                    if (!crossfadeInProgress && dur > 0L && (dur - newPosition.positionMs) > fadeWin) {
                        handledOutgoingKey = null
                        exoPlayer.volume = 1.0f
                    }
                }
            }
        }
        transitionListener = listener
        exoPlayer.addListener(listener)

        crossfadeJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(50L)

                if (!isCrossfadeActive()) {
                    if (crossfadeInProgress) {
                        overlapJob?.cancel()
                        cleanupTailDeck()
                        crossfadeInProgress = false
                    }
                    if (exoPlayer.volume < 1.0f) {
                        exoPlayer.volume = 1.0f
                    }
                    handledOutgoingKey = null
                    continue
                }

                if (crossfadeInProgress) {
                    continue
                }

                if (exoPlayer.volume < 0.999f) {
                    exoPlayer.volume = 1.0f
                }

                if (!exoPlayer.isPlaying || exoPlayer.playbackState != Player.STATE_READY) {
                    continue
                }

                val triggerNext = onTriggerNextTrack ?: continue
                val duration = exoPlayer.duration
                val position = exoPlayer.currentPosition
                val currentItem = exoPlayer.currentMediaItem ?: continue
                val currentMediaId = currentItem.mediaId
                val outgoingKey = "$transitionEpoch:$currentMediaId"

                if (duration > 15_000L && position > 2_000L && outgoingKey != handledOutgoingKey) {
                    val fadeWindowMs = resolveFadeWindowMs(duration)
                    val remainingMs = duration - position

                    if (remainingMs in 600L..fadeWindowMs) {
                        handledOutgoingKey = outgoingKey
                        crossfadeInProgress = true

                        val capturedEpoch = transitionEpoch
                        val capturedDuration = duration
                        val capturedSpeed = exoPlayer.playbackParameters
                        val capturedAttrs = exoPlayer.audioAttributes

                        overlapJob?.cancel()
                        overlapJob = scope.launch(Dispatchers.Main.immediate) {
                            try {
                                val mediaSourceFactory = if (dataSourceFactory != null) {
                                    DefaultMediaSourceFactory(dataSourceFactory)
                                } else {
                                    DefaultMediaSourceFactory(context)
                                }
                                val deck = ExoPlayer.Builder(
                                    context,
                                    DefaultRenderersFactory(context).setEnableDecoderFallback(true)
                                )
                                    .setMediaSourceFactory(mediaSourceFactory)
                                    .setAudioAttributes(capturedAttrs, /* handleAudioFocus = */ false)
                                    .build()
                                tailPlayer = deck

                                val handoffPos = (exoPlayer.currentPosition + 80L)
                                    .coerceAtMost((capturedDuration - 400L).coerceAtLeast(0L))
                                deck.playbackParameters = capturedSpeed
                                deck.volume = 0.0f
                                deck.setMediaItem(currentItem, handoffPos)
                                deck.prepare()
                                deck.play()

                                // Warm up tail deck silently while main player keeps playing at full 1.0f
                                var warmWaitMs = 0L
                                var tailReady = false
                                while (isActive && warmWaitMs < 1_200L) {
                                    if (!isCrossfadeActive() || !exoPlayer.playWhenReady || transitionEpoch != capturedEpoch) {
                                        return@launch
                                    }
                                    if (deck.playbackState == Player.STATE_READY &&
                                        deck.isPlaying &&
                                        deck.currentPosition >= (handoffPos - 150L).coerceAtLeast(0L)
                                    ) {
                                        tailReady = true
                                        break
                                    }
                                    delay(20L)
                                    warmWaitMs += 20L
                                }

                                if (!tailReady) {
                                    // Could not prepare tail deck cleanly — keep main player at 1.0f (never drop to silence)
                                    return@launch
                                }

                                // Instant handoff: tail deck keeps outgoing track at full 1.0f while main player loads next track
                                deck.volume = 1.0f
                                exoPlayer.volume = 0.0f
                                triggerNext.invoke()

                                // Wait until incoming track on main player is ready and actually advancing frames
                                val tailDur = deck.duration.takeIf { it > 0L } ?: capturedDuration
                                var incomingEpoch: Long? = null
                                var incomingInitialPos = -1L
                                var incomingReady = false
                                var waitIncomingMs = 0L
                                while (isActive && waitIncomingMs < 6_000L) {
                                    if (!isCrossfadeActive() || deck.playbackState == Player.STATE_ENDED) {
                                        break
                                    }
                                    if (transitionEpoch != capturedEpoch) {
                                        if (incomingEpoch == null) {
                                            incomingEpoch = transitionEpoch
                                        } else if (transitionEpoch != incomingEpoch) {
                                            // User manually skipped again during transition
                                            return@launch
                                        }
                                        if (exoPlayer.playbackState == Player.STATE_READY && exoPlayer.isPlaying) {
                                            val pos = exoPlayer.currentPosition
                                            if (incomingInitialPos < 0L) {
                                                incomingInitialPos = pos
                                            } else if (pos > incomingInitialPos || pos > 30L) {
                                                incomingReady = true
                                                break
                                            }
                                        }
                                    }
                                    delay(20L)
                                    waitIncomingMs += 20L
                                }

                                if (!incomingReady) {
                                    // No next track or delayed arrival — let tail finish cleanly at full volume
                                    while (isActive && deck.isPlaying && deck.playbackState == Player.STATE_READY && isCrossfadeActive()) {
                                        if (incomingEpoch != null && transitionEpoch != incomingEpoch) return@launch
                                        if (transitionEpoch != capturedEpoch &&
                                            exoPlayer.playbackState == Player.STATE_READY &&
                                            exoPlayer.isPlaying &&
                                            exoPlayer.currentPosition > 20L
                                        ) {
                                            incomingReady = true
                                            break
                                        }
                                        if ((tailDur - deck.currentPosition) <= 100L) break
                                        delay(35L)
                                    }
                                    if (!incomingReady) return@launch
                                }

                                val activeEpoch = incomingEpoch ?: transitionEpoch
                                val tailRemainingMs = (tailDur - deck.currentPosition).coerceAtLeast(0L)
                                val overlapDurationMs = tailRemainingMs.coerceIn(900L, fadeWindowMs)
                                val overlapStartRealtime = SystemClock.elapsedRealtime()

                                while (isActive) {
                                    if (!isCrossfadeActive() || !exoPlayer.isPlaying || transitionEpoch != activeEpoch) {
                                        return@launch
                                    }
                                    val elapsedMs = SystemClock.elapsedRealtime() - overlapStartRealtime
                                    val progress = (elapsedMs.toFloat() / overlapDurationMs.toFloat()).coerceIn(0f, 1f)
                                    val (outVol, inVol) = equalPowerGains(progress)
                                    deck.volume = outVol
                                    exoPlayer.volume = inVol
                                    if (progress >= 1f || deck.playbackState == Player.STATE_ENDED) {
                                        break
                                    }
                                    delay(25L)
                                }
                            } finally {
                                cleanupTailDeck()
                                exoPlayer.volume = 1.0f
                                crossfadeInProgress = false
                            }
                        }
                    }
                }
            }
        }
    }

    private fun cleanupTailDeck() {
        runCatching {
            tailPlayer?.stop()
            tailPlayer?.release()
        }
        tailPlayer = null
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
        overlapJob?.cancel()
        overlapJob = null
        cleanupTailDeck()
        crossfadeJob?.cancel()
        crossfadeJob = null
        transitionListener?.let { oldListener ->
            runCatching { attachedPlayer?.removeListener(oldListener) }
        }
        transitionListener = null
        attachedPlayer = null
    }
}
