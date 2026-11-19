package com.maxrave.domain.mediaservice.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadedClipTest {
    private fun state(
        watchVideo: Boolean = true,
        isVideo: Boolean = true,
        audioOnly: Boolean = true,
        streamed: String? = null,
    ) = DownloadedClips.stateOf("a", watchVideo, isVideo, audioOnly, streamed)

    @Test
    fun aDownloadedClipWithWatchVideoOffersTheClip() {
        assertEquals(DownloadedClip("a", shown = false), state())
    }

    @Test
    fun theOfferTurnsIntoHideOnceRequested() {
        assertEquals(DownloadedClip("a", shown = true), state(streamed = "a"))
    }

    @Test
    fun aRequestForAnotherTrackDoesNotCount() {
        assertEquals(DownloadedClip("a", shown = false), state(streamed = "b"))
    }

    @Test
    fun nothingToOfferWithoutWatchVideoForASongOrWithoutADownload() {
        assertNull(state(watchVideo = false))
        assertNull(state(isVideo = false))
        assertNull(state(audioOnly = false))
    }

    @Test
    fun onlyTheRequestedClipStreams() {
        assertTrue(DownloadedClips.streams("a", watchVideoEnabled = true, itemIsVideo = true, streamedClipId = "a"))
        assertFalse(DownloadedClips.streams("a", watchVideoEnabled = true, itemIsVideo = true, streamedClipId = null))
        assertFalse(DownloadedClips.streams("a", watchVideoEnabled = true, itemIsVideo = true, streamedClipId = "b"))
    }

    @Test
    fun aRequestIsVoidWithoutWatchVideoOrForASong() {
        assertFalse(DownloadedClips.streams("a", watchVideoEnabled = false, itemIsVideo = true, streamedClipId = "a"))
        assertFalse(DownloadedClips.streams("a", watchVideoEnabled = true, itemIsVideo = false, streamedClipId = "a"))
    }

    @Test
    fun theRequestLastsOnlyWhileItsTrackPlays() {
        assertEquals("a", DownloadedClips.carriedOver("a", "a"))
        assertNull(DownloadedClips.carriedOver("a", "b"))
        assertNull(DownloadedClips.carriedOver("a", null))
        assertNull(DownloadedClips.carriedOver(null, "a"))
    }
}
