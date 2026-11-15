package com.myplayer.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class UsbDacStatus(
    val connected: Boolean = false,
    val deviceName: String? = null,
    val activeSampleRateHz: Int = 0,
    val bitPerfectActive: Boolean = false
)

/**
 * OpenTune Bit-Perfect USB DAC Output Controller ported to MyPlayer.
 */
@Singleton
class BitPerfectUsbController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BitPerfectUsb"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _status = MutableStateFlow(UsbDacStatus())
    val status: StateFlow<UsbDacStatus> = _status.asStateFlow()

    @Volatile
    var isEnabled: Boolean = false
        private set

    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshUsbState()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshUsbState()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        refreshUsbState()
    }

    fun setBitPerfectEnabled(enabled: Boolean, streamSampleRateHz: Int = 44100) {
        isEnabled = enabled
        if (!enabled) {
            clearBitPerfectMixer()
        } else {
            applyBitPerfectForRate(streamSampleRateHz)
        }
    }

    fun applyBitPerfectForRate(sampleRateHz: Int): Boolean {
        val dac = findConnectedUsbDac()
        if (dac == null || !isEnabled || Build.VERSION.SDK_INT < 34) {
            _status.value = UsbDacStatus(
                connected = dac != null,
                deviceName = dac?.productName?.toString(),
                activeSampleRateHz = 0,
                bitPerfectActive = false
            )
            return false
        }

        val supported = dac.sampleRates
        val targetRate = when {
            supported.isEmpty() || sampleRateHz in supported -> sampleRateHz
            supported.any { it % sampleRateHz == 0 } -> supported.filter { it % sampleRateHz == 0 }.minOrNull() ?: sampleRateHz
            else -> supported.maxOrNull() ?: sampleRateHz
        }

        for (encoding in intArrayOf(AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_16BIT)) {
            val format = runCatching {
                AudioFormat.Builder()
                    .setSampleRate(targetRate)
                    .setEncoding(encoding)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            }.getOrNull() ?: continue

            val mixerAttrs = AudioMixerAttributes.Builder(format)
                .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                .build()

            val applied = runCatching {
                audioManager.setPreferredMixerAttributes(mediaAttributes, dac, mixerAttrs)
            }.getOrDefault(false)

            if (applied) {
                Log.d(TAG, "Bit-perfect USB active on ${dac.productName} @ ${targetRate}Hz")
                _status.value = UsbDacStatus(
                    connected = true,
                    deviceName = dac.productName?.toString(),
                    activeSampleRateHz = targetRate,
                    bitPerfectActive = true
                )
                return true
            }
        }
        return false
    }

    private fun clearBitPerfectMixer() {
        if (Build.VERSION.SDK_INT >= 34) {
            findConnectedUsbDac()?.let { dac ->
                runCatching { audioManager.clearPreferredMixerAttributes(mediaAttributes, dac) }
            }
        }
        refreshUsbState()
    }

    private fun refreshUsbState() {
        val dac = findConnectedUsbDac()
        _status.value = _status.value.copy(
            connected = dac != null,
            deviceName = dac?.productName?.toString(),
            bitPerfectActive = if (dac == null) false else _status.value.bitPerfectActive
        )
    }

    private fun findConnectedUsbDac(): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
    }
}
