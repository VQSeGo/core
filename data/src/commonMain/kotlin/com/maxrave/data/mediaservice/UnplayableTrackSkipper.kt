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
 * - Stop (pause) at the end of the queue, under Repeat One, and after [maxConsecutiveFailures]
 *   failures in a row, so a queue where nothing plays is not cycled through forever. A track that
 *   starts playing ([onPlaying]) clears the count.
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

    fun onPlaying() {
        failures = 0
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
        if (target == null) {
            Logger.i("Offline", "skip $failedId stop failures=$failures")
            // Whatever comes next is the listener's own request (Play, another track): a new run.
            failures = 0
            return Step.Stop
        }
        Logger.i("Offline", "skip $failedId -> ${player.getMediaItemAt(target)?.mediaId} failures=$failures")
        return Step.Skip(target, announce = failures == 1)
    }

    /** Timeline index to go to, in play order (shuffle included); null at the end of the queue. */
    private fun nextTarget(player: MediaPlayerInterface): Int? {
        val count = player.mediaItemCount
        val order =
            if (player.shuffleModeEnabled) {
                (0 until count).map { player.getUnshuffledIndex(it) }.filter { it in 0 until count }
            } else {
                (0 until count).toList()
            }
        val position = order.indexOf(player.currentMediaItemIndex)
        if (position < 0) return null
        // One lap at most: the tracks after this one, then (Repeat All) those before it.
        val following =
            order.subList(position + 1, order.size) +
                if (player.repeatMode == PlayerConstants.REPEAT_MODE_ALL) order.subList(0, position) else emptyList()
        if (following.isEmpty()) return null
        // A single failure may be one track blocked in the region while the network is fine: take the
        // neighbour, so streamed tracks in between are not skipped. A second failure in a row looks like
        // no network: jump to the nearest track on disk.
        if (failures < 2) return following.first()
        return following.firstOrNull { index ->
            player.getMediaItemAt(index)?.mediaId?.let(player::isLocallyPlayable) == true
        } ?: following.first()
    }
}
