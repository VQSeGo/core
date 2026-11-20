package com.maxrave.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalPlaylistPositionTest {
    @Test
    fun anEmptyPlaylistStartsAtZero() {
        assertEquals(0, nextPlaylistPosition(null))
    }

    @Test
    fun theNextPositionFollowsTheLastOne() {
        assertEquals(5, nextPlaylistPosition(4))
    }

    @Test
    fun aGapLeftByARemovalDoesNotMakeTheNextPositionTaken() {
        // 5 tracks at 0..4, the 3rd removed: 4 tracks, last position still 4. The count (4) is taken.
        val positions = listOf(0, 1, 3, 4)
        val next = nextPlaylistPosition(positions.maxOrNull())
        assertEquals(5, next)
        assertEquals(false, next in positions)
    }
}
