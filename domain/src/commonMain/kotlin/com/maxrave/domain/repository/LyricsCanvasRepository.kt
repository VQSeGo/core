package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.LyricsEntity
import com.maxrave.domain.data.entities.TranslatedLyricsEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow

/**
 * The error message of a lyrics request for a search result past the last one (`variant` too high):
 * Other lyrics moves on to the next source instead of trying further results.
 */
const val NO_MORE_LYRICS_VARIANTS = "no_more_lyrics_variants"

/**
 * `variant` in the lyrics requests: 0 is the source's automatic pick, 1, 2, … the other results of the
 * same search, best first (Other lyrics in the lyrics menu walks through them).
 */
interface LyricsCanvasRepository {
    fun getSavedLyrics(videoId: String): Flow<LyricsEntity?>

    suspend fun insertLyrics(lyricsEntity: LyricsEntity)

    suspend fun insertTranslatedLyrics(translatedLyrics: TranslatedLyricsEntity)

    fun getSavedTranslatedLyrics(
        videoId: String,
        language: String,
    ): Flow<TranslatedLyricsEntity?>

    suspend fun removeTranslatedLyrics(
        videoId: String,
        language: String,
    )

    fun getYouTubeCaption(
        preferLang: String,
        videoId: String,
    ): Flow<Resource<Pair<Lyrics, Lyrics?>>>

    /** [title] and [artist] as YouTube has them: the search cleans them (see LyricsQuery). */
    fun getSpotifyLyrics(
        dataStoreManager: DataStoreManager,
        title: String,
        artist: String,
        duration: Int?,
        variant: Int = 0,
    ): Flow<Resource<Lyrics>>

    fun getLrclibLyricsData(
        sartist: String,
        strack: String,
        duration: Int?,
        variant: Int = 0,
    ): Flow<Resource<Lyrics>>

    fun getBetterLyrics(
        artist: String,
        track: String,
        duration: Int?,
    ): Flow<Resource<Lyrics>>

    fun getSimpMusicLyrics(
        videoId: String,
        variant: Int = 0,
    ): Flow<Resource<Lyrics>>

    fun getSimpMusicTranslatedLyrics(
        videoId: String,
        language: String,
    ): Flow<Resource<Lyrics>>
}