package com.maxrave.media3.service.download

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.player.LiveStreamRegistry
import com.maxrave.domain.extension.now
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.handler.downloadNotificationsEnabled
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.isLive
import com.maxrave.logger.Logger
import com.maxrave.domain.mediaservice.StreamSpliceGuard
import com.maxrave.media3.extension.CacheBypassDataSourceFactory
import com.maxrave.media3.extension.SkipCache
import com.maxrave.media3.extension.dropStale
import com.maxrave.media3.extension.isFullyCached
import com.maxrave.media3.extension.isStaleFor
import com.maxrave.media3.extension.logSpliceGuard
import com.maxrave.media3.extension.recordStreamLength
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.util.concurrent.Executor

@UnstableApi
internal class DownloadUtils(
    private val context: Context,
    private val playerCache: SimpleCache,
    private val downloadCache: SimpleCache,
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
    private val songRepository: SongRepository,
    databaseProvider: DatabaseProvider,
) : DownloadHandler {
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpDataSourceFactory = OkHttpDataSource.Factory(OkHttpClient.Builder().build())

    // What the player has already streamed is reused, but read only: a download's own variant
    // (download quality is a setting of its own) must not land in playerCache under the same id,
    // and playerCache bytes of another variant are read around (SkipCache, see [guarded]).
    private val dataSourceFactory =
        ResolvingDataSource.Factory(
            CacheBypassDataSourceFactory(
                cached =
                    CacheDataSource
                        .Factory()
                        .setCache(playerCache)
                        .setCacheWriteDataSinkFactory(null)
                        .setUpstreamDataSourceFactory(httpDataSourceFactory),
                direct = httpDataSourceFactory,
            ),
        ) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            Logger.d("Stream", mediaId)
            Logger.d("Stream", mediaId.startsWith(MERGING_DATA_TYPE.VIDEO).toString())
            // Already downloaded in full: hand the DataSpec back untouched so the enclosing
            // CacheDataSource reads straight off disk. Safe precisely because the whole track
            // is there — it never needs the upstream, which is the only thing the bare media id
            // in the URI cannot survive. Re-resolving here would re-fetch a track the user
            // already has.
            if (downloadCache.isFullyCached(mediaId, dataSpec.position)) {
                Logger.d("Stream", "Already downloaded $mediaId")
                return@Factory dataSpec
            }
            // Anything short of a full copy must resolve a real URL, and must never hand back
            // the incoming DataSpec: its URI is the bare media id. Skipping the bytes already on
            // disk is not this resolver's job — CacheWriter does that via cache.getCachedLength(),
            // and the CacheDataSource wrapping this resolver still serves whatever playerCache
            // holds.
            //
            // The upstream here is OkHttpDataSource (not DefaultDataSource, as on the playback
            // side), so a scheme-less URI dies in HttpUrl.parse as
            // HttpDataSourceException("Malformed URL", ERROR_CODE_FAILED_RUNTIME_CHECK) — a
            // misleading error for what is really "we could not get a stream URL". Fail loudly
            // instead, and let DownloadManager mark the download failed for the true reason.
            var dataSpecReturn: DataSpec = dataSpec
            var resolved = false
            runBlocking(Dispatchers.IO) {
                if (mediaId.contains(MERGING_DATA_TYPE.VIDEO)) {
                    val id = mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
                    streamRepository.getNewFormat(id).lastOrNull()?.let {
                        val videoUrl = it.videoUrl
                        if (videoUrl != null && it.expiredTime > now()) {
                            Logger.d("Stream", videoUrl)
                            Logger.d("Stream", "Video from format")
                            val is403Url = streamRepository.is403Url(videoUrl).firstOrNull() != false
                            if (!is403Url) {
                                dataSpecReturn = guarded(dataSpec, mediaId, videoUrl)
                                resolved = true
                                return@runBlocking
                            }
                        }
                    }
                    streamRepository
                        .getStream(
                            dataStoreManager,
                            id,
                            isDownloading = true,
                            true,
                        ).lastOrNull()
                        ?.let {
                            dataSpecReturn = guarded(dataSpec, mediaId, it)
                            resolved = true
                        }
                } else {
                    streamRepository.getNewFormat(mediaId).lastOrNull()?.let {
                        val audioUrl = it.audioUrl
                        if (audioUrl != null && it.expiredTime > now()) {
                            Logger.d("Stream", audioUrl)
                            Logger.d("Stream", "Audio from format")
                            val is403Url = streamRepository.is403Url(audioUrl).firstOrNull() != false
                            if (!is403Url) {
                                dataSpecReturn = guarded(dataSpec, mediaId, audioUrl)
                                resolved = true
                                return@runBlocking
                            }
                        }
                    }
                    streamRepository
                        .getStream(
                            dataStoreManager,
                            mediaId,
                            isDownloading = true,
                            isVideo = false,
                        ).lastOrNull()
                        ?.let {
                            if (it.contains("MPD")) {
                                DashManifestParser().parse(
                                    it.toUri(),
                                    ByteArrayInputStream(it.toByteArray()),
                                )
                            }
                            dataSpecReturn = guarded(dataSpec, mediaId, it)
                            resolved = true
                        }
                }
            }
            if (!resolved) {
                // getStream hands a live broadcast no URL here (StreamRepositoryImpl), so say
                // which case this is rather than a bare "failed to resolve".
                val reason =
                    if (LiveStreamRegistry.isLive(mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO))) {
                        "it is a live stream"
                    } else {
                        "failed to resolve stream URL"
                    }
                Logger.e("Stream", "Cannot download $mediaId: $reason")
                throw java.io.IOException("Cannot download $mediaId: $reason")
            }
            return@Factory dataSpecReturn
        }
    /**
     * The URL [url] for [dataSpec], checked against what is already on disk for [key] (see
     * StreamSpliceGuard). The DownloadManager reading this has sized its work from downloadCache
     * before this runs, so stale bytes there cannot just be dropped and read on: they are dropped
     * and this attempt fails, and DownloadManager's retry starts the file over from byte zero.
     * Stale playerCache bytes belong to playback and are only read around.
     */
    private fun guarded(
        dataSpec: DataSpec,
        key: String,
        url: String,
    ): DataSpec {
        val spec = dataSpec.withUri(url.toUri())
        val fresh = StreamSpliceGuard.clenOf(url)
        if (fresh == null) {
            Logger.d("Offline", "no clen in the download URL of $key, splice guard skipped")
            return spec
        }
        if (downloadCache.isStaleFor(key, fresh)) {
            downloadCache.logSpliceGuard(key, fresh, "download (restart)")
            downloadCache.dropStale(key)
            throw java.io.IOException("Download of $key restarts: the bytes on disk came from another stream variant")
        }
        downloadCache.recordStreamLength(key, fresh)
        if (playerCache.isStaleFor(key, fresh)) {
            playerCache.logSpliceGuard(key, fresh, "cache (read around)")
            return spec.buildUpon().setCustomData(SkipCache).build()
        }
        return spec
    }

    val downloadNotificationHelper =
        DownloadNotificationHelper(
            context,
            MusicDownloadService.Companion.CHANNEL_ID,
        )
    val downloadManager: DownloadManager =
        DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            dataSourceFactory,
            Executor(Runnable::run),
        ).apply {
            maxParallelDownloads = 20
            minRetryCount = 3
            addListener(
                MusicDownloadService.TerminalStateNotificationHelper(
                    context = context,
                    notificationHelper = downloadNotificationHelper,
                    nextNotificationId = MusicDownloadService.NOTIFICATION_ID + 1,
                    scope = coroutineScope,
                    isSmartDownload = { videoId ->
                        songRepository.getSongById(videoId).lastOrNull()?.category == SongEntity.SMART_DOWNLOAD
                    },
                    notificationsEnabled = { dataStoreManager.downloadNotificationsEnabled.first() },
                ),
            )
        }
    private var _downloads = MutableStateFlow<Map<String, Pair<DownloadHandler.Download?, DownloadHandler.Download?>>>(emptyMap())

    // Audio / Video
    override val downloads: StateFlow<Map<String, Pair<DownloadHandler.Download?, DownloadHandler.Download?>>>
        get() = _downloads
    private val _downloadTask = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val downloadTask: StateFlow<Map<String, Int>> get() = _downloadTask
    val downloadingVideoIds = MutableStateFlow<MutableSet<String>>(mutableSetOf())

    /**
     * Use thumbnail to check video or audio. The video stream is fetched only while
     * "play video instead of audio" is on: playback merges it in for nothing else.
     */
    override suspend fun downloadTrack(
        videoId: String,
        title: String,
        thumbnail: String,
    ) {
        // Every download path ends here, so this is where a broadcast already known to be live
        // is turned away. One not known yet is refused when its stream resolves.
        if (songRepository.getSongById(videoId).lastOrNull()?.isLive == true) {
            Logger.i("Download", "$videoId is a live stream, not downloading")
            songRepository.updateDownloadState(videoId, DownloadState.STATE_NOT_DOWNLOADED)
            return
        }
        var isVideo = false
        if (dataStoreManager.watchVideoInsteadOfPlayingAudio.first() == DataStoreManager.Values.TRUE) {
            val request =
                ImageRequest
                    .Builder(context)
                    .data(thumbnail)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
            val imageResult = ImageLoader(context).execute(request)
            if (imageResult.image?.height != imageResult.image?.width && imageResult.image != null) {
                isVideo = true
            }
        }
        val downloadRequest =
            DownloadRequest
                .Builder(videoId, videoId.toUri())
                .setData(title.toByteArray())
                .setCustomCacheKey(videoId)
                .build()
        DownloadService.sendAddDownload(
            context,
            MusicDownloadService::class.java,
            downloadRequest,
            false,
        )
        if (isVideo) {
            downloadVideoPart(videoId, title)
        }
    }

    override fun downloadVideoPart(
        videoId: String,
        title: String,
    ) {
        // Called on every play of a clip whose video half is missing. Re-adding a running download
        // would cancel and restart its task. A queued or stopped one is sent again on purpose: after
        // a cold start nothing runs until DownloadService is started, and the add starts it.
        val state = _downloads.value[videoId]?.second?.state
        if (state == Download.STATE_DOWNLOADING || state == Download.STATE_COMPLETED) return
        val id = MERGING_DATA_TYPE.VIDEO + videoId
        val downloadRequestVideo =
            DownloadRequest
                .Builder(id, id.toUri())
                .setData("Video $title".toByteArray())
                .setCustomCacheKey(id)
                .build()
        try {
            DownloadService.sendAddDownload(
                context,
                MusicDownloadService::class.java,
                downloadRequestVideo,
                false,
            )
            Logger.i("Offline", "video part queued $videoId")
        } catch (e: IllegalStateException) {
            // startService from the background; the next play asks again.
            Logger.w("Offline", "video part not queued $videoId: ${e.message}")
        }
    }

    override fun removeDownload(videoId: String) {
        DownloadService.sendRemoveDownload(
            context,
            MusicDownloadService::class.java,
            videoId,
            false,
        )
        val id = MERGING_DATA_TYPE.VIDEO + videoId
        DownloadService.sendRemoveDownload(
            context,
            MusicDownloadService::class.java,
            id,
            false,
        )
    }

    /**
     * The bytes go synchronously, before the caller reloads the track: DownloadManager removes them
     * too, but on its own thread, and a player reloading first would read the broken file again.
     * Then the download is removed and added again, in that order: an add that finds the download
     * REMOVING turns it into RESTARTING, which fetches it anew once the removal is done, and the
     * song row follows through the usual states. An add on its own would find the download
     * COMPLETED and finish at once with nothing fetched.
     */
    override fun redownloadCorrupt(key: String): Boolean {
        val download = downloadManager.downloadIndex.getDownload(key)
        if (download == null || download.state != Download.STATE_COMPLETED) return false
        downloadCache.dropStale(key)
        try {
            DownloadService.sendRemoveDownload(context, MusicDownloadService::class.java, key, false)
            DownloadService.sendAddDownload(context, MusicDownloadService::class.java, download.request, false)
        } catch (e: IllegalStateException) {
            // startService from the background. The manager runs the same requests itself; the
            // service only keeps the process alive, and picks the queue up when it next starts.
            Logger.w("Offline", "redownload $key without the service: ${e.message}")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                downloadManager.removeDownload(key)
                downloadManager.addDownload(download.request)
            }
        }
        Logger.i("Offline", "redownload $key queued")
        return true
    }

    override fun removeAllDownloads() {
        _downloads.value = emptyMap()
        _downloadTask.value = emptyMap()
        downloadingVideoIds.value = mutableSetOf()
        downloadManager.removeAllDownloads()
    }

    init {
        coroutineScope.launch {
            var previousIds = emptySet<String>()
            downloads.collect { download ->
                // A song that left the map was removed (onDownloadRemoved, removeAllDownloads).
                // Written here rather than in the listener so it lands after any older snapshot
                // that still listed the song as downloaded.
                (previousIds - download.keys).forEach { videoId ->
                    _downloadTask.update { it + (videoId to DownloadState.STATE_NOT_DOWNLOADED) }
                    downloadingVideoIds.update {
                        it.apply {
                            remove(videoId)
                        }
                    }
                    songRepository.updateDownloadState(videoId, DownloadState.STATE_NOT_DOWNLOADED)
                }
                previousIds = download.keys.toSet()
                download.forEach {
                    val videoId = it.key
                    val audio = it.value.first?.state
                    val video = it.value.second?.state
                    val combineState =
                        if (audio == Download.STATE_REMOVING || video == Download.STATE_REMOVING) {
                            // A removal reports REMOVING before the files go. The pairs below
                            // would read it as downloading and write that over the
                            // NOT_DOWNLOADED the caller has just set.
                            DownloadState.STATE_NOT_DOWNLOADED
                        } else if (audio == Download.STATE_COMPLETED) {
                            // The audio alone makes the track playable, video or not: the player
                            // falls back to it while the video half is queued, downloading or has
                            // failed. Waiting for the video would hide the "downloaded" badge
                            // (and the track from the offline list) for the length of a video
                            // download fetched after the fact, and for good if that one fails.
                            DownloadState.STATE_DOWNLOADED
                        } else {
                            when (audio to video) {
                                Download.STATE_FAILED to Download.STATE_FAILED -> DownloadState.STATE_NOT_DOWNLOADED
                                Download.STATE_QUEUED to Download.STATE_QUEUED -> DownloadState.STATE_PREPARING
                                Download.STATE_FAILED to null -> DownloadState.STATE_NOT_DOWNLOADED
                                Download.STATE_QUEUED to null -> DownloadState.STATE_PREPARING
                                null to Download.STATE_COMPLETED -> DownloadState.STATE_DOWNLOADING
                                null to Download.STATE_QUEUED -> DownloadState.STATE_PREPARING
                                null to Download.STATE_FAILED -> DownloadState.STATE_NOT_DOWNLOADED
                                else -> DownloadState.STATE_DOWNLOADING
                            }
                        }
                    _downloadTask.update {
                        it.toMutableMap().apply {
                            set(videoId, combineState)
                        }
                    }
                    when (combineState) {
                        DownloadState.STATE_DOWNLOADED -> {
                            downloadingVideoIds.update {
                                it.apply {
                                    remove(videoId)
                                }
                            }
                            songRepository.updateDownloadState(videoId, DownloadState.STATE_DOWNLOADED)
                        }
                        DownloadState.STATE_DOWNLOADING -> {
                            downloadingVideoIds.update {
                                it.apply {
                                    add(videoId)
                                }
                            }
                            songRepository.updateDownloadState(videoId, DownloadState.STATE_DOWNLOADING)
                        }
                        DownloadState.STATE_NOT_DOWNLOADED -> {
                            downloadingVideoIds.update {
                                it.apply {
                                    remove(videoId)
                                }
                            }
                            songRepository.updateDownloadState(videoId, DownloadState.STATE_NOT_DOWNLOADED)
                        }
                        DownloadState.STATE_PREPARING -> {
                            songRepository.updateDownloadState(videoId, DownloadState.STATE_PREPARING)
                        }
                    }
                }
            }
        }

        // Pair Audio and Video
        val result = mutableMapOf<String, Pair<DownloadHandler.Download?, DownloadHandler.Download?>>()
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            val id = cursor.download.request.id
            val isVideo = id.contains(MERGING_DATA_TYPE.VIDEO)
            val songId =
                if (id.contains(MERGING_DATA_TYPE.VIDEO)) {
                    id.removePrefix(MERGING_DATA_TYPE.VIDEO)
                } else {
                    id
                }
            result[songId] =
                if (isVideo) {
                    result[songId]?.copy(second = DownloadHandler.Download(cursor.download.state))
                        ?: Pair(null, DownloadHandler.Download(cursor.download.state))
                } else {
                    result[songId]?.copy(first = DownloadHandler.Download(cursor.download.state))
                        ?: Pair(DownloadHandler.Download(cursor.download.state), null)
                }
        }
        _downloads.value = result
        downloadManager.addListener(
            object : DownloadManager.Listener {
                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?,
                ) {
                    download.request.id.let { id ->
                        var isVideo = false
                        val songId =
                            if (id.contains(MERGING_DATA_TYPE.VIDEO)) {
                                isVideo = true
                                id.removePrefix(MERGING_DATA_TYPE.VIDEO)
                            } else {
                                id
                            }
                        _downloads.update { map ->
                            map.toMutableMap().apply {
                                val current = map.getOrDefault(songId, null)
                                if (isVideo) {
                                    set(
                                        songId,
                                        current?.copy(second = DownloadHandler.Download(download.state))
                                            ?: Pair(null, DownloadHandler.Download(download.state)),
                                    )
                                } else {
                                    set(
                                        songId,
                                        current?.copy(first = DownloadHandler.Download(download.state))
                                            ?: Pair(DownloadHandler.Download(download.state), null),
                                    )
                                }
                            }
                        }
                        // The song's stored state is written by the collector alone. A write of
                        // its own here, on a coroutine of its own, could land after a removal had
                        // already reset the song, and nothing would correct it once the song left
                        // the map.
                        when (download.state) {
                            Download.STATE_COMPLETED -> {
                                playerCache.removeResource(id)
                            }

                            else -> {
                            }
                        }
                    }
                }

                // Without this a removed track stayed in the map at REMOVING for good, and the
                // collector above rewrote its song's row on every later emission. Once both
                // tracks are gone the song leaves the map, which the collector reads as removed.
                override fun onDownloadRemoved(
                    downloadManager: DownloadManager,
                    download: Download,
                ) {
                    val id = download.request.id
                    val isVideo = id.contains(MERGING_DATA_TYPE.VIDEO)
                    val songId = if (isVideo) id.removePrefix(MERGING_DATA_TYPE.VIDEO) else id
                    _downloads.update { map ->
                        val current = map[songId] ?: return@update map
                        val remaining = if (isVideo) current.copy(second = null) else current.copy(first = null)
                        if (remaining.first == null && remaining.second == null) {
                            map - songId
                        } else {
                            map + (songId to remaining)
                        }
                    }
                }
            },
        )
    }
}