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

    suspend fun searchLrclibLyrics(
        q_track: String,
        q_artist: String,
        duration: Int?,
    ) = runCatching {
        val rs =
            lyricsService
                .searchLrclibLyrics(
                    q_track = q_track,
                    q_artist = q_artist,
                ).body<List<LrclibObject>>()
        val lrclibObject = selectLrclibLyricsRecord(rs, duration)
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
            records.filter { abs(it.duration.toInt() - duration) <= 10 }
        } else {
            records
        }
    return candidates.firstOrNull { it.hasWordSync } ?: candidates.firstOrNull()
}
