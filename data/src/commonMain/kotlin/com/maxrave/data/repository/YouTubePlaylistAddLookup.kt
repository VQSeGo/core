package com.maxrave.data.repository

import com.maxrave.domain.utils.YouTubePlaylistAdd
import com.maxrave.kotlinytmusicscraper.YouTube

/** One `(videoId, setVideoId)` pair of a YouTube playlist entry. */
internal data class PlaylistEntry(
    val videoId: String,
    val setVideoId: String,
)

internal data class YouTubePlaylistAddOutcome(
    val result: YouTubePlaylistAdd,
    /** The entries to remember: the new ones, or the one the track already has. Never blank. */
    val entries: List<PlaylistEntry>,
)

/**
 * Adds [videoId] to the YouTube playlist [playlistId] and says what came of it.
 *
 * No `dedupeOption` is sent on purpose. YouTube then checks for duplicates itself and refuses the
 * add of a track the playlist holds; ytmusicapi sets `DEDUPE_OPTION_SKIP` only to let duplicates
 * through (`duplicates=True`), so sending it here would create them.
 *
 * A refused add carries no entry, and the body does not name the reason, so every answer without
 * an entry is checked against the playlist itself: a track found there is [YouTubePlaylistAdd.ALREADY_IN_PLAYLIST]
 * (its real entry id is returned), anything else is a failure.
 */
internal suspend fun YouTube.addPlaylistItemChecked(
    playlistId: String,
    videoId: String,
): Result<YouTubePlaylistAddOutcome> =
    addPlaylistItem(playlistId, videoId).mapCatching { response ->
        val added = response.addedEntries()
        if (added.isNotEmpty()) {
            return@mapCatching YouTubePlaylistAddOutcome(
                YouTubePlaylistAdd.ADDED,
                added.map { PlaylistEntry(it.videoId, it.setVideoId) },
            )
        }
        val existing = findSetVideoIdOnline(playlistId, videoId)
        if (existing.isNullOrBlank()) {
            throw IllegalStateException("edit_playlist answered ${response.status.ifBlank { "no status" }} with no entry")
        }
        YouTubePlaylistAddOutcome(
            YouTubePlaylistAdd.ALREADY_IN_PLAYLIST,
            listOf(PlaylistEntry(videoId, existing)),
        )
    }
