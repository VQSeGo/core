package com.maxrave.domain.mediaservice.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicEndTest {
    // The user's example: a 4:22 track whose last 4 s are silence.
    private val duration = 262_000L
    private val musicEnd = 258_000L

    @Test
    fun crossfadeFinishesAtTheMusicEnd() {
        assertEquals(musicEnd, MusicEnd.fadeEndMs(duration, musicEnd))
        // Precached next track, 5 s fade: starts at 4:13, not at 4:17 as it did from the file's end.
        val fadeEnd = MusicEnd.fadeEndMs(duration, musicEnd)
        assertFalse(MusicEnd.shouldStartCrossfade(MusicEnd.remainingMs(252_999L, fadeEnd), 5_000L, 0L))
        assertTrue(MusicEnd.shouldStartCrossfade(MusicEnd.remainingMs(253_000L, fadeEnd), 5_000L, 0L))
        // Not precached: 3 s earlier, and the fade itself is cut to what is left once it is ready.
        assertTrue(MusicEnd.shouldStartCrossfade(MusicEnd.remainingMs(250_000L, fadeEnd), 5_000L, 3_000L))
        assertEquals(5_000, MusicEnd.effectiveFadeMs(5_000L, MusicEnd.remainingMs(252_000L, fadeEnd)))
        assertEquals(2_500, MusicEnd.effectiveFadeMs(5_000L, MusicEnd.remainingMs(255_500L, fadeEnd)))
    }

    @Test
    fun unknownOrNoTailKeepsTheFileEnd() {
        assertEquals(duration, MusicEnd.fadeEndMs(duration, null))
        assertEquals(duration, MusicEnd.fadeEndMs(duration, duration - 100L))
        assertEquals(duration, MusicEnd.fadeEndMs(duration, duration + 500L))
        assertEquals(duration, MusicEnd.fadeEndMs(duration, 0L))
        // Unknown length (a live stream) stays unknown.
        assertEquals(0L, MusicEnd.fadeEndMs(0L, musicEnd))
    }

    @Test
    fun pastTheMusicEndOnlyWhenThereIsATail() {
        assertFalse(MusicEnd.isPastMusicEnd(musicEnd, duration, musicEnd))
        assertFalse(MusicEnd.isPastMusicEnd(musicEnd + MusicEnd.KEEP_TAIL_MS - 1, duration, musicEnd))
        assertTrue(MusicEnd.isPastMusicEnd(musicEnd + MusicEnd.KEEP_TAIL_MS, duration, musicEnd))
        assertFalse(MusicEnd.isPastMusicEnd(duration, duration, null))
        assertFalse(MusicEnd.isPastMusicEnd(duration - 50L, duration, duration - 100L))
    }

    @Test
    fun seekingPastTheMusicEndDoesNotStartAFade() {
        val fadeEnd = MusicEnd.fadeEndMs(duration, musicEnd)
        assertFalse(MusicEnd.shouldStartCrossfade(MusicEnd.remainingMs(259_000L, fadeEnd), 5_000L, 0L))
    }

    @Test
    fun speedShortensTheWallClockRemaining() {
        assertEquals(2_500L, MusicEnd.remainingMs(253_000L, musicEnd, 2f))
    }

    @Test
    fun measuredOnlyNearTheEnd() {
        assertFalse(MusicEnd.shouldMeasure(duration, 100_000L))
        assertTrue(MusicEnd.shouldMeasure(duration, duration - MusicEnd.ANALYSIS_LEAD_MS))
        assertTrue(MusicEnd.shouldMeasure(60_000L, 0L))
        assertFalse(MusicEnd.shouldMeasure(0L, 0L))
    }

    @Test
    fun tailWindow() {
        assertEquals(duration - MusicEnd.TAIL_WINDOW_MS, MusicEnd.tailStartMs(duration))
        assertEquals(0L, MusicEnd.tailStartMs(30_000L))
        assertEquals(202_000L + 56_000L, MusicEnd.fromTail(202_000L, 56_000L))
        assertNull(MusicEnd.fromTail(202_000L, null))
        assertNull(MusicEnd.fromTail(202_000L, 0L))
    }

    @Test
    fun storedMeasurements() {
        assertEquals(MusicEnd.Stored(musicEnd), MusicEnd.decode(MusicEnd.encode(musicEnd, duration), duration))
        assertEquals(MusicEnd.Stored(null), MusicEnd.decode(MusicEnd.encode(null, duration), duration + 1_000L))
        // Another stream of a different length is measured again.
        assertNull(MusicEnd.decode(MusicEnd.encode(musicEnd, duration), duration + 5_000L))
        assertNull(MusicEnd.decode("garbage", duration))
        assertNull(MusicEnd.decode("", duration))
    }
}
