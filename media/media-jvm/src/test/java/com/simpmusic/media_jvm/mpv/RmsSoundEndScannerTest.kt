package com.simpmusic.media_jvm.mpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class RmsSoundEndScannerTest {
    private val rate = 48_000
    private val window = (rate * SILENCE_WINDOW_SECONDS).roundToInt()

    /** Interleaved stereo: [toneSeconds] of a 440 Hz tone at [db] dBFS, then [silenceSeconds] of zeros. */
    private fun stereo(
        toneSeconds: Double,
        silenceSeconds: Double,
        db: Double = -10.0,
        leftOnly: Boolean = false,
    ): ShortArray {
        val toneFrames = (toneSeconds * rate).toInt()
        val frames = toneFrames + (silenceSeconds * rate).toInt()
        val amplitude = 10.0.pow(db / 20.0) * 32767.0
        val out = ShortArray(frames * 2)
        for (f in 0 until toneFrames) {
            val v = (amplitude * sin(2 * PI * 440.0 * f / rate)).toInt().toShort()
            out[2 * f] = v
            out[2 * f + 1] = if (leftOnly) 0 else v
        }
        return out
    }

    private fun scan(
        samples: ShortArray,
        chunk: Int = samples.size,
    ): Long {
        val scanner = RmsSoundEndScanner(2, window, SILENCE_THRESHOLD_DB.toDouble())
        var i = 0
        while (i < samples.size) {
            val n = minOf(chunk, samples.size - i)
            scanner.feed(samples.copyOfRange(i, i + n), n)
            i += n
        }
        return scanner.soundEndFrame
    }

    @Test
    fun soundEndsWhereTheToneStopsPlusAtMostTheWindow() {
        val end = scan(stereo(toneSeconds = 8.0, silenceSeconds = 4.0))
        val toneEnd = 8L * rate
        assertTrue("end $end", end in toneEnd..toneEnd + window)
    }

    @Test
    fun feedingInPiecesGivesTheSameAnswer() {
        val samples = stereo(toneSeconds = 2.0, silenceSeconds = 1.0)
        // Pieces of whole frames, the way the PCM file is read.
        assertEquals(scan(samples), scan(samples, chunk = 4_096))
    }

    @Test
    fun aQuietToneBelowTheThresholdIsSilence() {
        assertEquals(0L, scan(stereo(toneSeconds = 2.0, silenceSeconds = 1.0, db = -50.0)))
    }

    @Test
    fun oneLoudChannelIsSound() {
        val end = scan(stereo(toneSeconds = 2.0, silenceSeconds = 1.0, leftOnly = true))
        assertTrue("end $end", end >= 2L * rate)
    }

    @Test
    fun noTailEndsAtTheLastFrame() {
        val samples = stereo(toneSeconds = 3.0, silenceSeconds = 0.0)
        assertEquals((samples.size / 2).toLong(), scan(samples))
    }
}
