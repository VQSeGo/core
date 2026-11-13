package com.maxrave.domain.mediaservice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamSpliceGuardTest {
    @Test
    fun clenFromQuery() {
        assertEquals(
            3052268L,
            StreamSpliceGuard.clenOf(
                "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&itag=251&source=youtube&clen=3052268&dur=190.1&lmt=1",
            ),
        )
    }

    @Test
    fun clenAsLastParameterAndAfterRangeSuffix() {
        assertEquals(42L, StreamSpliceGuard.clenOf("https://x.googlevideo.com/videoplayback?itag=140&clen=42"))
        assertEquals(42L, StreamSpliceGuard.clenOf("https://x.googlevideo.com/videoplayback?clen=42&cpn=abc&range=0-42"))
    }

    @Test
    fun clenFromPath() {
        assertEquals(7L, StreamSpliceGuard.clenOf("https://x.googlevideo.com/videoplayback/id/abc/itag/251/clen/7/dur/1"))
    }

    @Test
    fun noClen() {
        assertNull(StreamSpliceGuard.clenOf(null))
        assertNull(StreamSpliceGuard.clenOf("https://manifest.googlevideo.com/api/manifest/hls_playlist/id/abc"))
        assertNull(StreamSpliceGuard.clenOf("6Bn_fPFqnf4"))
        // Similar names are not the parameter.
        assertNull(StreamSpliceGuard.clenOf("https://x.googlevideo.com/videoplayback?xclen=5&aclen=6"))
        assertNull(StreamSpliceGuard.clenOf("https://x.googlevideo.com/videoplayback?clen=&itag=1"))
        assertNull(StreamSpliceGuard.clenOf("https://x.googlevideo.com/videoplayback?clen=0"))
    }

    @Test
    fun sameLengthIsNotStale() {
        assertFalse(StreamSpliceGuard.isStale(recorded = 100L, hasBytes = true, fresh = 100L))
    }

    @Test
    fun otherLengthIsStale() {
        assertTrue(StreamSpliceGuard.isStale(recorded = 100L, hasBytes = true, fresh = 101L))
        // A length noted with no bytes left still misleads the next writer about where the file ends.
        assertTrue(StreamSpliceGuard.isStale(recorded = 100L, hasBytes = false, fresh = 101L))
    }

    @Test
    fun bytesOfUnknownLengthAreStale() {
        assertTrue(StreamSpliceGuard.isStale(recorded = null, hasBytes = true, fresh = 100L))
    }

    @Test
    fun emptyCacheIsNotStale() {
        assertFalse(StreamSpliceGuard.isStale(recorded = null, hasBytes = false, fresh = 100L))
    }

    @Test
    fun unknownFreshLengthNeverDrops() {
        assertFalse(StreamSpliceGuard.isStale(recorded = 100L, hasBytes = true, fresh = null))
        assertFalse(StreamSpliceGuard.isStale(recorded = null, hasBytes = true, fresh = null))
    }
}
