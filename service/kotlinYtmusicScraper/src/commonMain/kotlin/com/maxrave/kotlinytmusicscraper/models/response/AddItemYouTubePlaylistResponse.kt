package com.maxrave.kotlinytmusicscraper.models.response

import kotlinx.serialization.Serializable

/**
 * The answer of `browse/edit_playlist` to `ACTION_ADD_VIDEO`. Every field has a default: a refused
 * add (the track is already in the playlist, the edit failed) comes back without
 * `playlistEditResults`, and a model that requires it fails the whole decode, which hides the status.
 */
@Serializable
data class AddItemYouTubePlaylistResponse(
    val status: String = "",
    val playlistEditResults: List<PlaylistEditResult> = emptyList(),
) {
    @Serializable
    data class PlaylistEditResult(
        val playlistEditVideoAddedResultData: PlaylistEditVideoAddedResultData? = null,
    ) {
        @Serializable
        data class PlaylistEditVideoAddedResultData(
            val setVideoId: String = "",
            val videoId: String = "",
        )
    }

    /**
     * The entries YouTube says it created, empty unless the edit succeeded. An entry with no
     * `setVideoId` is dropped: a blank id stored for a track would later be sent as the entry to remove.
     */
    fun addedEntries(): List<PlaylistEditResult.PlaylistEditVideoAddedResultData> =
        if (status != STATUS_SUCCEEDED) {
            emptyList()
        } else {
            playlistEditResults
                .mapNotNull { it.playlistEditVideoAddedResultData }
                .filter { it.setVideoId.isNotBlank() && it.videoId.isNotBlank() }
        }

    companion object {
        const val STATUS_SUCCEEDED = "STATUS_SUCCEEDED"
    }
}
