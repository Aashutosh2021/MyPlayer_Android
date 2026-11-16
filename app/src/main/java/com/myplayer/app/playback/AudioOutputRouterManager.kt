package com.myplayer.app.playback

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
        subtitle = "Pins MyPlayer to Bluetooth Earbuds/Speaker & plays simultaneously with Reels/Movies on Phone Speaker",
        badge = "BT + Phone"
    ),
    SCENARIO_2_MYPLAYER_SPEAKER_REELS_EARBUDS(
        id = "myplayer_speaker_reels_earbuds",
        title = "Scenario 2: MyPlayer on Phone/Wired Speaker • Reels on Earbuds",
        subtitle = "Routes MyPlayer directly to Phone/AUX/USB Speaker while Reels/Movies play in your Bluetooth Earbuds",
        badge = "Speaker + Buds"
    ),
    SCENARIO_3_DUAL_EXTERNAL_SINKS(
        id = "dual_speaker_and_earbuds",
        title = "Scenario 3: Dual Connected Devices (Speaker + Earbuds Split)",
        subtitle = "Pins MyPlayer to External Speaker (AUX/USB/BLE/Cast) while Earbuds play Reels/Movies",
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
    val isPinnedByMyPlayer: Boolean = false
)

data class AudioOutputRouterState(
    val activeScenario: MultiOutputScenario = MultiOutputScenario.DEFAULT_SYSTEM,
    val ignoreAudioFocus: Boolean = false,
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
 * Implements:
 * 1. Live hardware output sink discovery via [AudioManager.getDevices] and [AudioDeviceCallback].
 * 2. Direct per-app hardware output pinning via [ExoPlayer.setPreferredAudioDevice] (API 23+).
 * 3. Live Simultaneous Playback (Reels / YouTube / Movies + MyPlayer at the same time) by
 *    dynamically toggling `handleAudioFocus = !ignoreAudioFocus` on [ExoPlayer.setAudioAttributes].
 * 4. Independent MyPlayer app volume scaling (`0.05f..1.0f`) read in real time by `MyPlayerVolumeSink`.
 * 5. One-tap Scenario Presets (Scenario 1: MyPlayer BT + Reels Phone Speaker; Scenario 2: MyPlayer
 *    Speaker + Reels Earbuds; Scenario 3: 2 Connected Devices Speaker + Earbuds Split) and
 *    System Media Output / OEM Separate App Sound launchers.
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
        private const val KEY_APP_VOLUME = "independent_app_volume"
        private const val KEY_PINNED_CATEGORY = "pinned_device_category"
        private const val KEY_PINNED_DEVICE_ID = "pinned_device_id"

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

    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<AudioOutputRouterState> = _state.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshConnectedDevicesAndApplyRouting()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshConnectedDevicesAndApplyRouting()
        }
    }

    init {
        instance = this
        currentIndependentVolumeMultiplier = _state.value.independentAppVolume
        runCatching {
            audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        }
        refreshConnectedDevicesAndApplyRouting()
    }

    private fun loadInitialState(): AudioOutputRouterState {
        val scenario = MultiOutputScenario.fromId(prefs.getString(KEY_SCENARIO, null))
        val ignoreFocus = prefs.getBoolean(KEY_IGNORE_FOCUS, scenario != MultiOutputScenario.DEFAULT_SYSTEM)
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
            independentAppVolume = appVol,
            pinnedDeviceId = savedId,
            pinnedCategory = savedCat
        )
    }

    fun attachExoPlayer(player: ExoPlayer, attributes: AudioAttributes) {
        this.attachedPlayer = player
        this.baseAudioAttributes = attributes
        mainHandler.post {
            refreshConnectedDevicesAndApplyRouting()
        }
    }

    /**
     * Enables or disables Simultaneous Playback with Reels / YouTube / Movies
     * by dynamically updating ExoPlayer's `handleAudioFocus` flag.
     */
    fun setIgnoreAudioFocus(ignore: Boolean) {
        prefs.edit().putBoolean(KEY_IGNORE_FOCUS, ignore).apply()
        _state.update { it.copy(ignoreAudioFocus = ignore) }
        applyAudioFocusToPlayer(ignore)
        refreshConnectedDevicesAndApplyRouting()
    }

    /**
     * Adjusts MyPlayer's independent in-app volume (`0.05f..1.0f`) so the user can balance
     * song loudness against Reels/Movie volume without altering Android's system volume.
     */
    fun setIndependentAppVolume(volume: Float) {
        val clamped = volume.coerceIn(0.05f, 1.0f)
        currentIndependentVolumeMultiplier = clamped
        prefs.edit().putFloat(KEY_APP_VOLUME, clamped).apply()
        _state.update { it.copy(independentAppVolume = clamped) }
        mainHandler.post {
            attachedPlayer?.let { player ->
                // Trigger MyPlayerVolumeSink recalculation
                val currentVol = player.volume
                player.volume = currentVol
            }
        }
    }

    /**
     * Applies one of the 3 user scenarios (or restores Default System Routing).
     */
    fun applyScenario(scenario: MultiOutputScenario) {
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
        applyAudioFocusToPlayer(shouldIgnoreFocus)
        refreshConnectedDevicesAndApplyRouting()
    }

    /**
     * Pins MyPlayer directly to a specific hardware [AudioDeviceInfo] chosen from the live list.
     * Pass `null` to return to automatic system routing.
     */
    fun pinSpecificDevice(device: ConnectedAudioOutputDevice?) {
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
            // Automatically enable simultaneous playback when user locks a specific hardware sink
            val enableSimultaneous = true
            val inferredScenario = when (device.category) {
                OutputDeviceCategory.BLUETOOTH_A2DP,
                OutputDeviceCategory.BLUETOOTH_LE -> MultiOutputScenario.SCENARIO_1_MYPLAYER_BT_REELS_PHONE
                OutputDeviceCategory.PHONE_SPEAKER,
                OutputDeviceCategory.EARPIECE -> MultiOutputScenario.SCENARIO_2_MYPLAYER_SPEAKER_REELS_EARBUDS
                OutputDeviceCategory.WIRED_AUX_HEADPHONES,
                OutputDeviceCategory.USB_DAC_SPEAKER,
                OutputDeviceCategory.HDMI_TV,
                OutputDeviceCategory.OTHER_SINK -> MultiOutputScenario.SCENARIO_3_DUAL_EXTERNAL_SINKS
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
            applyAudioFocusToPlayer(enableSimultaneous)
        }
        refreshConnectedDevicesAndApplyRouting()
    }

    private fun applyAudioFocusToPlayer(ignoreFocus: Boolean) {
        mainHandler.post {
            val player = attachedPlayer ?: return@post
            runCatching {
                player.setAudioAttributes(baseAudioAttributes, /* handleAudioFocus = */ !ignoreFocus)
                player.setHandleAudioBecomingNoisy(!ignoreFocus)
                Log.d(TAG, "Updated ExoPlayer handleAudioFocus=${!ignoreFocus}")
            }.onFailure { e ->
                Log.w(TAG, "Failed to update AudioFocus mode: ${e.message}")
            }
        }
    }

    fun refreshConnectedDevicesAndApplyRouting() {
        val rawSinks = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
        }.getOrDefault(emptyList())

        // Filter out telephony-only / internal duplicate sinks and map to friendly descriptors
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

            val dedupeKey = "${category.name}_${friendlyName}_$addr"
            if (seenKeys.add(dedupeKey)) {
                mapped.add(
                    ConnectedAudioOutputDevice(
                        id = info.id,
                        productName = friendlyName,
                        type = info.type,
                        typeLabel = typeLabel,
                        category = category,
                        address = addr
                    )
                )
            }
        }

        val current = _state.value
        val resolvedRawInfo: AudioDeviceInfo? = resolveTargetAudioDeviceInfo(
            rawSinks = rawSinks,
            scenario = current.activeScenario,
            pinnedId = current.pinnedDeviceId,
            pinnedCategory = current.pinnedCategory
        )

        mainHandler.post {
            val player = attachedPlayer
            if (player != null) {
                runCatching {
                    player.setAudioAttributes(baseAudioAttributes, !current.ignoreAudioFocus)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        player.setPreferredAudioDevice(resolvedRawInfo)
                    }
                }.onFailure { e ->
                    Log.w(TAG, "Error setting preferred audio device: ${e.message}")
                }
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

        val hintText = buildStatusHint(current.activeScenario, pinnedDeviceObj, updatedDevices, current.ignoreAudioFocus)

        _state.update {
            it.copy(
                connectedDevices = updatedDevices,
                activeRouteSummary = summaryText,
                statusHint = hintText
            )
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
                // When 2 external devices are connected (e.g., Speaker + Earbuds):
                // Priority: Wired AUX Speaker -> USB DAC Speaker -> BLE Speaker -> Secondary BT Sink -> Phone Speaker
                rawSinks.firstOrNull { categorizeDevice(it.type) == OutputDeviceCategory.WIRED_AUX_HEADPHONES }
                    ?: rawSinks.firstOrNull { categorizeDevice(it.type) == OutputDeviceCategory.USB_DAC_SPEAKER }
                    ?: rawSinks.firstOrNull { it.type == 27 /* TYPE_BLE_SPEAKER */ }
                    ?: rawSinks.lastOrNull {
                        val cat = categorizeDevice(it.type)
                        cat == OutputDeviceCategory.BLUETOOTH_A2DP || cat == OutputDeviceCategory.BLUETOOTH_LE
                    }
                    ?: rawSinks.firstOrNull { categorizeDevice(it.type) == OutputDeviceCategory.PHONE_SPEAKER }
            }
        }
    }

    private fun buildStatusHint(
        scenario: MultiOutputScenario,
        pinned: ConnectedAudioOutputDevice?,
        allDevices: List<ConnectedAudioOutputDevice>,
        ignoreFocus: Boolean
    ): String {
        val hasBt = allDevices.any {
            it.category == OutputDeviceCategory.BLUETOOTH_A2DP || it.category == OutputDeviceCategory.BLUETOOTH_LE
        }
        val hasWiredOrUsb = allDevices.any {
            it.category == OutputDeviceCategory.WIRED_AUX_HEADPHONES || it.category == OutputDeviceCategory.USB_DAC_SPEAKER
        }
        return when (scenario) {
            MultiOutputScenario.DEFAULT_SYSTEM ->
                if (ignoreFocus) "MyPlayer will keep playing when you open Instagram Reels or Movies."
                else "Normal mode: MyPlayer pauses automatically when another app plays audio."

            MultiOutputScenario.SCENARIO_1_MYPLAYER_BT_REELS_PHONE ->
                if (pinned != null && (pinned.category == OutputDeviceCategory.BLUETOOTH_A2DP || pinned.category == OutputDeviceCategory.BLUETOOTH_LE)) {
                    "MyPlayer is locked to ${pinned.productName}. Tap 'System Media Output' below and select 'Phone Speaker' so Reels/Movies play from your phone speaker!"
                } else {
                    "Connect your Bluetooth Earbuds/Speaker to lock MyPlayer onto Bluetooth while Reels play on Phone Speaker."
                }

            MultiOutputScenario.SCENARIO_2_MYPLAYER_SPEAKER_REELS_EARBUDS ->
                if (pinned != null) {
                    if (hasBt) {
                        "100% Active Split! MyPlayer is locked to ${pinned.productName}, while Instagram Reels & Movies automatically play in your connected Bluetooth Earbuds."
                    } else {
                        "MyPlayer is locked to ${pinned.productName}. Connect your Bluetooth Earbuds so Reels/Movies route to your Earbuds automatically."
                    }
                } else {
                    "MyPlayer is routed to Phone Speaker while Bluetooth handles Reels/Movies."
                }

            MultiOutputScenario.SCENARIO_3_DUAL_EXTERNAL_SINKS ->
                if (hasBt && hasWiredOrUsb && pinned != null) {
                    "Dual External Hardware Split Active! MyPlayer -> ${pinned.productName}, while Reels/Movies play on Bluetooth."
                } else if (pinned != null) {
                    "MyPlayer pinned to ${pinned.productName}. For 2 wireless speakers + earbuds, use AUX/USB/BLE or Wi-Fi DLNA Cast for the Speaker and Bluetooth for Earbuds."
                } else {
                    "Select any connected Speaker below for MyPlayer while keeping Earbuds for Reels/Movies."
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
     * Opens Android 11–15's native System Media Output Switcher panel so the user can switch
     * the default route (used by Instagram Reels / YouTube / Movies) between Phone Speaker and Bluetooth.
     */
    fun openSystemMediaOutputSwitcher(activityOrContext: Context) {
        val candidates = listOf(
            Intent("com.android.settings.panel.action.MEDIA_OUTPUT").apply {
                putExtra("com.android.settings.panel.extra.PACKAGE_NAME", activityOrContext.packageName)
            },
            Intent("com.android.settings.panel.action.MEDIA_OUTPUT"),
            Intent("android.settings.MEDIA_OUTPUT"),
            Intent(Settings.ACTION_SOUND_SETTINGS)
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val launched = runCatching {
                activityOrContext.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launched) return
        }
        Toast.makeText(
            activityOrContext,
            "Open your notification shade and tap 'Media Output' to switch Reels/Movie audio.",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Opens OEM Separate App Sound / Multi-Audio settings (Samsung One UI Separate App Sound,
     * Xiaomi HyperOS/MIUI Sound Assistant Multiple Audio Sources, OnePlus/Realme Multi-Volume).
     */
    fun openOemSeparateAppSoundSettings(activityOrContext: Context) {
        val oemIntents = listOf(
            // Samsung One UI Separate App Sound direct activity
            Intent().setClassName(
                "com.android.settings",
                "com.android.settings.Settings\$SeparateAppSoundSettingsActivity"
            ),
            Intent("com.samsung.android.settings.action.SEPARATE_APP_SOUND_SETTINGS"),
            // Xiaomi / Redmi / POCO Sound Assistant
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
                    "Enable Separate App Sound / Multiple Audio Sources for MyPlayer",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }
    }
}
