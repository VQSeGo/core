package com.maxrave.data.repository

import com.maxrave.common.Config
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.extension.getFullDataFromDB
import com.maxrave.data.parser.parseArtistData
import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.model.browse.artist.ArtistBrowse
import com.maxrave.domain.repository.ArtistRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlin.concurrent.Volatile

internal class ArtistRepositoryImpl(
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
    private val dataStoreManager: DataStoreManager,
) : ArtistRepository {
    override fun getAllArtists(limit: Int): Flow<List<ArtistEntity>> =
        flow {
            emit(localDataSource.getAllArtists(limit))
        }.flowOn(Dispatchers.IO)

    override fun getArtistById(id: String): Flow<ArtistEntity?> =
        flow {
            emit(localDataSource.getArtist(id))
        }.flowOn(Dispatchers.IO)

    override suspend fun insertArtist(artistEntity: ArtistEntity) =
        withContext(Dispatchers.IO) {
            localDataSource.insertArtist(artistEntity)
        }

    override suspend fun updateArtistImage(
        channelId: String,
        thumbnail: String,
    ) = withContext(
        Dispatchers.Main,
    ) {
        localDataSource.updateArtistImage(
            channelId,
            thumbnail,
        )
    }

    // Moves on every follow, so a sync that read the account before it does not overwrite the tap.
    @Volatile
    private var followWrites = 0
    private val followSyncMutex = Mutex()

    // One follow at a time, and the sync reads and writes only between them: see syncFollowedArtists.
    private val followMutex = Mutex()

    /**
     * Unfollowing also drops what only existed to serve the follow.
     *
     * Flipping the flag was all this ever did, so an artist's notifications and their new-releases
     * tracking row survived every unfollow — and once the unfollowed `artist` row is itself swept by
     * `SongRepository.clearHistoryAndOrphanedSongs`, they have nothing left to point back at.
     */
    private suspend fun setFollowedLocally(
        channelId: String,
        followed: Boolean,
    ) {
        localDataSource.updateFollowed(if (followed) 1 else 0, channelId)
        if (!followed) {
            localDataSource.deleteNotificationsByChannelId(channelId)
            localDataSource.deleteFollowedArtistSingleAndAlbum(channelId)
        }
    }

    override suspend fun updateFollowedStatus(
        channelId: String,
        followedStatus: Int,
    ): Boolean? =
        withContext(Dispatchers.Main) {
            followMutex.withLock {
                followWrites++
                setFollowedLocally(channelId, followedStatus == 1)
                if (dataStoreManager.loggedIn.first() != DataStoreManager.TRUE) {
                    return@withLock null
                }
                val updated = withContext(Dispatchers.IO) { setSubscription(channelId, followedStatus == 1) }
                if (!updated) setFollowedLocally(channelId, followedStatus != 1)
                updated
            }
        }

    override suspend fun syncFollowedArtists() {
        if (dataStoreManager.loggedIn.first() != DataStoreManager.TRUE) return
        if (!followSyncMutex.tryLock()) {
            // One already runs (the app's start-up one): wait for it rather than return at once,
            // so a caller that reads the list afterwards reads what that sync wrote.
            followSyncMutex.withLock { }
            return
        }
        try {
            withContext(Dispatchers.IO) {
                // Read behind a follow still on its way to YouTube, so the list fetched below has it.
                val writes = followMutex.withLock { followWrites }
                youTube
                    .getLibrarySubscriptions()
                    .onSuccess { subscriptions ->
                        // Empty is more likely a page the parser could not read than an account
                        // with no subscriptions.
                        if (subscriptions.isEmpty()) {
                            Logger.w("ArtistRepositoryImpl", "syncFollowedArtists: skipped, no subscriptions read")
                            return@onSuccess
                        }
                        // Under the lock, so a tap cannot land between the check and the writes.
                        followMutex.withLock {
                            // A follow made while this ran is not in the list.
                            if (writes != followWrites) {
                                Logger.w("ArtistRepositoryImpl", "syncFollowedArtists: skipped, a follow changed meanwhile")
                                return@withLock
                            }
                            val followed = getFullDataFromDB { limit, offset -> localDataSource.getFollowedArtists(limit, offset) }
                            val followedIds = followed.map { it.channelId }.toSet()
                            val subscribedIds = subscriptions.map { it.channelId }.toSet()
                            followed.filter { it.channelId !in subscribedIds }.forEach { setFollowedLocally(it.channelId, false) }
                            subscriptions.filter { it.channelId !in followedIds }.forEach { artist ->
                                // Ignored when the row exists. Dated out of the library's recently added.
                                localDataSource.insertArtist(
                                    ArtistEntity(
                                        channelId = artist.channelId,
                                        name = artist.name,
                                        thumbnails = artist.thumbnail,
                                        inLibrary = Config.REMOVED_SONG_DATE_TIME,
                                    ),
                                )
                                setFollowedLocally(artist.channelId, true)
                            }
                        }
                    }.onFailure {
                        Logger.w("ArtistRepositoryImpl", "syncFollowedArtists: ${it.message}")
                    }
            }
        } finally {
            followSyncMutex.unlock()
        }
    }

    /** One subscribe/unsubscribe call, logged on failure. Returns whether the account was updated. */
    private suspend fun setSubscription(
        channelId: String,
        subscribe: Boolean,
    ): Boolean {
        val target = youTube.subscriptionChannelId(channelId).getOrNull() ?: channelId
        val result =
            when {
                subscribe -> youTube.subscribeChannel(target)
                target == channelId -> youTube.unsubscribeChannel(target)
                // A follow from before the button's channel was read went to the page's own id.
                else -> youTube.unsubscribeChannel(target).recoverCatching { youTube.unsubscribeChannel(channelId).getOrThrow() }
            }
        return result
            .onFailure {
                Logger.w("ArtistRepositoryImpl", "Channel subscription sync failed: ${it.message}")
            }.isSuccess
    }

    override fun getFollowedArtists(): Flow<List<ArtistEntity>> =
        flow {
            emit(
                getFullDataFromDB { limit, offset ->
                    localDataSource.getFollowedArtists(limit, offset)
                },
            )
        }.flowOn(Dispatchers.IO)

    override suspend fun updateArtistInLibrary(
        inLibrary: LocalDateTime,
        channelId: String,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updateArtistInLibrary(
            inLibrary,
            channelId,
        )
    }

    override fun getArtistData(channelId: String): Flow<Resource<ArtistBrowse>> =
        flow {
            runCatching {
                youTube
                    .artist(channelId)
                    .onSuccess { result ->
                        emit(Resource.Success<ArtistBrowse>(parseArtistData(result)))
                    }.onFailure { e ->
                        Logger.d("Artist", "Error: ${e.message}")
                        emit(Resource.Error<ArtistBrowse>(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)
}