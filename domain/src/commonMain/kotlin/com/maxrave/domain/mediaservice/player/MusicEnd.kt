package com.maxrave.domain.mediaservice.player

import kotlin.math.abs

/**
 * Where a track's music ends, and how skip silence times a crossfade from it. Shared by both players.
 *
 * With skip silence on, the silence at the end of a track is dropped by the audio filter
 * (`silenceremove` on Desktop, the trailing-silence processor on Android) only as it is reached, so
 * a crossfade timed from the file's length used to fall on that silence: the old track had already
 * gone quiet and only the new one was heard coming in. Each player therefore decodes the end of the
 * track ahead of time ([tailStartMs]) and finds the last moment the same filter would call sound,
 * the music end. The crossfade is then timed to finish there ([fadeEndMs]): the old track fades out
 * over its last seconds of music while the new one comes in.
 *
 * When the music end is not known (not measured yet, measuring failed, a live stream) everything
 * falls back to the file's length, which is how it worked before.
 */
object MusicEnd {
    /** How much of the end of a track is decoded to find its music end. */
    const val TAIL_WINDOW_MS = 60_000L

    /** The music end is measured once this little of the track remains, or at once for a shorter track. */
    const val ANALYSIS_LEAD_MS = 90_000L

    /** Silence still played after the music end before a track is ended there (Android keeps the same). */
    const val KEEP_TAIL_MS = 150L

    /** A tail shorter than this is not worth moving the crossfade for. */
    const val MIN_TAIL_MS = 250L

    /** Stored music ends are reused only for a stream whose length is this close to the one measured. */
    const val DURATION_TOLERANCE_MS = 2_000L

    /** The shortest a crossfade gets, however late it starts. */
    const val MIN_FADE_MS = 1_000L

    /** Where decoding starts so it covers [TAIL_WINDOW_MS] of the end. */
    fun tailStartMs(durationMs: Long): Long = (durationMs - TAIL_WINDOW_MS).coerceAtLeast(0L)

    /** Whether the music end of a track at [positionMs] of [durationMs] should be measured now. */
    fun shouldMeasure(
        durationMs: Long,
        positionMs: Long,
    ): Boolean = durationMs > 0L && durationMs - positionMs <= ANALYSIS_LEAD_MS

    /**
     * The music end found by a scan of the tail: [tailStartMs] plus where the last sound in the
     * decoded window ended, or null when the window held no sound (a track silent for its last
     * minute, or entirely) and nothing can be said.
     */
    fun fromTail(
        tailStartMs: Long,
        lastSoundEndInTailMs: Long?,
    ): Long? = lastSoundEndInTailMs?.takeIf { it > 0L }?.let { tailStartMs + it }

    /**
     * The point a crossfade should finish at: the music end when it is known and leaves a tail worth
     * skipping, the file's end otherwise (no tail, or nothing measured).
     */
    fun fadeEndMs(
        durationMs: Long,
        musicEndMs: Long?,
    ): Long {
        if (durationMs <= 0L || musicEndMs == null || musicEndMs <= 0L) return durationMs
        return if (musicEndMs < durationMs - MIN_TAIL_MS) musicEndMs else durationMs
    }

    /** Wall-clock time until [fadeEndMs] at playback [speed]. */
    fun remainingMs(
        positionMs: Long,
        fadeEndMs: Long,
        speed: Float = 1f,
    ): Long = ((fadeEndMs - positionMs) / speed.coerceAtLeast(0.1f)).toLong()

    /**
     * Whether the polling loop should start the crossfade: [remainingMs] has come within the fade
     * plus the time the next track needs to get ready ([preparationMs], 0 when it is precached).
     */
    fun shouldStartCrossfade(
        remainingMs: Long,
        crossfadeMs: Long,
        preparationMs: Long,
    ): Boolean = remainingMs in 1..(crossfadeMs + preparationMs)

    /** The fade actually run: the configured length, or what is left of the music when that is less. */
    fun effectiveFadeMs(
        configuredMs: Long,
        remainingMs: Long,
    ): Int = minOf(configuredMs, remainingMs).coerceAtLeast(MIN_FADE_MS).toInt()

    /**
     * Whether a track at [positionMs] has only silence left and can end now. Only when the music end
     * is known and before the file's end; the caller ends the track once per pass.
     */
    fun isPastMusicEnd(
        positionMs: Long,
        durationMs: Long,
        musicEndMs: Long?,
    ): Boolean {
        val end = fadeEndMs(durationMs, musicEndMs)
        return end < durationMs && positionMs >= end + KEEP_TAIL_MS
    }

    /** A stored measurement, `musicEndMs durationMs`; a music end of -1 means "no sound in the window". */
    fun encode(
        musicEndMs: Long?,
        durationMs: Long,
    ): String = "${musicEndMs ?: -1L} $durationMs"

    /**
     * Reads what [encode] wrote. Returns null when the text is unreadable or measured a stream of a
     * different length (then it is measured again); otherwise a [Stored] whose [Stored.musicEndMs]
     * may be null for a tail with no sound in it.
     */
    fun decode(
        text: String,
        durationMs: Long,
    ): Stored? {
        val parts = text.trim().split(' ')
        if (parts.size != 2) return null
        val end = parts[0].toLongOrNull() ?: return null
        val measuredDuration = parts[1].toLongOrNull() ?: return null
        if (durationMs > 0L && abs(measuredDuration - durationMs) > DURATION_TOLERANCE_MS) return null
        return Stored(end.takeIf { it > 0L })
    }

    data class Stored(
        val musicEndMs: Long?,
    )
}
