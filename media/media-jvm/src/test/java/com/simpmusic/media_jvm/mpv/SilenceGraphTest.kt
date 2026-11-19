package com.simpmusic.media_jvm.mpv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceGraphTest {
    @Test
    fun pausesShorterThanTwoSecondsAreLeftAlone() {
        assertTrue(silenceRemoveGraph().contains("stop_duration=2.0000:"))
        assertTrue(silenceRemoveGraph().contains("detection=rms:timestamp=copy"))
    }

    @Test
    fun leadingSilenceIsTrimmedOnlyFromTheTop() {
        assertTrue(silenceRemoveGraph(trimStart = true).contains("start_periods=1"))
        val afterSeek = silenceRemoveGraph(trimStart = false)
        assertFalse(afterSeek.contains("start_"))
        assertTrue(afterSeek.startsWith("silenceremove=stop_periods=-1:"))
    }
}
