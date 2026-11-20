package com.maxrave.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncedPlaylistPlanTest {
    @Test
    fun aTrackYouTubeDroppedLeavesAndTheOrderIsYouTubes() {
        val plan = planSyncedPlaylist(listOf("a", "b", "c"), listOf("c", "a"), setOf("a", "b", "c"), complete = true)
        assertEquals(listOf("b"), plan.removed)
        assertEquals(listOf("c", "a"), plan.order)
    }

    @Test
    fun newTracksJoinInYouTubesPlace() {
        val plan = planSyncedPlaylist(listOf("a"), listOf("n", "a"), setOf("a"), complete = true)
        assertTrue(plan.removed.isEmpty())
        assertEquals(listOf("n", "a"), plan.order)
    }

    @Test
    fun aTrackNeverConfirmedInYouTubeStaysAfterTheRest() {
        val plan = planSyncedPlaylist(listOf("x", "a"), listOf("a"), setOf("a"), complete = true)
        assertTrue(plan.removed.isEmpty())
        assertEquals(listOf("a", "x"), plan.order)
    }

    @Test
    fun anIncompleteListRemovesNothingAndOnlyAppends() {
        val plan = planSyncedPlaylist(listOf("a", "b"), listOf("b", "n"), setOf("a", "b"), complete = false)
        assertTrue(plan.removed.isEmpty())
        assertEquals(listOf("a", "b", "n"), plan.order)
    }

    @Test
    fun anEmptyYouTubeListRemovesNothing() {
        val plan = planSyncedPlaylist(listOf("a", "b"), emptyList(), setOf("a", "b"), complete = true)
        assertTrue(plan.removed.isEmpty())
        assertEquals(listOf("a", "b"), plan.order)
    }

    @Test
    fun aVideoTwiceInYouTubeIsOneTrack() {
        val plan = planSyncedPlaylist(listOf("a"), listOf("a", "b", "a"), setOf("a"), complete = true)
        assertEquals(listOf("a", "b"), plan.order)
    }
}
