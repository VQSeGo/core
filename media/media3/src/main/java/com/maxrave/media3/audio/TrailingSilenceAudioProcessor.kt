package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.maxrave.logger.Logger
import java.nio.ByteBuffer

/**
 * Media3 [AudioProcessor] that drops the silence at the end of a track, the part of "skip silence"
 * the built-in skipper misses: it plays any silence shorter than its two-second minimum, and the
 * tail of a song is usually shorter. See [TrailingSilenceKernel] for how the tail is told apart from
 * a pause inside the song.
 *
 * Follows the rules the other processors here do:
 * - [enabled] is read on every buffer, never captured, so switching the setting takes effect in
 *   the track that is playing. The processor stays in the chain either way and passes audio through
 *   while it is off.
 * - `isActive` is not overridden: the base class answers it from the format [onConfigure] accepted.
 * - One instance per player, all reading the same setting.
 * - Supports PCM 16-bit, which is what the YouTube sources decode to.
 *
 * It sits first in the chain, so silence is judged on the decoded audio before the equalizer or an
 * effect can lift it above the threshold.
 */
@UnstableApi
class TrailingSilenceAudioProcessor(
    private val enabled: () -> Boolean,
) : BaseAudioProcessor() {
    private var kernel: TrailingSilenceKernel? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        kernel = TrailingSilenceKernel(inputAudioFormat.channelCount, inputAudioFormat.sampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val kernel = kernel ?: return
        if (enabled()) {
            kernel.process(inputBuffer, ::replaceOutputBuffer)
        } else {
            kernel.passThrough(inputBuffer, ::replaceOutputBuffer)
        }
    }

    override fun onQueueEndOfStream() {
        val kernel = kernel ?: return
        val trim = enabled()
        val held = kernel.heldBytes
        val dropped = kernel.finish(trim = trim, allocate = ::replaceOutputBuffer)
        // One line per track end, whether or not anything was cut: without it a quiet log cannot be
        // told apart from a processor that never ran.
        Logger.i(
            TAG,
            "End of track: ${kernel.durationMs(held)} ms of silence at the end, " +
                "${kernel.durationMs(dropped)} ms skipped${if (trim) "" else " (setting off)"}",
        )
    }

    /** A seek or a new stream: what was held back belongs to a position that no longer plays. */
    override fun onFlush() {
        kernel?.clear()
    }

    override fun onReset() {
        kernel = null
    }

    private companion object {
        const val TAG = "TrailingSilence"
    }
}
