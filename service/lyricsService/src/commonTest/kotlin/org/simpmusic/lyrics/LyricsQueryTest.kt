package org.simpmusic.lyrics

import org.simpmusic.lyrics.models.response.LrclibObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsQueryTest {
    private fun clean(
        title: String,
        artist: String? = null,
    ) = LyricsQuery.cleanTitle(title, artist)

    @Test
    fun dropsVideoLabelsInBrackets() {
        assertEquals("Numb", clean("Numb (Official Music Video)"))
        assertEquals("Numb", clean("Numb (Official Video)"))
        assertEquals("Dynamite", clean("Dynamite [MV]"))
        assertEquals("Dynamite", clean("Dynamite [M/V]"))
        assertEquals("Numb", clean("Numb (Lyric Video)"))
        assertEquals("Numb", clean("Numb (Lyrics)"))
        assertEquals("Numb", clean("Numb (Audio)"))
        assertEquals("Numb", clean("Numb (Official Audio)"))
        assertEquals("Numb", clean("Numb (Visualizer)"))
        assertEquals("Numb", clean("Numb (Official HD Video)"))
        assertEquals("Numb", clean("Numb [4K]"))
        assertEquals("Numb", clean("Numb HD"))
        assertEquals("Numb", clean("Numb 【Official Music Video】"))
        assertEquals("Numb", clean("Numb (Remastered 2011)"))
        assertEquals("Numb", clean("Numb (2011 Remaster)"))
        assertEquals("Numb", clean("Numb (Explicit)"))
    }

    @Test
    fun dropsRussianVideoLabels() {
        assertEquals("На весь район", clean("На весь район (Клип)"))
        assertEquals("На весь район", clean("На весь район (Официальное видео)"))
        assertEquals("На весь район", clean("На весь район (официальный клип)"))
        assertEquals("На весь район", clean("На весь район (премьера клипа, 2020)"))
        assertEquals("На весь район", clean("На весь район [Премьера клипа]"))
        assertEquals("На весь район", clean("На весь район (Аудио)"))
        assertEquals("На весь район", clean("На весь район (текст песни)"))
    }

    @Test
    fun keepsBracketsThatNameAnotherVersion() {
        assertEquals("Numb (Live)", clean("Numb (Live)"))
        assertEquals("Numb (Remix)", clean("Numb (Remix)"))
        assertEquals("Numb (Acoustic)", clean("Numb (Acoustic)"))
        assertEquals("Numb (Live Video)", clean("Numb (Live Video)"))
        assertEquals("Numb (Official Live Video)", clean("Numb (Official Live Video)"))
        assertEquals("Numb (Radio Edit)", clean("Numb (Radio Edit)"))
        assertEquals("Numb (Instrumental)", clean("Numb (Instrumental)"))
        assertEquals("Numb (Sped Up)", clean("Numb (Sped Up)"))
        assertEquals("Numb (Full Version)", clean("Numb (Full Version)"))
        assertEquals("Numb (2003)", clean("Numb (2003)"))
        assertEquals("Numb - Live at Rock am Ring", clean("Numb - Live at Rock am Ring"))
    }

    @Test
    fun dropsFeaturedArtistsAndProducers() {
        assertEquals("Song", clean("Song (feat. Someone)"))
        assertEquals("Song", clean("Song [ft. Someone & Other]"))
        assertEquals("Song", clean("Song feat. Someone"))
        assertEquals("Song (Remix)", clean("Song ft. Someone (Remix)"))
        assertEquals("Song", clean("Song (prod. Someone)"))
        assertEquals("Song", clean("Song (при уч. Хамиль)"))
    }

    @Test
    fun dropsTheUploadersNameInFront() {
        assertEquals("На весь район", clean("Каста - На весь район", "Каста"))
        assertEquals("На весь район", clean("Каста — На весь район (Official Video)", "Каста"))
        assertEquals("На весь район", clean("Каста – «На весь район»", "Каста"))
        assertEquals("Numb", clean("Linkin Park - Numb [Official Music Video]", "LinkinParkVEVO"))
        assertEquals("Numb", clean("Linkin Park feat. Jay-Z - Numb", "Linkin Park"))
        assertEquals("Numb", clean("Linkin Park, Jay-Z - Numb", "Linkin Park"))
        assertEquals("Numb", clean("Numb | Linkin Park", "Linkin Park"))
        assertEquals("Numb", clean("Numb - Official Video", "Linkin Park"))
        // A self-titled song keeps its title.
        assertEquals("Каста", clean("Каста - Каста", "Каста"))
    }

    @Test
    fun keepsAPrefixThatIsNotTheArtist() {
        // Another artist on a label's channel, or a title with a dash in it: nothing to tell them apart.
        assertEquals("Other - Song", clean("Other - Song", "Label Records"))
        assertEquals("Other - Song", clean("Other - Song"))
    }

    @Test
    fun neverReturnsBlank() {
        assertEquals("(Official Video)", clean("(Official Video)"))
        assertEquals("Audio", clean("Audio"))
        assertEquals("", clean("   "))
    }

    @Test
    fun cleansChannelNames() {
        assertEquals("Kasta", LyricsQuery.cleanArtist("KastaVEVO"))
        assertEquals("Kasta", LyricsQuery.cleanArtist("Kasta - Topic"))
        assertEquals("Kasta", LyricsQuery.cleanArtist("Kasta Official"))
        assertEquals("VEVO", LyricsQuery.cleanArtist("VEVO"))
    }

    @Test
    fun matchesTitles() {
        assertTrue(LyricsQuery.titlesMatch("На весь район", "На Весь Район"))
        assertTrue(LyricsQuery.titlesMatch("Numb", "Numb (Official Video)"))
        assertTrue(LyricsQuery.titlesMatch("Numb", "Numb - Remastered 2011"))
        assertTrue(LyricsQuery.titlesMatch("Ёлка", "елка"))
        assertFalse(LyricsQuery.titlesMatch("Вокруг шум", "На весь район"))
        assertFalse(LyricsQuery.titlesMatch("", "Numb"))
        assertFalse(LyricsQuery.titlesMatch("A", "AB"))
    }

    private data class Hit(
        val name: String,
        val seconds: Double?,
    )

    private fun rank(
        hits: List<Hit>,
        title: String?,
        duration: Int?,
    ) = LyricsQuery.rankSearchResults(hits, title, duration, 2, { it.name }, { it.seconds })

    @Test
    fun titleMatchBeatsAStrangerOfTheRightLength() {
        // The case behind Б57: a music video longer than the song, and the artist's other song first.
        val other = Hit("Вокруг шум", 215.0)
        val song = Hit("На весь район", 234.0)
        assertEquals(listOf(song, other), rank(listOf(other, song), "На весь район", 254))
        val sameLength = Hit("Другая", 254.0)
        assertEquals(listOf(song, sameLength, other), rank(listOf(other, sameLength, song), "На весь район", 254))
    }

    @Test
    fun titleAndLengthBeatTitleAlone() {
        val remix = Hit("Numb - Remix", 250.0)
        val album = Hit("Numb", 185.0)
        assertEquals(listOf(album, remix), rank(listOf(remix, album), "Numb", 186))
    }

    @Test
    fun keepsTheLoneResultAndTheSiteOrderOtherwise() {
        val only = Hit("Вокруг шум", 215.0)
        assertEquals(listOf(only), rank(listOf(only), "На весь район", 234))
        val a = Hit("A song", 100.0)
        val b = Hit("B song", null)
        assertEquals(listOf(a, b), rank(listOf(a, b), "Numb", null))
        assertEquals(emptyList(), rank(emptyList(), "Numb", 100))
    }

    @Test
    fun lrclibAlternativesSkipTheAutomaticPickAndStrangers() {
        val auto = lrclib(1, "Numb", 186f, synced = true)
        val plain = lrclib(2, "Numb", 187f, synced = false)
        val videoLength = lrclib(3, "Numb", 210f, synced = true)
        val stranger = lrclib(4, "Faint", 240f, synced = true)
        val records = listOf(plain, auto, stranger, videoLength)
        assertEquals(auto, selectLrclibLyricsRecord(records, 186))
        assertEquals(listOf(plain, videoLength), lrclibLyricsAlternatives(records, 186, "Numb"))
        // A music video's length: no record within ten seconds, so no automatic pick; the one named
        // like the song is offered first.
        assertEquals(null, selectLrclibLyricsRecord(listOf(videoLength, stranger), 260))
        assertEquals(listOf(videoLength), lrclibLyricsAlternatives(listOf(videoLength, stranger), 260, "Numb"))
    }

    private fun lrclib(
        id: Int,
        track: String,
        duration: Float,
        synced: Boolean,
    ) = LrclibObject(
        id = id,
        name = track,
        trackName = track,
        artistName = "Example",
        albumName = null,
        duration = duration,
        instrumental = false,
        plainLyrics = "Text",
        syncedLyrics = if (synced) "[00:01.00]Text" else null,
    )
}
