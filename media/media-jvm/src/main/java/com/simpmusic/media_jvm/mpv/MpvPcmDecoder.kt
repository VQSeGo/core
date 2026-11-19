package com.simpmusic.media_jvm.mpv

import com.maxrave.logger.Logger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Decodes [url] through a silent libmpv handle whose `ao=pcm` writes the audio raw to [pcm] as fast
 * as it decodes (a 4-minute track in under a second), for the readers that measure a track's audio
 * ([MpvWaveformReader], [MpvMusicEndReader]). mpv shares no cache between handles, so this is a
 * second read of the stream unless [url] is a local file.
 *
 * Every entry of [options] must be accepted, or nothing is decoded. Returns true when the file
 * played to its end.
 */
internal suspend fun mpvDecodeToPcm(
    tag: String,
    url: String,
    pcm: File,
    options: List<Pair<String, String>>,
    timeoutMs: Long,
): Boolean {
    val lib = MpvLibrary.INSTANCE ?: return false
    val ctx = lib.mpv_create() ?: return false
    try {
        fun option(
            name: String,
            value: String,
        ): Boolean {
            val rc = lib.mpv_set_option_string(ctx, name, value)
            if (rc < 0) Logger.w(tag, "mpv_set_option_string($name) failed: ${lib.mpv_error_string(rc)}")
            return rc >= 0
        }
        option("terminal", "no")
        // Absent from lua-less builds, where it is off anyway.
        lib.mpv_set_option_string(ctx, "ytdl", "no")
        option("vid", "no")
        option("vo", "null")
        if (!option("ao", "pcm")) return false
        if (!option("ao-pcm-file", pcm.absolutePath)) return false
        option("ao-pcm-waveheader", "no")
        // Same reconnect behaviour as the playing handles (see MpvPlayer.create).
        option("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=30")
        for ((name, value) in options) {
            if (!option(name, value)) return false
        }
        if (lib.mpv_initialize(ctx) < 0) return false
        if (lib.mpv_command(ctx, arrayOf("loadfile", url, null)) < 0) return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val pointer = lib.mpv_wait_event(ctx, 0.25) ?: continue
            val event = MpvEvent(pointer).apply { read() }
            when (event.event_id) {
                MpvEventId.END_FILE -> {
                    val endFile = event.data?.let { MpvEventEndFile(it).apply { read() } }
                    if (endFile?.reason == MpvEndFileReason.EOF) return true
                    // An error here is how a slice without a filter the options need shows up, too.
                    Logger.w(tag, "Decoding stopped early: reason ${endFile?.reason}, ${endFile?.error?.let { lib.mpv_error_string(it) }}")
                    return false
                }
                MpvEventId.SHUTDOWN -> return false
            }
        }
        Logger.w(tag, "Gave up decoding after ${timeoutMs / 1000} s")
        return false
    } finally {
        // Tears any filter graph down too, which is what flushes files it writes to disk.
        lib.mpv_terminate_destroy(ctx)
    }
}
