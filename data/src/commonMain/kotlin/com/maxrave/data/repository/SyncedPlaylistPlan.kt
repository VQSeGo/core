package com.maxrave.data.repository

/**
 * What an update from YouTube does to a local playlist synced to it: the tracks that leave the
 * playlist, and the order the rest takes.
 */
internal data class SyncedPlaylistPlan(
    val removed: List<String>,
    val order: List<String>,
)

/**
 * Follows YouTube's list for a synced local playlist.
 *
 * A local track YouTube no longer has leaves the playlist, but only one [confirmed] to have been in
 * YouTube's playlist (it has a setVideoId on record). A track the app never managed to add there,
 * because the request failed, is absent from YouTube for another reason and stays. Nothing is
 * removed unless [complete] says the YouTube list was read to its end with no row lost, and never
 * on an empty one; then the answer only adds, after the local tracks, as it always did.
 *
 * The order is YouTube's, then the local tracks YouTube does not have, as they were.
 *
 * @param local the playlist's tracks in their current order
 * @param remote the video ids of YouTube's playlist in its order
 */
internal fun planSyncedPlaylist(
    local: List<String>,
    remote: List<String>,
    confirmed: Set<String>,
    complete: Boolean,
): SyncedPlaylistPlan {
    val remoteSet = remote.toSet()
    if (!complete || remote.isEmpty()) {
        val localSet = local.toSet()
        return SyncedPlaylistPlan(
            removed = emptyList(),
            order = local + remote.distinct().filter { it !in localSet },
        )
    }
    val removed = local.filter { it !in remoteSet && it in confirmed }.distinct()
    val removedSet = removed.toSet()
    val keptLocalOnly = local.filter { it !in remoteSet && it !in removedSet }.distinct()
    return SyncedPlaylistPlan(
        removed = removed,
        order = remote.distinct() + keptLocalOnly,
    )
}
