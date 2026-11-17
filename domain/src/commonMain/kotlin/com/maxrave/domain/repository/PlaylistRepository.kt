package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.YourYouTubePlaylistList
import com.maxrave.domain.data.model.browse.playlist.PlaylistBrowse
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.type.ChartItem
import com.maxrave.domain.data.type.PlaylistType
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDateTime

interface PlaylistRepository {
    fun getAllPlaylists(limit: Int): Flow<List<PlaylistEntity>>

    fun getPlaylist(id: String): Flow<PlaylistEntity?>

    fun getLikedPlaylists(): Flow<List<PlaylistEntity>>

    suspend fun insertPlaylist(playlistEntity: PlaylistEntity)

    suspend fun insertAndReplacePlaylist(playlistEntity: PlaylistEntity)

    suspend fun insertRadioPlaylist(playlistEntity: PlaylistEntity)

    suspend fun updatePlaylistLiked(
        playlistId: String,
        likeStatus: Int,
    )

    suspend fun updatePlaylistInLibrary(
        inLibrary: LocalDateTime,
        playlistId: String,
    )

    /**
     * Brings the saved row of [fresh] up to what the network says: title, author, cover, description,
     * track count and year. Liked, library date and download state stay as they are. The saved track
     * list is replaced when [replaceTracks] (the network sent the whole playlist) or when the
     * network's list is at least as long as the saved one; a first page shorter than the saved list
     * leaves it alone. Returns whether anything differed (nothing is written when it did not). A missing row
     * is not created.
     */
    suspend fun refreshPlaylistContent(
        fresh: PlaylistEntity,
        replaceTracks: Boolean,
    ): Boolean

    suspend fun updatePlaylistDownloadState(
        playlistId: String,
        downloadState: Int,
    )

    fun getAllDownloadedPlaylist(): Flow<List<PlaylistType>>

    fun getAllDownloadingPlaylist(): Flow<List<PlaylistType>>

    fun getRadio(
        radioId: String,
        defaultDescription: String,
        radioString: String,
        viewString: String,
        originalTrack: SongEntity? = null,
        artist: ArtistEntity? = null,
    ): Flow<Resource<Pair<PlaylistBrowse, String?>>>

    fun getRDATRadioData(
        radioId: String,
        viewString: String,
    ): Flow<Resource<Pair<PlaylistBrowse, String?>>>

    fun getFullPlaylistData(
        playlistId: String,
        viewString: String,
    ): Flow<Resource<PlaylistBrowse>>

    fun getPlaylistData(
        playlistId: String,
        viewString: String,
    ): Flow<Resource<Pair<PlaylistBrowse, String?>>>

    fun getLibraryPlaylist(): Flow<List<PlaylistsResult>?>

    fun getMixedForYou(): Flow<List<PlaylistsResult>?>

    fun updateYourYouTubePlaylistTitle(
        playlistId: String,
        newTitle: String,
    ): Flow<Resource<String>>

    /**
     * Creates a private playlist named [title] in the signed-in account. It is saved here as a
     * favourite at once and shows in the account's playlist list from then on. Success carries the
     * new playlist's id.
     */
    fun createYouTubePlaylist(title: String): Flow<Resource<String>>

    /**
     * Deletes one of the account's own playlists from YouTube, then everything kept of it here: the
     * saved copy, its entry ids, its place in the cached list of the account's playlists, and the
     * link of any local playlist synced to it.
     */
    fun deleteYouTubePlaylist(playlistId: String): Flow<Resource<String>>

    /**
     * Removes the entry [setVideoId] (song [videoId]) from one of the account's own playlists. A null
     * [setVideoId] is looked up from the playlist first. The saved copy loses the song too. Success
     * carries the setVideoId that was removed.
     */
    fun removeYouTubePlaylistItem(
        playlistId: String,
        videoId: String,
        setVideoId: String?,
    ): Flow<Resource<String>>

    /** Goes up by one whenever the app creates or deletes one of the account's playlists. */
    val youTubePlaylistsVersion: StateFlow<Int>

    suspend fun insertYourYouTubePlaylist(yourYouTubePlaylist: YourYouTubePlaylistList)

    /**
     * @param emailPageId = $email_$pageId
     */
    fun getYourYouTubePlaylistList(emailPageId: String): Flow<YourYouTubePlaylistList?>

    suspend fun deleteAllYourYouTubePlaylist()

    /**
     * @return Country Code -> YouTube Music Playlist ID
     */
    fun getChartPlaylist(): Flow<Resource<List<ChartItem>>>
}