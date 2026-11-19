package com.simpmusic.media_jvm.mpv

import com.maxrave.domain.mediaservice.player.WAVEFORM_BARS_PER_SECOND
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Builds the SoundCloud style's waveform on Desktop, matching Android's WaveformReader: one level per
 * 1 / [WAVEFORM_BARS_PER_SECOND] s, each 0..1 against the loudest bar, kept under ~/.simpmusic/waveforms.
 *
 * mpv shares no cache between handles, so the track's audio is played once more through a second,
 * silent handle. A lavfi chain resamples it to 8 kHz mono 16-bit, cuts it into one frame per bar,
 * measures each frame's RMS with `astats`, and `ametadata` prints the values to a file that is read
 * back once the file has ended. The audio that comes out of the chain goes to `ao=pcm`, which writes
 * it raw to a scratch file as fast as it decodes (a 4-minute track in under a second) and is deleted
 * afterwards: the point is the untimed output, not the copy. Every bundled slice carries ao_pcm.
 *
 * The chain needs `asetnsamples`, `astats` and `ametadata`. The macOS and Windows slices carry every
 * FFmpeg filter; the Linux slice carries only its whitelist (scripts/mpv-linux/Dockerfile). On a slice
 * without them the graph fails, nothing is written, and the caller gets null: a flat line.
 */
internal object MpvWaveformReader {
    private const val TAG = "Waveform"
    private const val SAMPLE_RATE = 8_000
    private val SAMPLES_PER_BAR = (SAMPLE_RATE / WAVEFORM_BARS_PER_SECOND).roundToInt()

    // A stream that has not ended in this long is not worth holding a decoder for.
    private const val TIMEOUT_MS = 120_000L

    // Silence still draws a sliver, as on Android, so the line never breaks.
    private const val MIN_LEVEL = 0.04f

    // One decode at a time: two requests for the same track would write the same files.
    private val decodeLock = Mutex()
    private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9_-]")

    private val dir: File by lazy {
        File(System.getProperty("user.home"), ".simpmusic" + File.separator + "waveforms").apply { mkdirs() }
    }

    /**
     * The stored waveform for [mediaId], or one measured from [sourceUrl]'s audio. [sourceUrl] is only
     * asked when nothing is stored, so a track heard before costs no network at all.
     */
    suspend fun read(
        mediaId: String,
        sourceUrl: suspend () -> String?,
    ): FloatArray? =
        withContext(Dispatchers.IO) {
            val name = mediaId.replace(UNSAFE_FILE_CHARS, "_")
            storedBars(name) ?: decodeLock.withLock { storedBars(name) ?: measure(mediaId, name, sourceUrl) }
        }

    private fun storedBars(name: String): FloatArray? {
        val stored = File(dir, "$name.wf")
        if (!stored.isFile || stored.length() == 0L) return null
        val bytes = stored.readBytes()
        return FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 255f }
    }

    private suspend fun measure(
        mediaId: String,
        name: String,
        sourceUrl: suspend () -> String?,
    ): FloatArray? {
        val url = sourceUrl()
        if (url == null) {
            Logger.d(TAG, "No playable audio URL for $mediaId yet")
            return null
        }
        val levels = File(dir, "$name.levels")
        val pcm = File(dir, "$name.pcm")
        try {
            levels.delete()
            pcm.delete()
            if (!decode(url, levels, pcm)) return null
            val bars = parse(levels)
            if (bars == null) {
                Logger.w(TAG, "Measured $mediaId but read no levels back")
                return null
            }
            File(dir, "$name.wf").writeBytes(ByteArray(bars.size) { (bars[it] * 255f).toInt().coerceIn(0, 255).toByte() })
            return bars
        } finally {
            levels.delete()
            pcm.delete()
        }
    }

    /** Plays [url] through a silent handle that writes one RMS line per bar to [levels] and the audio to the scratch [pcm]. */
    private suspend fun decode(
        url: String,
        levels: File,
        pcm: File,
    ): Boolean {
        val graph =
            "lavfi=[aresample=$SAMPLE_RATE,aformat=sample_fmts=s16:channel_layouts=mono," +
                "asetnsamples=n=$SAMPLES_PER_BAR:p=0,astats=metadata=1:reset=1," +
                "ametadata=mode=print:key=lavfi.astats.1.RMS_level:file=${lavfiEscapePath(levels.absolutePath)}]"
        return mpvDecodeToPcm(TAG, url, pcm, listOf("af" to graph), TIMEOUT_MS)
    }

    /**
     * Reads ametadata's print output: a `frame:… pts_time:<s>` line, then `…RMS_level=<dBFS>` for that
     * frame ("-inf" for digital silence). Each frame is one bar, placed by its own time.
     */
    private fun parse(levels: File): FloatArray? {
        if (!levels.isFile) return null
        val values = HashMap<Int, Float>()
        var time = -1.0
        levels.forEachLine { line ->
            val pts = line.substringAfter("pts_time:", "").trim()
            if (pts.isNotEmpty()) {
                time = pts.toDoubleOrNull() ?: -1.0
                return@forEachLine
            }
            val db = line.substringAfter("RMS_level=", "").trim()
            if (db.isNotEmpty() && time >= 0.0) {
                values[(time * WAVEFORM_BARS_PER_SECOND).roundToInt()] = db.toDoubleOrNull()?.let { 10.0.pow(it / 20.0).toFloat() } ?: 0f
            }
        }
        if (values.isEmpty()) return null
        val rms = FloatArray(values.keys.max() + 1) { values[it] ?: 0f }
        val loudest = rms.max().takeIf { it > 0f } ?: return null
        return FloatArray(rms.size) { (rms[it] / loudest).coerceIn(MIN_LEVEL, 1f) }
    }
}
