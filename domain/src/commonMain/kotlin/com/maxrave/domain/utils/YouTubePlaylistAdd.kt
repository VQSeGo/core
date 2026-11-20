package com.maxrave.domain.utils

/** What adding a track to one of the user's YouTube playlists came to; failures are [LocalResource.Error]. */
enum class YouTubePlaylistAdd {
    /** YouTube created a new entry for the track. */
    ADDED,

    /** The track was in the playlist already; nothing was added and nothing is wrong. */
    ALREADY_IN_PLAYLIST,
}
