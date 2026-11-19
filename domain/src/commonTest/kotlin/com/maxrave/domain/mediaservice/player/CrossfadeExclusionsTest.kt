package com.maxrave.domain.mediaservice.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossfadeExclusionsTest {
    private fun blocks(
        current: String?,
        next: String?,
        ends: Set<String> = emptySet(),
        starts: Set<String> = emptySet(),
    ) = CrossfadeExclusions.blocksJunction(current, next, ends, starts)

    @Test
    fun nothingMarkedCrossfades() {
        assertFalse(blocks("a", "b"))
    }

    @Test
    fun markedEndOfTheOutgoingTrackBlocks() {
        assertTrue(blocks("a", "b", ends = setOf("a")))
    }

    @Test
    fun markedStartOfTheIncomingTrackBlocks() {
        assertTrue(blocks("a", "b", starts = setOf("b")))
    }

    @Test
    fun marksOnTheOtherSideOfEachTrackDoNotBlock() {
        // A's start and B's end belong to other junctions.
        assertFalse(blocks("a", "b", ends = setOf("b"), starts = setOf("a")))
    }

    @Test
    fun bothMarkedBlocks() {
        assertTrue(blocks("a", "b", ends = setOf("a"), starts = setOf("b")))
    }

    @Test
    fun unknownIdsDoNotBlock() {
        assertFalse(blocks(null, null, ends = setOf("a"), starts = setOf("b")))
        assertTrue(blocks(null, "b", starts = setOf("b")))
        assertTrue(blocks("a", null, ends = setOf("a")))
    }
}
