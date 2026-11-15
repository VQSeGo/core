package com.maxrave.domain.data.model.searchResult.playlists

import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.type.PlaylistType
import com.maxrave.domain.data.type.SearchResultType
import com.maxrave.domain.utils.isRadioPlaylistId

data class PlaylistsResult(
    val author: String,
    val browseId: String,
    val category: String,
    val itemCount: String,
    val resultType: String,
    val thumbnails: List<Thumbnail>,
    val title: String,
) : PlaylistType,
    SearchResultType {
    override fun playlistType(): PlaylistType.Type =
        if (browseId.isRadioPlaylistId()) {
            PlaylistType.Type.RADIO
        } else {
            PlaylistType.Type.YOUTUBE_PLAYLIST
        }

    override fun objectType(): SearchResultType.Type =
        SearchResultType.Type.PLAYLIST
}