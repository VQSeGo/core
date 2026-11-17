package com.maxrave.data.mediaservice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackTrackingGateTest {
    @Test
    fun freshRowStartsOnce() {
        val gate = PlaybackTrackingGate()
        assertTrue(gate.shouldStart(stale = false))
        assertFalse(gate.shouldStart(stale = false))
    }

    @Test
    fun freshRowAfterFreshRowOfTheSamePlayIsIgnored() {
        // The saved link was dead and the stream was resolved again: a new row with a new cpn.
        val gate = PlaybackTrackingGate()
        assertTrue(gate.shouldStart(stale = false))
        assertFalse(gate.shouldStart(stale = false))
        assertFalse(gate.shouldStart(stale = true))
    }

    @Test
    fun staleFallbackIsReplacedByTheFreshRowOnce() {
        val gate = PlaybackTrackingGate()
        assertTrue(gate.shouldStart(stale = true))
        assertFalse(gate.shouldStart(stale = true))
        assertTrue(gate.shouldStart(stale = false))
        assertFalse(gate.shouldStart(stale = false))
    }

    @Test
    fun newTrackStartsAgain() {
        val gate = PlaybackTrackingGate()
        assertTrue(gate.shouldStart(stale = false))
        gate.reset()
        assertTrue(gate.shouldStart(stale = false))
    }
}
