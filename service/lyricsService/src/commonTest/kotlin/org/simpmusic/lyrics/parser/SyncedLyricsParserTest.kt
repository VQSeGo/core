package org.simpmusic.lyrics.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncedLyricsParserTest {
    private fun parse(source: String) =
        parseSyncedLyrics(source).lyrics!!.lines!!.map { it.startTimeMs.toLong() to it.words }

    @Test
    fun hundredthsAreScaledToMilliseconds() {
        // LRCLIB's own shape: two-digit hundredths, text right against the bracket.
        assertEquals(
            listOf(420L to "Is this the real life?", 4030L to "Is this just fantasy?", 27120L to "boy"),
            parse("[00:00.42] Is this the real life?\n[00:04.03] Is this just fantasy?\n[00:27.12]boy"),
        )
    }

    @Test
    fun oneAndThreeDigitFractionsAndNoFraction() {
        assertEquals(
            listOf(1500L to "a", 2123L to "b", 3000L to "c", 64050L to "d"),
            parse("[00:01.5]a\n[00:02.123]b\n[00:03]c\n[01:04:05]d"),
        )
    }

    @Test
    fun aLineWithSeveralTimesPlaysAtEachInTimeOrder() {
        assertEquals(
            listOf(12000L to "Chorus", 20000L to "Verse", 90000L to "Chorus"),
            parse("[00:12.00][01:30.00]Chorus\n[00:20.00]Verse"),
        )
    }

    @Test
    fun emptyLineIsAnInstrumentalGap() {
        assertEquals(listOf(1000L to "a", 2000L to "♫", 3000L to "♫"), parse("[00:01.00]a\n[00:02.00] \n[00:03.00]"))
    }

    @Test
    fun offsetTagMakesEveryTimeSooner() {
        assertEquals(listOf(750L to "a", 0L to "b").sortedBy { it.first }, parse("[offset:+250]\n[00:01.00]a\n[00:00.10]b"))
        assertEquals(listOf(1250L to "a"), parse("[offset:-250]\n[00:01.00]a"))
    }

    @Test
    fun tagsAndUntimedLinesAreSkipped() {
        assertEquals(listOf(1000L to "a"), parse("[ar:Artist]\n[ti:Title]\nnot timed\n  [00:01.00]a\r"))
    }

    @Test
    fun htmlEntitiesAreDecoded() {
        assertEquals(listOf(1000L to "Rock & roll"), parse("[00:01.00]Rock &amp; roll"))
    }
}
