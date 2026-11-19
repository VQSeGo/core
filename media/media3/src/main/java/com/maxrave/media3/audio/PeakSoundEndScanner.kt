package com.maxrave.media3.audio

import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Finds where the last sound in decoded audio ends, by the rule [TrailingSilenceKernel] drops the
 * end of a track with: a frame is sound when any channel's sample is above [silenceThreshold] in
 * 16-bit terms. The point after the last such frame is where the processor would start cutting, the
 * track's music end, which the crossfade is timed to (`MusicEnd`).
 *
 * Buffers are fed in decoding order with [feed], each placed by its own presentation time.
 */
class PeakSoundEndScanner(
    private val silenceThreshold: Int = TrailingSilenceKernel.DEFAULT_SILENCE_THRESHOLD,
) {
    /** End of the last sound fed, in microseconds; 0 when none was. */
    var soundEndUs = 0L
        private set

    /**
     * Scans [buffer] from its position to its limit without moving either: interleaved [channels]
     * samples, 16-bit or (when [floatPcm]) 32-bit float in the buffer's own byte order, the first
     * frame at [startUs].
     */
    fun feed(
        buffer: ByteBuffer,
        floatPcm: Boolean,
        channels: Int,
        sampleRate: Int,
        startUs: Long,
    ) {
        val channelCount = channels.coerceAtLeast(1)
        val bytesPerSample = if (floatPcm) 4 else 2
        val frameBytes = channelCount * bytesPerSample
        val start = buffer.position()
        val frames = buffer.remaining() / frameBytes
        // From the back: a buffer that ends in sound costs one frame.
        var frame = frames - 1
        while (frame >= 0) {
            val at = start + frame * frameBytes
            for (c in 0 until channelCount) {
                val offset = at + c * bytesPerSample
                val level = if (floatPcm) abs(buffer.getFloat(offset) * 32768f).toInt() else abs(buffer.getShort(offset).toInt())
                if (level > silenceThreshold) {
                    val end = startUs + (frame + 1) * 1_000_000L / sampleRate.coerceAtLeast(1)
                    if (end > soundEndUs) soundEndUs = end
                    return
                }
            }
            frame--
        }
    }
}
