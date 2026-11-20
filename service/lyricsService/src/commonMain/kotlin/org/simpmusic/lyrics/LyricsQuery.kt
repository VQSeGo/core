package org.simpmusic.lyrics

import kotlin.math.abs

/**
 * Turns a YouTube title into the song title the lyrics sites know. A music video comes as
 * "Artist - Song (Official Music Video)", "Song [MV]", "Song (Клип)": the extra words make LRCLIB,
 * BetterLyrics and Spotify miss the song or find another one.
 *
 * Only what never names another version of the song goes: video and audio labels, quality, remaster,
 * explicit, featured artists and producer credits, the uploader's name in front. A bracket that also
 * holds anything else stays whole, so "(Live)", "(Remix)", "(Acoustic)", "(Live Video)",
 * "(Radio Edit)", "(Instrumental)", "(Sped Up)" keep telling which recording it is.
 */
object LyricsQuery {
    /** Words a bracket of nothing but video, audio or upload labels is made of. */
    private val noiseWords =
        setOf(
            "official", "officiel", "officielle", "oficial", "offizielles", "ufficiale",
            "music", "musical", "musik", "video", "videos", "musikvideo", "videoclip", "videoclipe", "clip",
            "audio", "lyric", "lyrics", "visualizer", "visualiser", "visualiser", "mv", "m", "v", "pv",
            "hd", "hq", "uhd", "4k", "8k", "1080p", "720p", "60fps", "full", "version", "remaster", "remastered",
            "explicit", "new", "premiere", "with", "and", "the", "en", "de", "con", "letra",
            "клип", "клипа", "видеоклип", "видео", "аудио", "официальный", "официальное", "официальная",
            "премьера", "премьеры", "трека", "песни", "текст", "текстом", "с", "и", "новый", "новинка",
        )

    private val bracketGroup = Regex("""[(\[【「]([^()\[\]【】「」]*)[)\]】」]""")
    private val featPrefix = Regex("""^(?:feat\.?|ft\.|featuring|при\s+уч\.?|при\s+участии)\s""", RegexOption.IGNORE_CASE)
    private val producerPrefix = Regex("""^(?:prod\.?|produced\s+by|прод\.?)\s""", RegexOption.IGNORE_CASE)
    private val bareFeat =
        Regex("""\s+(?:feat\.?|ft\.|featuring)\s+.*?(?=\s*(?:[(\[【]|\s[-–—|]\s|$))""", RegexOption.IGNORE_CASE)
    private val dashSplit = Regex("""\s+[-–—|]\s+""")
    private val trailingQuality = Regex("""(?:\s+(?:HD|HQ|4K|UHD|1080p|720p))+$""", RegexOption.IGNORE_CASE)
    private val quotes = Regex("""^["«“„'](.+)["»”'"]$""")

    /**
     * The song title in [title], for a search on the lyrics sites. [artist] is who the track is by
     * (YouTube's artist or channel): a leading "Artist - " goes only when it names them. Never blank:
     * when nothing would be left, [title] comes back as it was.
     */
    fun cleanTitle(
        title: String,
        artist: String? = null,
    ): String {
        var t = title.trim()
        // Brackets: drop the ones of labels, featured artists and producers.
        t =
            bracketGroup.replace(t) { m ->
                val inner = m.groupValues[1].trim()
                if (inner.isEmpty() || isNoise(inner) || featPrefix.containsMatchIn(inner) || producerPrefix.containsMatchIn(inner)) {
                    " "
                } else {
                    m.value
                }
            }
        t = bareFeat.replace(t, "")
        // "Artist - Song", "Song - Official Video", "Song | Artist": the parts around a spaced dash or bar.
        val parts = t.split(dashSplit).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size > 1) {
            val barred = t.contains(" | ")
            val kept =
                parts.filterIndexed { index, part ->
                    when {
                        isNoise(part) -> false
                        // The uploader's own name in front ("Artist - Song"), or after a bar ("Song | Artist").
                        artist != null && sameArtist(part, artist) -> !(index < parts.lastIndex || barred)
                        else -> true
                    }
                }
            if (kept.isNotEmpty()) t = kept.joinToString(" - ")
        }
        t = trailingQuality.replace(t.trim(), "")
        t = t.replace(Regex("""\s{2,}"""), " ").trim()
        quotes.find(t)?.let { t = it.groupValues[1].trim() }
        return t.ifBlank { title.trim() }
    }

    /** [artist] without what a channel adds to it: "KastaVEVO", "Kasta - Topic", "Kasta Official". */
    fun cleanArtist(artist: String): String {
        val cleaned =
            artist
                .trim()
                .replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*VEVO$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+(?:official|официальный)$""", RegexOption.IGNORE_CASE), "")
                .trim()
        return cleaned.ifBlank { artist.trim() }
    }

    /**
     * Whether [a] and [b] name the same song: the same letters and digits once case, punctuation and
     * the labels [cleanTitle] drops are gone, or one holding the other ("Song" and "Song - Remix" do).
     */
    fun titlesMatch(
        a: String?,
        b: String?,
    ): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val x = normalize(cleanTitle(a))
        val y = normalize(cleanTitle(b))
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val (short, long) = if (x.length <= y.length) x to y else y to x
        return short.length >= 3 && long.contains(short)
    }

    /**
     * Search results in the order to take them: the same title and length first, then the same
     * title (the closest length first), then the same length, then the rest as the site ranked them.
     * A video often runs longer than the song, so a title match beats a stranger of the right length.
     * Nothing is dropped: a lone result still comes first.
     */
    fun <T> rankSearchResults(
        items: List<T>,
        title: String?,
        durationSec: Int?,
        toleranceSec: Int,
        titleOf: (T) -> String?,
        durationSecOf: (T) -> Double?,
    ): List<T> {
        val known = durationSec != null && durationSec > 0
        fun diff(item: T): Double? = if (known) durationSecOf(item)?.let { abs(it - durationSec!!) } else null
        fun lengthMatches(item: T): Boolean = diff(item)?.let { it <= toleranceSec } == true
        val titled = items.map { titlesMatch(titleOf(it), title) }
        val tier =
            items.mapIndexed { i, item ->
                when {
                    titled[i] && lengthMatches(item) -> 0
                    titled[i] -> 1
                    lengthMatches(item) -> 2
                    else -> 3
                }
            }
        return items.indices
            .sortedWith(
                compareBy<Int> { tier[it] }
                    .thenBy { if (tier[it] == 1) diff(items[it]) ?: Double.MAX_VALUE else 0.0 }
                    .thenBy { it },
            ).map { items[it] }
    }

    private fun isNoise(text: String): Boolean {
        val words =
            text
                .lowercase()
                .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
                .split(' ')
                .filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        return words.all { it in noiseWords || it.matches(Regex("""(?:19|20)\d\d""")) }
            // A bracket of only a year, "and" or "the" names nothing worth dropping on its own.
            && words.any { it !in setOf("and", "the", "with", "en", "de", "con", "с", "и", "new", "full", "version") && !it.matches(Regex("""\d+""")) }
    }

    private fun sameArtist(
        part: String,
        artist: String,
    ): Boolean {
        val p = normalize(part)
        val a = normalize(cleanArtist(artist))
        if (p.isEmpty() || a.isEmpty()) return false
        // "Kasta, Hamil - Song" and "Kasta feat. Hamil - Song" name the artist too.
        return p == a || (a.length >= 3 && p.startsWith(a))
    }

    private fun normalize(text: String): String =
        text
            .lowercase()
            .replace('ё', 'е')
            .replace(Regex("""[^\p{L}\p{N}]+"""), "")
}
