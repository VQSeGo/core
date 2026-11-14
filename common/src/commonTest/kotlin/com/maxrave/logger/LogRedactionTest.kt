package com.maxrave.logger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogRedactionTest {
    private val link =
        "https://rr4---sn-aigl6nze.googlevideo.com/videoplayback?expire=1794694790&ei=Jor4ap-6IsCBhc4PjYnuwA8" +
            "&ip=2.26.76.222&id=o-ALE_O802lH4Qd5qphRRCeUVdKIuD2rQomCgsgtAJaMOE&itag=250&source=youtube&n=AbCd-EfGh_12" +
            "&mime=audio%2Fwebm&clen=1796514&dur=218.561&sparams=expire%2Cei%2Cip%2Cid%2Citag&sig=AE0s2JYwRAIgQRsQ" +
            "&lsig=APaTxxMwRQIhAO&pot=MnQ_pot-value&key=yt8"

    @Test
    fun streamLinkSignaturesAreMasked() {
        val out = LogRedaction.redact("curl -X HEAD '$link'")
        for (name in listOf("sig", "lsig", "n", "pot", "key", "ip", "ei")) {
            assertTrue(out.contains("&$name=***") || out.contains("?$name=***"), "$name not masked in $out")
        }
        assertFalse(out.contains("AE0s2JYw"))
        assertFalse(out.contains("2.26.76.222"))
        assertFalse(out.contains("AbCd-EfGh_12"))
    }

    @Test
    fun streamLinkKeepsWhatHelpsToReadTheLog() {
        val out = LogRedaction.redact(link)
        for (kept in listOf("rr4---sn-aigl6nze.googlevideo.com", "expire=1794694790", "itag=250", "mime=audio%2Fwebm", "clen=1796514")) {
            assertTrue(out.contains(kept), "$kept lost in $out")
        }
        // The name sparams lists is not a parameter and keeps its value.
        assertTrue(out.contains("sparams=expire%2Cei%2Cip%2Cid%2Citag"))
    }

    @Test
    fun otherHostsAreUntouched() {
        val text = "GET https://example.com/a?n=1&sig=2&ip=3"
        assertEquals(text, LogRedaction.redact(text))
    }

    @Test
    fun maskingTwiceChangesNothing() {
        val once = LogRedaction.redact(link)
        assertEquals(once, LogRedaction.redact(once))
    }

    @Test
    fun throwableMessageIsMasked() {
        val error = IllegalStateException("Client request(HEAD $link) invalid: 403 Forbidden")
        val logged = LogRedaction.redactThrowable(error)
        assertFalse(logged.stackTraceToString().contains("AE0s2JYw"))
        assertTrue(logged.stackTraceToString().contains("403 Forbidden"))
        val clean = IllegalStateException("nothing to hide")
        assertTrue(LogRedaction.redactThrowable(clean) === clean)
    }
}
