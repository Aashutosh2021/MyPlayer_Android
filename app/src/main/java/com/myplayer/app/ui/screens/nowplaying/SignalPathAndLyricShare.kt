package com.myplayer.app.ui.screens.nowplaying

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.myplayer.app.playback.AudioQualityInfo
import com.myplayer.app.playback.UsbDacStatus
import java.io.File
import java.io.FileOutputStream

/**
 * 1. OpenTune Audiophile Signal Path Inspector Dialog for MyPlayer.
 */
@Composable
fun SignalPathInspectorDialog(
    qualityInfo: AudioQualityInfo,
    eqEnabled: Boolean = false,
    spatialEnabled: Boolean = false,
    autoEqModel: String? = null,
    usbStatus: UsbDacStatus = UsbDacStatus(),
    onDismiss: () -> Unit
) {
    val bitrateText = qualityInfo.bitrateKbps?.let { " ($it kbps)" }.orEmpty()
    val sampleRateText = qualityInfo.sampleRateHz?.let { "$it Hz" } ?: "44100 Hz"
    val bitDepthText = qualityInfo.bitDepth?.let { "$it-bit" } ?: "16-bit"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF14161F),
        titleContentColor = Color.White,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color(0xFF00E676))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Audio Signal Path", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        text = if (usbStatus.bitPerfectActive) "Bit-Perfect Lossless Pipeline" else "High-Precision 64-Bit DSP Pipeline",
                        color = Color(0xFF00E676),
                        fontSize = 12.sp
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SignalNodeRow(
                    step = "1. Source Stream",
                    title = "${qualityInfo.format.ifBlank { "AUTO" }}$bitrateText",
                    subtitle = if (qualityInfo.isLossless) "Verified Lossless Stream (${qualityInfo.sourceName})" else "${qualityInfo.sourceName} (9s Rolling Buffer)"
                )
                SignalNodeRow(
                    step = "2. Audio Decoder",
                    title = if (qualityInfo.format.contains("EAC3", true)) "Android Dolby Atmos HAL" else "Media3 / FFmpeg Decoder",
                    subtitle = "$sampleRateText • $bitDepthText PCM"
                )
                SignalNodeRow(
                    step = "3. MyPlayer DSP Engine",
                    title = buildString {
                        val active = mutableListOf<String>()
                        if (eqEnabled) active.add("10-Band Rainbow EQ")
                        if (!autoEqModel.isNullOrBlank()) active.add("AutoEq ($autoEqModel)")
                        if (spatialEnabled) active.add("3D Spatial Audio")
                        if (active.isEmpty()) append("Direct Bypass (0 dB Bit-Transparent)") else append(active.joinToString(" + "))
                    },
                    subtitle = "MasterAudioDspProcessor + ReplayGain Normalization"
                )
                SignalNodeRow(
                    step = "4. Hardware Output",
                    title = if (usbStatus.connected) (usbStatus.deviceName ?: "USB-C External DAC") else "System AudioTrack Sink",
                    subtitle = if (usbStatus.bitPerfectActive) "MIXER_BEHAVIOR_BIT_PERFECT (${usbStatus.activeSampleRateHz} Hz)" else "Standard Low-Latency AudioTrack"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFF00E676))
            }
        }
    )
}

@Composable
private fun SignalNodeRow(step: String, title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(12.dp)
    ) {
        Text(step, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

/**
 * 2. OpenTune Shareable Instagram/WhatsApp Lyric Card Generator for MyPlayer.
 */
object LyricCardExporter {
    fun shareLyricCardPng(
        context: Context,
        songTitle: String,
        artistName: String,
        selectedLines: List<String>
    ) {
        val width = 1080
        val height = 1350
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(0xFF0F172A.toInt(), 0xFF1E1B4B.toInt(), 0xFF064E3B.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val cardRect = RectF(72f, 120f, width - 72f, height - 120f)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }
        canvas.drawRoundRect(cardRect, 56f, 56f, cardPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 48f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB3FFFFFF.toInt()
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        canvas.drawText(songTitle.take(32), 130f, 230f, titlePaint)
        canvas.drawText(artistName.take(40), 130f, 285f, artistPaint)

        val lyricPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 54f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        var y = 440f
        for (line in selectedLines.take(5)) {
            val words = line.split(" ")
            var currentLine = StringBuilder()
            for (w in words) {
                val test = if (currentLine.isEmpty()) w else "$currentLine $w"
                if (lyricPaint.measureText(test) > (width - 260f)) {
                    canvas.drawText(currentLine.toString(), 130f, y, lyricPaint)
                    y += 74f
                    currentLine = StringBuilder(w)
                } else {
                    currentLine = StringBuilder(test)
                }
            }
            if (currentLine.isNotEmpty()) {
                canvas.drawText(currentLine.toString(), 130f, y, lyricPaint)
                y += 92f
            }
        }

        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00E676.toInt()
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("♪ Shared via MyPlayer", 130f, height - 180f, footerPaint)

        val shareDir = File(context.cacheDir, "shared_lyrics").apply { mkdirs() }
        val outFile = File(shareDir, "myplayer_lyric_card.png")
        FileOutputStream(outFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Lyric Card"))
    }
}
