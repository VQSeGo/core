@file:OptIn(ExperimentalTime::class)

package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.mapping.toLyrics
import com.maxrave.domain.data.entities.LyricsEntity
import com.maxrave.domain.data.entities.TranslatedLyricsEntity
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
                            Logger.d("Lyrics") { "track: ${Logger.clip(track)}" }
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
                    Logger.d(simpMusicLyricsTag) { "Lyrics found: ${Logger.clip(lyrics)}" }
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
                    Logger.d(simpMusicLyricsTag) { "Translated Lyrics found: ${Logger.clip(lyrics.toLyrics())}" }
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
