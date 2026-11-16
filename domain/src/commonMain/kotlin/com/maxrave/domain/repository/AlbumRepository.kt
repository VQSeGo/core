package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.model.browse.album.AlbumBrowse
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDateTime

interface AlbumRepository {
    fun getAllAlbums(limit: Int): Flow<List<AlbumEntity>>

    fun getAlbum(id: String): Flow<AlbumEntity?>

    fun getAlbumAsFlow(id: String): Flow<AlbumEntity?>

    fun getLikedAlbums(): Flow<List<AlbumEntity>>

    fun insertAlbum(albumEntity: AlbumEntity): Flow<Long>

    suspend fun updateAlbumLiked(
        albumId: String,
        likeStatus: Int,
    )

    suspend fun updateAlbumInLibrary(
        inLibrary: LocalDateTime,
        albumId: String,
    )

    /**
     * Brings the saved row of [fresh] up to what the network says: title, cover, year, artists, track
     * list and the like. Liked, library date and download state stay as they are. Returns whether
     * anything differed (nothing is written when it did not). A missing row is not created.
     */
    suspend fun refreshAlbumContent(fresh: AlbumEntity): Boolean

    suspend fun updateAlbumDownloadState(
        albumId: String,
        downloadState: Int,
    )

    suspend fun deleteFollowedArtistSingleAndAlbum(channelId: String)

    fun getAlbumData(browseId: String): Flow<Resource<AlbumBrowse>>

    fun getAlbumMore(
        browseId: String,
        params: String,
    ): Flow<Pair<String, List<AlbumsResult>>?>
}