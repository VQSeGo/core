package com.maxrave.domain.data.player

import kotlin.test.Test
import kotlin.test.assertEquals

class LoudnessNormalizationTest {
    @Test
    fun `a loud track comes down by how far above the reference it is`() {
        assertEquals(-6f, loudnessGainDb(6f))
    }

    @Test
    fun `a quiet track goes up by how far below the reference it is`() {
        assertEquals(3f, loudnessGainDb(-3f))
    }

    @Test
    fun `a boost is capped but a cut is not`() {
        assertEquals(3f, loudnessGainDb(-9f, maxBoostDb = 3f))
        assertEquals(-12f, loudnessGainDb(12f, maxBoostDb = 3f))
    }

    @Test
    fun `a boost under the cap passes through`() {
        assertEquals(2f, loudnessGainDb(-2f, maxBoostDb = 3f))
    }

    @Test
    fun `missing and non finite figures leave the track alone`() {
        assertEquals(0f, loudnessGainDb(null))
        assertEquals(0f, loudnessGainDb(Float.NaN))
        assertEquals(0f, loudnessGainDb(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `a figure outside the credible range is ignored like on Android`() {
        assertEquals(0f, loudnessGainDb(25f))
        assertEquals(0f, loudnessGainDb(-21f))
        // The edge itself is still honoured.
        assertEquals(-20f, loudnessGainDb(20f))
    }

    @Test
    fun `zero loudness is no change`() {
        assertEquals(0f, loudnessGainDb(0f))
    }
}
