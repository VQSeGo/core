package com.maxrave.domain.mediaservice.handler

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.repository.SongRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * The lyrics offset of whatever is playing, following the track. A composable has to remember the
 * result: a new flow on each recomposition would restart the collection every time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun SongRepository.currentLyricsOffsetMs(mediaPlayerHandler: MediaPlayerHandler): Flow<Int> =
    mediaPlayerHandler.nowPlayingState
        .map { it.mediaItem.mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO) }
        .distinctUntilChanged()
        .flatMapLatest { videoId -> getLyricsOffsetMs(videoId) }
