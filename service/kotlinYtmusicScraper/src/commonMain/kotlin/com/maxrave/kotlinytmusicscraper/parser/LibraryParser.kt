package com.maxrave.kotlinytmusicscraper.parser

import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicShelfRenderer
import com.maxrave.kotlinytmusicscraper.models.getContinuation
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse

/** An artist the signed-in account is subscribed to. */
data class LibrarySubscription(
    val channelId: String,
    val name: String,
    val thumbnail: String?,
)

/** The shelf of a library page, directly in the section list or inside an item section. */
fun BrowseResponse.libraryShelf(): MusicShelfRenderer? =
    (
        contents?.singleColumnBrowseResultsRenderer?.tabs
            ?: contents?.twoColumnBrowseResultsRenderer?.tabs
    )?.firstOrNull()
        ?.tabRenderer
        ?.content
        ?.sectionListRenderer
        ?.contents
        ?.firstNotNullOfOrNull { section ->
            section.musicShelfRenderer
                ?: section.itemSectionRenderer?.contents?.firstNotNullOfOrNull { it.musicShelfRenderer }
        }

fun MusicShelfRenderer.subscriptions(): List<LibrarySubscription> =
    contents.orEmpty().mapNotNull { it.musicResponsiveListItemRenderer?.toLibrarySubscription() }

fun MusicShelfRenderer.nextPage(): String? =
    continuations?.getContinuation()
        ?: contents
            ?.lastOrNull()
            ?.continuationItemRenderer
            ?.continuationEndpoint
            ?.continuationCommand
            ?.token

/** The rows of a continuation page, in either of the shapes YouTube answers with. */
fun BrowseResponse.continuationSubscriptions(): List<LibrarySubscription> =
    (
        continuationContents?.musicShelfContinuation?.contents?.map { it.musicResponsiveListItemRenderer }
            ?: playlistContinuationContents()?.map { it.musicResponsiveListItemRenderer }
    ).orEmpty().mapNotNull { it?.toLibrarySubscription() }

fun BrowseResponse.nextContinuationPage(): String? =
    continuationContents?.musicShelfContinuation?.continuations?.getContinuation() ?: getPlaylistContinuation()

private fun MusicResponsiveListItemRenderer.toLibrarySubscription(): LibrarySubscription? {
    // MPLA marks an artist page opened from the library; the rest of the id is the channel.
    val channelId = navigationEndpoint?.browseEndpoint?.browseId?.removePrefix("MPLA")?.takeIf { it.startsWith("UC") } ?: return null
    val name =
        flexColumns
            .firstOrNull()
            ?.musicResponsiveListItemFlexColumnRenderer
            ?.text
            ?.runs
            ?.firstOrNull()
            ?.text ?: return null
    return LibrarySubscription(channelId, name, thumbnail?.musicThumbnailRenderer?.getThumbnailUrl())
}
