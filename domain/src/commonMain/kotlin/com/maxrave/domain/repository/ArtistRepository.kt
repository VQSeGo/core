package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.entities.ArtistMotionEntity
import com.maxrave.domain.data.model.browse.artist.ArtistBrowse
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDateTime

interface ArtistRepository {
    fun getAllArtists(limit: Int): Flow<List<ArtistEntity>>

    fun getArtistById(id: String): Flow<ArtistEntity?>

    suspend fun insertArtist(artistEntity: ArtistEntity)

    suspend fun updateArtistImage(
        channelId: String,
        thumbnail: String,
    )

    suspend fun updateArtistNameLogo(
        channelId: String,
        nameLogoUrl: String?,
        nameLogoColor: String?,
    )

    /**
     * The cached Apple Music animated artwork for this artist, or null when it has never been
     * looked up. A row whose urls are all null means "looked up, has none" — see
     * [com.maxrave.domain.data.entities.ArtistMotionEntity].
     */
    suspend fun getArtistMotion(channelId: String): ArtistMotionEntity?

    suspend fun upsertArtistMotion(motion: ArtistMotionEntity)

    /**
     * Follows or unfollows on the YouTube account; the local flag is a copy of that, see
     * [syncFollowedArtists].
     *
     * Returns null when signed out, so only the local flag changed; true when the account was
     * updated; false when the call failed, and the local flag has been put back.
     */
    suspend fun updateFollowedStatus(
        channelId: String,
        followedStatus: Int,
    ): Boolean?

    /** Makes the followed artists match the account's subscriptions. Writes nothing to the account. */
    suspend fun syncFollowedArtists()

    fun getFollowedArtists(): Flow<List<ArtistEntity>>

    suspend fun updateArtistInLibrary(
        inLibrary: LocalDateTime,
        channelId: String,
    )

    fun getArtistData(channelId: String): Flow<Resource<ArtistBrowse>>
}