package com.maxrave.media3.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The kernel is tested rather than the processor for the reason the echo test gives: this module's
 * `src/test` runs on a plain JVM against android.jar stubs, so building an `AudioFormat` here would
 * be a bet on which framework calls Media3 makes. The processor is a thin wrapper over this.
 *
 * Mono 48 kHz unless a test says otherwise: 48 samples are a millisecond, so the defaults
 * (150 ms kept, 10 s held at most) are 7 200 and 480 000 samples.
 */
class TrailingSilenceKernelTest {
    private val rate = 48_000
    private val out = ByteArrayOutputStream()

    private fun kernel(channels: Int = 1) = TrailingSilenceKernel(channelCount = channels, sampleRateHz = rate)

    private fun pcm(vararg samples: Int): ByteBuffer = pcm(samples.toList())

    private fun pcm(samples: List<Int>): ByteBuffer {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { buffer.putShort(it.toShort()) }
        buffer.flip()
        return buffer
    }

    private fun sound(count: Int) = List(count) { 5_000 }

    private fun silence(count: Int) = List(count) { 0 }

    private fun TrailingSilenceKernel.feed(samples: List<Int>) {
        val buffer = pcm(samples)
        process(buffer) { size ->
            ByteBuffer.allocate(size).order(ByteOrder.nativeOrder()).also { captured = it }
        }
        captured?.let { out.write(it.array(), 0, it.limit()) }
        captured = null
        assertEquals("the whole input is consumed", 0, buffer.remaining())
    }

    private var captured: ByteBuffer? = null

    private fun TrailingSilenceKernel.end(trim: Boolean = true) {
        finish(trim) { size -> ByteBuffer.allocate(size).order(ByteOrder.nativeOrder()).also { captured = it } }
        captured?.let { out.write(it.array(), 0, it.limit()) }
        captured = null
    }

    private fun played(): List<Int> {
        val bytes = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.nativeOrder())
        return List(bytes.remaining() / 2) { bytes.getShort(it * 2).toInt() }
    }

    @Test
    fun `a buffer that ends in sound passes through unchanged`() {
        val kernel = kernel()
        val input = sound(100)
        kernel.feed(input)

        assertEquals(input, played())
        assertEquals(0, kernel.heldBytes)
    }

    @Test
    fun `silence at the end of the stream is dropped past the kept tail`() {
        val kernel = kernel()
        // 150 ms are kept: 7 200 samples of a 2 s (96 000 sample) silent tail.
        kernel.feed(sound(1_000) + silence(96_000))
        kernel.end()

        assertEquals(sound(1_000) + silence(7_200), played())
    }

    @Test
    fun `silence shorter than the kept tail still plays in full`() {
        val kernel = kernel()
        kernel.feed(sound(1_000) + silence(2_000))
        kernel.end()

        assertEquals(sound(1_000) + silence(2_000), played())
    }

    @Test
    fun `silence inside the track is held and then played before the sound that follows`() {
        val kernel = kernel()
        val parts = listOf(sound(500) + silence(60_000), silence(30_000), silence(10_000) + sound(500))
        parts.forEach { kernel.feed(it) }
        kernel.end()

        assertEquals("every sample, in order", parts.flatten(), played())
    }

    @Test
    fun `a pause is held back until sound proves it was not the end`() {
        val kernel = kernel()
        kernel.feed(sound(500) + silence(20_000))
        assertEquals("only the sound has gone out", sound(500), played())
        assertEquals(40_000, kernel.heldBytes)

        kernel.feed(sound(10))
        assertEquals(sound(500) + silence(20_000) + sound(10), played())
        assertEquals(0, kernel.heldBytes)
    }

    @Test
    fun `silence that goes on past the cap is passed on instead of held forever`() {
        val kernel = kernel()
        // The cap is 10 s = 480 000 samples; the second buffer takes the held total over it.
        kernel.feed(sound(10) + silence(300_000))
        kernel.feed(silence(300_000))
        assertEquals(sound(10) + silence(600_000), played())
        assertEquals(0, kernel.heldBytes)
    }

    @Test
    fun `a quiet sample under the threshold is silence and one over it is sound`() {
        val kernel = kernel()
        kernel.feed(listOf(5_000, 256, -256, 255))
        kernel.end()
        assertEquals("the last sound is 5 000, so the rest is a tail short enough to keep", listOf(5_000, 256, -256, 255), played())

        out.reset()
        val second = kernel()
        second.feed(listOf(5_000) + silence(20_000) + listOf(257))
        assertEquals("257 is sound, so nothing is held", listOf(5_000) + silence(20_000) + listOf(257), played())
        assertEquals(0, second.heldBytes)
    }

    @Test
    fun `a frame is silent only when every channel is`() {
        val kernel = kernel(channels = 2)
        // Interleaved L,R: the right channel has sound in the last frame, so nothing is held.
        kernel.feed(listOf(0, 0, 0, 0, 0, 4_000))
        assertEquals(listOf(0, 0, 0, 0, 0, 4_000), played())
        assertEquals(0, kernel.heldBytes)
    }

    @Test
    fun `switched off, what was held plays ahead of the input and nothing is dropped`() {
        val kernel = kernel()
        kernel.feed(sound(100) + silence(50_000))
        val rest = pcm(silence(1_000) + sound(10))
        kernel.passThrough(rest) { size -> ByteBuffer.allocate(size).order(ByteOrder.nativeOrder()).also { captured = it } }
        captured?.let { out.write(it.array(), 0, it.limit()) }

        assertEquals(sound(100) + silence(51_000) + sound(10), played())
        assertEquals(0, rest.remaining())
    }

    @Test
    fun `the end of a stream with the feature off plays everything that was held`() {
        val kernel = kernel()
        kernel.feed(sound(100) + silence(50_000))
        kernel.end(trim = false)

        assertEquals(sound(100) + silence(50_000), played())
    }

    @Test
    fun `a seek forgets the held silence`() {
        val kernel = kernel()
        kernel.feed(sound(100) + silence(50_000))
        kernel.clear()
        kernel.end()

        assertEquals(sound(100), played())
    }

    @Test
    fun `an empty input produces nothing`() {
        val kernel = kernel()
        kernel.process(pcm(emptyList())) { error("nothing to write") }
        assertArrayEquals(ByteArray(0), out.toByteArray())
    }
}
