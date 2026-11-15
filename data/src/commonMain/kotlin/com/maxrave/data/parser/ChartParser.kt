package com.maxrave.data.parser

import com.maxrave.domain.data.model.browse.artist.ResultPlaylist
import com.maxrave.domain.data.model.home.chart.Artists
import com.maxrave.domain.data.model.home.chart.Chart
import com.maxrave.domain.data.model.home.chart.ChartItemPlaylist
import com.maxrave.domain.data.model.home.chart.ItemArtist
import com.maxrave.kotlinytmusicscraper.models.MusicCarouselShelfRenderer
import com.maxrave.kotlinytmusicscraper.models.SectionListRenderer

internal fun parseChart(data: SectionListRenderer?): Chart? {
    val content = data?.contents ?: return null
    val listArtistItem: ArrayList<ItemArtist> = arrayListOf()
    val listItemPlaylist: ArrayList<ChartItemPlaylist> = arrayListOf()
    content.forEach {
        val contents = it.musicCarouselShelfRenderer?.contents ?: return@forEach
        val pageType =
            contents
                .firstOrNull()
                ?.musicResponsiveListItemRenderer
                ?.navigationEndpoint
                ?.browseEndpoint
                ?.browseEndpointContextSupportedConfigs
                ?.browseEndpointContextMusicConfig
                ?.pageType
        if (pageType == "MUSIC_PAGE_TYPE_ARTIST") {
            parseArtistChart(contents)?.let { listArtistItem.addAll(it) }
        } else if (pageType == "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE") {
            // Podcast shows are not shown: this shelf is dropped whole.
            return@forEach
        } else {
            // Only two-row playlist cards belong here; a shelf of list rows of any other kind adds
            // nothing, and must not blank the whole chart.
            val playlists =
                contents.mapNotNull { contentItem ->
                    val item = contentItem.musicTwoRowItemRenderer ?: return@mapNotNull null
                    ResultPlaylist(
                        id = item.navigationEndpoint?.browseEndpoint?.browseId ?: return@mapNotNull null,
                        thumbnails =
                            item.thumbnailRenderer
                                ?.musicThumbnailRenderer
                                ?.thumbnail
                                ?.thumbnails
                                ?.toListThumbnail() ?: emptyList(),
                        title = item.title?.runs?.firstOrNull()?.text ?: "",
                        author = item.subtitle?.runs?.joinToString(separator = " ") { it.text } ?: "",
                    )
                }
            if (playlists.isNotEmpty()) {
                listItemPlaylist.add(
                    ChartItemPlaylist(
                        title =
                            it.musicCarouselShelfRenderer
                                ?.header
                                ?.musicCarouselShelfBasicHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text ?: "",
                        playlists = playlists,
                    ),
                )
            }
        }
    }
    return Chart(
        artists = Artists(itemArtists = listArtistItem, playlist = ""),
        countries = null,
        listChartItem = listItemPlaylist,
    )
}

internal fun parseArtistChart(contents: List<MusicCarouselShelfRenderer.Content>?): ArrayList<ItemArtist>? =
    if (contents != null) {
        val artists: ArrayList<ItemArtist> = arrayListOf()
        for (i in contents.indices) {
            val content = contents[i]
            if (content.musicResponsiveListItemRenderer != null) {
                val title =
                    content.musicResponsiveListItemRenderer
                        ?.flexColumns
                        ?.get(0)
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text
                        ?.runs
                        ?.get(
                            0,
                        )?.text
                val subscriber =
                    content.musicResponsiveListItemRenderer
                        ?.flexColumns
                        ?.get(1)
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text
                        ?.runs
                        ?.get(
                            0,
                        )?.text
                val thumbnails =
                    content.musicResponsiveListItemRenderer
                        ?.thumbnail
                        ?.musicThumbnailRenderer
                        ?.thumbnail
                        ?.thumbnails
                val artistId =
                    content.musicResponsiveListItemRenderer
                        ?.navigationEndpoint
                        ?.browseEndpoint
                        ?.browseId
                artists.add(
                    ItemArtist(
                        browseId = artistId ?: "",
                        rank = "${i + 1}",
                        subscribers = subscriber ?: "",
                        thumbnails = thumbnails?.toListThumbnail() ?: listOf(),
                        title = title ?: "",
                        trend = "",
                    ),
                )
            }
        }
        artists
    } else {
        null
    }