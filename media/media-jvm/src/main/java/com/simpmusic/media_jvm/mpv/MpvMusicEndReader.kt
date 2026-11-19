package com.simpmusic.media_jvm.mpv

import com.maxrave.domain.mediaservice.player.MusicEnd
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Finds where a track's music ends on Desktop ([MusicEnd]), so a crossfade with skip silence on is
 * timed to the music and not to the silence after it.
 *
 * Only the last [MusicEnd.TAIL_WINDOW_MS] are decoded: a silent handle starts there (`start`, with an
 * exact seek) and writes the audio raw through `ao=pcm` ([mpvDecodeToPcm]), which [RmsSoundEndScanner]
 * reads by the same rule as the skip-silence filter. A downloaded or cached track is a local file;
 * a streamed one costs one ranged read of its last minute (about 1 MB). Decoding takes a few tens of
 * milliseconds. The answer is kept under ~/.simpmusic/waveforms as `<id>.end`, next to the waveform
 * files and apart from them, so a track heard before is not decoded again.
 */
internal object MpvMusicEndReader {
    private const val TAG = "MusicEnd"
    private const val SAMPLE_RATE = 48_000
    private const val CHANNELS = 2
    private const val TIMEOUT_MS = 60_000L
    private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9_-]")

    private val decodeLock = Mutex()

    private val dir: File by lazy {
        File(System.getProperty("user.home"), ".simpmusic" + File.separator + "waveforms").apply { mkdirs() }
    }

    /**
     * The music end of [mediaId] in ms, or null when it could not be told (no source, the decode failed,
     * no sound in the last minute). [sourceUrl] is asked only when nothing is stored.
     */
    suspend fun read(
        mediaId: String,
        durationMs: Long,
        sourceUrl: suspend () -> String?,
    ): Long? =
        withContext(Dispatchers.IO) {
            if (durationMs <= 0L) return@withContext null
            val name = mediaId.replace(UNSAFE_FILE_CHARS, "_")
            stored(name, durationMs)?.let { return@withContext it.musicEndMs }
            decodeLock.withLock {
                stored(name, durationMs)?.let { return@withLock it.musicEndMs }
                measure(mediaId, name, durationMs, sourceUrl)
            }
        }

    private fun stored(
        name: String,
        durationMs: Long,
    ): MusicEnd.Stored? {
        val file = File(dir, "$name.end")
        if (!file.isFile) return null
        return runCatching { MusicEnd.decode(file.readText(), durationMs) }.getOrNull()
    }

    private suspend fun measure(
        mediaId: String,
        name: String,
        durationMs: Long,
        sourceUrl: suspend () -> String?,
    ): Long? {
        val url = sourceUrl()
        if (url == null) {
            Logger.i(TAG, "$mediaId: no audio source to measure the music end from")
            return null
        }
        Logger.i(TAG, "measuring the music end of $mediaId from ${if (url.startsWith("http")) "the stream" else "a local file"}")
        val pcm = File(dir, "$name.endpcm")
        val started = System.nanoTime()
        try {
            pcm.delete()
            val tailStartMs = MusicEnd.tailStartMs(durationMs)
            val options =
                listOf(
                    "audio-format" to "s16",
                    "audio-channels" to "stereo",
                    "audio-samplerate" to SAMPLE_RATE.toString(),
                    // Exact, so the first sample written is the one at tailStartMs.
                    "hr-seek" to "yes",
                    "start" to (tailStartMs / 1000.0).toString(),
                )
            if (!mpvDecodeToPcm(TAG, url, pcm, options, TIMEOUT_MS)) return null
            val soundEndFrame = scan(pcm) ?: return null
            val musicEnd = MusicEnd.fromTail(tailStartMs, soundEndFrame * 1000L / SAMPLE_RATE)
            File(dir, "$name.end").writeText(MusicEnd.encode(musicEnd, durationMs))
            Logger.i(
                TAG,
                "$mediaId: music ends at ${musicEnd ?: "?"} ms of $durationMs ms " +
                    "(${musicEnd?.let { durationMs - it } ?: "-"} ms of silence after it), " +
                    "measured in ${(System.nanoTime() - started) / 1_000_000} ms from ${if (url.startsWith("http")) "the stream" else "a local file"}",
            )
            return musicEnd
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.w(TAG, "$mediaId: measuring the music end failed: ${e.message}")
            return null
        } finally {
            pcm.delete()
        }
    }

    /** The frame, from the start of [pcm], after which only silence follows; null when nothing was written. */
    private fun scan(pcm: File): Long? {
        if (!pcm.isFile || pcm.length() < CHANNELS * 2) return null
        val scanner =
            RmsSoundEndScanner(
                channels = CHANNELS,
                windowFrames = (SAMPLE_RATE * SILENCE_WINDOW_SECONDS).roundToInt(),
                thresholdDb = SILENCE_THRESHOLD_DB.toDouble(),
            )
        val bytes = ByteArray(64 * 1024)
        val samples = ShortArray(bytes.size / 2)
        pcm.inputStream().buffered().use { input ->
            while (true) {
                val read = input.readNBytes(bytes, 0, bytes.size)
                if (read <= 0) break
                val frameBytes = CHANNELS * 2
                val whole = read / frameBytes * frameBytes
                ByteBuffer.wrap(bytes, 0, whole).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples, 0, whole / 2)
                scanner.feed(samples, whole / 2)
                if (read < bytes.size) break
            }
        }
        return scanner.soundEndFrame
    }
}
