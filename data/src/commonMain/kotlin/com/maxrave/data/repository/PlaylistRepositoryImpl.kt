package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.extension.getFullDataFromDB
import com.maxrave.data.mapping.toListTrack
import com.maxrave.data.mapping.toTrack
import com.maxrave.data.mapping.toYouTubeWatchEndpoint
import com.maxrave.data.parser.parseLibraryPlaylist
import com.maxrave.data.parser.parseNextLibraryPlaylist
import com.maxrave.data.parser.parsePlaylistData
import com.maxrave.data.parser.parseSetVideoId
import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.SetVideoIdEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.YourYouTubePlaylistList
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.browse.playlist.Author
import com.maxrave.domain.data.model.browse.playlist.PlaylistBrowse
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.type.ChartItem
import com.maxrave.domain.data.type.PlaylistType
import com.maxrave.domain.extension.now
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.isRadioMix
import com.maxrave.domain.utils.toTrack
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.MusicShelfRenderer
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.WatchEndpoint
import com.maxrave.kotlinytmusicscraper.pages.NextPage
import com.maxrave.kotlinytmusicscraper.parser.getPlaylistContinuation
import com.maxrave.kotlinytmusicscraper.parser.getPlaylistRadioEndpoint
import com.maxrave.kotlinytmusicscraper.parser.getPlaylistShuffleEndpoint
import com.maxrave.kotlinytmusicscraper.parser.playlistContinuationContents
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime

private const val TAG = "PlaylistRepositoryImpl"

internal class PlaylistRepositoryImpl(
    private val dataStoreManager: DataStoreManager,
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
) : PlaylistRepository {
    override fun getAllPlaylists(limit: Int): Flow<List<PlaylistEntity>> =
        flow {
            emit(localDataSource.getAllPlaylists(limit))
        }.flowOn(Dispatchers.IO)

    override fun getPlaylist(id: String): Flow<PlaylistEntity?> =
        flow {
            emit(localDataSource.getPlaylist(id))
        }.flowOn(Dispatchers.IO)

    override fun getLikedPlaylists(): Flow<List<PlaylistEntity>> =
        flow {
            emit(
                getFullDataFromDB { limit, offset ->
                    localDataSource.getLikedPlaylists(limit, offset)
                },
            )
        }.flowOn(Dispatchers.IO)

    override suspend fun insertPlaylist(playlistEntity: PlaylistEntity) =
        withContext(Dispatchers.IO) { localDataSource.insertPlaylist(playlistEntity) }

    override suspend fun insertAndReplacePlaylist(playlistEntity: PlaylistEntity) =
        withContext(Dispatchers.IO) {
            val oldPlaylist = getPlaylist(playlistEntity.id).firstOrNull()
            if (oldPlaylist != null) {
                localDataSource.insertAndReplacePlaylist(
                    playlistEntity.copy(
                        downloadState = oldPlaylist.downloadState,
                        liked = oldPlaylist.liked,
                    ),
                )
            } else {
                localDataSource.insertAndReplacePlaylist(playlistEntity)
            }
        }

    override suspend fun insertRadioPlaylist(playlistEntity: PlaylistEntity) =
        withContext(Dispatchers.IO) { localDataSource.insertRadioPlaylist(playlistEntity) }

    override suspend fun updatePlaylistLiked(
        playlistId: String,
        likeStatus: Int,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updatePlaylistLiked(
            likeStatus,
            playlistId,
        )
    }

    override suspend fun updatePlaylistInLibrary(
        inLibrary: LocalDateTime,
        playlistId: String,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updatePlaylistInLibrary(
            inLibrary,
            playlistId,
        )
    }

    override suspend fun refreshPlaylistContent(
        fresh: PlaylistEntity,
        replaceTracks: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val saved = localDataSource.getPlaylist(fresh.id) ?: return@withContext false
            // A first page shorter than the saved list is a cut of it (paged through for a download):
            // keep the saved one. A page as long as the saved list or longer loses nothing.
            val freshTracks =
                fresh.tracks?.takeIf {
                    it.isNotEmpty() && (replaceTracks || it.size >= (saved.tracks?.size ?: 0))
                }
            val same =
                saved.title == fresh.title &&
                    saved.author == fresh.author &&
                    saved.description == fresh.description &&
                    saved.duration == fresh.duration &&
                    saved.durationSeconds == fresh.durationSeconds &&
                    saved.privacy == fresh.privacy &&
                    (fresh.thumbnails.isEmpty() || saved.thumbnails == fresh.thumbnails) &&
                    saved.trackCount == fresh.trackCount &&
                    saved.year == fresh.year &&
                    (freshTracks == null || saved.tracks == freshTracks)
            if (same) return@withContext false
            localDataSource.updatePlaylistContent(fresh, freshTracks)
            true
        }

    override suspend fun updatePlaylistDownloadState(
        playlistId: String,
        downloadState: Int,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updatePlaylistDownloadState(
            downloadState,
            playlistId,
        )
    }

    override fun getAllDownloadedPlaylist(): Flow<List<PlaylistType>> =
        flow { emit(localDataSource.getAllDownloadedPlaylist()) }.flowOn(Dispatchers.IO)

    override fun getAllDownloadingPlaylist(): Flow<List<PlaylistType>> =
        flow { emit(localDataSource.getAllDownloadingPlaylist()) }.flowOn(Dispatchers.IO)

    private suspend fun insertSetVideoId(setVideoId: SetVideoIdEntity) = withContext(Dispatchers.IO) { localDataSource.insertSetVideoId(setVideoId) }

    override fun getRadio(
        radioId: String,
        defaultDescription: String,
        radioString: String,
        viewString: String,
        originalTrack: SongEntity?,
        artist: ArtistEntity?,
    ): Flow<Resource<Pair<PlaylistBrowse, String?>>> =
        if (radioId.isRadioMix()) {
            getRDATRadioData(radioId, viewString)
        } else {
            flow {
                runCatching {
                    youTube
                        .next(endpoint = WatchEndpoint(playlistId = radioId))
                        .onSuccess { next ->
                            Logger.w("Radio", "Title: ${next.title}")
                            val data: ArrayList<SongItem> = arrayListOf()
                            data.addAll(next.items)
                            var continuation = next.continuation
                            Logger.w("Radio", "data: ${data.size}")
                            var count = 0
                            while (continuation != null && count < 3) {
                                youTube
                                    .next(
                                        endpoint = WatchEndpoint(playlistId = radioId),
                                        continuation = continuation,
                                    ).onSuccess { nextContinue ->
                                        data.addAll(nextContinue.items)
                                        continuation = nextContinue.continuation
                                        if (data.size >= 50) {
                                            count = 3
                                        }
                                        Logger.w("Radio", "data: ${data.size}")
                                        count++
                                    }.onFailure {
                                        count = 3
                                    }
                            }
                            val listTrackResult = data.toListTrack()
                            if (originalTrack != null) {
                                listTrackResult.add(0, originalTrack.toTrack())
                            }
                            Logger.w("Repository", "data: ${data.size}")
                            // Opened by a link there is no seed track or artist in hand: the seed of
                            // `RDAMVM<videoId>` is the first track YouTube returns for it. With nothing to
                            // name it after, the title is the bare word rather than "null Radio".
                            val seedTitle =
                                originalTrack?.title
                                    ?: artist?.name
                                    ?: radioId.takeIf { it.startsWith("RDAMVM") }?.removePrefix("RDAMVM")?.let { seedId ->
                                        listTrackResult.firstOrNull { it.videoId == seedId } ?: listTrackResult.firstOrNull()
                                    }?.title
                            val radioTitle = if (seedTitle.isNullOrBlank()) radioString else "$seedTitle $radioString"
                            val playlistBrowse =
                                PlaylistBrowse(
                                    author = Author(id = "", name = "YouTube Music"),
                                    description =
                                    defaultDescription,
                                    duration = "",
                                    durationSeconds = 0,
                                    id = radioId,
                                    privacy = "PRIVATE",
                                    thumbnails =
                                        listOf(
                                            Thumbnail(
                                                544,
                                                originalTrack?.thumbnails ?: artist?.thumbnails ?: "",
                                                544,
                                            ),
                                        ),
                                    title = radioTitle,
                                    trackCount = listTrackResult.size,
                                    tracks = listTrackResult,
                                    year = now().year.toString(),
                                )
                            Logger.w("Repository", "playlistBrowse: $playlistBrowse")
                            emit(Resource.Success<Pair<PlaylistBrowse, String?>>(Pair(playlistBrowse, continuation)))
                        }.onFailure { exception ->
                            Logger.e("PlaylistRepositoryImpl", "getRadio failed: ${exception.message}", exception)
                            emit(Resource.Error<Pair<PlaylistBrowse, String?>>(exception.message.toString()))
                        }
                }
            }.flowOn(Dispatchers.IO)
        }

    override fun getRDATRadioData(
        radioId: String,
        viewString: String,
    ): Flow<Resource<Pair<PlaylistBrowse, String?>>> =
        flow<Resource<Pair<PlaylistBrowse, String?>>> {
            runCatching {
                val id =
                    if (radioId.startsWith("VL")) {
                        radioId
                    } else {
                        "VL$radioId"
                    }
                youTube
                    .customQuery(browseId = id, setLogin = true)
                    .onSuccess { result ->
                        val listContent: ArrayList<MusicShelfRenderer.Content> = arrayListOf()
                        val data: List<MusicShelfRenderer.Content>? =
                            result.contents
                                ?.singleColumnBrowseResultsRenderer
                                ?.tabs
                                ?.get(
                                    0,
                                )?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.get(
                                    0,
                                )?.musicPlaylistShelfRenderer
                                ?.contents
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.secondaryContents
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicPlaylistShelfRenderer
                                    ?.contents
                        if (data != null) {
                            Logger.d("Data") { "data: ${Logger.clip(data)}" }
                            Logger.d("Data", "data size: ${data.size}")
                            listContent.addAll(data)
                        }
                        val header =
                            result.header?.musicDetailHeaderRenderer
                                ?: result.header?.musicEditablePlaylistDetailHeaderRenderer
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.get(0)
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicResponsiveHeaderRenderer
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.get(0)
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicEditablePlaylistDetailHeaderRenderer
                                    ?.header
                                    ?.musicResponsiveHeaderRenderer
                        Logger.d("Header") { "header: ${Logger.clip(header)}" }
                        val finalContinueParam =
                            result.getPlaylistContinuation()
                        Logger.d("Repository", "playlist data: ${listContent.size}")
                        Logger.d("Repository", "continueParam: $finalContinueParam")
//                        else {
//                            var listTrack = playlistBrowse.tracks.toMutableList()
                        Logger.d("Repository", "playlist final data: ${listContent.size}")
                        if (finalContinueParam != null) {
                            parsePlaylistData(header, listContent, radioId, viewString)?.let { playlist ->
                                emit(
                                    Resource.Success(
                                        Pair(
                                            playlist.copy(
                                                author = Author("", "YouTube Music"),
                                            ),
                                            finalContinueParam,
                                        ),
                                    ),
                                )
                            } ?: emit(Resource.Error("Can't parse data"))
                        } else {
                            emit(Resource.Error("Continue param is null"))
                        }
                    }.onFailure { e ->
                        Logger.e("Playlist Data", e.message ?: "Error")
                        emit(Resource.Error(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getFullPlaylistData(
        playlistId: String,
        viewString: String,
    ): Flow<Resource<PlaylistBrowse>> =
        flow {
            runCatching {
                var id = ""
                id +=
                    if (!playlistId.startsWith("VL")) {
                        "VL$playlistId"
                    } else {
                        playlistId
                    }
                Logger.d("getPlaylistData", "playlist id: $id")
                youTube
                    .customQuery(browseId = id, setLogin = true)
                    .onSuccess { result ->
                        val listContent: ArrayList<Track> = arrayListOf()
                        val data: List<MusicShelfRenderer.Content>? =
                            result.contents
                                ?.singleColumnBrowseResultsRenderer
                                ?.tabs
                                ?.get(
                                    0,
                                )?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.get(
                                    0,
                                )?.musicPlaylistShelfRenderer
                                ?.contents
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.secondaryContents
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicPlaylistShelfRenderer
                                    ?.contents
                        if (data != null) {
                            Logger.d("getPlaylistData") { "data: ${Logger.clip(data)}" }
                            Logger.d("getPlaylistData", "data size: ${data.size}")
                        }
                        val header =
                            result.header?.musicDetailHeaderRenderer
                                ?: result.header?.musicEditablePlaylistDetailHeaderRenderer
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.get(0)
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicResponsiveHeaderRenderer
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.get(0)
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicEditablePlaylistDetailHeaderRenderer
                                    ?.header
                                    ?.musicResponsiveHeaderRenderer
                        Logger.d("getPlaylistData") { "header: ${Logger.clip(header)}" }
                        var continueParam =
                            result.getPlaylistContinuation()
                        var count = 0
                        Logger.d("getPlaylistData", "playlist data: ${listContent.size}")
                        Logger.d("getPlaylistData", "continueParam: $continueParam")
//                        else {
//                            var listTrack = playlistBrowse.tracks.toMutableList()
                        while (continueParam != null) {
                            youTube
                                .customQuery(
                                    browseId = null,
                                    continuation = continueParam,
                                    setLogin = true,
                                ).onSuccess { values ->
                                    Logger.d("getPlaylistData", "continue: $continueParam")
                                    Logger.d(
                                        "getPlaylistData",
                                        "values: ${values.onResponseReceivedActions}",
                                    )
                                    val dataMore: List<SongItem> =
                                        values.onResponseReceivedActions
                                            ?.firstOrNull()
                                            ?.appendContinuationItemsAction
                                            ?.continuationItems
                                            ?.apply {
                                                Logger.w("getPlaylistData", "dataMore: ${this.size}")
                                            }?.mapNotNull {
                                                NextPage.fromMusicResponsiveListItemRenderer(
                                                    it.musicResponsiveListItemRenderer ?: return@mapNotNull null,
                                                )
                                            } ?: emptyList()
                                    listContent.addAll(dataMore.map { it.toTrack() })
                                    continueParam =
                                        values.getPlaylistContinuation()
                                    count++
                                }.onFailure {
                                    Logger.e("getPlaylistData", "Error: ${it.message}")
                                    continueParam = null
                                    count++
                                }
                        }
                        Logger.d("getPlaylistData", "playlist final data: ${listContent.size}")
                        parsePlaylistData(header, data ?: emptyList(), playlistId, viewString)?.let { playlist ->
                            emit(
                                Resource.Success<PlaylistBrowse>(
                                    playlist.copy(
                                        tracks =
                                            playlist.tracks.toMutableList().apply {
                                                addAll(listContent)
                                            },
                                        trackCount = (playlist.trackCount + listContent.size),
                                    ),
                                ),
                            )
                        } ?: emit(Resource.Error<PlaylistBrowse>("Error"))
                    }.onFailure { e ->
                        Logger.e("getPlaylistData", e.message ?: "Error")
                        emit(Resource.Error<PlaylistBrowse>(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getPlaylistData(
        playlistId: String,
        viewString: String,
    ): Flow<Resource<Pair<PlaylistBrowse, String?>>> =
        flow {
            runCatching {
                var id = ""
                id +=
                    if (!playlistId.startsWith("VL")) {
                        "VL$playlistId"
                    } else {
                        playlistId
                    }
                Logger.d("getPlaylistData", "playlist id: $id")
                youTube
                    .customQuery(browseId = id, setLogin = true)
                    .onSuccess { result ->
                        val listContent: ArrayList<Track> = arrayListOf()
                        val data: List<MusicShelfRenderer.Content>? =
                            result.contents
                                ?.singleColumnBrowseResultsRenderer
                                ?.tabs
                                ?.get(
                                    0,
                                )?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.get(
                                    0,
                                )?.musicPlaylistShelfRenderer
                                ?.contents
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.secondaryContents
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicPlaylistShelfRenderer
                                    ?.contents
                        if (data != null) {
                            Logger.d("getPlaylistData") { "data: ${Logger.clip(data)}" }
                            Logger.d("getPlaylistData", "data size: ${data.size}")
                        }
                        val header =
                            result.header?.musicDetailHeaderRenderer
                                ?: result.header?.musicEditablePlaylistDetailHeaderRenderer
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.get(0)
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicResponsiveHeaderRenderer
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.get(0)
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicEditablePlaylistDetailHeaderRenderer
                                    ?.header
                                    ?.musicResponsiveHeaderRenderer
                        Logger.d("getPlaylistData") { "header: ${Logger.clip(header)}" }
                        // YouTube sends the editable header only to the playlist's owner (ytmusicapi
                        // reads `owned` the same way); a saved playlist of someone else's has the
                        // plain one, even though its rows carry entry ids too.
                        val isOwned =
                            result.header?.musicEditablePlaylistDetailHeaderRenderer != null ||
                                result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.firstOrNull()
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.firstOrNull()
                                    ?.musicEditablePlaylistDetailHeaderRenderer != null
                        val continueParam =
                            result.getPlaylistContinuation()
                        val radioEndpoint =
                            result.getPlaylistRadioEndpoint()
                        val shuffleEndpoint =
                            result.getPlaylistShuffleEndpoint()
                        Logger.d("getPlaylistData", "Endpoint: $radioEndpoint $shuffleEndpoint")
                        try {
                            parsePlaylistData(header, data ?: emptyList(), playlistId, viewString)?.let { playlist ->
                                emit(
                                    Resource.Success<Pair<PlaylistBrowse, String?>>(
                                        Pair(
                                            playlist.copy(
                                                tracks =
                                                    playlist.tracks.toMutableList().apply {
                                                        addAll(listContent)
                                                    },
                                                trackCount = (playlist.trackCount + listContent.size),
                                                shuffleEndpoint = shuffleEndpoint?.toYouTubeWatchEndpoint(),
                                                radioEndpoint = radioEndpoint?.toYouTubeWatchEndpoint(),
                                                isOwned = isOwned,
                                            ),
                                            continueParam,
                                        ),
                                    ),
                                )
                            } ?: emit(
                                Resource.Error<
                                    Pair<PlaylistBrowse, String?>,
                                >("Error"),
                            )
                        } catch (e: Exception) {
                            Logger.e("PlaylistRepositoryImpl", "getPlaylistData failed: ${e.message}", e)
                            emit(
                                Resource.Error<
                                    Pair<PlaylistBrowse, String?>,
                                >(e.message.toString()),
                            )
                        }
                    }.onFailure { e ->
                        Logger.e("getPlaylistData", e.message ?: "Error")
                        emit(
                            Resource.Error<
                                Pair<PlaylistBrowse, String?>,
                            >(e.message.toString()),
                        )
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getLibraryPlaylist(): Flow<List<PlaylistsResult>?> =
        libraryPlaylistsFromNetwork().map { list -> withLocalPlaylistChanges(list) }

    private fun libraryPlaylistsFromNetwork(): Flow<List<PlaylistsResult>?> =
        flow {
            youTube
                .getLibraryPlaylists()
                .onSuccess { data ->
                    val input =
                        data.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.get(
                                0,
                            )?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.get(
                                0,
                            )?.gridRenderer
                            ?.items
                    val listItem = mutableListOf<PlaylistsResult>()
                    if (input.isNullOrEmpty()) {
                        Logger.w("Library", "No playlists found")
                        emit(null)
                        return@onSuccess
                    }
                    listItem.addAll(
                        parseLibraryPlaylist(input),
                    )
                    var continuation =
                        data.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                            ?.gridRenderer
                            ?.continuations
                            ?.firstOrNull()
                            ?.nextContinuationData
                            ?.continuation
                    while (continuation != null) {
                        youTube
                            .nextYouTubePlaylists(continuation)
                            .onSuccess { nextData ->
                                continuation = nextData.second
                                Logger.w("Library", "continuation: $continuation")
                                val nextInput = nextData.first
                                listItem.addAll(
                                    parseNextLibraryPlaylist(nextInput),
                                )
                            }.onFailure { exception ->
                                Logger.e("PlaylistRepositoryImpl", "getLibraryPlaylist failed: ${exception.message}", exception)
                                Logger.e("Library", "Error: ${exception.message}")
                                continuation = null
                            }
                    }
                    if (listItem.isNotEmpty()) {
                        emit(listItem)
                        val account = localDataSource.getUsedGoogleAccount()
                        val isNeeded =
                            dataStoreManager.keepYouTubePlaylistOffline.first() == DataStoreManager.TRUE &&
                                dataStoreManager.loggedIn.first() == DataStoreManager.TRUE && account != null
                        if (isNeeded) {
                            insertYourYouTubePlaylist(
                                YourYouTubePlaylistList(
                                    emailPageId = "${account.email}_${account.pageId ?: ""}",
                                    listBrowseIds = listItem.map { it.browseId },
                                ),
                            )
                        }
                    } else {
                        emit(null)
                    }
                }.onFailure { e ->
                    Logger.e("Library", "Error: ${e.message}")
                    Logger.e("PlaylistRepositoryImpl", "getLibraryPlaylist failed: ${e.message}", e)
                    val account = localDataSource.getUsedGoogleAccount()
                    val isNeeded =
                        dataStoreManager.keepYouTubePlaylistOffline.first() == DataStoreManager.TRUE &&
                            dataStoreManager.loggedIn.first() == DataStoreManager.TRUE && account != null
                    if (isNeeded) {
                        val list =
                            getYourYouTubePlaylistList("${account.email}_${account.pageId ?: ""}")
                                .lastOrNull()
                        if (list != null) {
                            emit(
                                list.listBrowseIds.mapNotNull { id ->
                                    getPlaylist(id).lastOrNull()?.let {
                                        PlaylistsResult(
                                            author = it.author ?: "",
                                            browseId = it.id,
                                            category = "",
                                            itemCount = "${ it.trackCount }",
                                            resultType = "",
                                            thumbnails =
                                                listOf(
                                                    Thumbnail(
                                                        width = 544,
                                                        url = it.thumbnails,
                                                        height = 544,
                                                    ),
                                                ),
                                            title = it.title,
                                        )
                                    }
                                },
                            )
                        } else {
                            emit(null)
                        }
                    } else {
                        emit(null)
                    }
                }
        }.flowOn(Dispatchers.IO)

    override fun getMixedForYou(): Flow<List<PlaylistsResult>?> =
        flow {
            youTube
                .getMixedForYou()
                .onSuccess { data ->
                    val input =
                        data.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.get(
                                0,
                            )?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.get(
                                0,
                            )?.gridRenderer
                            ?.items
                    val listItem = mutableListOf<PlaylistsResult>()
                    if (input.isNullOrEmpty()) {
                        Logger.w("Mixed For You", "No playlists found")
                        emit(null)
                        return@onSuccess
                    }
                    listItem.addAll(
                        parseLibraryPlaylist(input),
                    )
                    var continuation =
                        data.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                            ?.gridRenderer
                            ?.continuations
                            ?.firstOrNull()
                            ?.nextContinuationData
                            ?.continuation
                    while (continuation != null) {
                        youTube
                            .nextYouTubePlaylists(continuation)
                            .onSuccess { nextData ->
                                continuation = nextData.second
                                Logger.w("Mixed For You", "continuation: $continuation")
                                val nextInput = nextData.first
                                listItem.addAll(
                                    parseNextLibraryPlaylist(nextInput),
                                )
                            }.onFailure { exception ->
                                Logger.e("PlaylistRepositoryImpl", "getMixedForYou failed: ${exception.message}", exception)
                                Logger.e("Mixed For You", "Error: ${exception.message}")
                                continuation = null
                            }
                    }
                    if (listItem.isNotEmpty()) {
                        emit(listItem)
                    } else {
                        emit(null)
                    }
                }
        }.flowOn(Dispatchers.IO)

    override fun updateYourYouTubePlaylistTitle(
        playlistId: String,
        newTitle: String,
    ): Flow<Resource<String>> =
        flow {
            youTube
                .editPlaylist(playlistId, newTitle)
                .onSuccess {
                    emit(Resource.Success(it.toString()))
                }.onFailure {
                    emit(Resource.Error<String>(it.message ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

    // What this run of the app created and deleted in the account. YouTube's list of the account's
    // playlists can lag behind a create or a delete for a while; these keep the lists in the app
    // right in the meantime. Keyed by the id without "VL".
    private val createdPlaylists = MutableStateFlow<Map<String, PlaylistsResult>>(emptyMap())
    private val deletedPlaylistIds = MutableStateFlow<Set<String>>(emptySet())
    private val _youTubePlaylistsVersion = MutableStateFlow(0)
    override val youTubePlaylistsVersion: StateFlow<Int> = _youTubePlaylistsVersion.asStateFlow()

    private fun withLocalPlaylistChanges(list: List<PlaylistsResult>?): List<PlaylistsResult>? {
        val deleted = deletedPlaylistIds.value
        val created = createdPlaylists.value
        if (deleted.isEmpty() && created.isEmpty()) return list
        val kept = list.orEmpty().filterNot { it.browseId.removePrefix("VL") in deleted }
        val listed = kept.map { it.browseId.removePrefix("VL") }.toSet()
        val missing = created.filterKeys { it !in listed && it !in deleted }.values
        return if (list == null && missing.isEmpty()) null else missing.toList() + kept
    }

    override fun createYouTubePlaylist(title: String): Flow<Resource<String>> =
        flow {
            youTube
                .createPlaylist(title, null)
                .onSuccess { response ->
                    val id = response.playlistId.removePrefix("VL")
                    val author = localDataSource.getUsedGoogleAccount()?.name.orEmpty()
                    Logger.i(TAG, "createYouTubePlaylist: created $id")
                    // Saved as a favourite, which is what puts it on the Desktop sidebar and under
                    // the favourites; the network fills the rest when it is opened.
                    localDataSource.insertAndReplacePlaylist(
                        PlaylistEntity(
                            id = id,
                            author = author,
                            title = title,
                            privacy = "PRIVATE",
                            trackCount = 0,
                            tracks = emptyList(),
                            year = now().year.toString(),
                            liked = true,
                            favoriteAt = now(),
                        ),
                    )
                    deletedPlaylistIds.update { it - id }
                    createdPlaylists.update {
                        it +
                            (
                                id to
                                    PlaylistsResult(
                                        author = author,
                                        browseId = "VL$id",
                                        category = "",
                                        itemCount = "0",
                                        resultType = "",
                                        thumbnails = emptyList(),
                                        title = title,
                                    )
                            )
                    }
                    _youTubePlaylistsVersion.update { it + 1 }
                    emit(Resource.Success(id))
                }.onFailure { e ->
                    Logger.e(TAG, "createYouTubePlaylist failed: ${e.message}", e)
                    emit(Resource.Error<String>(e.message ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

    override fun deleteYouTubePlaylist(playlistId: String): Flow<Resource<String>> =
        flow {
            val id = playlistId.removePrefix("VL")
            youTube
                .deletePlaylist(id)
                .onSuccess { status ->
                    Logger.i(TAG, "deleteYouTubePlaylist: deleted $id (HTTP $status)")
                    forgetYouTubePlaylist(id)
                    emit(Resource.Success(id))
                }.onFailure { e ->
                    Logger.e(TAG, "deleteYouTubePlaylist $id failed: ${e.message}", e)
                    emit(Resource.Error<String>(e.message ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

    /** Everything kept here of a playlist that is gone from the account. */
    private suspend fun forgetYouTubePlaylist(id: String) {
        val spellings = listOf(id, "VL$id")
        val rows = localDataSource.deletePlaylists(spellings)
        localDataSource.deleteSetVideoIdsOfPlaylist(spellings)
        // A local playlist synced to it would go on syncing into a playlist that no longer exists.
        spellings.forEach { spelling ->
            localDataSource.getLocalPlaylistByYoutubePlaylistId(spelling)?.let { local ->
                localDataSource.unsyncLocalPlaylist(local.id)
            }
        }
        val account = localDataSource.getUsedGoogleAccount()
        if (account != null) {
            val emailPageId = "${account.email}_${account.pageId ?: ""}"
            localDataSource.getYourYouTubePlaylistList(emailPageId)?.let { index ->
                val left = index.listBrowseIds.filterNot { it.removePrefix("VL") == id }
                if (left.size != index.listBrowseIds.size) {
                    localDataSource.insertYourYouTubePlaylist(index.copy(listBrowseIds = left))
                }
            }
        }
        createdPlaylists.update { it - id }
        deletedPlaylistIds.update { it + id }
        _youTubePlaylistsVersion.update { it + 1 }
        Logger.i(TAG, "deleteYouTubePlaylist: $id forgotten locally ($rows saved rows)")
    }

    override fun removeYouTubePlaylistItem(
        playlistId: String,
        videoId: String,
        setVideoId: String?,
    ): Flow<Resource<String>> =
        flow {
            val id = playlistId.removePrefix("VL")
            val entry = setVideoId ?: findSetVideoIdOnline(id, videoId)
            if (entry == null) {
                Logger.w(TAG, "removeYouTubePlaylistItem: no entry id for $videoId in $id")
                emit(Resource.Error<String>("No setVideoId for $videoId"))
                return@flow
            }
            youTube
                .removeItemYouTubePlaylist(id, videoId, entry)
                .onSuccess {
                    // The saved copy loses the song too, so the next open does not show it from
                    // there before the network answers.
                    localDataSource.getPlaylist(id)?.let { saved ->
                        val tracks = saved.tracks?.toMutableList()
                        if (tracks != null && tracks.remove(videoId)) {
                            localDataSource.insertAndReplacePlaylist(
                                saved.copy(tracks = tracks, trackCount = (saved.trackCount - 1).coerceAtLeast(0)),
                            )
                        }
                    }
                    listOf(id, "VL$id").forEach { localDataSource.deleteSetVideoId(videoId, it) }
                    Logger.i(TAG, "removeYouTubePlaylistItem: removed $videoId from $id, saved copy updated")
                    emit(Resource.Success(entry))
                }.onFailure { e ->
                    Logger.e(TAG, "removeYouTubePlaylistItem $videoId from $id failed: ${e.message}", e)
                    emit(Resource.Error<String>(e.message ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

    /** Pages through playlist [id] until the row of [videoId] turns up, for its entry id. */
    private suspend fun findSetVideoIdOnline(
        id: String,
        videoId: String,
    ): String? {
        val first = youTube.customQuery(browseId = "VL$id", setLogin = true).getOrNull() ?: return null
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
            parseSetVideoId(id, rows).firstOrNull { it.videoId == videoId }?.setVideoId?.let { return it }
            val next = continuation?.takeIf { seen.add(it) } ?: return null
            val page = youTube.customQuery("", continuation = next, setLogin = true).getOrNull() ?: return null
            rows = page.playlistContinuationContents().orEmpty()
            continuation = page.getPlaylistContinuation()
        }
    }

    override suspend fun insertYourYouTubePlaylist(yourYouTubePlaylist: YourYouTubePlaylistList) =
        withContext(Dispatchers.IO) {
            localDataSource.insertYourYouTubePlaylist(yourYouTubePlaylist)
        }

    override suspend fun deleteAllYourYouTubePlaylist() =
        withContext(Dispatchers.IO) {
            localDataSource.deleteAllYourYouTubePlaylist()
        }

    override fun getYourYouTubePlaylistList(emailPageId: String): Flow<YourYouTubePlaylistList?> =
        flow {
            emit(localDataSource.getYourYouTubePlaylistList(emailPageId))
        }.flowOn(Dispatchers.IO)

    override fun getChartPlaylist(): Flow<Resource<List<ChartItem>>> =
        flow {
            youTube
                .getSimpMusicChart()
                .onSuccess { response ->
                    val data = response.data?.filterNotNull() ?: emptyList()
                    val result =
                        data.mapNotNull {
                            ChartItem(
                                name = it.name ?: return@mapNotNull null,
                                ytPlaylistId = it.youtubePlaylistId ?: return@mapNotNull null,
                            )
                        }
                    emit(Resource.Success(result))
                }.onFailure { exception ->
                    Logger.e("PlaylistRepositoryImpl", "getChartPlaylist failed: ${exception.message}", exception)
                    emit(Resource.Error<List<ChartItem>>(exception.message ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)
}