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
import com.maxrave.domain.repository.NO_MORE_LYRICS_VARIANTS
import com.maxrave.domain.utils.Resource
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.logger.Logger
import com.maxrave.spotify.Spotify
import com.maxrave.spotify.SpotifyLyricsNotFoundException
import com.maxrave.spotify.SpotifyUnauthorizedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.simpmusic.lyrics.LyricsQuery
import org.simpmusic.lyrics.NoMoreLyricsVariantsException
import org.simpmusic.lyrics.SimpMusicLyricsApiException
import org.simpmusic.lyrics.SimpMusicLyricsClient
import org.simpmusic.lyrics.parser.parseTtmlLyrics
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
        title: String,
        artist: String,
        duration: Int?,
        variant: Int,
    ): Flow<Resource<Lyrics>> =
        flow {
            runCatching {
                val songTitle = LyricsQuery.cleanTitle(title, artist)
                val q =
                    "$songTitle ${LyricsQuery.cleanArtist(artist)}"
                        .replace(
                            Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                            " ",
                        ).replace(
                            Regex("( và | & | и | e | und |, |和| dan )"),
                            " ",
                        ).replace("  ", " ")
                        .replace(Regex("([()])"), "")
                        .replace(".", " ")
                        .replace("  ", " ")
                Logger.d("Lyrics", "query: $q")
                // Both expiry keys hold epoch milliseconds. Older builds also wrote epoch seconds (a reset at
                // launch), which is about 1000 times smaller than now in milliseconds and so reads as expired.
                // The margin keeps a token that dies mid-request from being reused.
                val expiryMarginMs = 60_000L
                var forceRefresh = false
                for (attempt in 0..1) {
                    var spotifyPersonalToken = ""
                    var spotifyClientToken = ""
                    var reused = false
                    val nowMs = Clock.System.now().toEpochMilliseconds()
                    if (!forceRefresh &&
                        dataStoreManager.spotifyPersonalToken
                            .first()
                            .isNotEmpty() &&
                        dataStoreManager.spotifyClientToken
                            .first()
                            .isNotEmpty() &&
                        dataStoreManager.spotifyPersonalTokenExpires.first() > nowMs + expiryMarginMs &&
                        dataStoreManager.spotifyClientTokenExpires.first() > nowMs + expiryMarginMs
                    ) {
                        spotifyPersonalToken = dataStoreManager.spotifyPersonalToken.first()
                        spotifyClientToken = dataStoreManager.spotifyClientToken.first()
                        reused = true
                        Logger.i("Spotify", "Spotify tokens reused")
                    } else if (dataStoreManager.spdc.first().isNotEmpty()) {
                        spotify
                            .getClientToken()
                            .onSuccess {
                                dataStoreManager.setSpotifyClientTokenExpires(
                                    (it.grantedToken.expiresAfterSeconds * 1000L) + Clock.System.now().toEpochMilliseconds(),
                                )
                                dataStoreManager.setSpotifyClientToken(it.grantedToken.token)
                                spotifyClientToken = it.grantedToken.token
                            }.onFailure {
                                Logger.e("LyricsCanvasRepositoryImpl", "getSpotifyLyrics failed: ${it.message}", it)
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
                                Logger.e("LyricsCanvasRepositoryImpl", "getSpotifyLyrics failed: ${it.message}", it)
                                emit(Resource.Error<Lyrics>("Not found"))
                            }
                        if (spotifyPersonalToken.isNotEmpty() && spotifyClientToken.isNotEmpty()) {
                            Logger.i("Spotify", "Spotify token refreshed")
                        }
                    }
                    if (spotifyPersonalToken.isEmpty() || spotifyClientToken.isEmpty()) break
                    // A stored token Spotify no longer accepts: clear it and go round once more with fresh ones.
                    val retryOnUnauthorized = reused && attempt == 0
                    var unauthorized = false
                    spotify
                        .searchSpotifyTrack(q, spotifyPersonalToken, spotifyClientToken)
                        .onSuccess { searchResponse ->
                            // The same title and length first, then the same title, then the same
                            // length, then Spotify's order; nothing is dropped, so a lone result is
                            // still taken (Other lyrics in the lyrics menu goes on to the next one).
                            val ranked =
                                LyricsQuery.rankSearchResults(
                                    items = searchResponse.data?.searchV2?.tracksV2?.items.orEmpty(),
                                    title = songTitle,
                                    durationSec = duration,
                                    toleranceSec = 2,
                                    titleOf = { it.item?.data?.name },
                                    // Spotify gives milliseconds, the duration here is in seconds.
                                    durationSecOf = { it.item?.data?.duration?.totalMilliseconds?.let { ms -> ms / 1000.0 } },
                                )
                            val track = ranked.getOrNull(variant.coerceAtLeast(0))
                            if (track == null && variant > 0) {
                                emit(Resource.Error<Lyrics>(NO_MORE_LYRICS_VARIANTS))
                                return@onSuccess
                            }
                            Logger.d("Lyrics") { "track: ${Logger.clip(track)}" }
                            if (track != null) {
                                spotify
                                    .getSpotifyLyrics(track.item?.data?.id ?: "", spotifyPersonalToken, spotifyClientToken)
                                    .onSuccess {
                                        emit(Resource.Success<Lyrics>(it.toLyrics()))
                                    }.onFailure {
                                        if (it is SpotifyUnauthorizedException) unauthorized = true
                                        if (!(unauthorized && retryOnUnauthorized)) {
                                            if (it is SpotifyLyricsNotFoundException) {
                                                Logger.d("Lyrics", "Spotify has no lyrics for this track")
                                            } else {
                                                Logger.e("LyricsCanvasRepositoryImpl", "getSpotifyLyrics failed: ${it.message}", it)
                                            }
                                            emit(Resource.Error<Lyrics>("Not found"))
                                        }
                                    }
                            } else {
                                emit(Resource.Error<Lyrics>("Not found"))
                            }
                        }.onFailure { throwable ->
                            if (throwable is SpotifyUnauthorizedException) unauthorized = true
                            if (!(unauthorized && retryOnUnauthorized)) {
                                Logger.e("LyricsCanvasRepositoryImpl", "getSpotifyLyrics failed: ${throwable.message}", throwable)
                                emit(Resource.Error<Lyrics>("Not found"))
                            }
                        }
                    if (unauthorized && retryOnUnauthorized) {
                        Logger.i("Spotify", "Spotify stored token rejected (401), refreshing and retrying once")
                        dataStoreManager.setSpotifyPersonalToken("")
                        dataStoreManager.setSpotifyClientToken("")
                        dataStoreManager.setSpotifyPersonalTokenExpires(0L)
                        dataStoreManager.setSpotifyClientTokenExpires(0L)
                        forceRefresh = true
                        continue
                    }
                    break
                }
            }
        }.flowOn(Dispatchers.IO)

    override fun getLrclibLyricsData(
        sartist: String,
        strack: String,
        duration: Int?,
        variant: Int,
    ): Flow<Resource<Lyrics>> =
        flow {
            Logger.d("Lyrics", "getLrclibLyricsData: $sartist $strack $duration")
            val qartist =
                LyricsQuery
                    .cleanArtist(sartist)
                    .replace(
                        Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                        " ",
                    ).replace(
                        Regex("( và | & | и | e | und |, |和| dan )"),
                        " ",
                    ).replace("  ", " ")
                    .replace(Regex("([()])"), "")
                    .replace(".", " ")
            val qtrack =
                LyricsQuery
                    .cleanTitle(strack, sartist)
                    .replace(
                        Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                        " ",
                    ).replace(
                        Regex("( và | & | и | e | und |, |和| dan )"),
                        " ",
                    ).replace("  ", " ")
                    .replace(Regex("([()])"), "")
                    .replace(".", " ")
            simpMusicLyrics
                .searchLrclibLyrics(qtrack, qartist, duration, variant)
                .onSuccess {
                    // A search with no match must still answer: the caller falls back to the
                    // offline cache only on an emitted error, and an empty flow skips it.
                    if (it != null) emit(Resource.Success<Lyrics>(it.toLyrics())) else emit(Resource.Error<Lyrics>("Not found"))
                }.onFailure {
                    if (it is NoMoreLyricsVariantsException) {
                        emit(Resource.Error<Lyrics>(NO_MORE_LYRICS_VARIANTS))
                        return@onFailure
                    }
                    Logger.e("LyricsCanvasRepositoryImpl", "getLrclibLyricsData failed: ${it.message}", it)
                    emit(Resource.Error<Lyrics>("Not found"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getBetterLyrics(
        artist: String,
        track: String,
        duration: Int?,
    ): Flow<Resource<Lyrics>> =
        flow {
            Logger.d("Lyrics", "getBetterLyrics: $artist $track")
            val qartist =
                LyricsQuery
                    .cleanArtist(artist)
                    .replace(
                        Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                        " ",
                    ).replace(
                        Regex("( và | & | и | e | und |, |和| dan )"),
                        " ",
                    ).replace("  ", " ")
                    .replace(Regex("([()])"), "")
                    .replace(".", " ")
            val qtrack =
                LyricsQuery
                    .cleanTitle(track, artist)
                    .replace(
                        Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                        " ",
                    ).replace(
                        Regex("( và | & | и | e | und |, |和| dan )"),
                        " ",
                    ).replace("  ", " ")
                    .replace(Regex("([()])"), "")
                    .replace(".", " ")
            simpMusicLyrics
                .searchBetterLyrics(qtrack, qartist, duration)
                .onSuccess { ttml ->
                    if (ttml.isNullOrEmpty()) {
                        Logger.d("Lyrics", "BetterLyrics has no text for this track")
                        emit(Resource.Error<Lyrics>("No BetterLyrics found"))
                        return@onSuccess
                    }
                    val lyrics = parseTtmlLyrics(ttml).toLyrics()
                    emit(Resource.Success(lyrics))
                }.onFailure {
                    Logger.e("LyricsCanvasRepositoryImpl", "getBetterLyrics failed: ${it.message}", it)
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

    override fun getSimpMusicLyrics(
        videoId: String,
        variant: Int,
    ): Flow<Resource<Lyrics>> =
        flow {
            simpMusicLyrics
                .getLyrics(videoId)
                .onSuccess { lyrics ->
                    Logger.d(simpMusicLyricsTag) { "Lyrics found: ${Logger.clip(lyrics)}" }
                    // Several people can have added lyrics for one video: Other lyrics goes through them.
                    val result = lyrics.getOrNull(variant.coerceAtLeast(0))
                    if (result == null && variant > 0) {
                        emit(Resource.Error<Lyrics>(NO_MORE_LYRICS_VARIANTS))
                        return@onSuccess
                    }
                    if (result == null) {
                        Logger.d(simpMusicLyricsTag, "No lyrics found for videoId: $videoId")
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
