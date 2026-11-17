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
import com.myplayer.app.di.MyPlayerDeckFactory
import com.myplayer.app.playback.dsp.MyPlayerCrossmixRole
import com.myplayer.app.playback.dsp.MyPlayerCrossmixStyle
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

/**
 * Host callbacks implemented by [MusicService] so [AudioFeatureManagers] can promote a newly
 * prepared incoming [ExoPlayer] deck without ever seeking or interrupting the outgoing deck
 * (ported from MyPlayer's `CrossmixDirector.Host` & `performCrossfadeSwap`).
 */
interface MyPlayerCrossfadeHost {
    val activePlayer: ExoPlayer
    fun promoteIncomingDeck(incoming: ExoPlayer, outgoing: ExoPlayer)
    fun onOutgoingDeckReleased(outgoing: ExoPlayer)
    fun requestNextTrackFromController()
}

@OptIn(UnstableApi::class)
@Singleton
class AudioFeatureManagers @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val deckFactory: MyPlayerDeckFactory
) {
    private var crossfadeMonitorJob: Job? = null
    private var activeRampJob: Job? = null
    private var onlineInterceptTimeoutJob: Job? = null

    @Volatile var fadingOutgoingPlayer: ExoPlayer? = null
        private set

    @Volatile var isCrossfading: Boolean = false
        private set

    @Volatile var awaitingOnlineCrossfadeNext: Boolean = false
        private set

    @Volatile var lastInterceptedSetMediaItemsMs: Long = 0L
        private set

    val shouldKeepOutgoingPlayingOnResolve: Boolean
        get() = awaitingOnlineCrossfadeNext || isCrossfading || MyPlayerAudioFxDspBridge.manualCrossfadeEnabled

    private var pendingIncomingPlayer: ExoPlayer? = null

    @Volatile private var pendingFadeDurationMs: Long = 5_000L
    @Volatile private var handledOutgoingKey: String? = null
    @Volatile private var transitionEpoch: Long = 0L

    private var attachedHost: MyPlayerCrossfadeHost? = null
    private var attachedPlayer: ExoPlayer? = null
    private var transitionListener: Player.Listener? = null

    companion object {
        private const val CROSSFADE_FRAME_MS = 16L
        private const val INCOMING_START_WAIT_MS = 10_000L

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
     * 2. MYPLAYER TRUE DUAL-DECK FULL-DSP CROSSFADE ENGINE
     *
     * Fixes both issues from the previous implementation:
     * - Fix 1 (Zero Millisecond Dropout): Outgoing Track A NEVER leaves the [ExoPlayer] it started on!
     *   It is never sought (`currentPosition + 80ms`), never handed off to a tail player, and never
     *   muted until Incoming Track B is `STATE_READY`, `isPlaying`, and actively advancing audio frames.
     * - Fix 2 (Zero Audio Quality Change): Incoming Track B is loaded at `0:00` onto a new [ExoPlayer]
     *   created via [MyPlayerDeckFactory.createExoPlayerDeck], which carries the EXACT SAME full DSP
     *   pipeline ([MasterAudioDspProcessor], [LastWaveDspAudioProcessor], Spatial, Reverb, Loudness,
     *   [MyPlayerCrossmixAudioProcessor], [MyPlayerVolumeSink], and [AudioOutputRouterManager] routing).
     */
    fun attachMyPlayerCrossfadeEngine(
        scope: CoroutineScope,
        host: MyPlayerCrossfadeHost,
        isEnabled: () -> Boolean,
        crossfadeDurationSec: Int = 5,
        crossfadeSecondsProvider: () -> Float = {
            if (MyPlayerAudioFxDspBridge.crossfadeEnabled) MyPlayerAudioFxDspBridge.crossfadeSeconds
            else crossfadeDurationSec.toFloat()
        }
    ) {
        crossfadeMonitorJob?.cancel()
        abortActiveCrossfade(scope)
        attachedHost = host
        rebindListenerToActivePlayer(host.activePlayer, crossfadeSecondsProvider)

        fun isCrossfadeActive(): Boolean {
            val player = host.activePlayer
            return (isEnabled() || MyPlayerAudioFxDspBridge.crossfadeEnabled) &&
                player.repeatMode != Player.REPEAT_MODE_ONE
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

        crossfadeMonitorJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(40L)
                val player = host.activePlayer

                if (!isCrossfadeActive()) {
                    if (isCrossfading || awaitingOnlineCrossfadeNext) {
                        abortActiveCrossfade(scope)
                    }
                    if (player.volume < 1.0f) {
                        player.volume = 1.0f
                    }
                    handledOutgoingKey = null
                    continue
                }

                if (isCrossfading || awaitingOnlineCrossfadeNext) {
                    continue
                }

                if (player.volume < 0.999f) {
                    player.volume = 1.0f
                }

                if (!player.isPlaying || player.playbackState != Player.STATE_READY) {
                    continue
                }

                val duration = player.duration
                val position = player.currentPosition
                val currentItem = player.currentMediaItem ?: continue
                val currentMediaId = currentItem.mediaId
                val outgoingKey = "$transitionEpoch:$currentMediaId"

                if (duration > 14_000L && position > 2_000L && outgoingKey != handledOutgoingKey) {
                    val fadeWindowMs = resolveFadeWindowMs(duration)
                    val remainingMs = duration - position

                    // Trigger slightly ahead so incoming deck can pre-buffer while outgoing song
                    // continues playing untouched at 1.0f volume!
                    val prewarmLeadMs = if (player.mediaItemCount > 1 && player.hasNextMediaItem()) 600L else 1_200L
                    if (remainingMs in 500L..(fadeWindowMs + prewarmLeadMs)) {
                        // Optional gapless album check (from MyPlayer): if consecutive tracks
                        // are on the same album and keepGaplessAlbums is enabled, let them play gapless
                        if (MyPlayerAudioFxDspBridge.keepGaplessAlbums && isNextItemSameGaplessAlbum(player)) {
                            handledOutgoingKey = outgoingKey
                            continue
                        }

                        handledOutgoingKey = outgoingKey
                        pendingFadeDurationMs = fadeWindowMs

                        if (player.mediaItemCount > 1 && player.hasNextMediaItem()) {
                            val nextIndex = player.nextMediaItemIndex
                            if (nextIndex != C.INDEX_UNSET) {
                                startMultiItemDeckCrossfade(
                                    scope = scope,
                                    host = host,
                                    outgoingPlayer = player,
                                    targetNextIndex = nextIndex,
                                    fadeDurationMs = fadeWindowMs
                                )
                            }
                        } else {
                            // Single-item online queue managed by MusicController._songQueue:
                            // Arm the online crossfade intercept in MusicService and ask MusicController
                            // to resolve & send the next track. Outgoing player stays at 1.0f untouched!
                            awaitingOnlineCrossfadeNext = true
                            onlineInterceptTimeoutJob?.cancel()
                            onlineInterceptTimeoutJob = scope.launch(Dispatchers.Main) {
                                delay(8_000L)
                                awaitingOnlineCrossfadeNext = false
                            }
                            host.requestNextTrackFromController()
                        }
                    }
                }
            }
        }
    }

    /**
     * Legacy overload preserved for binary/source compatibility if called directly.
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
        val fallbackHost = object : MyPlayerCrossfadeHost {
            override val activePlayer: ExoPlayer get() = attachedPlayer ?: exoPlayer
            override fun promoteIncomingDeck(incoming: ExoPlayer, outgoing: ExoPlayer) {
                attachedPlayer = incoming
            }
            override fun onOutgoingDeckReleased(outgoing: ExoPlayer) {}
            override fun requestNextTrackFromController() {
                onTriggerNextTrack?.invoke()
            }
        }
        attachMyPlayerCrossfadeEngine(
            scope = scope,
            host = fallbackHost,
            isEnabled = isEnabled,
            crossfadeDurationSec = crossfadeDurationSec,
            crossfadeSecondsProvider = crossfadeSecondsProvider
        )
    }

    private fun isNextItemSameGaplessAlbum(player: ExoPlayer): Boolean {
        if (player.mediaItemCount <= 1) return false
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return false
        val currentAlbum = player.currentMediaItem?.mediaMetadata?.albumTitle?.toString()?.trim()
        val nextAlbum = player.getMediaItemAt(nextIndex).mediaMetadata?.albumTitle?.toString()?.trim()
        return !currentAlbum.isNullOrBlank() &&
            !currentAlbum.equals("Online", ignoreCase = true) &&
            !currentAlbum.equals("YouTube Music", ignoreCase = true) &&
            !currentAlbum.equals("Downloads", ignoreCase = true) &&
            currentAlbum == nextAlbum
    }

    internal fun rebindListenerToActivePlayer(
        newActivePlayer: ExoPlayer,
        crossfadeSecondsProvider: () -> Float = {
            if (MyPlayerAudioFxDspBridge.crossfadeEnabled) MyPlayerAudioFxDspBridge.crossfadeSeconds else 5f
        }
    ) {
        transitionListener?.let { old ->
            runCatching { attachedPlayer?.removeListener(old) }
        }
        attachedPlayer = newActivePlayer
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                transitionEpoch++
                if (!isCrossfading) {
                    newActivePlayer.volume = 1.0f
                    deckFactory.crossmixProcessorFor(newActivePlayer)?.clear()
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
                    val dur = newActivePlayer.duration
                    val fadeWin = (crossfadeSecondsProvider().coerceIn(1f, 12f) * 1000f).toLong()
                    if (!isCrossfading && dur > 0L && (dur - newPosition.positionMs) > fadeWin) {
                        handledOutgoingKey = null
                        newActivePlayer.volume = 1.0f
                    }
                }
            }
        }
        transitionListener = listener
        newActivePlayer.addListener(listener)
    }

    /**
     * Triggers a dual-deck MyPlayer crossfade for a local multi-item queue skip (`seekToNext` / `seekToPrevious`)
     * when Blend Manual Skips is enabled. Returns `true` if the crossfade handled the transition.
     */
    fun tryManualMultiItemSkipCrossfade(
        scope: CoroutineScope,
        host: MyPlayerCrossfadeHost,
        outgoingPlayer: ExoPlayer,
        targetIndex: Int
    ): Boolean {
        if (!MyPlayerAudioFxDspBridge.manualCrossfadeEnabled || isCrossfading) return false
        if (!outgoingPlayer.isPlaying && !outgoingPlayer.playWhenReady) return false
        if (outgoingPlayer.currentPosition <= 1_000L || outgoingPlayer.repeatMode == Player.REPEAT_MODE_ONE) return false
        if (targetIndex !in 0 until outgoingPlayer.mediaItemCount) return false

        val fadeDurationMs = (
            (if (MyPlayerAudioFxDspBridge.crossfadeEnabled) MyPlayerAudioFxDspBridge.crossfadeSeconds else 3.5f)
                .coerceIn(1.5f, 8f) * 1000f
            ).toLong()
        handledOutgoingKey = "$transitionEpoch:${outgoingPlayer.currentMediaItem?.mediaId.orEmpty()}"
        startMultiItemDeckCrossfade(
            scope = scope,
            host = host,
            outgoingPlayer = outgoingPlayer,
            targetNextIndex = targetIndex,
            fadeDurationMs = fadeDurationMs
        )
        return true
    }

    /**
     * Called by [MusicService]'s ForwardingPlayer when `setMediaItems(...)` arrives from
     * [MusicController] during an online crossfade transition (or when manual Blend Skips is enabled).
     *
     * Keeps [outgoingPlayer] playing untouched until [incomingPlayer] reaches `STATE_READY`, `isPlaying`,
     * and actively advancing frames, then promotes [incomingPlayer] and runs the equal-power ramp.
     */
    fun interceptIncomingMediaItemsForCrossfade(
        scope: CoroutineScope,
        host: MyPlayerCrossfadeHost,
        outgoingPlayer: ExoPlayer,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ExoPlayer {
        lastInterceptedSetMediaItemsMs = SystemClock.elapsedRealtime()
        onlineInterceptTimeoutJob?.cancel()
        onlineInterceptTimeoutJob = null
        awaitingOnlineCrossfadeNext = false

        // If a previous crossfade was still running, release its outgoing deck first
        fadingOutgoingPlayer?.let { prevOut ->
            if (prevOut !== outgoingPlayer) {
                cleanupOutgoingDeck(prevOut, host)
            }
        }
        pendingIncomingPlayer?.let { stalePending ->
            deckFactory.releaseExoPlayerDeck(stalePending)
            pendingIncomingPlayer = null
        }

        val style = MyPlayerAudioFxDspBridge.crossmixStyle
        val incomingPlayer = deckFactory.createExoPlayerDeck(isPrimary = false)
        val inProcessor = deckFactory.crossmixProcessorFor(incomingPlayer)

        incomingPlayer.playbackParameters = outgoingPlayer.playbackParameters
        incomingPlayer.repeatMode = outgoingPlayer.repeatMode
        incomingPlayer.shuffleModeEnabled = outgoingPlayer.shuffleModeEnabled
        incomingPlayer.volume = 0.0f
        inProcessor?.arm(MyPlayerCrossmixRole.Incoming, style, initialProgress = 0f)

        val safeIndex = if (startIndex in mediaItems.indices) startIndex else 0
        val safePos = if (startPositionMs == C.TIME_UNSET || startPositionMs < 0L) 0L else startPositionMs
        incomingPlayer.setMediaItems(mediaItems, safeIndex, safePos)
        incomingPlayer.prepare()
        incomingPlayer.playWhenReady = true
        incomingPlayer.play()

        isCrossfading = true
        pendingIncomingPlayer = incomingPlayer
        fadingOutgoingPlayer = outgoingPlayer
        outgoingPlayer.pauseAtEndOfMediaItems = true

        val fadeDurationMs = pendingFadeDurationMs.coerceIn(1_500L, 12_000L)
        activeRampJob?.cancel()
        activeRampJob = scope.launch(Dispatchers.Main.immediate) {
            runMyPlayerDualDeckRamp(
                host = host,
                outgoingPlayer = outgoingPlayer,
                incomingPlayer = incomingPlayer,
                requestedDurationMs = fadeDurationMs,
                style = style
            )
        }
        return incomingPlayer
    }

    /**
     * Multi-item queue crossfade (e.g., local / downloaded playlist already inside [outgoingPlayer]):
     * Pre-buffers [targetNextIndex] on a new full-DSP [incomingPlayer] deck while [outgoingPlayer]
     * keeps playing untouched at 1.0f, then promotes and runs the MyPlayer crossfade ramp once ready.
     */
    private fun startMultiItemDeckCrossfade(
        scope: CoroutineScope,
        host: MyPlayerCrossfadeHost,
        outgoingPlayer: ExoPlayer,
        targetNextIndex: Int,
        fadeDurationMs: Long
    ) {
        if (isCrossfading) return
        isCrossfading = true

        pendingIncomingPlayer?.let { stalePending ->
            deckFactory.releaseExoPlayerDeck(stalePending)
            pendingIncomingPlayer = null
        }

        val style = MyPlayerAudioFxDspBridge.crossmixStyle
        val items = (0 until outgoingPlayer.mediaItemCount).map { outgoingPlayer.getMediaItemAt(it) }
        val incomingPlayer = deckFactory.createExoPlayerDeck(isPrimary = false)
        val inProcessor = deckFactory.crossmixProcessorFor(incomingPlayer)

        incomingPlayer.playbackParameters = outgoingPlayer.playbackParameters
        incomingPlayer.repeatMode = outgoingPlayer.repeatMode
        incomingPlayer.shuffleModeEnabled = outgoingPlayer.shuffleModeEnabled
        incomingPlayer.volume = 0.0f
        inProcessor?.arm(MyPlayerCrossmixRole.Incoming, style, initialProgress = 0f)

        incomingPlayer.setMediaItems(items, targetNextIndex, 0L)
        incomingPlayer.prepare()
        incomingPlayer.playWhenReady = true
        incomingPlayer.play()

        pendingIncomingPlayer = incomingPlayer
        fadingOutgoingPlayer = outgoingPlayer
        outgoingPlayer.pauseAtEndOfMediaItems = true

        activeRampJob?.cancel()
        activeRampJob = scope.launch(Dispatchers.Main.immediate) {
            runMyPlayerDualDeckRamp(
                host = host,
                outgoingPlayer = outgoingPlayer,
                incomingPlayer = incomingPlayer,
                requestedDurationMs = fadeDurationMs,
                style = style
            )
        }
    }

    /**
     * MyPlayer frame-locked (`16ms`, `heardMs`) dual-deck crossfade ramp:
     * 1. Keeps [outgoingPlayer] active and untouched until [incomingPlayer] is `STATE_READY`,
     *    `isPlaying`, and actively advancing frames (`> 0`).
     * 2. Only then promotes [incomingPlayer], arms [outProcessor] once, and steps every 16ms by
     *    actual media time played (`movedMs`) while driving both hardware player volumes (`cos`/`sin`)
     *    and [MyPlayerCrossmixAudioProcessor] filter sweeps.
     */
    private suspend fun runMyPlayerDualDeckRamp(
        host: MyPlayerCrossfadeHost,
        outgoingPlayer: ExoPlayer,
        incomingPlayer: ExoPlayer,
        requestedDurationMs: Long,
        style: MyPlayerCrossmixStyle
    ) {
        val outProcessor = deckFactory.crossmixProcessorFor(outgoingPlayer)
        val inProcessor = deckFactory.crossmixProcessorFor(incomingPlayer)
        var promoted = false
        var followListener: Player.Listener? = null
        val startOutgoingVolume = outgoingPlayer.volume.coerceIn(0.1f, 1.0f)

        try {
            // Phase 1: Wait silently while outgoingPlayer remains the promoted active player
            var waitedMs = 0L
            var initialPos = -1L
            var incomingHeard = false
            while (waitedMs < INCOMING_START_WAIT_MS) {
                if (host.activePlayer !== outgoingPlayer || incomingPlayer.playerError != null) return
                if (outgoingPlayer.playbackState == Player.STATE_ENDED && !outgoingPlayer.isPlaying) {
                    // Outgoing finished while buffering — still promote incoming if ready
                    if (incomingPlayer.playbackState == Player.STATE_READY && incomingPlayer.isPlaying) {
                        incomingHeard = true
                    }
                    break
                }

                if (incomingPlayer.playbackState == Player.STATE_READY && incomingPlayer.isPlaying) {
                    val pos = incomingPlayer.currentPosition
                    if (initialPos < 0L) {
                        initialPos = pos
                    } else if (pos > initialPos || pos > 20L) {
                        incomingHeard = true
                        break
                    }
                }
                delay(CROSSFADE_FRAME_MS)
                waitedMs += CROSSFADE_FRAME_MS
            }

            if (!incomingHeard) {
                inProcessor?.clear()
                return
            }

            // Phase 2: Incoming track is producing audio — promote it cleanly & attach listeners
            transitionEpoch++
            pendingIncomingPlayer = null
            host.promoteIncomingDeck(incoming = incomingPlayer, outgoing = outgoingPlayer)
            rebindListenerToActivePlayer(incomingPlayer)
            promoted = true

            followListener = object : Player.Listener {
                private fun syncPlayPause() {
                    if (!isCrossfading || fadingOutgoingPlayer !== outgoingPlayer) {
                        incomingPlayer.removeListener(this)
                        return
                    }
                    if (!incomingPlayer.playWhenReady) {
                        if (outgoingPlayer.isPlaying) outgoingPlayer.pause()
                    } else {
                        val outFinished = outgoingPlayer.playbackState == Player.STATE_ENDED ||
                            (outgoingPlayer.duration > 0L && outgoingPlayer.currentPosition >= outgoingPlayer.duration - 150L)
                        if (!outFinished && !outgoingPlayer.playWhenReady) {
                            outgoingPlayer.play()
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) = syncPlayPause()
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = syncPlayPause()
            }.also { incomingPlayer.addListener(it) }

            val outRemMs = if (outgoingPlayer.duration > 0L) {
                (outgoingPlayer.duration - outgoingPlayer.currentPosition).coerceAtLeast(800L)
            } else {
                requestedDurationMs
            }
            val effectiveDurationMs = minOf(requestedDurationMs, outRemMs).coerceAtLeast(800L)

            outProcessor?.arm(MyPlayerCrossmixRole.Outgoing, style, initialProgress = 0f)

            var heardMs = 0L
            var lastClock = SystemClock.elapsedRealtime()
            var lastPosition = incomingPlayer.currentPosition

            while (heardMs < effectiveDurationMs) {
                delay(CROSSFADE_FRAME_MS)
                if (host.activePlayer !== incomingPlayer) break
                if (outgoingPlayer.playbackState == Player.STATE_ENDED) break

                val now = SystemClock.elapsedRealtime()
                val position = incomingPlayer.currentPosition
                val wallMs = (now - lastClock).coerceAtLeast(0L)
                val movedMs = position - lastPosition
                val speed = incomingPlayer.playbackParameters.speed.takeIf { it > 0f } ?: 1f

                heardMs += when {
                    movedMs in 0L..(wallMs * 4L + 250L) -> (movedMs / speed).toLong()
                    incomingPlayer.isPlaying -> wallMs
                    else -> 0L
                }
                lastClock = now
                lastPosition = position

                val progress = (heardMs.toFloat() / effectiveDurationMs.toFloat()).coerceIn(0f, 1f)
                outProcessor?.updateProgress(progress)
                inProcessor?.updateProgress(progress)

                val (outGain, inGain) = equalPowerGains(progress)
                outgoingPlayer.volume = (startOutgoingVolume * outGain).coerceIn(0f, 1f)
                incomingPlayer.volume = inGain.coerceIn(0f, 1f)
            }

            inProcessor?.finish()
            incomingPlayer.volume = 1.0f
        } finally {
            followListener?.let { runCatching { incomingPlayer.removeListener(it) } }
            pendingIncomingPlayer = null
            if (promoted) {
                outProcessor?.fadeOutNow()
                delay(65L)
                cleanupOutgoingDeck(outgoingPlayer, host)
                if (host.activePlayer === incomingPlayer) {
                    inProcessor?.finish()
                    incomingPlayer.volume = 1.0f
                }
            } else {
                deckFactory.releaseExoPlayerDeck(incomingPlayer)
                outgoingPlayer.pauseAtEndOfMediaItems = false
                outgoingPlayer.volume = startOutgoingVolume
                if (fadingOutgoingPlayer === outgoingPlayer) {
                    fadingOutgoingPlayer = null
                }
            }
            isCrossfading = false
        }
    }

    fun abortActiveCrossfade(scope: CoroutineScope? = null) {
        onlineInterceptTimeoutJob?.cancel()
        onlineInterceptTimeoutJob = null
        awaitingOnlineCrossfadeNext = false
        activeRampJob?.cancel()
        activeRampJob = null

        val pending = pendingIncomingPlayer
        pendingIncomingPlayer = null
        val out = fadingOutgoingPlayer
        fadingOutgoingPlayer = null
        isCrossfading = false

        val host = attachedHost
        host?.activePlayer?.let { active ->
            deckFactory.crossmixProcessorFor(active)?.finish()
            active.pauseAtEndOfMediaItems = false
            active.volume = 1.0f
        }
        if (pending != null && pending !== host?.activePlayer) {
            deckFactory.releaseExoPlayerDeck(pending)
        }

        if (out != null && out !== host?.activePlayer) {
            val outProc = deckFactory.crossmixProcessorFor(out)
            outProc?.fadeOutNow()
            if (scope != null) {
                scope.launch(Dispatchers.Main) {
                    delay(65L)
                    cleanupOutgoingDeck(out, host)
                }
            } else {
                cleanupOutgoingDeck(out, host)
            }
        }
    }

    private fun cleanupOutgoingDeck(outgoing: ExoPlayer?, host: MyPlayerCrossfadeHost?) {
        if (outgoing == null) return
        if (fadingOutgoingPlayer === outgoing) {
            fadingOutgoingPlayer = null
        }
        host?.onOutgoingDeckReleased(outgoing)
        deckFactory.releaseExoPlayerDeck(outgoing)
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
            val req = Request.Builder().url(url).header("User-Agent", "MyPlayer/${com.myplayer.app.BuildConfig.VERSION_NAME}").build()

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
        crossfadeMonitorJob?.cancel()
        crossfadeMonitorJob = null
        abortActiveCrossfade(null)
        transitionListener?.let { oldListener ->
            runCatching { attachedPlayer?.removeListener(oldListener) }
        }
        transitionListener = null
        attachedPlayer = null
        attachedHost = null
    }
}
