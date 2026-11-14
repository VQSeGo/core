package com.maxrave.domain.data.entities

import com.maxrave.domain.data.entities.DownloadState.STATE_DOWNLOADED
import com.maxrave.domain.data.entities.DownloadState.STATE_DOWNLOADING
import com.maxrave.domain.data.entities.DownloadState.STATE_NOT_DOWNLOADED
import com.maxrave.domain.data.entities.DownloadState.STATE_PREPARING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionDownloadStateTest {
    @Test
    fun allTracksDownloadedIsDownloaded() {
        assertEquals(STATE_DOWNLOADED, collectionDownloadState(listOf(STATE_DOWNLOADED, STATE_DOWNLOADED)))
    }

    @Test
    fun oneTrackDownloadingIsDownloadingEvenWithUnreportedOnes() {
        assertEquals(STATE_DOWNLOADING, collectionDownloadState(listOf(STATE_DOWNLOADED, STATE_DOWNLOADING, null)))
    }

    @Test
    fun queuedTrackBetweenTwoDownloadsIsStillDownloading() {
        assertEquals(STATE_DOWNLOADING, collectionDownloadState(listOf(STATE_DOWNLOADED, STATE_PREPARING)))
    }

    @Test
    fun unreportedTrackLeavesTheStateAlone() {
        assertNull(collectionDownloadState(listOf(STATE_DOWNLOADED, null)))
    }

    @Test
    fun emptyCollectionLeavesTheStateAlone() {
        assertNull(collectionDownloadState(emptyList()))
    }

    @Test
    fun failedTrackMakesItNotDownloaded() {
        assertEquals(STATE_NOT_DOWNLOADED, collectionDownloadState(listOf(STATE_DOWNLOADED, STATE_NOT_DOWNLOADED)))
    }
}
