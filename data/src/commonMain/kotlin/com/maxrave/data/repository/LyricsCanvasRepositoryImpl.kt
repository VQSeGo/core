@file:OptIn(ExperimentalTime::class)

package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.mapping.toLyrics
import com.maxrave.domain.data.entities.LyricsEntity
import com.maxrave.domain.data.entities.TranslatedLyricsEntity
import com.maxrave.domain.data.model.browse.artist.ArtistEditorial
import com.maxrave.domain.data.model.browse.artist.ArtistLogo
import com.maxrave.domain.data.model.browse.artist.ArtistMotion
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.extension.now
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.LyricsCanvasRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.logger.Logger
import com.maxrave.spotify.Spotify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.simpmusic.lyrics.SimpMusicLyricsApiException
import org.simpmusic.lyrics.SimpMusicLyricsClient
import org.simpmusic.lyrics.am.AMArtwork
import org.simpmusic.lyrics.am.AMEditorialVideo
import org.simpmusic.lyrics.am.toImageUrl
import org.simpmusic.lyrics.parser.parseTtmlLyrics
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

internal class LyricsCanvasRepositoryImpl(
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
    private val spotify: Spotify,
    private val simpMusicLyrics: SimpMusicLyricsClient,
) : LyricsCanvasRepository {
    override fun getSavedLyrics(videoId: String): Flow<LyricsEntity?> = flow { emit(localDataSource.getSavedLyrics(videoId)) }.flowOn(Dispatchers.IO)

    override suspend fun insertLyrics(lyricsEntity: LyricsEntity) =
        withContext(Dispatchers.IO) {
            localDataSource.insertLyrics(lyricsEntity)
        }

    override suspend fun insertTranslatedLyrics(translatedLyrics: TranslatedLyricsEntity) =
        withContext(Dispatchers.IO) {
            localDataSource.insertTranslatedLyrics(translatedLyrics)
        }

    override fun getSavedTranslatedLyrics(
        videoId: String,
        language: String,
    ): Flow<TranslatedLyricsEntity?> = flow { emit(localDataSource.getTranslatedLyrics(videoId, language)) }.flowOn(Dispatchers.IO)

    override suspend fun removeTranslatedLyrics(
        videoId: String,
        language: String,
    ) = withContext(Dispatchers.IO) {
        localDataSource.removeTranslatedLyrics(videoId, language)
    }

    override fun getYouTubeCaption(
        preferLang: String,
        videoId: String,
    ): Flow<Resource<Pair<Lyrics, Lyrics?>>> =
        flow {
            runCatching {
                youTube
                    .getYouTubeCaption(videoId, preferLang)
                    .onSuccess { lyrics ->
                        emit(
                            Resource.Success<Pair<Lyrics, Lyrics?>>(
                                Pair(lyrics.first.toLyrics(), lyrics.second?.toLyrics()),
                            ),
                        )
                    }.onFailure { e ->
                        Logger.d("Lyrics", "Error: ${e.message}")
                        emit(Resource.Error<Pair<Lyrics, Lyrics?>>(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getSpotifyLyrics(
        dataStoreManager: DataStoreManager,
        query: String,
        duration: Int?,
    ): Flow<Resource<Lyrics>> =
        flow {
            runCatching {
                val q =
                    query
                        .replace(
                            Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                            " ",
                        ).replace(
                            Regex("( và | & | и | e | und |, |和| dan)"),
                            " ",
                        ).replace("  ", " ")
                        .replace(Regex("([()])"), "")
                        .replace(".", " ")
                        .replace("  ", " ")
                Logger.d("Lyrics", "query: $q")
                var spotifyPersonalToken = ""
                var spotifyClientToken = ""
                if (dataStoreManager.spotifyPersonalToken
                        .first()
                        .isNotEmpty() &&
                    dataStoreManager.spotifyPersonalTokenExpires.first() > Clock.System.now().toEpochMilliseconds() &&
                    dataStoreManager.spotifyPersonalTokenExpires.first() != 0L &&
                    dataStoreManager.spotifyClientTokenExpires.first() > Clock.System.now().toEpochMilliseconds() &&
                    dataStoreManager.spotifyClientTokenExpires.first() != 0L
                ) {
                    spotifyPersonalToken = dataStoreManager.spotifyPersonalToken.first()
                    spotifyClientToken = dataStoreManager.spotifyClientToken.first()
                    Logger.d("Lyrics", "Spotify tokens reused")
                } else if (dataStoreManager.spdc.first().isNotEmpty()) {
                    spotify
                        .getClientToken()
                        .onSuccess {
                            Logger.d("Lyrics", "Spotify client token refreshed")
                            dataStoreManager.setSpotifyClientTokenExpires(
                                (it.grantedToken.expiresAfterSeconds * 1000L) + Clock.System.now().toEpochMilliseconds(),
                            )
                            dataStoreManager.setSpotifyClientToken(it.grantedToken.token)
                            spotifyClientToken = it.grantedToken.token
                        }.onFailure {
                            it.printStackTrace()
                            emit(Resource.Error<Lyrics>("Not found"))
                        }
                    spotify
                        .getPersonalTokenWithTotp(dataStoreManager.spdc.first())
                        .onSuccess {
                            spotifyPersonalToken = it.accessToken
                            dataStoreManager.setSpotifyPersonalToken(spotifyPersonalToken)
                            dataStoreManager.setSpotifyPersonalTokenExpires(
                                it.accessTokenExpirationTimestampMs,
                            )
                        }.onFailure {
                            it.printStackTrace()
                            emit(Resource.Error<Lyrics>("Not found"))
                        }
                }
                if (spotifyPersonalToken.isNotEmpty() && spotifyClientToken.isNotEmpty()) {
                    val authToken = spotifyPersonalToken
                    spotify
                        .searchSpotifyTrack(q, authToken, spotifyClientToken)
                        .onSuccess { searchResponse ->
                            val track =
                                if (duration != 0 && duration != null) {
                                    searchResponse.data?.searchV2?.tracksV2?.items?.find {
                                        abs(
                                            (
                                                (
                                                    (
                                                        it.item
                                                            ?.data
                                                            ?.duration
                                                            ?.totalMilliseconds ?: (0 / 1000)
                                                    ) - duration
                                                )
                                            ),
                                        ) < 1
                                    }
                                        ?: searchResponse.data
                                            ?.searchV2
                                            ?.tracksV2
                                            ?.items
                                            ?.firstOrNull()
                                } else {
                                    searchResponse.data
                                        ?.searchV2
                                        ?.tracksV2
                                        ?.items
                                        ?.firstOrNull()
                                }
                            Logger.d("Lyrics", "track: $track")
                            if (track != null) {
                                spotify
                                    .getSpotifyLyrics(track.item?.data?.id ?: "", spotifyPersonalToken, spotifyClientToken)
                                    .onSuccess {
                                        emit(Resource.Success<Lyrics>(it.toLyrics()))
                                    }.onFailure {
                                        it.printStackTrace()
                                        emit(Resource.Error<Lyrics>("Not found"))
                                    }
                            } else {
                                emit(Resource.Error<Lyrics>("Not found"))
                            }
                        }.onFailure { throwable ->
                            throwable.printStackTrace()
                            emit(Resource.Error<Lyrics>("Not found"))
                        }
                }
            }
        }.flowOn(Dispatchers.IO)

    override fun getLrclibLyricsData(
        sartist: String,
        strack: String,
        duration: Int?,
    ): Flow<Resource<Lyrics>> =
        flow {
            Logger.w("Lyrics", "getLrclibLyricsData: $sartist $strack $duration")
            val qartist =
                sartist
                    .replace(
                        Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                        " ",
                    ).replace(
                        Regex("( và | & | и | e | und |, |和| dan)"),
                        " ",
                    ).replace("  ", " ")
                    .replace(Regex("([()])"), "")
                    .replace(".", " ")
            val qtrack =
                strack
                    .replace(
                        Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                        " ",
                    ).replace(
                        Regex("( và | & | и | e | und |, |和| dan)"),
                        " ",
                    ).replace("  ", " ")
                    .replace(Regex("([()])"), "")
                    .replace(".", " ")
            simpMusicLyrics
                .searchLrclibLyrics(qtrack, qartist, duration)
                .onSuccess {
                    // A search with no match must still answer: the caller falls back to the
                    // offline cache only on an emitted error, and an empty flow skips it.
                    if (it != null) emit(Resource.Success<Lyrics>(it.toLyrics())) else emit(Resource.Error<Lyrics>("Not found"))
                }.onFailure {
                    it.printStackTrace()
                    emit(Resource.Error<Lyrics>("Not found"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getBetterLyrics(
        artist: String,
        track: String,
        duration: Int?,
    ): Flow<Resource<Lyrics>> =
        flow {
            Logger.w("Lyrics", "getBetterLyrics: $artist $track")
            val qartist =
                artist
                    .replace(
                        Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                        " ",
                    ).replace(
                        Regex("( và | & | и | e | und |, |和| dan)"),
                        " ",
                    ).replace("  ", " ")
                    .replace(Regex("([()])"), "")
                    .replace(".", " ")
            val qtrack =
                track
                    .replace(
                        Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                        " ",
                    ).replace(
                        Regex("( và | & | и | e | und |, |和| dan)"),
                        " ",
                    ).replace("  ", " ")
                    .replace(Regex("([()])"), "")
                    .replace(".", " ")
            simpMusicLyrics
                .searchBetterLyrics(qtrack, qartist, duration)
                .onSuccess { ttml ->
                    if (ttml.isNullOrEmpty()) {
                        emit(Resource.Error<Lyrics>("No BetterLyrics found"))
                        return@onSuccess
                    }
                    val lyrics = parseTtmlLyrics(ttml).toLyrics()
                    emit(Resource.Success(lyrics))
                }.onFailure {
                    it.printStackTrace()
                    emit(Resource.Error<Lyrics>("BetterLyrics search failed"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getArtistEditorial(artistName: String): Flow<Resource<ArtistEditorial>> =
        flow {
            simpMusicLyrics
                .searchAMArtist(artistName, limit = 1)
                .onSuccess { artists ->
                    val id = artists.firstOrNull()?.id
                    if (id == null) {
                        // A definitive answer, not a failure: the catalog was reached and holds no
                        // such artist. Handed back as an empty Success so the caller can remember
                        // it and stop asking — an Error here is indistinguishable from a dead
                        // connection, which must NOT be cached.
                        emit(Resource.Success(ArtistEditorial(logo = null, motion = null)))
                        return@onSuccess
                    }
                    simpMusicLyrics
                        .getAMArtist(id)
                        .onSuccess { artist ->
                            val attributes = artist?.attributes
                            val logo = attributes?.editorialArtwork?.musicContentColorLogoTrimmed
                            val srcW = logo?.width ?: 0
                            val srcH = logo?.height ?: 0
                            // Scale down to ~1000px wide, keeping the logo aspect ratio.
                            val targetW = AM_LOGO_TARGET_WIDTH
                            val targetH = if (srcW > 0) (srcH.toLong() * targetW / srcW).toInt() else srcH
                            val artistLogo =
                                logo?.toImageUrl(targetW, targetH)?.let { url ->
                                    ArtistLogo(
                                        logoUrl = url,
                                        bgColorHex = logo.bgColor,
                                        width = srcW,
                                        height = srcH,
                                    )
                                }
                            val motion = attributes?.editorialVideo?.toArtistMotion()
                            // Both halves are optional and are reported as they came. They are
                            // independently present — Billie Eilish ships motion and no name logo,
                            // Taylor Swift the reverse — so failing on a missing logo would throw
                            // the video away for exactly the artists that have one. Neither present
                            // is still a Success, for the same reason as above: this artist
                            // genuinely has nothing, and that is worth remembering.
                            emit(Resource.Success(ArtistEditorial(logo = artistLogo, motion = motion)))
                        }.onFailure {
                            emit(Resource.Error<ArtistEditorial>(it.message ?: "Failed to fetch artist"))
                        }
                }.onFailure {
                    emit(Resource.Error<ArtistEditorial>(it.message ?: "Artist search failed"))
                }
        }.flowOn(Dispatchers.IO)

    /**
     * Resolve the artist's animated artwork down to the two cuts the header can actually use.
     *
     * `motionArtistFullscreen16x9` is the same master as `motionArtistWide16x9` and is read only
     * as a fallback. Each master is collapsed to a single rendition here rather than at play time,
     * for the same reason the album path does it: left to themselves mpv takes the top of the
     * ladder and ExoPlayer picks by bandwidth estimate, so the two platforms would show different
     * files for the same artist.
     */
    private suspend fun AMEditorialVideo.toArtistMotion(): ArtistMotion? {
        val square = motionArtistSquare1x1
        val wide = motionArtistWide16x9 ?: motionArtistFullscreen16x9
        if (square?.video == null && wide?.video == null) return null
        return ArtistMotion(
            squareVideoUrl = square?.video?.let { resolveAMRendition(it) },
            squareThumbUrl = square?.previewFrame?.toPreviewFrameUrl(),
            wideVideoUrl = wide?.video?.let { resolveAMRendition(it) },
            wideThumbUrl = wide?.previewFrame?.toPreviewFrameUrl(),
        )
    }

    /** Falls back to the master, which still plays — just not as cheaply. */
    private suspend fun resolveAMRendition(master: String): String =
        simpMusicLyrics
            .selectAMRendition(master, AM_MIN_RENDITION_WIDTH)
            .getOrNull() ?: master

    // SimpMusic Lyrics
    private val simpMusicLyricsTag = "SimpMusicLyricsRepository"

    /** A 404 only means nobody has added lyrics for this song yet; anything else is a real failure. */
    private fun logSimpMusicLyricsFailure(
        what: String,
        error: Throwable,
    ) {
        val message = "$what: ${error.message}"
        if ((error as? SimpMusicLyricsApiException)?.code == 404) {
            Logger.d(simpMusicLyricsTag, message)
        } else {
            Logger.e(simpMusicLyricsTag, message)
        }
    }

    override fun getSimpMusicLyrics(videoId: String): Flow<Resource<Lyrics>> =
        flow {
            simpMusicLyrics
                .getLyrics(videoId)
                .onSuccess { lyrics ->
                    Logger.d(simpMusicLyricsTag, "Lyrics found: $lyrics")
                    val result = lyrics.firstOrNull()
                    if (result == null) {
                        Logger.w(simpMusicLyricsTag, "No lyrics found for videoId: $videoId")
                        emit(Resource.Error<Lyrics>("No lyrics found"))
                        return@onSuccess
                    }
                    val appLyrics = result.toLyrics()
                    if (appLyrics == null) {
                        Logger.w(simpMusicLyricsTag, "Failed to convert lyrics for videoId: $videoId")
                        emit(Resource.Error<Lyrics>("Failed to convert lyrics"))
                        return@onSuccess
                    }
                    emit(
                        Resource.Success<Lyrics>(
                            appLyrics,
                        ),
                    )
                }.onFailure {
                    logSimpMusicLyricsFailure("Get Lyrics Error", it)
                    emit(Resource.Error<Lyrics>(it.message ?: "Failed to get lyrics"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getSimpMusicTranslatedLyrics(
        videoId: String,
        language: String,
    ): Flow<Resource<Lyrics>> =
        flow {
            simpMusicLyrics
                .getTranslatedLyrics(videoId, language)
                .onSuccess { lyrics ->
                    Logger.d(simpMusicLyricsTag, "Translated Lyrics found: ${lyrics.toLyrics()}")
                    emit(
                        Resource.Success<Lyrics>(
                            lyrics.toLyrics(),
                        ),
                    )
                }.onFailure {
                    logSimpMusicLyricsFailure("Get Translated Lyrics Error", it)
                    emit(Resource.Error<Lyrics>(it.message ?: "Failed to get translated lyrics"))
                }
        }.flowOn(Dispatchers.IO)
}

// Narrowest animated-artwork rendition considered sharp enough. On the measured ladders this lands
// on 830x1106 for the tall cut and 768x768 for the square one, both H.264 at roughly 2-3 Mbps.
private const val AM_MIN_RENDITION_WIDTH = 720

// Only used when AM omits the preview frame's own dimensions, which it normally supplies.
private const val AM_PREVIEW_FRAME_SIZE = 1080

/** The name-logo is served far larger than any header needs; ~1000px wide is plenty. */
private const val AM_LOGO_TARGET_WIDTH = 1000

/**
 * The url is a `{w}x{h}` template and the frame carries its own dimensions — an artist's wide cut
 * is 3840x2160, not square — so asking for a square crop of it would squash the image.
 */
private fun AMArtwork.toPreviewFrameUrl(): String? =
    toImageUrl(
        width = width ?: AM_PREVIEW_FRAME_SIZE,
        height = height ?: AM_PREVIEW_FRAME_SIZE,
    )
