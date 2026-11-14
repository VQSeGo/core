package com.simpmusic.media_jvm.download

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.handler.downloadNotificationsEnabled
import com.maxrave.domain.notification.DesktopNotificationManager
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.isLive
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import com.simpmusic.media_jvm.cache.ListenCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "DownloadUtils"

// Not localised: this module sits below the UI layer and has no access to compose resources. If the
// notification text needs translating, the label has to be passed down from the caller instead.
private const val DOWNLOAD_STARTED_TITLE = "Downloading"
private const val DOWNLOAD_FINISHED_TITLE = "Download complete"
private const val DOWNLOAD_FAILED_TITLE = "Download failed"
private const val ACTIVE_DOWNLOAD_NOTIFICATION_ID = "downloads.active"
private const val DOWNLOAD_RESULT_NOTIFICATION_ID = "downloads.result"

// Enough that one slow track does not hold up the rest, few enough not to compete with the player.
private const val PARALLEL_DOWNLOADS = 3

// YouTube answers about one stream request in five with no URL and gives one seconds later, so a
// track gets several tries. Playback asks again the same way.
private const val DOWNLOAD_ATTEMPTS = 4
private const val RETRY_DELAY_MS = 2_000L

// A transfer writes here and nowhere else. The player takes any `<videoId>.webm` in the downloads
// folder for a finished track, so a file may only appear there whole, by a rename out of this folder.
private const val PARTIAL_DIR = PartialFileStore.PARTIAL_DIR

internal class DownloadUtils(
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
    private val songRepository: SongRepository,
    private val desktopNotificationManager: DesktopNotificationManager,
    private val listenCache: ListenCache,
) : DownloadHandler {
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var _downloads = MutableStateFlow<Map<String, Pair<DownloadHandler.Download?, DownloadHandler.Download?>>>(emptyMap())

    // Audio / Video
    override val downloads: StateFlow<Map<String, Pair<DownloadHandler.Download?, DownloadHandler.Download?>>>
        get() = _downloads
    /**
     * Per-track download state, keyed by videoId.
     *
     * This is a SECOND state, separate from `SongEntity.downloadState`, and the only one the
     * playlist-level UI reads: `LocalPlaylistViewModel.downloadFullPlaylistState` derives a
     * playlist's state purely from this map. It used to be initialised here and never written
     * again on Desktop, and because an empty map makes both `all { == DOWNLOADED }` and
     * `any { == DOWNLOADING }` false, every playlist resolved to STATE_NOT_DOWNLOADED — the
     * download button stayed on its idle icon for the whole transfer, with no error anywhere,
     * while the songs really did download through the per-song state.
     */
    private val _downloadTask = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val downloadTask: StateFlow<Map<String, Int>> get() = _downloadTask

    /** Publishes [state] for [videoId], or drops the entry when [state] is null. */
    private fun publishDownloadTask(
        videoId: String,
        state: Int?,
    ) {
        _downloadTask.update { current ->
            if (state == null) current - videoId else current + (videoId to state)
        }
    }

    val downloadingVideoIds = MutableStateFlow<MutableSet<String>>(mutableSetOf())

    private val notificationsEnabled =
        dataStoreManager.downloadNotificationsEnabled.stateIn(coroutineScope, SharingStarted.Eagerly, true)

    // The queue belongs to this class, not to whoever asked. The screens ask from their own scope, and
    // a transfer running there died with the screen: the row stayed on "downloading" for good, and
    // every track behind it in the caller's loop never started.
    private val queue = Channel<SongEntity>(Channel.UNLIMITED)

    /** Everything queued or running. An id leaves it once, and whoever takes it out finishes it up. */
    private val pendingIds = ConcurrentHashMap.newKeySet<String>()
    private val transfers = ConcurrentHashMap<String, Job>()

    /**
     * A row still marked as downloading has no transfer behind it at launch: the last one died with the app.
     * Whatever it left is thrown away, because the transfer starts from byte zero anyway: the unfinished
     * file in [PARTIAL_DIR], and the same track's file in the downloads folder itself, where an earlier
     * version of the app wrote straight to the final name and the player took the half for a whole.
     */
    private suspend fun resumeInterruptedDownloads() {
        val interrupted =
            songRepository.getDownloadingSongs().lastOrNull().orEmpty() +
                songRepository.getPreparingSongs().lastOrNull().orEmpty()
        // Skipping what a caller has already queued: its row says "downloading" too, but its transfer is live.
        interrupted.filter { it.videoId !in pendingIds }.forEach { song ->
            val removed =
                listOf(audioFile(song.videoId), File(getDownloadPath(), "${song.videoId}.mp4"), File(getDownloadPath(), "temp_chunks_${song.videoId}.webm"))
                    .filter { it.exists() }
            removed.forEach { it.deleteRecursively() }
            if (removed.isNotEmpty()) {
                Logger.i(TAG, "Dropped the unfinished file of ${song.videoId}: ${removed.joinToString { it.name }}")
            }
        }
        Logger.i(TAG, "Resuming ${interrupted.size} interrupted downloads")
        interrupted.forEach { enqueue(it.videoId, it.title) }
    }

    private val store = PartialFileStore(File(getDownloadPath()))

    private fun partialDir() = store.partialDir

    private fun deletePartial(videoId: String) = store.deletePartial(videoId)

    private fun publish(videoId: String): Boolean = store.publish(videoId)

    override suspend fun downloadTrack(
        videoId: String,
        title: String,
        thumbnail: String,
    ) {
        // await() and not a plain call: leaving the screen may stop the asking, not the queueing.
        coroutineScope.async { enqueue(videoId, title) }.await()
    }

    private suspend fun enqueue(
        videoId: String,
        title: String,
    ) {
        val song = songRepository.getSongById(videoId).lastOrNull() ?: return
        // Every download path ends here, so this is where a broadcast already known to be live is
        // turned away. One not known yet fails inside YouTube.download, which checks the stream.
        if (song.isLive) {
            Logger.i(TAG, "$videoId is a live stream, not downloading")
            songRepository.updateDownloadState(videoId, DownloadState.STATE_NOT_DOWNLOADED)
            publishDownloadTask(videoId, null)
            return
        }
        if (!pendingIds.add(videoId)) return
        songRepository.updateDownloadState(videoId, DownloadState.STATE_DOWNLOADING)
        publishDownloadTask(videoId, DownloadState.STATE_DOWNLOADING)
        onDownloadStarted(videoId, title)
        queue.trySend(song)
    }

    private suspend fun runDownload(song: SongEntity) {
        val videoId = song.videoId
        // Taken off the queue by removeDownload while it waited.
        if (videoId !in pendingIds) return
        var succeeded = false
        val transfer = coroutineScope.launch(start = CoroutineStart.LAZY) { succeeded = transfer(song) }
        transfers[videoId] = transfer
        transfer.join()
        transfers.remove(videoId)
        if (!pendingIds.remove(videoId)) {
            // Removed while it ran.
            deletePartial(videoId)
            audioFile(videoId).delete()
            return
        }
        val state = if (succeeded) DownloadState.STATE_DOWNLOADED else DownloadState.STATE_NOT_DOWNLOADED
        if (!succeeded) deletePartial(videoId)
        songRepository.updateDownloadState(videoId, state)
        publishDownloadTask(videoId, state)
        onDownloadFinished(videoId, song.title, succeeded)
    }

    /** Each try asks YouTube for the stream anew, which is also what a URL refused outright needs. */
    private suspend fun transfer(song: SongEntity): Boolean {
        partialDir().mkdirs()
        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RETRY_DELAY_MS shl (attempt - 1))
            deletePartial(song.videoId)
            var done = false
            var error: String? = null
            try {
                songRepository
                    .downloadToFile(
                        song.toTrack(),
                        path = store.partialBase(song.videoId),
                        videoId = song.videoId,
                        isVideo = false,
                    ).collect { state ->
                        if (state.isError) error = state.errorMessage ?: "unknown" else if (state.isDone) done = true
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            if (done && error == null) {
                if (publish(song.videoId)) {
                    listenCache.dropDownloaded(song.videoId)
                    return true
                }
                error = "the finished file is missing or empty"
            }
            Logger.e(TAG, "Download of ${song.videoId} failed, attempt ${attempt + 1}: $error")
        }
        return false
    }

    private fun audioFile(videoId: String) = store.file(videoId)

    // ===== Desktop download notifications =====
    //
    // Grouped per batch, the same way Android collapses every queued download into the single
    // foreground-service notification rather than one per track. One notification goes out when the
    // queue goes from idle to busy, and one summary replaces it when the queue drains. A track counts
    // from the moment it is queued: counted from its start, every track was a batch of its own.
    //
    // Progress cannot be tracked the way Android does: NotificationHandle only exposes dismiss(),
    // there is no update, and desktop notifications carry no progress bar. So the opening
    // notification says work started, and the closing one says how it went.

    private val batchLock = Any()
    private val activeDownloads = mutableSetOf<String>()
    private var batchCompleted = 0
    private var batchFailed = 0
    private var batchTotal = 0
    private var lastSongTitle = ""

    // Last of the properties: these coroutines start at once and touch the ones above.
    init {
        // Nothing is running yet, so whatever lies in here was left by a transfer the last run died in.
        store.dropAllPartials()
        File(getDownloadPath()).listFiles { file -> file.isDirectory && file.name.startsWith("temp_chunks_") }?.forEach { it.deleteRecursively() }
        repeat(PARALLEL_DOWNLOADS) {
            coroutineScope.launch {
                for (song in queue) runDownload(song)
            }
        }
        coroutineScope.launch { resumeInterruptedDownloads() }
    }

    private fun onDownloadStarted(
        videoId: String,
        songTitle: String,
    ) = synchronized(batchLock) {
        val wasIdle = activeDownloads.isEmpty()
        activeDownloads += videoId
        Logger.d(TAG, "Download started: $videoId (active=${activeDownloads.size}, batchOpening=$wasIdle)")
        if (wasIdle) {
            batchCompleted = 0
            batchFailed = 0
            batchTotal = 0
            lastSongTitle = songTitle
            // Only one track is known at this point, so name it. If more join the batch, the
            // closing notification switches to a count.
            if (notificationsEnabled.value) {
                desktopNotificationManager.post(
                    id = ACTIVE_DOWNLOAD_NOTIFICATION_ID,
                    title = DOWNLOAD_STARTED_TITLE,
                    message = songTitle,
                )
            }
        }
        batchTotal++
    }

    private fun onDownloadFinished(
        videoId: String,
        songTitle: String,
        succeeded: Boolean,
    ) = synchronized(batchLock) {
        activeDownloads -= videoId
        lastSongTitle = songTitle
        if (succeeded) batchCompleted++ else batchFailed++
        closeBatchIfDone()
    }

    /** A removed track was neither a success nor a failure, so it leaves the batch without a trace. */
    private fun onDownloadRemoved(videoId: String) =
        synchronized(batchLock) {
            if (activeDownloads.remove(videoId)) {
                batchTotal--
                closeBatchIfDone()
            }
        }

    private fun closeBatchIfDone() {
        if (activeDownloads.isEmpty()) {
            // Clear the "started" notification so the tray shows one line about this batch, not two.
            desktopNotificationManager.dismiss(ACTIVE_DOWNLOAD_NOTIFICATION_ID)
            if (batchTotal == 0 || !notificationsEnabled.value) return
            // A single-track batch says which track — that is what the user was waiting on. Several
            // tracks can only be summarised, since one notification cannot list them all.
            val summary =
                when {
                    batchTotal == 1 -> lastSongTitle
                    batchCompleted == 0 -> "$batchFailed failed"
                    batchFailed == 0 -> "$batchCompleted songs downloaded"
                    else -> "$batchCompleted downloaded, $batchFailed failed"
                }
            desktopNotificationManager.post(
                id = DOWNLOAD_RESULT_NOTIFICATION_ID,
                title = if (batchCompleted == 0) DOWNLOAD_FAILED_TITLE else DOWNLOAD_FINISHED_TITLE,
                message = summary,
            )
        }
    }

    /** Takes [videoId] off the queue, or stops its transfer. False if it was neither queued nor running. */
    private fun cancelDownload(videoId: String): Boolean {
        if (!pendingIds.remove(videoId)) return false
        transfers[videoId]?.cancel()
        onDownloadRemoved(videoId)
        return true
    }

    /** The file mpv could not play: dropped at once, so the next read streams, and downloaded again. */
    override fun redownloadCorrupt(key: String): Boolean {
        val file = audioFile(key)
        if (!file.isFile) return false
        file.delete()
        Logger.i("Offline", "redownload $key queued")
        coroutineScope.launch {
            val song = songRepository.getSongById(key).lastOrNull() ?: return@launch
            enqueue(key, song.title)
        }
        return true
    }

    // Downloads here are audio only and play without video.
    override fun downloadVideoPart(
        videoId: String,
        title: String,
    ) = Unit

    override fun removeDownload(videoId: String) {
        val wasPending = cancelDownload(videoId)
        val files = File(getDownloadPath()).listFiles().orEmpty().filter { it.name.contains(videoId) }
        files.forEach { it.delete() }
        if (wasPending || files.isNotEmpty()) {
            publishDownloadTask(videoId, null)
            coroutineScope.launch {
                songRepository.updateDownloadState(
                    videoId,
                    DownloadState.STATE_NOT_DOWNLOADED,
                )
            }
        }
    }

    override fun removeAllDownloads() {
        val pending = pendingIds.filter(::cancelDownload)
        _downloadTask.value = emptyMap()
        val files = File(getDownloadPath()).listFiles().orEmpty().filter { it.name != PARTIAL_DIR }
        files.forEach { it.delete() }
        val videoIds = pending + files.map { it.name.split(".").first().removePrefix(MERGING_DATA_TYPE.VIDEO) }
        coroutineScope.launch {
            videoIds.toSet().forEach { songRepository.updateDownloadState(it, DownloadState.STATE_NOT_DOWNLOADED) }
        }
    }
}

fun getDownloadPath(): String = System.getProperty("user.home") + File.separator + ".simpmusic" + File.separator + "downloads"
