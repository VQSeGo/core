package com.maxrave.domain.mediaservice

/**
 * Keeps bytes of two different encodes of one track out of one file.
 *
 * Caches and downloads key a track by its video id alone, while the stream behind that id comes in
 * several variants: another itag (stream and download quality are separate settings), the DRC twin
 * of the same itag, another client's file. Bytes already on disk from one variant, completed by a
 * freshly resolved URL of another, make a file whose container breaks at the seam ("No valid varint
 * length mask found" from the WebM extractor) and that never plays again.
 *
 * Every googlevideo URL names the length of its file (`clen`), so a cached copy that recorded the
 * length of the variant it came from can be checked against the URL about to complete it.
 */
object StreamSpliceGuard {
    // Query parameter (`?clen=123&`) or the path form some clients get (`/clen/123/`).
    private val CLEN = Regex("""(?:[?&/])clen[=/](\d+)""")

    /** The file length a googlevideo URL announces, or null when it names none (HLS playlists, other hosts). */
    fun clenOf(url: String?): Long? =
        url
            ?.let { CLEN.find(it) }
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }

    /**
     * Whether what the cache holds for a key may not be completed with a stream of length [fresh].
     *
     * [recorded] is the length the cache noted for the bytes it holds, null when it noted none;
     * [hasBytes] whether it holds any. Without a fresh length nothing can be told and nothing is
     * dropped. Bytes whose length was never noted have an unknown origin and count as stale: the
     * price of a wrong guess is downloading them again, the price of a splice is a broken file.
     */
    fun isStale(
        recorded: Long?,
        hasBytes: Boolean,
        fresh: Long?,
    ): Boolean = fresh != null && (recorded != null || hasBytes) && recorded != fresh
}
