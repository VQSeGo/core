package com.maxrave.domain.mediaservice.handler

import kotlin.test.Test
import kotlin.test.assertEquals

class AutoDownloadGuardTest {
    @Test
    fun aTrackRemovedByHandIsNotQueued() {
        assertEquals(listOf("new"), selectAutoDownloads(listOf("gone", "new"), removed = setOf("gone"), queued = emptySet()))
    }

    @Test
    fun aTrackQueuedThisLaunchIsNotQueuedAgain() {
        assertEquals(listOf("b"), selectAutoDownloads(listOf("a", "b"), removed = emptySet(), queued = setOf("a")))
    }

    @Test
    fun aNewTrackIsQueuedOnce() {
        assertEquals(listOf("n"), selectAutoDownloads(listOf("n", "n"), removed = emptySet(), queued = emptySet()))
    }

    @Test
    fun nothingIsLeftWhenEveryTrackIsHeldBack() {
        assertEquals(emptyList(), selectAutoDownloads(listOf("a", "b"), removed = setOf("a"), queued = setOf("b")))
    }
}
