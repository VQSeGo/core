package com.simpmusic.media_jvm.mpv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaysAsVideoTest {
    @Test
    fun aClipIsNotVideoWhileWatchVideoIsOff() {
        assertFalse(playsAsVideo(watchVideoEnabled = false, itemIsVideo = true, handleHasVideo = null, downloaded = false))
    }

    @Test
    fun aSongIsNeverVideo() {
        assertFalse(playsAsVideo(watchVideoEnabled = true, itemIsVideo = false, handleHasVideo = true, downloaded = false))
    }

    @Test
    fun aDownloadedClipPlaysAsAudioSoItMayCrossfade() {
        // The reported case: videoType OMV, "watch video" on, played from downloads/<id>.webm.
        assertFalse(playsAsVideo(watchVideoEnabled = true, itemIsVideo = true, handleHasVideo = null, downloaded = true))
        assertFalse(playsAsVideo(watchVideoEnabled = true, itemIsVideo = true, handleHasVideo = false, downloaded = true))
    }

    @Test
    fun aClipFromTheNetworkIsVideoUntilAHandleSaysOtherwise() {
        assertTrue(playsAsVideo(watchVideoEnabled = true, itemIsVideo = true, handleHasVideo = null, downloaded = false))
        assertTrue(playsAsVideo(watchVideoEnabled = true, itemIsVideo = true, handleHasVideo = true, downloaded = false))
        assertFalse(playsAsVideo(watchVideoEnabled = true, itemIsVideo = true, handleHasVideo = false, downloaded = false))
    }

    @Test
    fun theHandleOutranksTheDownload() {
        assertTrue(playsAsVideo(watchVideoEnabled = true, itemIsVideo = true, handleHasVideo = true, downloaded = true))
    }
}
