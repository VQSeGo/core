package com.maxrave.data.mediaservice

import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.logger.Logger

/**
 * What both player handlers do when the current track cannot be played (no stream without the
 * network, a video unavailable in the region): move on instead of pausing the whole queue. Shared
 * so that Android and desktop decide alike.
 *
 * - Nothing was asked to play (a queue restored at startup): stay on the track, quietly. Pressing
 *   Play later tries it again, and a second failure then skips.
 * - Otherwise skip to the next track; from the second failure in a row on, to the nearest following
 *   track that plays from disk ([MediaPlayerInterface.isLocallyPlayable]), or the next one if none.
 *   "Next" means the way the listener last moved ([onMediaItemTransition]): after Previous the skip
 *   keeps going backwards, so Back can pass an unavailable track. With nothing left behind it turns
 *   forward again rather than pausing on a track that cannot play.
 * - Stop (pause) at the end of the queue, under Repeat One, and after [maxConsecutiveFailures]
 *   failures in a row, so a queue where nothing plays is not cycled through forever. A track that
 *   really plays ([onPlaying]) clears the count: on Android once its position has moved, since the
 *   crossfade adapter reports "playing" for a track with no source as well.
 *
 * Not thread-safe: call it from the thread the player reports on.
 */
internal class UnplayableTrackSkipper(
    private val maxConsecutiveFailures: Int = 3,
) {
    sealed interface Step {
        /** Leave the player where it is, no message. */
        data object Stay : Step

        /** Load [index] (a timeline index); [announce] is true for the first failure of a run. */
        data class Skip(
            val index: Int,
            val announce: Boolean,
        ) : Step

        /** Pause and say so. */
        data object Stop : Step
    }

    private var failures = 0

    /** The track the player was last on, to tell which way the listener moved. */
    private var lastMediaId: String? = null

    /** The listener last moved towards the start of the queue. */
    private var backward = false

    /** Timeline index of a skip this class asked for: it is not the listener navigating. */
    private var ownTarget: Int? = null

    fun onPlaying() {
        failures = 0
    }

    /**
     * Remember which way the queue was last moved by the listener (Previous, Next, a track picked in the
     * queue, the end of a track). The direction is read off the play order rather than off [reason]: both
     * custom players report every load as "auto", whoever asked for it. A track that is not in the queue
     * any more (a new queue) resets it.
     */
    fun onMediaItemTransition(
        player: MediaPlayerInterface,
        reason: Int,
    ) {
        val index = player.currentMediaItemIndex
        val id = player.currentMediaItem?.mediaId
        val previousId = lastMediaId
        lastMediaId = id
        if (ownTarget == index) {
            ownTarget = null
            return
        }
        if (reason == PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_REPEAT || id == null || id == previousId) return
        if (previousId == null) return
        val order = playOrder(player)
        val position = order.indexOf(index)
        val previous = order.indexOfFirst { player.getMediaItemAt(it)?.mediaId == previousId }
        if (position < 0 || previous < 0) {
            backward = false
            return
        }
        backward =
            direction(
                previous = previous,
                position = position,
                size = order.size,
                repeatAll = player.repeatMode == PlayerConstants.REPEAT_MODE_ALL,
            )
    }

    fun onError(player: MediaPlayerInterface): Step {
        val failedId = player.currentMediaItem?.mediaId
        if (!player.playWhenReady) {
            Logger.i("Offline", "skip $failedId stay (not playing)")
            return Step.Stay
        }
        failures++
        val target =
            if (failures >= maxConsecutiveFailures || player.repeatMode == PlayerConstants.REPEAT_MODE_ONE) {
                null
            } else {
                nextTarget(player)
            }
        ownTarget = target
        if (target == null) {
            Logger.i("Offline", "skip $failedId stop failures=$failures")
            // Whatever comes next is the listener's own request (Play, another track): a new run.
            failures = 0
            return Step.Stop
        }
        Logger.i(
            "Offline",
            "skip $failedId -> ${player.getMediaItemAt(target)?.mediaId} failures=$failures backward=$backward",
        )
        return Step.Skip(target, announce = failures == 1)
    }

    /** Timeline indices in play order (shuffle included). */
    private fun playOrder(player: MediaPlayerInterface): List<Int> {
        val count = player.mediaItemCount
        return if (player.shuffleModeEnabled) {
            (0 until count).map { player.getUnshuffledIndex(it) }.filter { it in 0 until count }
        } else {
            (0 until count).toList()
        }
    }

    /** Timeline index to go to, in the listener's direction; null at the end of the queue. */
    private fun nextTarget(player: MediaPlayerInterface): Int? {
        val order = playOrder(player)
        val position = order.indexOf(player.currentMediaItemIndex)
        if (position < 0) return null
        val repeatAll = player.repeatMode == PlayerConstants.REPEAT_MODE_ALL
        var candidates = candidates(order, position, backward, repeatAll)
        if (candidates.isEmpty() && backward) {
            // Nothing behind: the unavailable track is the first of the queue. Go forward, to the track the
            // listener came from, instead of pausing here. The direction stays turned until the next move.
            backward = false
            candidates = candidates(order, position, false, repeatAll)
        }
        if (candidates.isEmpty()) return null
        // A single failure may be one track blocked in the region while the network is fine: take the
        // neighbour, so streamed tracks in between are not skipped. A second failure in a row looks like
        // no network: jump to the nearest track on disk.
        if (failures < 2) return candidates.first()
        return candidates.firstOrNull { index ->
            player.getMediaItemAt(index)?.mediaId?.let(player::isLocallyPlayable) == true
        } ?: candidates.first()
    }

    companion object {
        /**
         * True when going from play-order position [previous] to [position] is a step back. Under Repeat All
         * the wrap-around counts the way it was meant: first to last is Previous, last to first is Next.
         */
        fun direction(
            previous: Int,
            position: Int,
            size: Int,
            repeatAll: Boolean,
        ): Boolean =
            when {
                repeatAll && size > 2 && previous == 0 && position == size - 1 -> true
                repeatAll && size > 2 && previous == size - 1 && position == 0 -> false
                else -> position < previous
            }

        /**
         * Tracks to try, nearest first: one lap at most. Forward: those after [position], then (Repeat All)
         * those before it. Backward: those before it, last first, then (Repeat All) those after.
         */
        fun candidates(
            order: List<Int>,
            position: Int,
            backward: Boolean,
            repeatAll: Boolean,
        ): List<Int> =
            if (backward) {
                order.subList(0, position).reversed() +
                    if (repeatAll) order.subList(position + 1, order.size).reversed() else emptyList()
            } else {
                order.subList(position + 1, order.size) +
                    if (repeatAll) order.subList(0, position) else emptyList()
            }
    }
}
