package com.maxrave.domain.mediaservice.player

/**
 * Tracks the user marked, from the `⋯` menu, to be entered or left without a crossfade. Shared by both
 * players.
 *
 * The marks are per side: "no crossfade at the start" stops the fade into a track, "no crossfade at
 * the end" stops the fade out of it. A junction A → B therefore plays without overlap when A's end is
 * marked or B's start is, and crossfades as usual otherwise. Such a junction behaves as if crossfade
 * were off: with skip silence on, the track simply ends where its music ends.
 */
object CrossfadeExclusions {
    /** Whether the junction from [currentId] into [nextId] must not crossfade. */
    fun blocksJunction(
        currentId: String?,
        nextId: String?,
        excludedEnds: Set<String>,
        excludedStarts: Set<String>,
    ): Boolean = (currentId != null && currentId in excludedEnds) || (nextId != null && nextId in excludedStarts)
}
