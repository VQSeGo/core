package com.maxrave.data.repository

import com.maxrave.data.parser.parseSetVideoId
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.parser.getPlaylistContinuation
import com.maxrave.kotlinytmusicscraper.parser.playlistContinuationContents

/**
 * Pages through the YouTube playlist [id] until the row of [videoId] turns up, for its entry id
 * (`setVideoId`), which removing a track from the playlist needs. Null if the playlist cannot be
 * read or does not hold the track.
 */
internal suspend fun YouTube.findSetVideoIdOnline(
    id: String,
    videoId: String,
): String? {
    val bareId = id.removePrefix("VL")
    val first = customQuery(browseId = "VL$bareId", setLogin = true).getOrNull() ?: return null
    var rows =
        first.contents
            ?.twoColumnBrowseResultsRenderer
            ?.secondaryContents
            ?.sectionListRenderer
            ?.contents
            ?.firstOrNull()
            ?.musicPlaylistShelfRenderer
            ?.contents
            .orEmpty()
    var continuation = first.getPlaylistContinuation()
    val seen = mutableSetOf<String>()
    while (true) {
        parseSetVideoId(bareId, rows).firstOrNull { it.videoId == videoId }?.setVideoId?.let { return it }
        val next = continuation?.takeIf { seen.add(it) } ?: return null
        val page = customQuery("", continuation = next, setLogin = true).getOrNull() ?: return null
        rows = page.playlistContinuationContents().orEmpty()
        continuation = page.getPlaylistContinuation()
    }
}
