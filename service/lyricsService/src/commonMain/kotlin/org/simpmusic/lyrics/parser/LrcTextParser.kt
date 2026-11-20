package org.simpmusic.lyrics.parser

import com.maxrave.domain.extension.decodeHtmlEntities
import org.simpmusic.lyrics.domain.Lyrics

// One line time: `[mm:ss]`, `[mm:ss.x]`, `[mm:ss.xx]` (hundredths, what LRCLIB and SimpMusic write)
// or `[mm:ss.xxx]`; some files put a colon before the fraction.
private val LRC_LINE_TIME = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

// `[offset:+250]`: every time of the file is that many ms sooner (LRC's own definition).
private val LRC_OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

/**
 * Reads line-synced LRC. The fraction is scaled by its length, so `[00:27.12]` is 27 120 ms (it was
 * read as 27 012, every line up to 0.9 s early). A line that carries several times
 * (`[00:12.00][01:30.00]Chorus`) plays at each of them, `[offset:]` moves every time, a time with no
 * text after it is an instrumental gap (`♫`), and the lines come out in time order.
 */
fun parseSyncedLyrics(data: String): Lyrics {
    var offsetMs = 0L
    val timed = ArrayList<Pair<Long, String>>()
    data.lines().forEach { raw ->
        val line = raw.trim()
        LRC_OFFSET_TAG.matchEntire(line)?.let { tag ->
            offsetMs = tag.groupValues[1].toLongOrNull() ?: 0L
            return@forEach
        }
        val times = ArrayList<Long>()
        var pos = 0
        while (true) {
            val match = LRC_LINE_TIME.find(line, pos)?.takeIf { it.range.first == pos } ?: break
            val (minutes, seconds, fraction) = match.destructured
            val fractionMs =
                when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.toLong()
                }
            times += minutes.toLong() * 60_000L + seconds.toLong() * 1000L + fractionMs
            pos = match.range.last + 1
        }
        if (times.isEmpty()) return@forEach
        // Trim, don't drop a fixed first character. Most providers leave a space after
        // "]", but LRCLIB packs the text right against it — "[00:27.12]boy, you got me".
        val rawContent = line.substring(pos)
        val content = if (rawContent.isBlank()) "♫" else decodeHtmlEntities(rawContent.trimStart())
        times.forEach { timed += it to content }
    }
    val linesLyrics =
        timed.sortedBy { it.first }.map { (timeMs, words) ->
            Lyrics.LyricsX.Line(
                endTimeMs = "0",
                startTimeMs = (timeMs - offsetMs).coerceAtLeast(0L).toString(),
                syllables = listOf(),
                words = words,
            )
        }
    return Lyrics(
        lyrics =
            Lyrics.LyricsX(
                lines = linesLyrics,
                syncType = "LINE_SYNCED",
            ),
    )
}

fun parseRichSyncLyrics(data: String): Lyrics {
    // Unescape JSON string if needed (remove quotes and replace \n with actual newlines)
    val unescapedData =
        data
            .trim()
            .removePrefix("\"")
            .removeSuffix("\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")

    // Handle different line separators (Unix \n, Windows \r\n, Mac \r)
    val lines = unescapedData.lines()
    // Skip offset line if present (starts with [offset:)
    val lyricsLines =
        lines.filter { line ->
            line.isNotBlank() && !line.trim().startsWith("[offset:")
        }

    println("[parseRichSyncLyrics] Total lines: ${lines.size}, Filtered lines: ${lyricsLines.size}")
    if (lyricsLines.isNotEmpty()) {
        println("[parseRichSyncLyrics] First line sample: ${lyricsLines.first()}")
    }

    // Regex to match [MM:SS.mm] format (flexible with 1-2 digits)
    val regex = Regex("\\[(\\d{1,2}):(\\d{2})\\.(\\d{2,3})\\](.+)")
    // Strip a leading voice marker (e.g. "v1:", "v2:") that some lyrics
    // providers prepend before the first word timestamp, so it doesn't leak
    // into the word content and stick to the first word.
    val voiceMarkerRegex = Regex("""^v\d+:""")
    val linesLyrics = ArrayList<Lyrics.LyricsX.Line>()

    lyricsLines.forEachIndexed { index, line ->
        val matchResult = regex.matchEntire(line.trim())
        if (matchResult != null) {
            val minutes = matchResult.groupValues[1].toLongOrNull() ?: 0L
            val seconds = matchResult.groupValues[2].toLongOrNull() ?: 0L
            val centiseconds = matchResult.groupValues[3].toLongOrNull() ?: 0L

            // Convert to milliseconds
            // If centiseconds has 3 digits (milliseconds), use directly
            // If 2 digits (centiseconds), multiply by 10
            val millisPart = if (matchResult.groupValues[3].length == 3) centiseconds else centiseconds * 10
            val timeInMillis = minutes * 60_000L + seconds * 1000L + millisPart

            // Keep the rich sync content as-is (with <MM:SS.mm> word format)
            val content =
                matchResult.groupValues[4]
                    .trimStart()
                    .replace(voiceMarkerRegex, "")
                    .trimStart()

            if (content.isNotBlank()) {
                linesLyrics.add(
                    Lyrics.LyricsX.Line(
                        endTimeMs = "0",
                        startTimeMs = timeInMillis.toString(),
                        syllables = listOf(),
                        words = content,
                    ),
                )
            }
        } else {
            if (index < 3) { // Only log first 3 failed matches to avoid spam
                println("[parseRichSyncLyrics] Line $index failed to match: '${line.take(100)}'")
            }
        }
    }

    println("[parseRichSyncLyrics] Parsed ${linesLyrics.size} lines successfully")

    return Lyrics(
        lyrics =
            Lyrics.LyricsX(
                lines = linesLyrics,
                syncType = "RICH_SYNCED",
            ),
    )
}

/**
 * Parse TTML (Timed Text Markup Language) lyrics from BetterLyrics.
 * Supports both line-synced and word-by-word synced lyrics.
 *
 * TTML format: `<p begin="M:SS.mmm" end="M:SS.mmm">` contains `<span begin="..." end="...">word</span>`
 * If spans with timing exist → word-by-word (RICH_SYNCED)
 * If no spans → line-synced (LINE_SYNCED)
 */
fun parseTtmlLyrics(data: String): Lyrics {
    val linesLyrics = ArrayList<Lyrics.LyricsX.Line>()
    var hasWordTiming = false

    // Match each <p ...>...</p> element (use [\s\S] instead of . with DOT_MATCHES_ALL)
    val pRegex = Regex("""<p\s[^>]*begin="([^"]+)"[^>]*end="([^"]+)"[^>]*>([\s\S]*?)</p>""")
    // Match each <span ...>word</span> element
    val spanRegex = Regex("""<span\s[^>]*begin="([^"]+)"[^>]*end="([^"]+)"[^>]*>(.*?)</span>""")

    for (pMatch in pRegex.findAll(data)) {
        val lineBegin = parseTtmlTime(pMatch.groupValues[1])
        val lineEnd = parseTtmlTime(pMatch.groupValues[2])
        val innerContent = pMatch.groupValues[3]

        val spans = spanRegex.findAll(innerContent).toList()

        if (spans.isNotEmpty()) {
            hasWordTiming = true
            // Build word-by-word content with <MM:SS.mm> timing format for rich sync
            val wordParts = StringBuilder()
            for (span in spans) {
                val spanBegin = parseTtmlTime(span.groupValues[1])
                val word = span.groupValues[3].trim()
                if (word.isNotEmpty()) {
                    val beginFormatted = formatMsToLrc(spanBegin)
                    wordParts.append("<$beginFormatted>$word ")
                }
            }
            val words = wordParts.toString().trimEnd()
            if (words.isNotBlank()) {
                linesLyrics.add(
                    Lyrics.LyricsX.Line(
                        startTimeMs = lineBegin.toString(),
                        endTimeMs = lineEnd.toString(),
                        syllables = listOf(),
                        words = words,
                    ),
                )
            }
        } else {
            // No spans — extract plain text (strip any remaining tags)
            val plainText = innerContent.replace(Regex("<[^>]*>"), "").trim()
            if (plainText.isNotBlank()) {
                linesLyrics.add(
                    Lyrics.LyricsX.Line(
                        startTimeMs = lineBegin.toString(),
                        endTimeMs = lineEnd.toString(),
                        syllables = listOf(),
                        words = plainText,
                    ),
                )
            }
        }
    }

    return Lyrics(
        lyrics =
            Lyrics.LyricsX(
                lines = linesLyrics,
                syncType = if (hasWordTiming) "RICH_SYNCED" else "LINE_SYNCED",
            ),
    )
}

/**
 * Parse TTML time format to milliseconds.
 * Supports: "M:SS.mmm", "MM:SS.mmm", "H:MM:SS.mmm", "SS.mmm"
 */
private fun parseTtmlTime(time: String): Long {
    val parts = time.split(":")
    return when (parts.size) {
        3 -> {
            val hours = parts[0].toLongOrNull() ?: 0L
            val minutes = parts[1].toLongOrNull() ?: 0L
            val secParts = parts[2].split(".")
            val seconds = secParts[0].toLongOrNull() ?: 0L
            val millis = parseMillisPart(secParts.getOrNull(1))
            hours * 3_600_000L + minutes * 60_000L + seconds * 1000L + millis
        }
        2 -> {
            val minutes = parts[0].toLongOrNull() ?: 0L
            val secParts = parts[1].split(".")
            val seconds = secParts[0].toLongOrNull() ?: 0L
            val millis = parseMillisPart(secParts.getOrNull(1))
            minutes * 60_000L + seconds * 1000L + millis
        }
        else -> {
            val secParts = time.split(".")
            val seconds = secParts[0].toLongOrNull() ?: 0L
            val millis = parseMillisPart(secParts.getOrNull(1))
            seconds * 1000L + millis
        }
    }
}

private fun parseMillisPart(part: String?): Long {
    if (part.isNullOrEmpty()) return 0L
    val value = part.toLongOrNull() ?: return 0L
    return when (part.length) {
        1 -> value * 100
        2 -> value * 10
        3 -> value
        else -> value
    }
}

private fun formatMsToLrc(ms: Long): String {
    val minutes = ms / 60_000L
    val seconds = (ms % 60_000L) / 1000L
    val centis = (ms % 1000L) / 10L
    val m = if (minutes < 10) "0$minutes" else "$minutes"
    val s = if (seconds < 10) "0$seconds" else "$seconds"
    val c = if (centis < 10) "0$centis" else "$centis"
    return "$m:$s.$c"
}

fun parseUnsyncedLyrics(data: String): Lyrics {
    val lines = data.lines()
    val linesLyrics = ArrayList<Lyrics.LyricsX.Line>()
    lines.map { line ->
        linesLyrics.add(
            Lyrics.LyricsX.Line(
                endTimeMs = "0",
                startTimeMs = "0",
                syllables = listOf(),
                words = line,
            ),
        )
    }
    return Lyrics(
        lyrics =
            Lyrics.LyricsX(
                lines = linesLyrics,
                syncType = "UNSYNCED",
            ),
    )
}