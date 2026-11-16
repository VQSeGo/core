package com.maxrave.data.mediaservice

import com.maxrave.logger.Logger
import io.github.selemba1000.JMTC
import io.github.selemba1000.JMTCEnabledButtons
import io.github.selemba1000.JMTCPlayingState
import io.github.selemba1000.JMTCSettings
import io.github.selemba1000.JMTCTimelineProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "MprisBridge"
private const val OBJECT_PATH = "/org/mpris/MediaPlayer2"
private const val TRACK_PATH_PREFIX = "/org/mpris/MediaPlayer2/Track/"
private const val POSITION_PUSH_MS = 250L

/**
 * What the Linux MPRIS server of JMTC (the library behind NPYC) leaves out or gets wrong, filled in
 * through JMTC's own public API wherever it has one.
 *
 * - `mpris:length` is never written: NPYC's `setDuration` is not called by the handler (and it passes
 *   milliseconds where MPRIS wants microseconds). [updateTrack] writes it.
 * - `mpris:trackid` is a fixed string `/io/github/selemba1000/std` (type `s`, the spec wants an object
 *   path `o`, unique per track). [updateTrack] replaces it.
 * - `Position` is a plain stored field, 0 until someone writes it, and `Seek` / `SetPosition` hand
 *   their target to `onSeek` in microseconds. [startPositionPump] keeps the field within a quarter
 *   of a second of the player while it plays; every pause, seek and track change writes it at once.
 * - `Seeked` is never emitted: [notifySeeked] sends it.
 * - NPYC's `setButtonEnabled` ties `CanPause` to "is playing", so a paused player reported
 *   `CanPause=false`; [updateState] sets both play and pause whenever there is a track.
 *
 * Linux only: SMTC on Windows keeps going through NPYC unchanged ([create] returns null there).
 * Every call is wrapped, so a failure here never reaches playback.
 */
internal class MprisBridge private constructor(
    private val jmtc: JMTC,
    private val scope: CoroutineScope,
    private val positionMs: () -> Long,
) {
    private var pumpJob: Job? = null

    @Volatile
    private var hasTrack = false

    @Volatile
    private var durationUs = 0L

    /** Track changed (or its metadata was rewritten by NPYC, which resets `mpris:trackid`). */
    fun updateTrack(
        mediaId: String,
        durationMs: Long,
    ) {
        runCatching {
            hasTrack = true
            durationUs = durationMs.coerceAtLeast(0L) * 1000L
            writeTrackId(mediaId)
            writeLength()
            jmtc.setPosition(positionMs().coerceAtLeast(0L) * 1000L)
        }.onFailure { Logger.e(TAG, "updateTrack: ${it.message}") }
    }

    /** The engine learned the real length of the current track. */
    fun updateDuration(durationMs: Long) {
        if (durationMs <= 0L) return
        runCatching {
            durationUs = durationMs * 1000L
            writeLength()
        }.onFailure { Logger.e(TAG, "updateDuration: ${it.message}") }
    }

    fun clearTrack() {
        runCatching {
            hasTrack = false
            durationUs = 0L
            jmtc.setPosition(0L)
        }.onFailure { Logger.e(TAG, "clearTrack: ${it.message}") }
    }

    /** Play / Pause stay available whenever there is a track, whatever the current state is. */
    fun updateState(
        isPlaying: Boolean,
        canGoNext: Boolean,
        canGoPrevious: Boolean,
        hasTrack: Boolean,
    ) {
        runCatching {
            this.hasTrack = hasTrack
            jmtc.setEnabledButtons(JMTCEnabledButtons(hasTrack, hasTrack, false, canGoNext, canGoPrevious))
            jmtc.setPlayingState(
                when {
                    !hasTrack -> JMTCPlayingState.STOPPED
                    isPlaying -> JMTCPlayingState.PLAYING
                    else -> JMTCPlayingState.PAUSED
                },
            )
            jmtc.setPosition(positionMs().coerceAtLeast(0L) * 1000L)
        }.onFailure { Logger.e(TAG, "updateState: ${it.message}") }
    }

    /** Position is written once more on its own (pause, seek) so a read between ticks is not stale. */
    fun pushPosition() {
        runCatching { jmtc.setPosition(positionMs().coerceAtLeast(0L) * 1000L) }
    }

    fun startPositionPump() {
        pumpJob?.cancel()
        pumpJob =
            scope.launch {
                while (isActive) {
                    pushPosition()
                    delay(POSITION_PUSH_MS)
                }
            }
    }

    fun stopPositionPump() {
        pumpJob?.cancel()
        pumpJob = null
        pushPosition()
    }

    /** The `Seeked` signal, with the position the player landed on. */
    fun notifySeeked(positionMs: Long) {
        runCatching {
            val us = positionMs.coerceAtLeast(0L) * 1000L
            jmtc.setPosition(us)
            val connection =
                jmtc.javaClass
                    .getDeclaredField("connection")
                    .apply { isAccessible = true }
                    .get(jmtc) ?: return
            val signal =
                Class
                    .forName("io.github.selemba1000.linux.MPRISPlayer2Player\$Seeked")
                    .getConstructor(String::class.java, java.lang.Long.TYPE)
                    .newInstance(OBJECT_PATH, us)
            connection.javaClass.methods
                .first { it.name == "sendMessage" && it.parameterCount == 1 }
                .invoke(connection, signal)
        }.onFailure { Logger.e(TAG, "notifySeeked: ${it.message}") }
    }

    fun release() {
        pumpJob?.cancel()
        pumpJob = null
    }

    private fun writeLength() {
        // JMTC publishes `mpris:length` as end - start.
        jmtc.setTimelineProperties(JMTCTimelineProperties(0L, durationUs, 0L, durationUs))
    }

    /** `Metadata` is a package-private map inside JMTC; `setAt` also sends PropertiesChanged. */
    private fun writeTrackId(mediaId: String) {
        val metadata =
            jmtc.javaClass
                .getDeclaredField("Metadata")
                .apply { isAccessible = true }
                .get(jmtc)
        metadata.javaClass
            .getDeclaredMethod("setAt", String::class.java, Any::class.java)
            .apply { isAccessible = true }
            .invoke(metadata, "mpris:trackid", dbusPath(trackPath(mediaId)))
    }

    /** dbus-java is not on the compile classpath (JMTC keeps it to itself), so the object path is built by name. */
    private fun dbusPath(path: String): Any =
        Class
            .forName("org.freedesktop.dbus.DBusPath")
            .getConstructor(String::class.java)
            .newInstance(path)

    companion object {
        /** Object paths allow only `[A-Za-z0-9_]`; every other character (and `_`) becomes `_xx` hex. */
        fun trackPath(mediaId: String): String =
            buildString {
                append(TRACK_PATH_PREFIX)
                if (mediaId.isEmpty()) append("none")
                mediaId.forEach { c ->
                    if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9') {
                        append(c)
                    } else {
                        append('_').append(c.code.toString(16).padStart(2, '0'))
                    }
                }
            }

        /** Needs NPYC to have created the JMTC singleton already; null off Linux or if it did not come up. */
        fun create(
            scope: CoroutineScope,
            positionMs: () -> Long,
        ): MprisBridge? =
            runCatching {
                if (!System.getProperty("os.name").lowercase().let { !it.contains("win") && !it.contains("mac") }) {
                    return@runCatching null
                }
                val jmtc = JMTC.getInstance(JMTCSettings("SimpMusic", ""))
                if (jmtc.javaClass.name != "io.github.selemba1000.linux.LinuxJMTC") return@runCatching null
                MprisBridge(jmtc, scope, positionMs)
            }.onFailure { Logger.e(TAG, "create: ${it.message}") }.getOrNull()
    }
}
