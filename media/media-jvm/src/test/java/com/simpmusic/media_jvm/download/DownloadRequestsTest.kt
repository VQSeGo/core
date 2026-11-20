package com.simpmusic.media_jvm.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRequestsTest {
    @Test
    fun aSecondRequestForTheSameTrackIsTurnedAway() {
        val requests = DownloadRequests()
        assertNotNull(requests.register("a"))
        assertNull(requests.register("a"))
        assertNotNull(requests.register("b"))
    }

    @Test
    fun aRemovedAndRequestedAgainTrackBelongsToTheNewRequest() {
        val requests = DownloadRequests()
        val old = requests.register("a")!!
        assertTrue(requests.cancel("a"))
        val new = requests.register("a")!!
        assertNotEquals(old, new)
        // The old run wakes up after its transfer was cancelled: the id is there, but it is not its request.
        assertFalse(requests.isCurrent("a", old))
        assertFalse(requests.complete("a", old))
        assertTrue("a" in requests)
        // The new request is untouched and its own run finishes it.
        assertTrue(requests.isCurrent("a", new))
        assertTrue(requests.complete("a", new))
        assertFalse("a" in requests)
    }

    @Test
    fun aRemovedTrackLeavesNoRequestForItsRun() {
        val requests = DownloadRequests()
        val token = requests.register("a")!!
        requests.cancel("a")
        assertFalse(requests.isCurrent("a", token))
        assertFalse(requests.complete("a", token))
        assertFalse("a" in requests)
    }

    @Test
    fun cancelOfATrackWithNoRequestSaysSo() {
        assertFalse(DownloadRequests().cancel("a"))
    }

    @Test
    fun aFinishedRequestIsEndedOnceAndTheTrackCanBeAskedForAgain() {
        val requests = DownloadRequests()
        val token = requests.register("a")!!
        assertTrue(requests.complete("a", token))
        assertFalse(requests.complete("a", token))
        assertNotNull(requests.register("a"))
    }

    @Test
    fun idsListsWhatIsPending() {
        val requests = DownloadRequests()
        requests.register("a")
        requests.register("b")
        requests.cancel("a")
        assertEquals(listOf("b"), requests.ids())
    }
}
