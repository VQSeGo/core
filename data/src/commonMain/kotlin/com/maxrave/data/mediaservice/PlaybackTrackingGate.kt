package com.maxrave.data.mediaservice

import kotlin.concurrent.Volatile

/**
 * Lets playback tracking (YouTube's `initPlayback`) start once per play of a track.
 *
 * The format row of a track is what carries the tracking links, and its subscription emits whenever
 * the row is written. A track that already has a row from an earlier session emits the old row first
 * (links and cpn of that earlier session, already expired), and the refresh of the links (updateFormat
 * on Android, a fresh getStream on the desktop) writes a second one a moment later. Each emission
 * used to start tracking, so the statistics left twice: once with the old links, once with the new.
 *
 * An expired row therefore waits [STALE_GRACE_MS] for the fresh one (the wait is cancelled by the next
 * emission); if none comes, the old links go out as a fallback and a fresh row may still replace them.
 * Once a fresh row has started tracking, later rows of the same play (a rewritten URL with a new cpn)
 * do not start it again.
 */
class PlaybackTrackingGate {
    @Volatile
    private var state = NONE

    /** A new track has landed: tracking may start again. */
    fun reset() {
        state = NONE
    }

    /**
     * Whether a row ([stale] = its links expired) starts tracking now. Call it after the grace wait,
     * just before sending.
     */
    fun shouldStart(stale: Boolean): Boolean {
        val current = state
        if (current == STARTED_FRESH) return false
        if (current == STARTED_STALE && stale) return false
        state = if (stale) STARTED_STALE else STARTED_FRESH
        return true
    }

    companion object {
        /** How long an expired row waits for the refreshed one. Desktop resolves a stream in up to ~10 s. */
        const val STALE_GRACE_MS = 20_000L

        private const val NONE = 0
        private const val STARTED_STALE = 1
        private const val STARTED_FRESH = 2
    }
}
