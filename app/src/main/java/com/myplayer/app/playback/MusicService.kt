package com.myplayer.app.playback

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.myplayer.app.data.preferences.AudioPlaybackState
import com.myplayer.app.data.preferences.AudioPlaybackStore
import com.myplayer.app.data.preferences.AudioSettingsRepository
import com.myplayer.app.playback.dsp.LastWaveDspAudioProcessor
import com.myplayer.app.playback.dsp.MasterAudioDspProcessor
import com.myplayer.app.playback.dsp.SpatialAudioProcessor
import com.myplayer.app.playback.dsp.SpatialParams
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MusicService : MediaSessionService() {

    @Inject
    lateinit var player: ExoPlayer

    @Inject
    lateinit var audioAttributes: AudioAttributes

    @Inject
    lateinit var playbackStateManager: PlaybackStateManager

    @Inject
    lateinit var playbackEventBus: PlaybackEventBus

    @Inject
    lateinit var audioSettingsRepository: AudioSettingsRepository

    @Inject
    lateinit var lastWaveDspProcessor: LastWaveDspAudioProcessor

    @Inject
    lateinit var audioPlaybackStore: AudioPlaybackStore

    @Inject
    lateinit var audioFeatureManagers: AudioFeatureManagers

    @Inject
    lateinit var masterAudioDspProcessor: MasterAudioDspProcessor
    @Inject
    lateinit var spatialAudioProcessor: SpatialAudioProcessor
    @Inject
    lateinit var cacheDataSourceFactory: androidx.media3.datasource.cache.CacheDataSource.Factory
    @Inject
    lateinit var sponsorBlockManager: com.myplayer.app.data.sponsorblock.SponsorBlockManager
    @Inject
    lateinit var upgradeHandoffManager: UpgradeHandoffManager
    @Inject
    lateinit var bitPerfectUsbController: BitPerfectUsbController
    @Inject
    lateinit var audioOutputRouterManager: AudioOutputRouterManager
    @Inject
    lateinit var mixAnalyzer: com.myplayer.app.playback.mix.MixAnalyzer

    @Volatile private var currentAudioPlaybackState = AudioPlaybackState()

    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var loudnessSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    // Ported from MyPlayer: keeps the outgoing song's LoudnessEnhancer alive on its own
    // audioSessionId until the crossfade finishes so the outgoing track never drops loudness mid-fade!
    private var fadingLoudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val servicePlayerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            super.onMediaItemTransition(mediaItem, reason)
            playbackStateManager.currentOnlineSong.value?.let { online ->
                val tier = currentAudioPlaybackState.streamingQuality
                val preferLossless = tier != com.myplayer.app.data.preferences.AudioQualityTier.YOUTUBE_MUSIC &&
                    tier != com.myplayer.app.data.preferences.AudioQualityTier.DATA_SAVER
                upgradeHandoffManager.scheduleMidStreamUpgrade(
                    scope = serviceScope,
                    player = player,
                    videoId = online.videoId,
                    title = online.title,
                    artist = online.artist,
                    durationMs = online.durationMs,
                    preferLossless = preferLossless
                )
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            updateServiceLocks(isPlaying || player.playWhenReady)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)
            val active = player.playWhenReady &&
                (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY)
            if (active) {
                acquireLocks()
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            super.onRepeatModeChanged(repeatMode)
            if (repeatMode == Player.REPEAT_MODE_OFF && playbackStateManager.repeatMode.value == Player.REPEAT_MODE_ALL) {
                return
            }
            getSharedPreferences("playback_preferences", Context.MODE_PRIVATE)
                .edit().putInt("playback_repeat_mode", repeatMode).apply()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            super.onShuffleModeEnabledChanged(shuffleModeEnabled)
            getSharedPreferences("playback_preferences", Context.MODE_PRIVATE)
                .edit().putBoolean("playback_shuffle_mode", shuffleModeEnabled).apply()
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            super.onAudioSessionIdChanged(audioSessionId)
            val allowSystemFx = currentAudioPlaybackState.systemAudioEffects &&
                !audioOutputRouterManager.isSplitRoutingActive
            audioFeatureManagers.toggleSystemAudioEffects(audioSessionId, allowSystemFx)
            serviceScope.launch {
                val config = audioSettingsRepository.configFlow.firstOrNull()
                if (config != null) {
                    updateLoudnessEnhancer(
                        enabled = config.loudnessNormalize && !audioOutputRouterManager.isSplitRoutingActive,
                        gainMb = config.loudnessGainMb
                    )
                }
            }
        }
    }

    private val crossfadeHost = object : MyPlayerCrossfadeHost {
        override val activePlayer: ExoPlayer
            get() = this@MusicService.player

        override fun promoteIncomingDeck(incoming: ExoPlayer, outgoing: ExoPlayer) {
            outgoing.removeListener(servicePlayerListener)
            this@MusicService.player = incoming
            incoming.addListener(servicePlayerListener)

            // Attach SponsorBlock & AudioOutputRouter to the newly promoted incoming player
            audioOutputRouterManager.attachExoPlayer(incoming, audioAttributes)
            sponsorBlockManager.attachToPlayer(
                scope = serviceScope,
                player = incoming,
                currentVideoIdProvider = {
                    playbackStateManager.currentOnlineSong.value?.videoId
                        ?: playbackStateManager.currentSong.value?.id?.takeIf { it.startsWith("online://") }
                }
            )

            // Open system audio effects & LoudnessEnhancer on the incoming player's audioSessionId
            // while keeping the outgoing player's session alive until it finishes fading out!
            val allowSystemFx = currentAudioPlaybackState.systemAudioEffects &&
                !audioOutputRouterManager.isSplitRoutingActive
            if (incoming.audioSessionId != C.AUDIO_SESSION_ID_UNSET && incoming.audioSessionId > 0) {
                audioFeatureManagers.toggleSystemAudioEffects(incoming.audioSessionId, allowSystemFx)
            }
            serviceScope.launch {
                val config = audioSettingsRepository.configFlow.firstOrNull()
                if (config != null) {
                    updateLoudnessEnhancer(
                        enabled = config.loudnessNormalize && !audioOutputRouterManager.isSplitRoutingActive,
                        gainMb = config.loudnessGainMb
                    )
                }
            }

            runCatching {
                mediaSession?.player = buildForwardingPlayer(incoming)
            }
        }

        override fun onOutgoingDeckReleased(outgoing: ExoPlayer) {
            runCatching { outgoing.removeListener(servicePlayerListener) }
            if (outgoing.audioSessionId != C.AUDIO_SESSION_ID_UNSET && outgoing.audioSessionId > 0) {
                audioFeatureManagers.toggleSystemAudioEffects(outgoing.audioSessionId, false)
            }
            fadingLoudnessEnhancer?.let { oldEnhancer ->
                runCatching { oldEnhancer.release() }
            }
            fadingLoudnessEnhancer = null
        }

        override fun requestNextTrackFromController() {
            playbackEventBus.emit(PlaybackEvent.SkipToNextRequested)
        }
    }

    private fun shouldCrossfadeIncomingSetMediaItems(targetDeck: ExoPlayer): Boolean {
        if (targetDeck !== this.player) return false
        if (audioFeatureManagers.awaitingOnlineCrossfadeNext &&
            (targetDeck.isPlaying || targetDeck.playWhenReady || targetDeck.playbackState == Player.STATE_READY)
        ) {
            return true
        }
        // Also support MyPlayer "Blend Skips" (Manual Crossfade) when enabled in Audio Lab
        val manualBlendOn = com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge.manualCrossfadeEnabled &&
            !audioFeatureManagers.isCrossfading &&
            (targetDeck.isPlaying || targetDeck.playWhenReady) &&
            targetDeck.currentPosition > 1_000L &&
            targetDeck.repeatMode != Player.REPEAT_MODE_ONE
        return manualBlendOn
    }

    private fun buildForwardingPlayer(deckPlayer: ExoPlayer): Player {
        return object : androidx.media3.common.ForwardingPlayer(deckPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return if (command == Player.COMMAND_SEEK_TO_NEXT ||
                    command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                ) {
                    true
                } else {
                    super.isCommandAvailable(command)
                }
            }

            override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
                if (this@MusicService.player !== deckPlayer) {
                    this@MusicService.player.setMediaItems(mediaItems, startIndex, startPositionMs)
                    return
                }
                if (shouldCrossfadeIncomingSetMediaItems(deckPlayer)) {
                    audioFeatureManagers.interceptIncomingMediaItemsForCrossfade(
                        scope = serviceScope,
                        host = crossfadeHost,
                        outgoingPlayer = deckPlayer,
                        mediaItems = mediaItems,
                        startIndex = startIndex,
                        startPositionMs = startPositionMs
                    )
                    return
                }
                if (audioFeatureManagers.isCrossfading) {
                    audioFeatureManagers.abortActiveCrossfade(serviceScope)
                }
                super.setMediaItems(mediaItems, startIndex, startPositionMs)
            }

            override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
                if (this@MusicService.player !== deckPlayer) {
                    this@MusicService.player.setMediaItems(mediaItems, resetPosition)
                    return
                }
                if (shouldCrossfadeIncomingSetMediaItems(deckPlayer)) {
                    audioFeatureManagers.interceptIncomingMediaItemsForCrossfade(
                        scope = serviceScope,
                        host = crossfadeHost,
                        outgoingPlayer = deckPlayer,
                        mediaItems = mediaItems,
                        startIndex = 0,
                        startPositionMs = 0L
                    )
                    return
                }
                if (audioFeatureManagers.isCrossfading) {
                    audioFeatureManagers.abortActiveCrossfade(serviceScope)
                }
                super.setMediaItems(mediaItems, resetPosition)
            }

            override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
                if (this@MusicService.player !== deckPlayer) {
                    this@MusicService.player.setMediaItem(mediaItem, startPositionMs)
                    return
                }
                if (shouldCrossfadeIncomingSetMediaItems(deckPlayer)) {
                    audioFeatureManagers.interceptIncomingMediaItemsForCrossfade(
                        scope = serviceScope,
                        host = crossfadeHost,
                        outgoingPlayer = deckPlayer,
                        mediaItems = listOf(mediaItem),
                        startIndex = 0,
                        startPositionMs = startPositionMs
                    )
                    return
                }
                if (audioFeatureManagers.isCrossfading) {
                    audioFeatureManagers.abortActiveCrossfade(serviceScope)
                }
                super.setMediaItem(mediaItem, startPositionMs)
            }

            override fun prepare() {
                if (android.os.SystemClock.elapsedRealtime() - audioFeatureManagers.lastInterceptedSetMediaItemsMs < 1_500L &&
                    audioFeatureManagers.isCrossfading
                ) {
                    return
                }
                if (this@MusicService.player !== deckPlayer) {
                    this@MusicService.player.prepare()
                    return
                }
                super.prepare()
            }

            override fun play() {
                if (android.os.SystemClock.elapsedRealtime() - audioFeatureManagers.lastInterceptedSetMediaItemsMs < 1_500L &&
                    audioFeatureManagers.isCrossfading
                ) {
                    return
                }
                if (this@MusicService.player !== deckPlayer) {
                    this@MusicService.player.play()
                    return
                }
                super.play()
            }

            override fun pause() {
                if (this@MusicService.player !== deckPlayer) {
                    this@MusicService.player.pause()
                    return
                }
                super.pause()
            }

            override fun seekToNext() {
                val active = this@MusicService.player
                if (active.mediaItemCount > 1 && active.hasNextMediaItem()) {
                    if (audioFeatureManagers.tryManualMultiItemSkipCrossfade(
                            scope = serviceScope,
                            host = crossfadeHost,
                            outgoingPlayer = active,
                            targetIndex = active.nextMediaItemIndex
                        )
                    ) {
                        return
                    }
                    active.seekToNext()
                } else {
                    playbackEventBus.emit(PlaybackEvent.SkipToNextRequested)
                }
            }

            override fun seekToNextMediaItem() {
                seekToNext()
            }

            override fun seekToPrevious() {
                val active = this@MusicService.player
                if (active.currentPosition <= 3_000L && active.mediaItemCount > 1 && active.hasPreviousMediaItem()) {
                    if (audioFeatureManagers.tryManualMultiItemSkipCrossfade(
                            scope = serviceScope,
                            host = crossfadeHost,
                            outgoingPlayer = active,
                            targetIndex = active.previousMediaItemIndex
                        )
                    ) {
                        return
                    }
                    active.seekToPrevious()
                } else if (active.mediaItemCount > 1 && active.hasPreviousMediaItem()) {
                    active.seekToPrevious()
                } else {
                    playbackEventBus.emit(PlaybackEvent.SkipToPreviousRequested)
                }
            }

            override fun seekToPreviousMediaItem() {
                seekToPrevious()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MyPlayer:MusicServiceWakeLock"
        ).apply { setReferenceCounted(false) }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiLockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(wifiLockMode, "MyPlayer:MusicServiceWifiLock").apply {
            setReferenceCounted(false)
        }

        val callback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val connectionResult = super.onConnect(session, controller)
                val sessionCommands = connectionResult.availableSessionCommands
                val playerCommands = connectionResult.availablePlayerCommands.buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
                return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands)
            }

            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        emptyList(),
                        C.INDEX_UNSET,
                        C.TIME_UNSET
                    )
                )
            }
        }

        mediaSession = MediaSession.Builder(this, buildForwardingPlayer(player))
            .setCallback(callback)
            .build()

        sponsorBlockManager.attachToPlayer(
            scope = serviceScope,
            player = player,
            currentVideoIdProvider = {
                playbackStateManager.currentOnlineSong.value?.videoId
                    ?: playbackStateManager.currentSong.value?.id?.takeIf { it.startsWith("online://") }
            }
        )

        player.addListener(servicePlayerListener)

        // 1. MyPlayer Smooth Crossfade Engine (synced live with Audio FX Lab & AudioPlaybackStore)
        com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge.attachMasterDsp(masterAudioDspProcessor)
        com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge.loadFineTune(this)
        com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge.onUpdateCrossfadeConfig = { enabled, seconds ->
            serviceScope.launch {
                runCatching { audioPlaybackStore.setCrossfade(enabled, seconds) }
            }
        }
        audioFeatureManagers.attachMyPlayerCrossfadeEngine(
            scope = serviceScope,
            host = crossfadeHost,
            isEnabled = {
                currentAudioPlaybackState.crossfadeEnabled ||
                    com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge.isAnyCrossfadeActive
            },
            crossfadeDurationSec = currentAudioPlaybackState.crossfadeSeconds,
            crossfadeSecondsProvider = {
                if (com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge.isAnyCrossfadeActive) {
                    com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge.crossfadeSeconds
                } else {
                    currentAudioPlaybackState.crossfadeSeconds.toFloat()
                }
            }
        )

        // 2. Observe Master Audio & Playback Preferences (15-Band EQ, Studio Master Clarity, -14 LUFS, Bit-Perfect)
        serviceScope.launch {
            audioPlaybackStore.stateFlow.collect { state ->
                currentAudioPlaybackState = state
                masterAudioDspProcessor.updateState(state)
                bitPerfectUsbController.setBitPerfectEnabled(state.bitPerfectMode || state.usbExclusiveOutput)
                val allowSystemFx = state.systemAudioEffects && !audioOutputRouterManager.isSplitRoutingActive
                audioFeatureManagers.toggleSystemAudioEffects(player.audioSessionId, allowSystemFx)
                spatialAudioProcessor.updateParams(
                    SpatialParams(
                        enabled = state.spatialAudioEnabled,
                        mode = state.spatialMode,
                        speakerAngle = state.spatialSpeakerAngle,
                        amount = state.spatialAmount,
                        surround = state.dolbySurroundAtmos
                    )
                )
                if (state.spatialAudioEnabled || state.dolbySurroundAtmos) {
                    com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge.spatialEnabled = state.spatialAudioEnabled
                    com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge.spatialIsSpeakersMode =
                        (state.spatialMode == com.myplayer.app.data.preferences.SpatialMode.SPEAKERS)
                    com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge.speakerAngleDeg = state.spatialSpeakerAngle
                    com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge.spatialAmount = state.spatialAmount
                    com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge.dolbySurroundEnabled = state.dolbySurroundAtmos
                    com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge.pushFx()
                }
            }
        }

        com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge.onUpdatePlaybackSpeedPitch = { speed, pitch ->
            serviceScope.launch(Dispatchers.Main.immediate) {
                try {
                    val params = androidx.media3.common.PlaybackParameters(speed, pitch)
                    player.playbackParameters = params
                    audioFeatureManagers.fadingOutgoingPlayer?.playbackParameters = params
                } catch (e: Exception) {
                    android.util.Log.w("MusicService", "Failed to set playbackParameters", e)
                }
            }
        }

        // 3. Observe Legacy/Simple Audio Settings (Skip Silence, Clarity Presets, True-Dolby Spatial)
        serviceScope.launch {
            audioSettingsRepository.configFlow.collect { config ->
                try {
                    player.skipSilenceEnabled = config.skipSilence
                } catch (e: Exception) {
                    android.util.Log.w("MusicService", "Failed to set skipSilenceEnabled", e)
                }

                lastWaveDspProcessor.updateDspConfig(
                    preset = config.clarityPreset,
                    dolby = config.trueDolbySpatial,
                    width = config.spatialWidth
                )

                updateLoudnessEnhancer(
                    enabled = config.loudnessNormalize && !audioOutputRouterManager.isSplitRoutingActive,
                    gainMb = config.loudnessGainMb
                )
            }
        }
    }

    private fun updateLoudnessEnhancer(enabled: Boolean, gainMb: Int) {
        val sessionId = player.audioSessionId
        if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId != 0) {
            try {
                if (loudnessEnhancer != null && loudnessSessionId != sessionId) {
                    // Preserve outgoing player's LoudnessEnhancer during crossfade until it is released!
                    if (fadingLoudnessEnhancer == null) {
                        fadingLoudnessEnhancer = loudnessEnhancer
                    } else {
                        runCatching { loudnessEnhancer?.release() }
                    }
                    loudnessEnhancer = null
                }
                if (loudnessEnhancer == null) {
                    loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(sessionId)
                    loudnessSessionId = sessionId
                }
                loudnessEnhancer?.setTargetGain(if (enabled) gainMb else 0)
                loudnessEnhancer?.enabled = enabled
            } catch (e: Exception) {
                android.util.Log.w("MusicService", "Failed to update LoudnessEnhancer", e)
            }
        }
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(4 * 60 * 60 * 1000L)
        }
        if (wifiLock?.isHeld == false) {
            wifiLock?.acquire()
        }
    }

    private fun updateServiceLocks(keepActive: Boolean) {
        if (keepActive) {
            acquireLocks()
        } else if (
            playbackStateManager.playbackMachineState.value != PlaybackMachineState.RESOLVING &&
            playbackStateManager.playbackMachineState.value != PlaybackMachineState.PREPARING &&
            playbackStateManager.playbackMachineState.value != PlaybackMachineState.RECOVERING
        ) {
            releaseLocks()
        }
    }

    private fun releaseLocks() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean
    ) {
        val state = playbackStateManager.playbackMachineState.value
        val isResolvingOrRecovering = state == PlaybackMachineState.RESOLVING ||
            state == PlaybackMachineState.PREPARING ||
            state == PlaybackMachineState.RECOVERING
        val keepForeground = startInForegroundRequired || player.playWhenReady || isResolvingOrRecovering
        if (keepForeground) {
            acquireLocks()
        }
        try {
            super.onUpdateNotification(session, keepForeground)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge.onUpdatePlaybackSpeedPitch = null
        com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge.onUpdateCrossfadeConfig = null
        releaseLocks()
        try {
            fadingLoudnessEnhancer?.release()
            fadingLoudnessEnhancer = null
            loudnessEnhancer?.release()
            loudnessEnhancer = null
        } catch (_: Exception) {}
        try {
            audioFeatureManagers.release()
        } catch (_: Exception) {}
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!player.playWhenReady || player.playbackState == Player.STATE_ENDED) {
            releaseLocks()
            stopSelf()
        }
    }
}