package com.myplayer.app.aria.providers

import com.myplayer.app.aria.model.AriaGeneralResponse
import com.myplayer.app.aria.model.AriaStatus
import com.myplayer.app.data.preferences.AudioPlaybackStore
import com.myplayer.app.data.preferences.Eq15Preset
import com.myplayer.app.playback.dsp.MasterAudioDspProcessor
import com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge
import com.myplayer.app.ui.myplayer.MyPlayerEqPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EqualizerProvider @Inject constructor(
    private val masterAudioDspProcessor: MasterAudioDspProcessor,
    private val audioPlaybackStore: AudioPlaybackStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun applyPreset(presetName: String, commandOrdinal: Int, transactionId: String?): AriaGeneralResponse {
        val clean = presetName.trim()
        val matched15 = Eq15Preset.entries.firstOrNull {
            it.name.equals(clean, ignoreCase = true) || it.label.equals(clean, ignoreCase = true)
        }
        val matched10 = MyPlayerEqPreset.entries.firstOrNull {
            it.name.equals(clean, ignoreCase = true) || it.label.equals(clean, ignoreCase = true)
        }

        return when {
            matched15 != null -> {
                scope.launch {
                    audioPlaybackStore.setEqPreset(matched15)
                }
                AriaGeneralResponse(
                    AriaStatus.SUCCESS,
                    commandOrdinal,
                    transactionId,
                    "Applied 15-band equalizer preset: ${matched15.label}"
                )
            }
            matched10 != null -> {
                MyPlayerAudioFxDspBridge.setEq(true, matched10.gains.toList(), 0f)
                AriaGeneralResponse(
                    AriaStatus.SUCCESS,
                    commandOrdinal,
                    transactionId,
                    "Applied 10-band equalizer preset: ${matched10.label}"
                )
            }
            else -> {
                AriaGeneralResponse(
                    AriaStatus.FAILURE,
                    commandOrdinal,
                    transactionId,
                    "Unknown equalizer preset '$presetName'"
                )
            }
        }
    }

    fun applyCustomBands(bands: FloatArray, commandOrdinal: Int, transactionId: String?): AriaGeneralResponse {
        if (bands.isEmpty()) {
            return AriaGeneralResponse(AriaStatus.FAILURE, commandOrdinal, transactionId, "Empty EQ bands array")
        }
        if (bands.size == 10) {
            MyPlayerAudioFxDspBridge.setEq(true, bands.toList(), 0f)
        } else {
            val gains10 = FloatArray(10) { bands.getOrElse(it) { 0f }.coerceIn(-12f, 12f) }
            masterAudioDspProcessor.updateFromAudioLab10Band(true, gains10, 0f, true)
        }
        return AriaGeneralResponse(
            AriaStatus.SUCCESS,
            commandOrdinal,
            transactionId,
            "Applied custom equalizer bands (${bands.size} bands)"
        )
    }
}