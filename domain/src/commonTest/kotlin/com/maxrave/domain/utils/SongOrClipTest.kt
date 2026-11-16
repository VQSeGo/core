package com.maxrave.domain.utils

import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import kotlin.test.Test
import kotlin.test.assertEquals

class SongOrClipTest {
    private val squareCover = Thumbnail(544, "https://lh3.googleusercontent.com/abc=w544-h544", 544)
    private val wideCover = Thumbnail(720, "https://i.ytimg.com/vi/x/hq720.jpg", 1280)

    private fun track(
        type: String?,
        cover: Thumbnail?,
    ) = Track(
        album = null,
        artists = null,
        duration = null,
        durationSeconds = null,
        isAvailable = true,
        isExplicit = false,
        likeStatus = null,
        thumbnails = cover?.let { listOf(it) },
        title = "t",
        videoId = "x",
        videoType = type,
        category = null,
        feedbackTokens = null,
        resultType = null,
    )

    private fun entity(
        type: String,
        cover: String?,
    ) = SongEntity(
        videoId = "x",
        albumId = null,
        albumName = null,
        artistId = null,
        artistName = null,
        duration = "",
        durationSeconds = 0,
        isAvailable = true,
        isExplicit = false,
        likeStatus = "",
        thumbnails = cover,
        title = "t",
        videoType = type,
        category = null,
        resultType = null,
        liked = false,
        totalPlayTime = 0,
        downloadState = 0,
    )

    @Test
    fun atvIsSongEvenWithWideCover() {
        assertEquals(true, track("MUSIC_VIDEO_TYPE_ATV", squareCover).isSongMedia())
        assertEquals(true, track("MUSIC_VIDEO_TYPE_ATV", wideCover).isSongMedia())
    }

    @Test
    fun omvAndUgcAreClipsEvenWithSquareCover() {
        assertEquals(false, track("MUSIC_VIDEO_TYPE_OMV", squareCover).isSongMedia())
        assertEquals(false, track("MUSIC_VIDEO_TYPE_UGC", squareCover).isSongMedia())
        assertEquals(false, entity("MUSIC_VIDEO_TYPE_OMV", "https://x/a=w544-h544").isSongMedia())
    }

    @Test
    fun privateUploadIsSong() {
        assertEquals(true, track("MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK", squareCover).isSongMedia())
        assertEquals(true, track("MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK", wideCover).isSongMedia())
    }

    @Test
    fun unknownTypeFallsBackToCoverShape() {
        assertEquals(true, track(null, squareCover).isSongMedia())
        assertEquals(true, track("", squareCover).isSongMedia())
        // An invented label from an older build is no type.
        assertEquals(true, track("Song", squareCover).isSongMedia())
        assertEquals(false, track(null, wideCover).isSongMedia())
        assertEquals(false, track(null, null).isSongMedia())
        assertEquals(true, entity("", "https://x/a=w544-h544").isSongMedia())
        assertEquals(false, entity("", "https://i.ytimg.com/vi/x/maxresdefault.jpg").isSongMedia())
    }

    @Test
    fun podcastEpisodeKeepsDecidingByCover() {
        assertEquals(false, track("MUSIC_VIDEO_TYPE_PODCAST_EPISODE", wideCover).isSongMedia())
        assertEquals(true, track("MUSIC_VIDEO_TYPE_PODCAST_EPISODE", squareCover).isSongMedia())
    }
}
