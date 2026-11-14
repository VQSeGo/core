package com.maxrave.domain.data.entities

import com.maxrave.domain.data.entities.DownloadState.STATE_DOWNLOADED
import com.maxrave.domain.data.entities.DownloadState.STATE_DOWNLOADING
import com.maxrave.domain.data.entities.DownloadState.STATE_NOT_DOWNLOADED
import com.maxrave.domain.data.entities.DownloadState.STATE_PREPARING

/**
 * The download state of a playlist or an album from its tracks' states, as `DownloadHandler.downloadTask`
 * reports them (null: nothing reported for that track yet).
 *
 * A track's state there already follows its audio: a clip whose audio is done counts as downloaded while
 * its video half is still downloading or has failed, because the audio alone plays without a network.
 * Reading the raw audio/video pairs instead held a playlist on "downloading" for the length of the video
 * download, and for good once the video failed.
 *
 * Null leaves the stored state alone: some track has not been reported yet and none is downloading.
 */
fun collectionDownloadState(trackStates: List<Int?>): Int? =
    when {
        trackStates.isEmpty() -> null
        // Before completeness: one track downloading or queued makes the whole downloading, reported or
        // not. Queued counts: between two tracks of a batch nothing is downloading for a moment.
        trackStates.any { it == STATE_DOWNLOADING || it == STATE_PREPARING } -> STATE_DOWNLOADING
        trackStates.all { it == STATE_DOWNLOADED } -> STATE_DOWNLOADED
        trackStates.any { it == null } -> null
        else -> STATE_NOT_DOWNLOADED
    }
