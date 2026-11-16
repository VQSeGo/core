package com.maxrave.data.mediaservice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnplayableTrackSkipperTest {
    private val order = listOf(0, 1, 2, 3, 4)

    @Test
    fun forwardTriesFollowingTracks() {
        assertEquals(listOf(3, 4), UnplayableTrackSkipper.candidates(order, 2, backward = false, repeatAll = false))
    }

    @Test
    fun backwardTriesPrecedingTracksNearestFirst() {
        assertEquals(listOf(1, 0), UnplayableTrackSkipper.candidates(order, 2, backward = true, repeatAll = false))
    }

    @Test
    fun backwardFromTheFirstTrackHasNowhereToGo() {
        assertTrue(UnplayableTrackSkipper.candidates(order, 0, backward = true, repeatAll = false).isEmpty())
    }

    @Test
    fun repeatAllWrapsOnceInTheSameDirection() {
        assertEquals(listOf(4, 3, 2, 1), UnplayableTrackSkipper.candidates(order, 0, backward = true, repeatAll = true))
        assertEquals(listOf(0, 1, 2, 3), UnplayableTrackSkipper.candidates(order, 4, backward = false, repeatAll = true))
    }

    @Test
    fun shuffledOrderIsFollowedInPlayOrder() {
        val shuffled = listOf(3, 0, 4, 1, 2)
        assertEquals(listOf(0, 3), UnplayableTrackSkipper.candidates(shuffled, 2, backward = true, repeatAll = false))
    }

    @Test
    fun stepDirectionFollowsPlayOrder() {
        assertTrue(UnplayableTrackSkipper.direction(previous = 3, position = 2, size = 5, repeatAll = false))
        assertFalse(UnplayableTrackSkipper.direction(previous = 2, position = 3, size = 5, repeatAll = false))
        // Jumping to a track picked in the queue counts too.
        assertTrue(UnplayableTrackSkipper.direction(previous = 4, position = 0, size = 5, repeatAll = false))
    }

    @Test
    fun repeatAllWrapIsReadAsNextAndPrevious() {
        assertTrue(UnplayableTrackSkipper.direction(previous = 0, position = 4, size = 5, repeatAll = true))
        assertFalse(UnplayableTrackSkipper.direction(previous = 4, position = 0, size = 5, repeatAll = true))
        // Two tracks: the step is forward whichever way it was meant.
        assertFalse(UnplayableTrackSkipper.direction(previous = 0, position = 1, size = 2, repeatAll = true))
    }
}
