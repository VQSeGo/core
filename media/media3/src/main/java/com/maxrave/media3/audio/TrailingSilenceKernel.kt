package com.maxrave.media3.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Drops the silence at the very end of a track, and nothing else.
 *
 * Media3's own `SilenceSkippingAudioProcessor` cannot do this. It plays out any silence shorter
 * than its minimum duration (two seconds here), and the quiet tail of a song is usually shorter
 * than that; lowering the minimum would start cutting the pauses inside songs. The end of a track is
 * different from every other silence in one way only: nothing follows it. So this holds back the
 * silent frames at the end of each buffer, gives them out if more sound arrives, and throws them
 * away when the stream ends.
 *
 * Nothing is delayed in time. The decoder runs far ahead of playback, so a held stretch of silence
 * is passed on a few milliseconds later, together with the sound that proves it was not the end.
 *
 * [process] and [finish] write into a buffer obtained from `allocate`, which is called at most once
 * and only when there is something to write. That is how a Media3 processor hands out its output
 * (`replaceOutputBuffer`), and it keeps the common case, a buffer ending in sound, to one copy.
 *
 * Works on interleaved 16-bit PCM in native byte order, the only thing the YouTube sources decode to.
 *
 * @param sampleRateHz sample rate of the stream, to turn milliseconds into frames.
 * @param silenceThreshold a sample whose absolute value is at or below this counts as silent, the
 *   same level (-42 dBFS) the built-in skipper uses. A frame is silent when every channel is.
 * @param keepTailMs how much of the silence is still played at the end, so a song does not stop on a
 *   hard edge. Everything past it is dropped.
 * @param maxHeldMs the longest silence held back (a minute is about 11 MB of stereo PCM). Beyond it the
 *   silence is passed on, so a stretch of silence in the middle of a long track cannot grow
 *   without bound. It has to be longer than any tail worth trimming: a tail that outgrows it is
 *   split, the part before the cap goes to the built-in skipper, and the position then
 *   jumps by what that skipper cut while the rest is dropped unseen at the end.
 */
class TrailingSilenceKernel(
    channelCount: Int,
    private val sampleRateHz: Int,
    private val silenceThreshold: Int = DEFAULT_SILENCE_THRESHOLD,
    keepTailMs: Int = DEFAULT_KEEP_TAIL_MS,
    maxHeldMs: Int = DEFAULT_MAX_HELD_MS,
) {
    private val frameBytes = channelCount.coerceAtLeast(1) * BYTES_PER_SAMPLE
    private val keepTailBytes = framesOf(keepTailMs, sampleRateHz) * frameBytes
    private val maxHeldBytes = framesOf(maxHeldMs, sampleRateHz) * frameBytes

    private var held = ByteArray(INITIAL_HELD_BYTES)
    private var heldSize = 0

    /** Bytes of silence being held back right now. */
    val heldBytes: Int get() = heldSize

    /**
     * Takes all of [input] and writes whatever is ready to be played into the buffer `allocate`
     * returns. The held silence goes out ahead of the sound that follows it.
     */
    fun process(
        input: ByteBuffer,
        allocate: (Int) -> ByteBuffer,
    ) {
        val size = input.remaining()
        if (size == 0) return
        val start = input.position()
        val soundEnd = endOfLastSound(input)

        if (soundEnd == 0) {
            // Nothing but silence in this buffer: hold it, unless it has gone on too long to be a tail.
            if (heldSize + size > maxHeldBytes) {
                write(allocate, heldSize + size) { out ->
                    out.put(held, 0, heldSize)
                    copyRange(input, start, size, out)
                }
                heldSize = 0
            } else {
                append(input, start, size)
            }
        } else {
            write(allocate, heldSize + soundEnd) { out ->
                out.put(held, 0, heldSize)
                copyRange(input, start, soundEnd, out)
            }
            heldSize = 0
            // The silent end of this buffer might be the end of the track, so it waits.
            if (soundEnd < size) append(input, start + soundEnd, size - soundEnd)
        }
        input.position(input.limit())
    }

    /** Plays [input] untouched, after anything still held: the feature was switched off mid-stream. */
    fun passThrough(
        input: ByteBuffer,
        allocate: (Int) -> ByteBuffer,
    ) {
        val size = input.remaining()
        if (size == 0 && heldSize == 0) return
        val start = input.position()
        write(allocate, heldSize + size) { out ->
            out.put(held, 0, heldSize)
            copyRange(input, start, size, out)
        }
        heldSize = 0
        input.position(input.limit())
    }

    /**
     * The stream has ended, so what is held is the end of the track. A short stretch of it still
     * plays when [trim] is on; with it off (switched off while silence was held) all of it does.
     * Returns how many bytes of silence were thrown away.
     */
    fun finish(
        trim: Boolean,
        allocate: (Int) -> ByteBuffer,
    ): Int {
        val playBytes = if (trim) minOf(heldSize, keepTailBytes) else heldSize
        if (playBytes > 0) {
            write(allocate, playBytes) { out -> out.put(held, 0, playBytes) }
        }
        val dropped = heldSize - playBytes
        heldSize = 0
        return dropped
    }

    /** How many whole frames [bytes] of audio hold. */
    fun framesIn(bytes: Int): Long = (bytes / frameBytes).toLong()

    /** How long [bytes] of audio last, in milliseconds. */
    fun durationMs(bytes: Int): Long = bytes.toLong() / frameBytes * 1000 / sampleRateHz

    /** Forgets what is held: a seek or a new stream makes it belong to nothing. */
    fun clear() {
        heldSize = 0
    }

    /**
     * End, in bytes from the start of the buffer, of the last frame that has sound in it; 0 when the
     * whole buffer is silent. Looks from the back, so a buffer that ends in sound costs one frame.
     */
    private fun endOfLastSound(input: ByteBuffer): Int {
        val order = input.order()
        input.order(ByteOrder.nativeOrder())
        try {
            val start = input.position()
            var frame = input.remaining() / frameBytes - 1
            while (frame >= 0) {
                val at = start + frame * frameBytes
                var loud = false
                var offset = 0
                while (offset < frameBytes) {
                    if (abs(input.getShort(at + offset).toInt()) > silenceThreshold) {
                        loud = true
                        break
                    }
                    offset += BYTES_PER_SAMPLE
                }
                if (loud) return (frame + 1) * frameBytes
                frame--
            }
            return 0
        } finally {
            input.order(order)
        }
    }

    private fun append(
        input: ByteBuffer,
        from: Int,
        length: Int,
    ) {
        if (heldSize + length > held.size) held = held.copyOf(maxOf(held.size * 2, heldSize + length))
        for (i in 0 until length) held[heldSize + i] = input.get(from + i)
        heldSize += length
    }

    private fun copyRange(
        input: ByteBuffer,
        from: Int,
        length: Int,
        out: ByteBuffer,
    ) {
        // Absolute reads, so the input's own position is left for the caller to move once, at the end.
        if (input.hasArray()) {
            out.put(input.array(), input.arrayOffset() + from, length)
        } else {
            val slice = input.duplicate()
            slice.position(from)
            slice.limit(from + length)
            out.put(slice)
        }
    }

    private inline fun write(
        allocate: (Int) -> ByteBuffer,
        size: Int,
        fill: (ByteBuffer) -> Unit,
    ) {
        if (size == 0) return
        val out = allocate(size)
        fill(out)
        out.flip()
    }

    companion object {
        const val DEFAULT_SILENCE_THRESHOLD = 256
        const val DEFAULT_KEEP_TAIL_MS = 150
        const val DEFAULT_MAX_HELD_MS = 60_000

        private const val BYTES_PER_SAMPLE = 2
        private const val INITIAL_HELD_BYTES = 64 * 1024

        private fun framesOf(
            ms: Int,
            sampleRate: Int,
        ): Int = (ms.toLong() * sampleRate / 1000).toInt()
    }
}
