package com.myplayer.app.playback

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.flac.VorbisComment
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import kotlin.math.pow

private const val KEY_TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN"
private const val KEY_ALBUM_GAIN = "REPLAYGAIN_ALBUM_GAIN"
private const val KEY_TRACK_PEAK = "REPLAYGAIN_TRACK_PEAK"
private const val KEY_ALBUM_PEAK = "REPLAYGAIN_ALBUM_PEAK"

data class ReplayGainTags(
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
) {
    val hasGain: Boolean get() = trackGainDb != null || albumGainDb != null
}

@UnstableApi
object LoudnessNormalizer {

    fun parseDbString(value: String?): Float? {
        if (value.isNullOrBlank()) return null
        var text = value.trim().lowercase()
        if (text.endsWith("db")) text = text.removeSuffix("db").trim()
        val parsed = text.toFloatOrNull() ?: return null
        if (!parsed.isFinite()) return null
        if (parsed < -51f || parsed > 51f) return null
        return parsed
    }

    fun parsePeakString(value: String?): Float? {
        if (value.isNullOrBlank()) return null
        val text = value.trim()
        if (text.lowercase().endsWith("db")) {
            val db = parseDbString(text) ?: return null
            val linear = 10f.pow(db / 20f)
            if (!linear.isFinite() || linear <= 0f || linear > 100f) return null
            return linear
        }
        val linear = text.toFloatOrNull() ?: return null
        if (!linear.isFinite() || linear <= 0f || linear > 100f) return null
        return linear
    }

    fun parseFromMap(tags: Map<String, String>): ReplayGainTags {
        if (tags.isEmpty()) return ReplayGainTags()
        val normalized = HashMap<String, String>(tags.size)
        for ((key, value) in tags) {
            val clean = key.trim().uppercase()
            if (clean.isNotEmpty() && !normalized.containsKey(clean)) {
                normalized[clean] = value
            }
        }
        return ReplayGainTags(
            trackGainDb = parseDbString(normalized[KEY_TRACK_GAIN]),
            albumGainDb = parseDbString(normalized[KEY_ALBUM_GAIN]),
            trackPeak = parsePeakString(normalized[KEY_TRACK_PEAK]),
            albumPeak = parsePeakString(normalized[KEY_ALBUM_PEAK]),
        )
    }

    fun parseFromFormat(format: Format?): ReplayGainTags {
        val metadata = format?.metadata ?: return ReplayGainTags()
        if (metadata.length() == 0) return ReplayGainTags()
        val tags = HashMap<String, String>()
        for (index in 0 until metadata.length()) {
            when (val entry = metadata.get(index)) {
                is VorbisComment -> {
                    val key = entry.key.trim()
                    if (key.isNotEmpty() && !tags.containsKey(key)) {
                        tags[key] = entry.value
                    }
                }
                is TextInformationFrame -> {
                    val key = if (entry.id == "TXXX") entry.description.orEmpty() else entry.id
                    val value = entry.value?.ifBlank { entry.values.firstOrNull().orEmpty() }.orEmpty()
                    if (key.isNotBlank() && value.isNotBlank() && !tags.containsKey(key)) {
                        tags[key] = value
                    }
                }
                else -> Unit
            }
        }
        return parseFromMap(tags)
    }
}
