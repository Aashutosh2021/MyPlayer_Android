package com.myplayer.app.playback

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class MultiOutputScenario(
    val id: String,
    val title: String,
    val subtitle: String,
    val badge: String
) {
    DEFAULT_SYSTEM(
        id = "default_system",
        title = "Standard Android Routing",
        subtitle = "Follows system default output & pauses when Reels/Movies start",
        badge = "Default"
    ),
    SCENARIO_1_MYPLAYER_BT_REELS_PHONE(
        id = "myplayer_bt_reels_phone",
        title = "Scenario 1: MyPlayer on Bluetooth • Reels on Phone Speaker",
        subtitle = "Locks MyPlayer to Bluetooth Earbuds/Speaker while Reels/YouTube play on Phone Speaker",
        badge = "BT + Phone"
    ),
    SCENARIO_2_MYPLAYER_SPEAKER_REELS_EARBUDS(
        id = "myplayer_speaker_reels_earbuds",
        title = "Scenario 2: MyPlayer on Phone/Wired Speaker • Reels on Earbuds",
        subtitle = "Locks MyPlayer to Phone/AUX/USB Speaker while Reels/YouTube play in Bluetooth Earbuds",
        badge = "Speaker + Buds"
    ),
    SCENARIO_3_DUAL_EXTERNAL_SINKS(
        id = "dual_speaker_and_earbuds",
        title = "Scenario 3: Dual Connected Devices (Speaker + Earbuds Split)",
        subtitle = "Locks MyPlayer to your Speaker while your Earbuds play Reels/YouTube/Movies",
        badge = "Dual Split"
    );

    companion object {
        fun fromId(id: String?): MultiOutputScenario =
            entries.firstOrNull { it.id == id } ?: DEFAULT_SYSTEM
    }
}

enum class OutputDeviceCategory {
    PHONE_SPEAKER,
    EARPIECE,
    BLUETOOTH_A2DP,
    BLUETOOTH_LE,
    WIRED_AUX_HEADPHONES,
    USB_DAC_SPEAKER,
    HDMI_TV,
    OTHER_SINK
}

data class ConnectedAudioOutputDevice(
    val id: Int,
    val productName: String,
    val type: Int,
    val typeLabel: String,
    val category: OutputDeviceCategory,
    val address: String = "",
    val isPinnedByMyPlayer: Boolean = false,
    val isLikelySpeaker: Boolean = false
)

data class AudioOutputRouterState(
    val activeScenario: MultiOutputScenario = MultiOutputScenario.DEFAULT_SYSTEM,
    val ignoreAudioFocus: Boolean = false,
    val autoSyncExternalStream: Boolean = true,
    val independentAppVolume: Float = 1.0f,
    val pinnedDeviceId: Int? = null,
    val pinnedCategory: OutputDeviceCategory? = null,
    val connectedDevices: List<ConnectedAudioOutputDevice> = emptyList(),
    val activeRouteSummary: String = "System Default Output",
    val statusHint: String = "Standard Android audio routing active."
)

/**
 * Multi-Output & Separate Sound Studio Manager for MyPlayer.
 *
 * Fixes & Capabilities:
 * 1. Strict Idempotent Routing: Never calls [ExoPlayer.setAudioAttributes] or
 *    [ExoPlayer.setPreferredAudioDevice] unless the target sink ID or AudioFocus flag actually
 *    changed. Debounces [AudioDeviceCallback] by 450ms so Bluetooth A2DP state transitions
 *    never tear down or stutter the AudioTrack ("zig-zag" startup sound fix).
 * 2. Automatic Split-Sink Stream Resync ([resyncExternalAudioStreams]): When switching scenarios
 *    or starting MyPlayer playback on a split sink while YouTube/Reels is already playing on the
 *    other sink, performs a clean 180ms transient AudioFocus resync so Android's AudioFlinger
 *    separates the two hardware mixer threads automatically without manual pause/resume.
 * 3. Scenario 1 Active Route Guardian & System Route Switcher: Switches default live-audio route
 *    to Phone Speaker via [MediaRouter] for YouTube/Reels while keeping MyPlayer's [ExoPlayer]
 *    continuously locked onto the Bluetooth sink via a periodic Route Guardian.
 * 4. Scenario 3 Smart Speaker-vs-Earbuds Classifier: Distinguishes connected Speakers from
 *    Earbuds so MyPlayer never pins itself to the same single Earbuds sink used by YouTube.
 */
@OptIn(UnstableApi::class)
@Singleton
class AudioOutputRouterManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AudioOutputRouter"
        private const val PREFS_NAME = "multi_output_router_prefs"
        private const val KEY_SCENARIO = "active_scenario"
        private const val KEY_IGNORE_FOCUS = "ignore_audio_focus"
        private const val KEY_AUTO_SYNC_EXTERNAL = "auto_sync_external_stream"
        private const val KEY_APP_VOLUME = "independent_app_volume"
        private const val KEY_PINNED_CATEGORY = "pinned_device_category"
        private const val KEY_PINNED_DEVICE_ID = "pinned_device_id"
        private const val NO_DEVICE_ID = -999

        private val SPEAKER_KEYWORDS = listOf(
            "speaker", "soundbar", "flip", "charge", "xtreme", "partybox", "boom",
            "go 2", "go 3", "go 4", "clip", "pulse", "marshall", "emberton", "kilburn",
            "stone", "aavante", " blitz", "rugby", "roam", "move", "era", "echo",
            "nest", "homepod", "srs-", "ult field", "wonderboom", "megaboom", "soundlink",
            "tribit", "stormbox", "soundcore", "motion", "select", "mini", "bar", "tv", "hdmi"
        )

        private val EARBUDS_KEYWORDS = listOf(
            "buds", "airpods", "pods", "ear", "headphone", "headset", "neckband",
            "rockerz", "airdopes", "immortal", "nirvana", "wf-", "wh-", "linkbuds",
            "enco", "bullets", "nord", "tws", "dots", "p40i", "liberty", "space",
            "momentum", "quietcomfort", "qc", "xm3", "xm4", "xm5", "tune", "wave", "live"
        )

        @Volatile
        private var instance: AudioOutputRouterManager? = null

        @Volatile
        var currentIndependentVolumeMultiplier: Float = 1.0f
            private set

        fun getInstance(context: Context): AudioOutputRouterManager {
            return instance ?: synchronized(this) {
                instance ?: AudioOutputRouterManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var attachedPlayer: ExoPlayer? = null
    private var baseAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

    // Idempotency tracking to prevent redundant AudioTrack recreation / "zig-zag" stutter
    private var lastAppliedPlayer: ExoPlayer? = null
    private var lastAppliedIgnoreFocus: Boolean? = null
    private var lastAppliedDeviceId: Int = NO_DEVICE_ID
    private var lastExternalResyncRealtimeMs: Long = 0L
    private var wasPlayingBefore: Boolean = false

    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<AudioOutputRouterState> = _state.asStateFlow()

    val isSplitRoutingActive: Boolean
        get() {
            if (!com.myplayer.app.BuildConfig.DEBUG) return false
            val s = _state.value
            return s.activeScenario != MultiOutputScenario.DEFAULT_SYSTEM || s.pinnedDeviceId != null
        }

    private val debouncedCallbackRefreshRunnable = Runnable {
        refreshConnectedDevicesAndApplyRouting(forceReapply = false)
    }

    /**
     * Route Guardian: While Scenario 1, 2, or 3 is active, periodically verifies that Android's
     * system MediaOutput switcher did not un-pin MyPlayer's preferred hardware sink.
     * Only touches ExoPlayer if the target device disappeared/reappeared or was overridden.
     */
    private val routeGuardianRunnable = object : Runnable {
        override fun run() {
            val s = _state.value
            if (s.activeScenario != MultiOutputScenario.DEFAULT_SYSTEM || s.pinnedDeviceId != null) {
                val rawSinks = getRawOutputSinks()
                val targetInfo = resolveTargetAudioDeviceInfo(
                    rawSinks = rawSinks,
                    scenario = s.activeScenario,
                    pinnedId = s.pinnedDeviceId,
                    pinnedCategory = s.pinnedCategory
                )
                val targetId = targetInfo?.id ?: -1
                if (targetId != lastAppliedDeviceId) {
                    refreshConnectedDevicesAndApplyRouting(forceReapply = true)
                }
                mainHandler.postDelayed(this, 2000L)
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            mainHandler.removeCallbacks(debouncedCallbackRefreshRunnable)
            mainHandler.postDelayed(debouncedCallbackRefreshRunnable, 450L)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            mainHandler.removeCallbacks(debouncedCallbackRefreshRunnable)
            mainHandler.postDelayed(debouncedCallbackRefreshRunnable, 450L)
        }
    }

    private val playerPlaybackListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val justStarted = isPlaying && !wasPlayingBefore
            wasPlayingBefore = isPlaying
            if (justStarted) {
                val s = _state.value
                if ((s.activeScenario != MultiOutputScenario.DEFAULT_SYSTEM || s.pinnedDeviceId != null) &&
                    s.autoSyncExternalStream
                ) {
                    // If another app (YouTube/Reels) is concurrently playing on a second sink at the
                    // moment MyPlayer starts, perform a single smooth stream resync after MyPlayer's
                    // AudioTrack has locked onto its hardware sink (eliminates startup zig-zag).
                    if (isAnotherMediaAppSimultaneouslyActive()) {
                        mainHandler.postDelayed({
                            resyncExternalAudioStreams(silent = true)
                        }, 240L)
                    }
                }
            }
        }
    }

    init {
        instance = this
        // ponytail: Multi-Output & Separate Sound disabled in release until stable
        if (com.myplayer.app.BuildConfig.DEBUG) {
            currentIndependentVolumeMultiplier = _state.value.independentAppVolume
            runCatching {
                audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
            }
            refreshConnectedDevicesAndApplyRouting(forceReapply = false)
            scheduleRouteGuardianIfNeeded()
        } else {
            currentIndependentVolumeMultiplier = 1.0f
        }
    }

    private fun loadInitialState(): AudioOutputRouterState {
        if (!com.myplayer.app.BuildConfig.DEBUG) {
            currentIndependentVolumeMultiplier = 1.0f
            return AudioOutputRouterState()
        }
        val scenario = MultiOutputScenario.fromId(prefs.getString(KEY_SCENARIO, null))
        val ignoreFocus = prefs.getBoolean(KEY_IGNORE_FOCUS, scenario != MultiOutputScenario.DEFAULT_SYSTEM)
        val autoSync = prefs.getBoolean(KEY_AUTO_SYNC_EXTERNAL, true)
        val appVol = prefs.getFloat(KEY_APP_VOLUME, 1.0f).coerceIn(0.05f, 1.0f)
        val savedCatName = prefs.getString(KEY_PINNED_CATEGORY, null)
        val savedCat = savedCatName?.let { name ->
            runCatching { OutputDeviceCategory.valueOf(name) }.getOrNull()
        }
        val savedId = if (prefs.contains(KEY_PINNED_DEVICE_ID)) {
            prefs.getInt(KEY_PINNED_DEVICE_ID, -1).takeIf { it >= 0 }
        } else null

        currentIndependentVolumeMultiplier = appVol
        return AudioOutputRouterState(
            activeScenario = scenario,
            ignoreAudioFocus = ignoreFocus,
            autoSyncExternalStream = autoSync,
            independentAppVolume = appVol,
            pinnedDeviceId = savedId,
            pinnedCategory = savedCat
        )
    }

    fun attachExoPlayer(player: ExoPlayer, attributes: AudioAttributes) {
        if (!com.myplayer.app.BuildConfig.DEBUG) return
        this.attachedPlayer?.removeListener(playerPlaybackListener)
        this.attachedPlayer = player
        this.baseAudioAttributes = attributes
        this.lastAppliedPlayer = null
        this.lastAppliedIgnoreFocus = null
        this.lastAppliedDeviceId = NO_DEVICE_ID
        player.addListener(playerPlaybackListener)
        mainHandler.post {
            refreshConnectedDevicesAndApplyRouting(forceReapply = true)
            scheduleRouteGuardianIfNeeded()
        }
    }

    /**
     * Enables or disables Simultaneous Playback with Reels / YouTube / Movies
     * by dynamically updating ExoPlayer's `handleAudioFocus` flag only when changed.
     */
    fun setIgnoreAudioFocus(ignore: Boolean) {
        if (!com.myplayer.app.BuildConfig.DEBUG) return
        prefs.edit().putBoolean(KEY_IGNORE_FOCUS, ignore).apply()
        _state.update { it.copy(ignoreAudioFocus = ignore) }
        refreshConnectedDevicesAndApplyRouting(forceReapply = false)
    }

    /**
     * Enables or disables automatic 180ms external stream resync to eliminate startup
     * zig-zag / HAL clock contention when YouTube/Reels is playing concurrently.
     */
    fun setAutoSyncExternalStream(enabled: Boolean) {
        if (!com.myplayer.app.BuildConfig.DEBUG) return
        prefs.edit().putBoolean(KEY_AUTO_SYNC_EXTERNAL, enabled).apply()
        _state.update { it.copy(autoSyncExternalStream = enabled) }
    }

    /**
     * Adjusts MyPlayer's independent in-app volume (`0.05f..1.0f`) so the user can balance
     * song loudness against Reels/Movie volume without altering Android's system volume.
     */
    fun setIndependentAppVolume(volume: Float) {
        if (!com.myplayer.app.BuildConfig.DEBUG) return
        val clamped = volume.coerceIn(0.05f, 1.0f)
        currentIndependentVolumeMultiplier = clamped
        prefs.edit().putFloat(KEY_APP_VOLUME, clamped).apply()
        _state.update { it.copy(independentAppVolume = clamped) }
        mainHandler.post {
            attachedPlayer?.let { player ->
                val currentVol = player.volume
                player.volume = currentVol
            }
        }
    }

    /**
     * Applies one of the 3 user scenarios (or restores Default System Routing).
     */
    fun applyScenario(scenario: MultiOutputScenario) {
        if (!com.myplayer.app.BuildConfig.DEBUG) return
        val shouldIgnoreFocus = scenario != MultiOutputScenario.DEFAULT_SYSTEM
        val targetCat: OutputDeviceCategory? = when (scenario) {
            MultiOutputScenario.DEFAULT_SYSTEM -> null
            MultiOutputScenario.SCENARIO_1_MYPLAYER_BT_REELS_PHONE -> OutputDeviceCategory.BLUETOOTH_A2DP
            MultiOutputScenario.SCENARIO_2_MYPLAYER_SPEAKER_REELS_EARBUDS -> OutputDeviceCategory.PHONE_SPEAKER
            MultiOutputScenario.SCENARIO_3_DUAL_EXTERNAL_SINKS -> OutputDeviceCategory.WIRED_AUX_HEADPHONES
        }

        prefs.edit()
            .putString(KEY_SCENARIO, scenario.id)
            .putBoolean(KEY_IGNORE_FOCUS, shouldIgnoreFocus)
            .apply {
                if (targetCat != null) putString(KEY_PINNED_CATEGORY, targetCat.name)
                else remove(KEY_PINNED_CATEGORY)
                remove(KEY_PINNED_DEVICE_ID)
            }
            .apply()

        _state.update {
            it.copy(
                activeScenario = scenario,
                ignoreAudioFocus = shouldIgnoreFocus,
                pinnedDeviceId = null,
                pinnedCategory = targetCat
            )
        }

        if (scenario == MultiOutputScenario.SCENARIO_1_MYPLAYER_BT_REELS_PHONE) {
            // In Scenario 1, attempt to switch Android's default live-audio route to Phone Speaker
            // so YouTube/Reels uses Phone Speaker, then immediately lock MyPlayer onto Bluetooth!
            trySwitchSystemLiveRouteToPhoneSpeaker()
        }

        refreshConnectedDevicesAndApplyRouting(forceReapply = true)
        scheduleRouteGuardianIfNeeded()

        if (scenario == MultiOutputScenario.SCENARIO_1_MYPLAYER_BT_REELS_PHONE) {
            // Re-assert MyPlayer's Bluetooth lock after system route transition settles
            mainHandler.postDelayed({
                refreshConnectedDevicesAndApplyRouting(forceReapply = true)
            }, 320L)
            mainHandler.postDelayed({
                refreshConnectedDevicesAndApplyRouting(forceReapply = true)
            }, 850L)
        }

        if (shouldIgnoreFocus && _state.value.autoSyncExternalStream && isAnotherMediaAppSimultaneouslyActive()) {
            mainHandler.postDelayed({
                resyncExternalAudioStreams(silent = true)
            }, 380L)
        }
    }

    /**
     * Pins MyPlayer directly to a specific hardware [ConnectedAudioOutputDevice] chosen from the live list,
     * optionally keeping the current scenario if the user selected a speaker inside Scenario 3.
     */
    fun pinSpecificDevice(
        device: ConnectedAudioOutputDevice?,
        keepScenario: MultiOutputScenario? = null
    ) {
        if (!com.myplayer.app.BuildConfig.DEBUG) return
        if (device == null) {
            prefs.edit()
                .putString(KEY_SCENARIO, MultiOutputScenario.DEFAULT_SYSTEM.id)
                .remove(KEY_PINNED_DEVICE_ID)
                .remove(KEY_PINNED_CATEGORY)
                .apply()
            _state.update {
                it.copy(
                    activeScenario = MultiOutputScenario.DEFAULT_SYSTEM,
                    pinnedDeviceId = null,
                    pinnedCategory = null
                )
            }
        } else {
            val enableSimultaneous = true
            val inferredScenario = keepScenario ?: when {
                device.category == OutputDeviceCategory.PHONE_SPEAKER ||
                    device.category == OutputDeviceCategory.EARPIECE ->
                    MultiOutputScenario.SCENARIO_2_MYPLAYER_SPEAKER_REELS_EARBUDS
                device.isLikelySpeaker && _state.value.connectedDevices.count {
                    it.category != OutputDeviceCategory.PHONE_SPEAKER &&
                        it.category != OutputDeviceCategory.EARPIECE
                } >= 2 ->
                    MultiOutputScenario.SCENARIO_3_DUAL_EXTERNAL_SINKS
                device.category == OutputDeviceCategory.BLUETOOTH_A2DP ||
                    device.category == OutputDeviceCategory.BLUETOOTH_LE ->
                    MultiOutputScenario.SCENARIO_1_MYPLAYER_BT_REELS_PHONE
                else ->
                    MultiOutputScenario.SCENARIO_3_DUAL_EXTERNAL_SINKS
            }

            prefs.edit()
                .putString(KEY_SCENARIO, inferredScenario.id)
                .putBoolean(KEY_IGNORE_FOCUS, enableSimultaneous)
                .putInt(KEY_PINNED_DEVICE_ID, device.id)
                .putString(KEY_PINNED_CATEGORY, device.category.name)
                .apply()

            _state.update {
                it.copy(
                    activeScenario = inferredScenario,
                    ignoreAudioFocus = enableSimultaneous,
                    pinnedDeviceId = device.id,
                    pinnedCategory = device.category
                )
            }
        }
        refreshConnectedDevicesAndApplyRouting(forceReapply = true)
        scheduleRouteGuardianIfNeeded()

        if (device != null && _state.value.autoSyncExternalStream && isAnotherMediaAppSimultaneouslyActive()) {
            mainHandler.postDelayed({
                resyncExternalAudioStreams(silent = true)
            }, 300L)
        }
    }

    /**
     * Refreshes the UI device list without unnecessarily resetting ExoPlayer's AudioTrack
     * unless the resolved target device or AudioFocus state actually changed.
     */
    fun refreshConnectedDevicesAndApplyRouting(forceReapply: Boolean = false) {
        if (!com.myplayer.app.BuildConfig.DEBUG) return
        val rawSinks = getRawOutputSinks()
        val mapped = mapRawSinksToUiDevices(rawSinks)

        val current = _state.value
        val resolvedRawInfo: AudioDeviceInfo? = resolveTargetAudioDeviceInfo(
            rawSinks = rawSinks,
            scenario = current.activeScenario,
            pinnedId = current.pinnedDeviceId,
            pinnedCategory = current.pinnedCategory
        )

        val targetDeviceId = resolvedRawInfo?.id ?: -1

        mainHandler.post {
            val player = attachedPlayer
            if (player != null) {
                val playerChanged = (lastAppliedPlayer !== player)
                val focusChanged = (lastAppliedIgnoreFocus != current.ignoreAudioFocus)
                val deviceChanged = (lastAppliedDeviceId != targetDeviceId)

                if (playerChanged || focusChanged) {
                    runCatching {
                        player.setAudioAttributes(baseAudioAttributes, /* handleAudioFocus = */ !current.ignoreAudioFocus)
                        player.setHandleAudioBecomingNoisy(!current.ignoreAudioFocus)
                        lastAppliedIgnoreFocus = current.ignoreAudioFocus
                        Log.d(TAG, "Applied handleAudioFocus=${!current.ignoreAudioFocus}")
                    }.onFailure { e ->
                        Log.w(TAG, "Error updating AudioAttributes: ${e.message}")
                    }
                }

                if (playerChanged || deviceChanged || forceReapply) {
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            player.setPreferredAudioDevice(resolvedRawInfo)
                            lastAppliedDeviceId = targetDeviceId
                            Log.d(TAG, "Pinned ExoPlayer to deviceId=$targetDeviceId (${resolvedRawInfo?.productName})")
                        }
                    }.onFailure { e ->
                        Log.w(TAG, "Error setting preferred audio device: ${e.message}")
                    }
                }
                lastAppliedPlayer = player
            }
        }

        val resolvedId = resolvedRawInfo?.id
        val updatedDevices = mapped.map { dev ->
            dev.copy(isPinnedByMyPlayer = (resolvedId != null && dev.id == resolvedId))
        }

        val pinnedDeviceObj = updatedDevices.firstOrNull { it.isPinnedByMyPlayer }
        val summaryText = when {
            pinnedDeviceObj != null ->
                "MyPlayer -> ${pinnedDeviceObj.productName}" +
                    if (current.ignoreAudioFocus) " • Simultaneous Mode ON" else ""
            current.ignoreAudioFocus ->
                "System Default Output • Simultaneous Mode ON (Plays with Reels)"
            else ->
                "System Default Output (Standard Audio Focus)"
        }

        val hintText = buildStatusHint(
            scenario = current.activeScenario,
            pinned = pinnedDeviceObj,
            allDevices = updatedDevices,
            ignoreFocus = current.ignoreAudioFocus
        )

        _state.update {
            it.copy(
                connectedDevices = updatedDevices,
                activeRouteSummary = summaryText,
                statusHint = hintText
            )
        }
    }

    /**
     * Performs a clean 180ms transient AudioFocus + MediaKey handshake so that when YouTube or
     * Instagram Reels is playing on Bluetooth and MyPlayer is pinned to Phone Speaker (or vice versa),
     * Android's AudioFlinger cleanly detaches and re-attaches the external app's stream on its own
     * hardware mixer thread—eliminating the startup "zig-zag" stutter automatically!
     */
    fun resyncExternalAudioStreams(silent: Boolean = false) {
        if (!com.myplayer.app.BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        if (silent && (now - lastExternalResyncRealtimeMs) < 6000L) {
            return
        }
        lastExternalResyncRealtimeMs = now

        mainHandler.post {
            // 1. Ensure MyPlayer is firmly pinned to its target sink with handleAudioFocus = false
            refreshConnectedDevicesAndApplyRouting(forceReapply = true)

            // 2. Request transient focus for 180ms so the external app (YouTube/Reels) briefly pauses
            //    its AudioTrack, allowing AudioFlinger to separate the Speaker and A2DP HAL clocks.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = PlatformAudioAttributes.Builder()
                    .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                    .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val focusListener = AudioManager.OnAudioFocusChangeListener { }
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(focusListener, mainHandler)
                    .build()

                runCatching {
                    audioManager.requestAudioFocus(focusReq)
                }

                mainHandler.postDelayed({
                    runCatching {
                        audioManager.abandonAudioFocusRequest(focusReq)
                    }
                    // If the external app (e.g. YouTube) paused on LOSS_TRANSIENT and didn't auto-resume
                    // within 200ms of abandonAudioFocusRequest, send a targeted MEDIA_PLAY key event.
                    mainHandler.postDelayed({
                        if (!isAnotherMediaAppSimultaneouslyActive()) {
                            sendTargetedPlayToExternalApp()
                        }
                    }, 200L)
                }, 180L)
            }

            if (!silent) {
                Toast.makeText(
                    appContext,
                    "Synced MyPlayer & YouTube/Reels audio streams!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun isAnotherMediaAppSimultaneouslyActive(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val activeConfigs = runCatching {
                audioManager.activePlaybackConfigurations
            }.getOrDefault(emptyList())
            val myPlayerPlaying = attachedPlayer?.isPlaying == true
            return if (myPlayerPlaying) {
                activeConfigs.size >= 2
            } else {
                activeConfigs.isNotEmpty() || audioManager.isMusicActive
            }
        }
        return audioManager.isMusicActive
    }

    private fun sendTargetedPlayToExternalApp() {
        runCatching {
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
            val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)
        }
    }

    /**
     * Attempts to switch Android's default live-audio route to the Built-in Phone Speaker
     * via [MediaRouter] so that apps without explicit device pinning (like YouTube / Reels)
     * output to the Phone Speaker while MyPlayer explicitly pins its own AudioTrack to Bluetooth.
     */
    private fun trySwitchSystemLiveRouteToPhoneSpeaker() {
        runCatching {
            val mr = appContext.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter ?: return
            val defaultRoute = mr.defaultRoute
            if (defaultRoute != null) {
                mr.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, defaultRoute)
                Log.d(TAG, "Switched MediaRouter live audio route to defaultRoute (${defaultRoute.name})")
            }
        }.onFailure { e ->
            Log.w(TAG, "MediaRouter route switch skipped: ${e.message}")
        }
    }

    private fun scheduleRouteGuardianIfNeeded() {
        mainHandler.removeCallbacks(routeGuardianRunnable)
        val s = _state.value
        if (s.activeScenario != MultiOutputScenario.DEFAULT_SYSTEM || s.pinnedDeviceId != null) {
            mainHandler.postDelayed(routeGuardianRunnable, 1500L)
        }
    }

    private fun getRawOutputSinks(): List<AudioDeviceInfo> {
        return runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
        }.getOrDefault(emptyList())
    }

    private fun mapRawSinksToUiDevices(rawSinks: List<AudioDeviceInfo>): List<ConnectedAudioOutputDevice> {
        val mapped = mutableListOf<ConnectedAudioOutputDevice>()
        val seenKeys = mutableSetOf<String>()

        for (info in rawSinks) {
            val category = categorizeDevice(info.type) ?: continue
            val typeLabel = labelForDeviceType(info.type)
            val rawName = info.productName?.toString()?.trim().orEmpty()
            val friendlyName = when (category) {
                OutputDeviceCategory.PHONE_SPEAKER -> "Phone Built-in Speaker"
                OutputDeviceCategory.EARPIECE -> "Phone Top Earpiece Speaker"
                else -> if (rawName.isNotBlank() && !rawName.equals(Build.MODEL, ignoreCase = true)) {
                    "$rawName ($typeLabel)"
                } else {
                    typeLabel
                }
            }
            val addr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { info.address }.getOrDefault("")
            } else ""

            val likelySpeaker = isDeviceLikelySpeaker(category, info.type, friendlyName)
            val dedupeKey = "${category.name}_${friendlyName}_$addr"
            if (seenKeys.add(dedupeKey)) {
                mapped.add(
                    ConnectedAudioOutputDevice(
                        id = info.id,
                        productName = friendlyName,
                        type = info.type,
                        typeLabel = typeLabel,
                        category = category,
                        address = addr,
                        isLikelySpeaker = likelySpeaker
                    )
                )
            }
        }
        return mapped
    }

    private fun isDeviceLikelySpeaker(
        category: OutputDeviceCategory,
        rawType: Int,
        friendlyName: String
    ): Boolean {
        val lower = friendlyName.lowercase()
        if (EARBUDS_KEYWORDS.any { lower.contains(it) }) return false
        if (SPEAKER_KEYWORDS.any { lower.contains(it) }) return true
        return when (category) {
            OutputDeviceCategory.PHONE_SPEAKER,
            OutputDeviceCategory.HDMI_TV -> true
            OutputDeviceCategory.WIRED_AUX_HEADPHONES,
            OutputDeviceCategory.USB_DAC_SPEAKER -> true
            OutputDeviceCategory.BLUETOOTH_LE -> (rawType == 27 /* TYPE_BLE_SPEAKER */ || rawType == 30)
            else -> false
        }
    }

    private fun resolveTargetAudioDeviceInfo(
        rawSinks: List<AudioDeviceInfo>,
        scenario: MultiOutputScenario,
        pinnedId: Int?,
        pinnedCategory: OutputDeviceCategory?
    ): AudioDeviceInfo? {
        if (scenario == MultiOutputScenario.DEFAULT_SYSTEM && pinnedId == null && pinnedCategory == null) {
            return null
        }

        // 1. Exact hardware device ID match if user tapped a specific device
        if (pinnedId != null) {
            rawSinks.firstOrNull { it.id == pinnedId }?.let { return it }
        }

        // 2. Scenario-specific smart resolution
        return when (scenario) {
            MultiOutputScenario.DEFAULT_SYSTEM -> null

            MultiOutputScenario.SCENARIO_1_MYPLAYER_BT_REELS_PHONE -> {
                // Prefer Bluetooth A2DP or BLE Audio sink for MyPlayer
                rawSinks.firstOrNull {
                    val cat = categorizeDevice(it.type)
                    cat == OutputDeviceCategory.BLUETOOTH_A2DP || cat == OutputDeviceCategory.BLUETOOTH_LE
                }
            }

            MultiOutputScenario.SCENARIO_2_MYPLAYER_SPEAKER_REELS_EARBUDS -> {
                // Prefer Phone Built-in Speaker (or Wired/USB Speaker if user pinned that category)
                if (pinnedCategory == OutputDeviceCategory.WIRED_AUX_HEADPHONES ||
                    pinnedCategory == OutputDeviceCategory.USB_DAC_SPEAKER
                ) {
                    rawSinks.firstOrNull { categorizeDevice(it.type) == pinnedCategory }
                        ?: rawSinks.firstOrNull { categorizeDevice(it.type) == OutputDeviceCategory.PHONE_SPEAKER }
                } else {
                    rawSinks.firstOrNull { categorizeDevice(it.type) == OutputDeviceCategory.PHONE_SPEAKER }
                }
            }

            MultiOutputScenario.SCENARIO_3_DUAL_EXTERNAL_SINKS -> {
                // Scenario 3: MyPlayer on External Speaker • Reels/Movies on Earbuds.
                // External sinks (excluding built-in phone speaker and earpiece):
                val externalSinks = rawSinks.filter {
                    val cat = categorizeDevice(it.type)
                    cat != null &&
                        cat != OutputDeviceCategory.PHONE_SPEAKER &&
                        cat != OutputDeviceCategory.EARPIECE
                }

                when {
                    // Case A: Wired AUX / USB / HDMI Speaker is connected alongside Bluetooth Earbuds
                    externalSinks.any {
                        val c = categorizeDevice(it.type)
                        c == OutputDeviceCategory.WIRED_AUX_HEADPHONES ||
                            c == OutputDeviceCategory.USB_DAC_SPEAKER ||
                            c == OutputDeviceCategory.HDMI_TV
                    } -> {
                        externalSinks.firstOrNull { categorizeDevice(it.type) == OutputDeviceCategory.WIRED_AUX_HEADPHONES }
                            ?: externalSinks.firstOrNull { categorizeDevice(it.type) == OutputDeviceCategory.USB_DAC_SPEAKER }
                            ?: externalSinks.firstOrNull { categorizeDevice(it.type) == OutputDeviceCategory.HDMI_TV }
                    }

                    // Case B: 2 or more Bluetooth / BLE sinks are connected simultaneously (Speaker + Earbuds)
                    externalSinks.size >= 2 -> {
                        // Pick the sink identified as a Speaker (or the first non-earbuds sink)
                        externalSinks.firstOrNull { info ->
                            val cat = categorizeDevice(info.type) ?: OutputDeviceCategory.OTHER_SINK
                            val name = info.productName?.toString().orEmpty()
                            isDeviceLikelySpeaker(cat, info.type, name)
                        } ?: externalSinks.first()
                    }

                    // Case C: Only 1 external sink is connected:
                    // If that single sink is a Speaker (and no Earbuds are connected), use it;
                    // otherwise (if only 1 Bluetooth Earbuds is connected), DO NOT pin MyPlayer to
                    // the same Earbuds that YouTube is using—route MyPlayer to Built-in Speaker until
                    // the second external speaker is connected!
                    externalSinks.size == 1 -> {
                        val onlySink = externalSinks.first()
                        val cat = categorizeDevice(onlySink.type) ?: OutputDeviceCategory.OTHER_SINK
                        val name = onlySink.productName?.toString().orEmpty()
                        if (isDeviceLikelySpeaker(cat, onlySink.type, name)) {
                            onlySink
                        } else {
                            rawSinks.firstOrNull { categorizeDevice(it.type) == OutputDeviceCategory.PHONE_SPEAKER }
                        }
                    }

                    else -> {
                        rawSinks.firstOrNull { categorizeDevice(it.type) == OutputDeviceCategory.PHONE_SPEAKER }
                    }
                }
            }
        }
    }

    private fun buildStatusHint(
        scenario: MultiOutputScenario,
        pinned: ConnectedAudioOutputDevice?,
        allDevices: List<ConnectedAudioOutputDevice>,
        ignoreFocus: Boolean
    ): String {
        val externalDevices = allDevices.filter {
            it.category != OutputDeviceCategory.PHONE_SPEAKER &&
                it.category != OutputDeviceCategory.EARPIECE
        }
        val hasBt = allDevices.any {
            it.category == OutputDeviceCategory.BLUETOOTH_A2DP || it.category == OutputDeviceCategory.BLUETOOTH_LE
        }
        return when (scenario) {
            MultiOutputScenario.DEFAULT_SYSTEM ->
                if (ignoreFocus) "MyPlayer will keep playing when you open Instagram Reels, YouTube, or Movies."
                else "Normal mode: MyPlayer pauses automatically when another app plays audio."

            MultiOutputScenario.SCENARIO_1_MYPLAYER_BT_REELS_PHONE ->
                if (pinned != null && (pinned.category == OutputDeviceCategory.BLUETOOTH_A2DP || pinned.category == OutputDeviceCategory.BLUETOOTH_LE)) {
                    "MyPlayer is locked to ${pinned.productName}! Note: If YouTube/Reels also plays on Bluetooth, tap 'Switch YouTube/Reels to Phone Speaker' inside Scenario 1 and select 'This Phone' — MyPlayer will stay locked on Bluetooth!"
                } else {
                    "Connect your Bluetooth Earbuds/Speaker to lock MyPlayer onto Bluetooth while Reels/YouTube play on Phone Speaker."
                }

            MultiOutputScenario.SCENARIO_2_MYPLAYER_SPEAKER_REELS_EARBUDS ->
                if (pinned != null) {
                    if (hasBt) {
                        "100% Active Split! MyPlayer is locked to ${pinned.productName}, while YouTube/Reels automatically play in your Bluetooth Earbuds with Anti-ZigZag Sync enabled."
                    } else {
                        "MyPlayer is locked to ${pinned.productName}. Connect your Bluetooth Earbuds so YouTube/Reels route to your Earbuds automatically."
                    }
                } else {
                    "MyPlayer is routed to Phone Speaker while Bluetooth handles YouTube/Reels."
                }

            MultiOutputScenario.SCENARIO_3_DUAL_EXTERNAL_SINKS ->
                if (externalDevices.size >= 2 && pinned != null) {
                    val otherSinks = externalDevices.filter { it.id != pinned.id }
                    val otherLabel = otherSinks.firstOrNull()?.productName ?: "your second device"
                    "Dual Device Split Active! MyPlayer is locked to ${pinned.productName}, while $otherLabel plays YouTube/Reels. You can also tap any device below to swap."
                } else if (externalDevices.size == 1 && pinned != null) {
                    "Currently 1 external device (${externalDevices.first().productName}) is active in Android Audio. MyPlayer is routed to ${pinned.productName} so both apps stay separated. Connect your 2nd device (AUX/USB/Dual-BT Speaker) or tap a device below to pin MyPlayer."
                } else {
                    "Connect both your Speaker and Earbuds, then tap your Speaker below to lock MyPlayer to it."
                }
        }
    }

    private fun categorizeDevice(type: Int): OutputDeviceCategory? {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputDeviceCategory.PHONE_SPEAKER
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputDeviceCategory.EARPIECE
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> OutputDeviceCategory.BLUETOOTH_A2DP
            26 /* TYPE_BLE_HEADSET */,
            27 /* TYPE_BLE_SPEAKER */,
            30 /* TYPE_BLE_BROADCAST */ -> OutputDeviceCategory.BLUETOOTH_LE
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE -> OutputDeviceCategory.WIRED_AUX_HEADPHONES
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            22 /* TYPE_USB_HEADSET */ -> OutputDeviceCategory.USB_DAC_SPEAKER
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            29 /* TYPE_HDMI_EARC */ -> OutputDeviceCategory.HDMI_TV
            else -> null
        }
    }

    private fun labelForDeviceType(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone Speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            26 -> "Bluetooth LE Audio Headset"
            27 -> "Bluetooth LE Audio Speaker"
            30 -> "Bluetooth Auracast"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "3.5mm AUX / Headphones"
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_AUX_LINE -> "AUX Line-Out Speaker"
            AudioDeviceInfo.TYPE_USB_DEVICE,
            22 -> "USB-C DAC / Speaker"
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            29 -> "HDMI / TV Audio"
            else -> "Audio Sink"
        }
    }

    /**
     * Opens Android 11–15's System Media Output Switcher for system/external apps (YouTube / Reels)
     * WITHOUT passing MyPlayer's package name so changing the route to "This Phone / Phone Speaker"
     * moves YouTube/Reels to the Phone Speaker while MyPlayer's Route Guardian keeps MyPlayer on Bluetooth!
     */
    fun openSystemMediaOutputSwitcher(activityOrContext: Context) {
        if (!com.myplayer.app.BuildConfig.DEBUG) return
        // First try programmatic live route switch to Phone Speaker if in Scenario 1
        if (_state.value.activeScenario == MultiOutputScenario.SCENARIO_1_MYPLAYER_BT_REELS_PHONE) {
            trySwitchSystemLiveRouteToPhoneSpeaker()
        }

        // IMPORTANT: Do NOT pass MyPlayer's packageName to EXTRA_PACKAGE_NAME!
        // Passing MyPlayer's package name scopes the Android output switcher to MyPlayer's session
        // instead of switching the system/YouTube/Reels output route.
        val candidates = listOf(
            Intent("com.android.settings.panel.action.MEDIA_OUTPUT"),
            Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG"),
            Intent("android.settings.MEDIA_OUTPUT"),
            Intent(Settings.ACTION_SOUND_SETTINGS)
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val launched = runCatching {
                activityOrContext.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launched) {
                Toast.makeText(
                    activityOrContext,
                    "Select 'This Phone / Speaker' for YouTube/Reels — MyPlayer stays locked to your pinned output!",
                    Toast.LENGTH_LONG
                ).show()
                // Schedule Route Guardian re-assertion while user interacts with the system panel
                mainHandler.postDelayed({ refreshConnectedDevicesAndApplyRouting(forceReapply = true) }, 1200L)
                mainHandler.postDelayed({ refreshConnectedDevicesAndApplyRouting(forceReapply = true) }, 3000L)
                mainHandler.postDelayed({ refreshConnectedDevicesAndApplyRouting(forceReapply = true) }, 5500L)
                return
            }
        }
        Toast.makeText(
            activityOrContext,
            "Swipe down Quick Settings -> tap 'Media Output' -> choose 'This Phone' for Reels/YouTube.",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Opens OEM Separate App Sound / Multi-Audio settings (Samsung One UI Separate App Sound,
     * Xiaomi HyperOS/MIUI Sound Assistant Multiple Audio Sources, OnePlus/Realme Multi-Volume).
     */
    fun openOemSeparateAppSoundSettings(activityOrContext: Context) {
        if (!com.myplayer.app.BuildConfig.DEBUG) return
        val oemIntents = listOf(
            Intent().setClassName(
                "com.android.settings",
                "com.android.settings.Settings\$SeparateAppSoundSettingsActivity"
            ),
            Intent("com.samsung.android.settings.action.SEPARATE_APP_SOUND_SETTINGS"),
            Intent().setClassName(
                "com.miui.misound",
                "com.miui.misound.HeadsetSettingsActivity"
            ),
            Intent(Settings.ACTION_SOUND_SETTINGS)
        )
        for (intent in oemIntents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching {
                activityOrContext.startActivity(intent)
                true
            }.getOrDefault(false)
            if (ok) {
                Toast.makeText(
                    activityOrContext,
                    "Turn on Separate App Sound -> Select MyPlayer -> Bluetooth Device",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }
    }
}
