package com.maxrave.media3.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PeakSoundEndScannerTest {
    private val rate = 48_000

    private fun shorts(vararg samples: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { buffer.putShort(it.toShort()) }
        buffer.flip()
        return buffer
    }

    private fun floats(vararg samples: Float): ByteBuffer {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder())
        samples.forEach { buffer.putFloat(it) }
        buffer.flip()
        return buffer
    }

    @Test
    fun endsAfterTheLastFrameAboveTheThreshold() {
        val scanner = PeakSoundEndScanner()
        // Mono: sound in frames 0..2, silence (at the threshold, as the kernel counts it) after.
        scanner.feed(shorts(1000, -2000, 300, 256, -256, 0), floatPcm = false, channels = 1, sampleRate = rate, startUs = 1_000_000L)
        assertEquals(1_000_000L + 3 * 1_000_000L / rate, scanner.soundEndUs)
    }

    @Test
    fun aLaterSilentBufferDoesNotMoveTheEnd() {
        val scanner = PeakSoundEndScanner()
        scanner.feed(shorts(1000, 1000), floatPcm = false, channels = 1, sampleRate = rate, startUs = 0L)
        val end = scanner.soundEndUs
        scanner.feed(shorts(0, 10, -10, 0), floatPcm = false, channels = 1, sampleRate = rate, startUs = 1_000L)
        assertEquals(end, scanner.soundEndUs)
    }

    @Test
    fun oneLoudChannelIsSound() {
        val scanner = PeakSoundEndScanner()
        // Stereo frames: (0, 0), (0, 900), (0, 0).
        scanner.feed(shorts(0, 0, 0, 900, 0, 0), floatPcm = false, channels = 2, sampleRate = rate, startUs = 0L)
        assertEquals(2 * 1_000_000L / rate, scanner.soundEndUs)
    }

    @Test
    fun floatOutputUsesTheSameThreshold() {
        val scanner = PeakSoundEndScanner()
        scanner.feed(floats(0.5f, 300f / 32768f, 200f / 32768f), floatPcm = true, channels = 1, sampleRate = rate, startUs = 0L)
        assertEquals(2 * 1_000_000L / rate, scanner.soundEndUs)
    }

    @Test
    fun silenceOnlyLeavesNoEnd() {
        val scanner = PeakSoundEndScanner()
        scanner.feed(shorts(0, 100, -100), floatPcm = false, channels = 1, sampleRate = rate, startUs = 0L)
        assertEquals(0L, scanner.soundEndUs)
    }

    @Test
    fun theBufferIsNotConsumed() {
        val buffer = shorts(1000, 0)
        PeakSoundEndScanner().feed(buffer, floatPcm = false, channels = 1, sampleRate = rate, startUs = 0L)
        assertEquals(0, buffer.position())
    }
}
