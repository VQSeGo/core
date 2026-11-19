package com.maxrave.domain.data.player

import kotlin.math.abs

/**
 * What "normalize volume" does to one track, in dB: the opposite of how far YouTube says the track
 * sits above its reference loudness. A track marked +6 dB loud comes down by 6, one marked -3 dB
 * quiet goes up by 3.
 *
 * Android's loudness enhancer accepts this as it is, since it carries its own limiter. mpv has no
 * limiter in the bundled Linux build, so a boost there can only be as large as the track's own
 * headroom, which is unknown; [maxBoostDb] caps it. A cut can never clip, so it is not capped.
 *
 * A value outside ±[MAX_ABS_LOUDNESS_DB] is treated as bad metadata and ignored, which is the rule
 * the Android path has always had: a wild figure should leave the track alone, not turn it down to
 * silence. A missing or non-finite value does the same.
 *
 * @return the gain to apply, `0f` meaning leave the track as it is.
 */
fun loudnessGainDb(
    loudnessDb: Float?,
    maxBoostDb: Float = Float.POSITIVE_INFINITY,
): Float {
    if (loudnessDb == null || !loudnessDb.isFinite() || abs(loudnessDb) > MAX_ABS_LOUDNESS_DB) return 0f
    // `0f - x` rather than `-x`: a loudness of exactly zero must give +0, not -0, so equality holds.
    return (0f - loudnessDb).coerceAtMost(maxBoostDb)
}

/** YouTube's loudness figures beyond this are not credible, and Android drops them the same way. */
const val MAX_ABS_LOUDNESS_DB = 20f
