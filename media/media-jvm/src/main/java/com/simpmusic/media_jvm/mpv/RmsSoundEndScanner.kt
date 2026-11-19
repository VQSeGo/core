package com.simpmusic.media_jvm.mpv

import kotlin.math.pow

/**
 * Finds where the last sound in interleaved 16-bit PCM ends, by the rule `silenceremove` uses with
 * `detection=rms` (see [silenceRemoveGraph]): each channel's RMS over a sliding window of
 * [windowFrames], and a frame is sound while any channel is above [thresholdDb]. Measured this way,
 * the music end is the point after which the filter would see only silence.
 *
 * Audio is fed in pieces with [feed]; [soundEndFrame] is the frame count, from the first frame fed, at
 * which the last sound ended (0 when there was none).
 */
internal class RmsSoundEndScanner(
    private val channels: Int,
    windowFrames: Int,
    thresholdDb: Double,
) {
    private val window = windowFrames.coerceAtLeast(1)

    // Compared against a window's sum of squares, so the per-sample mean is never divided out.
    private val thresholdSum = (10.0.pow(thresholdDb / 20.0) * 32768.0).pow(2) * window
    private val squares = Array(channels.coerceAtLeast(1)) { LongArray(window) }
    private val sums = LongArray(channels.coerceAtLeast(1))
    private var frame = 0L

    var soundEndFrame = 0L
        private set

    /** Takes [count] samples of [samples], interleaved, a whole number of frames. */
    fun feed(
        samples: ShortArray,
        count: Int = samples.size,
    ) {
        val channelCount = squares.size
        var i = 0
        while (i + channelCount <= count) {
            val slot = (frame % window).toInt()
            var sound = false
            for (c in 0 until channelCount) {
                val x = samples[i + c].toLong()
                val sq = x * x
                sums[c] += sq - squares[c][slot]
                squares[c][slot] = sq
                if (sums[c] > thresholdSum) sound = true
            }
            frame++
            if (sound) soundEndFrame = frame
            i += channelCount
        }
    }
}
