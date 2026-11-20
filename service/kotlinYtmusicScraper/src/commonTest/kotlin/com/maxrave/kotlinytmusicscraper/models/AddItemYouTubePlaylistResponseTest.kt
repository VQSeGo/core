package com.maxrave.kotlinytmusicscraper.models

import com.maxrave.kotlinytmusicscraper.models.response.AddItemYouTubePlaylistResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

private fun decode(body: String) = json.decodeFromString<AddItemYouTubePlaylistResponse>(body)

class AddItemYouTubePlaylistResponseTest {
    @Test
    fun aNewTrackKeepsItsEntry() {
        val added =
            decode(
                """{"responseContext": {}, "status": "STATUS_SUCCEEDED", "playlistEditResults": [
                    {"playlistEditVideoAddedResultData": {"videoId": "abc", "setVideoId": "SET1"}}]}""",
            ).addedEntries()
        assertEquals(1, added.size)
        assertEquals("abc", added[0].videoId)
        assertEquals("SET1", added[0].setVideoId)
    }

    @Test
    fun aSuccessWithoutResultsAddsNothing() {
        assertTrue(decode("""{"status": "STATUS_SUCCEEDED"}""").addedEntries().isEmpty())
        assertTrue(decode("""{"status": "STATUS_SUCCEEDED", "playlistEditResults": []}""").addedEntries().isEmpty())
    }

    @Test
    fun aRefusedAddDecodesAndAddsNothing() {
        val refused = decode("""{"responseContext": {}, "status": "STATUS_FAILED", "actions": [{"openPopupAction": {}}]}""")
        assertEquals("STATUS_FAILED", refused.status)
        assertTrue(refused.addedEntries().isEmpty())
    }

    @Test
    fun aFailedStatusDropsEvenEntriesThatCameAlong() {
        val body =
            """{"status": "STATUS_FAILED", "playlistEditResults": [
                {"playlistEditVideoAddedResultData": {"videoId": "abc", "setVideoId": "SET1"}}]}"""
        assertTrue(decode(body).addedEntries().isEmpty())
    }

    @Test
    fun anEntryWithoutSetVideoIdIsNeverKept() {
        val body =
            """{"status": "STATUS_SUCCEEDED", "playlistEditResults": [
                {"playlistEditVideoAddedResultData": {"videoId": "abc"}},
                {"playlistEditVideoAddedResultData": {"videoId": "def", "setVideoId": ""}},
                {"somethingElse": {}}]}"""
        assertTrue(decode(body).addedEntries().isEmpty())
    }
}
