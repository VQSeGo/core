package org.simpmusic.lyrics

import com.maxrave.logger.Logger
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import org.simpmusic.lyrics.models.response.BaseResponse
import org.simpmusic.lyrics.models.response.BetterLyricsResponse
import org.simpmusic.lyrics.models.response.LrclibObject
import org.simpmusic.lyrics.models.response.LyricsResponse
import org.simpmusic.lyrics.models.response.TranslatedLyricsResponse
import org.simpmusic.lyrics.parser.parseLrclibLyrics
import kotlin.math.abs

private const val TAG = "SimpMusicLyricsClient"

/** An error the SimpMusic Lyrics API answered with; [code] 404 means nobody has added that song yet. */
class SimpMusicLyricsApiException(
    val code: Int,
    message: String,
) : Exception(message)

/** Other lyrics asked for a search result past the last one. */
class NoMoreLyricsVariantsException : Exception("No more lyrics variants")

class SimpMusicLyricsClient {
    private val lyricsService = SimpMusicLyrics()

    suspend fun getLyrics(videoId: String): Result<List<LyricsResponse>> =
        runCatching {
            lyricsService.findLyricsByVideoId(videoId).bodyOrThrow<List<LyricsResponse>>()
        }

    suspend fun getTranslatedLyrics(
        videoId: String,
        language: String,
    ): Result<TranslatedLyricsResponse> =
        runCatching {
            if (language.length != 2) {
                throw IllegalArgumentException("Language code must be a 2-letter code")
            }
            lyricsService.findTranslatedLyrics(videoId, language).bodyOrThrow<TranslatedLyricsResponse>()
        }

    /**
     * LRCLIB's lyrics for the song. [variant] 0 is the automatic pick ([selectLrclibLyricsRecord]);
     * 1, 2, … are the other records worth showing ([lrclibLyricsAlternatives]), for Other lyrics.
     * Null when there is none, [NoMoreLyricsVariantsException] when [variant] is past the last one.
     */
    suspend fun searchLrclibLyrics(
        q_track: String,
        q_artist: String,
        duration: Int?,
        variant: Int = 0,
    ) = runCatching {
        val rs =
            lyricsService
                .searchLrclibLyrics(
                    q_track = q_track,
                    q_artist = q_artist,
                ).body<List<LrclibObject>>()
        val lrclibObject =
            if (variant <= 0) {
                selectLrclibLyricsRecord(rs, duration)
            } else {
                lrclibLyricsAlternatives(rs, duration, q_track).getOrNull(variant - 1)
                    ?: throw NoMoreLyricsVariantsException()
            }
        lrclibObject?.let { parseLrclibLyrics(it) }
    }

    suspend fun searchBetterLyrics(
        q_track: String,
        q_artist: String,
        durationSeconds: Int?,
    ) = runCatching {
        val response =
            lyricsService
                .searchBetterLyrics(
                    q_track = q_track,
                    q_artist = q_artist,
                    durationSeconds = durationSeconds,
                )
        // 404 is the normal answer for a song BetterLyrics has no text for; a body without `ttml` means the same.
        if (response.status.value == 404) {
            null
        } else {
            response.body<BetterLyricsResponse>().ttml
        }
    }

    private suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T {
        if (this.status.value == 429) {
            Logger.e(TAG, "Too many requests: ${this.status.value}")
        }
        try {
            val data = body<BaseResponse<T>>()
            if (data.error != null) {
                val error = data.error
                val message = "Error response: ${error.reason} (code: ${error.code})"
                // 404 is the normal answer for a song nobody has added lyrics for, not a failure.
                if (error.code == 404) Logger.d(TAG, message) else Logger.e(TAG, message)
                throw SimpMusicLyricsApiException(error.code, message)
            }
            return data.data ?: throw Exception("Response data is null")
        } catch (e: Exception) {
            throw e
        }
    }
}

internal fun selectLrclibLyricsRecord(
    records: List<LrclibObject>,
    duration: Int?,
): LrclibObject? {
    val candidates =
        if (duration != null) {
            records.filter { abs(it.duration.toInt() - duration) <= LRCLIB_DURATION_TOLERANCE }
        } else {
            records
        }
    return bySyncQuality(candidates).firstOrNull() ?: candidates.firstOrNull()
}

/**
 * The records Other lyrics can offer after the automatic pick, best first: the other ones of the
 * right length, then the ones of a different length named like [title] (a music video runs longer
 * than the song, so its record is outside the tolerance). Records with no text, and the automatic
 * pick itself, are left out; a record of another song with a different length never comes in.
 */
internal fun lrclibLyricsAlternatives(
    records: List<LrclibObject>,
    duration: Int?,
    title: String?,
): List<LrclibObject> {
    val auto = selectLrclibLyricsRecord(records, duration)
    val withText = records.filter { it.hasText() && it != auto }
    if (duration == null) return bySyncQuality(withText)
    val (inside, outside) = withText.partition { abs(it.duration.toInt() - duration) <= LRCLIB_DURATION_TOLERANCE }
    val named =
        outside
            .filter { LyricsQuery.titlesMatch(it.trackName, title) }
            .sortedBy { abs(it.duration - duration) }
    return bySyncQuality(inside) + bySyncQuality(named)
}

private const val LRCLIB_DURATION_TOLERANCE = 10

private fun LrclibObject.hasText(): Boolean = !syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank() || !lyricsfile.isNullOrBlank()

/**
 * Word sync first, then line sync, then any text, the order within each kept: the first match is
 * often a plain-only or an instrumental record, which gave plain or no lyrics while a synced record
 * of the song was next. Records with no text are left out.
 */
private fun bySyncQuality(records: List<LrclibObject>): List<LrclibObject> =
    records.filter { it.hasWordSync } +
        records.filter { !it.hasWordSync && !it.syncedLyrics.isNullOrBlank() } +
        records.filter { !it.hasWordSync && it.syncedLyrics.isNullOrBlank() && it.hasText() }
