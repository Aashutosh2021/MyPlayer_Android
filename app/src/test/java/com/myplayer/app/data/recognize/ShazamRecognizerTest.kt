package com.myplayer.app.data.recognize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

class ShazamRecognizerTest {
    private fun melody(seconds: Int): ShortArray {
        val notes = doubleArrayOf(330.0, 880.0, 2200.0, 4400.0, 440.0, 1200.0, 3000.0, 5000.0, 400.0, 700.0, 1800.0, 3900.0)
        return ShortArray(16_000 * seconds) { i ->
            val slot = i / 1920
            val inSlot = i % 1920
            if (inSlot > 960) 0 else (sin(2 * PI * notes[(slot * 5) % notes.size] * i / 16_000) * 12000).toInt().toShort()
        }
    }

    @Test
    fun signatureMatchesShazamBinaryFormat() {
        val sig = OpenTuneShazamSignature()
        val pcm = melody(6)
        var i = 0
        var chunk = 1000
        while (i < pcm.size) {
            val piece = pcm.copyOfRange(i, minOf(pcm.size, i + chunk))
            sig.add(piece)
            i += piece.size
            chunk = if (chunk == 1000) 777 else 1000
        }
        assertEquals(6000L, sig.durationMs)
        assertTrue("expected >20 peaks, got ${sig.peakCount}", sig.peakCount > 20)

        val bytes = sig.encode()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xCAFE2580.toInt(), b.getInt(0))
        assertEquals(0x94119C00.toInt(), b.getInt(12))
        assertEquals(3 shl 27, b.getInt(28))
        assertEquals((96_000 + 16_000 * 0.24).toInt(), b.getInt(40))
        assertEquals(bytes.size - 48, b.getInt(8))
        assertEquals(0x40000000, b.getInt(48))
        val crc = CRC32().apply { update(bytes, 8, bytes.size - 8) }.value.toInt()
        assertEquals(crc, b.getInt(4))

        var at = 56
        val bands = mutableListOf<Int>()
        while (at < bytes.size) {
            val marker = b.getInt(at)
            assertEquals(0x60030040, marker and 0xFFFFFFF0.toInt())
            bands += marker - 0x60030040
            val length = b.getInt(at + 4)
            at += 8 + length + (4 - length % 4) % 4
        }
        assertEquals(bytes.size, at)
        assertEquals(listOf(0, 1, 2, 3), bands)
    }

    @Test
    fun silenceGeneratesZeroPeaks() {
        val sig = OpenTuneShazamSignature()
        sig.add(ShortArray(16_000 * 3))
        assertEquals(0, sig.peakCount)
    }

    @Test
    fun parseShazamJsonExtractsTrackMetadata() {
        val json = """
            {"matches":[{"id":"1"}],"track":{"title":"Never Gonna Give You Up","subtitle":"Rick Astley",
             "url":"https://www.shazam.com/track/1/never-gonna-give-you-up",
             "images":{"coverart":"https://img/400.jpg","coverarthq":"https://img/hq.jpg"},
             "sections":[{"type":"SONG","metadata":[{"title":"Album","text":"Whenever You Need Somebody"}]}]}}
        """.trimIndent()
        val song = ShazamRecognizer.parseShazamJson(json)!!
        assertEquals("Never Gonna Give You Up", song.title)
        assertEquals("Rick Astley", song.artist)
        assertEquals("Whenever You Need Somebody", song.album)
        assertEquals("https://img/hq.jpg", song.coverUrl)
        assertEquals("https://www.shazam.com/track/1/never-gonna-give-you-up", song.shazamUrl)
        assertNull(ShazamRecognizer.parseShazamJson("""{"matches":[],"tagid":"x"}"""))
    }
}
