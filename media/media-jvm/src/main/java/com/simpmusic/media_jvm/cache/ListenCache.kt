package com.simpmusic.media_jvm.cache

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.SongRepository
import com.maxrave.logger.Logger
import com.simpmusic.media_jvm.download.PartialFileStore
import com.simpmusic.media_jvm.download.getDownloadPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ListenCache"

/** When the shared "limit player cache" setting says no limit: the desktop cache still gets one. */
private const val UNLIMITED_FALLBACK_BYTES = 2_000L * 1024 * 1024

/**
 * Desktop counterpart of the Android player cache: tracks heard from the network, kept on disk so they
 * play again with no network and with no wait for a stream URL.
 *
 * mpv has no cache layer of its own that outlives a track, so a track that has played long enough is
 * saved a second time in the background, from the URL it is playing from (no new player request).
 * One at a time, in `~/.simpmusic/cache`, through the same `.partial` + rename scheme as the downloads.
 * Nothing goes into the database and no track is marked as downloaded. The oldest-played files go
 * when the folder grows past the player cache limit; a file's modification time is its last play.
 */
class ListenCache(
    private val songRepository: SongRepository,
    private val dataStoreManager: DataStoreManager,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store = PartialFileStore(File(cachePath()))

    private class Request(
        val videoId: String,
        val url: String,
    )

    private val queue = Channel<Request>(Channel.UNLIMITED)

    /** Queued or running. Cleared by [clear], which is how a waiting request learns it was dropped. */
    private val pendingIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var currentTransfer: Job? = null

    init {
        // Nothing runs yet: an unfinished file here was left by a transfer the last run died in.
        store.dropAllPartials()
        scope.launch {
            for (request in queue) {
                if (request.videoId !in pendingIds) continue
                val job = scope.launch { transfer(request) }
                currentTransfer = job
                job.join()
                currentTransfer = null
                pendingIds.remove(request.videoId)
            }
        }
    }

    /** The cached file of [videoId], or null. */
    fun cachedFile(videoId: String): File? = store.existing(videoId)

    /** Marks [file] as just played, so the size limit takes it last. */
    fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    /**
     * Asks for [videoId] to be saved from [url], the plain audio stream it is playing from. Skipped when
     * the track is already cached, downloaded or on its way.
     */
    fun offer(
        videoId: String,
        url: String,
    ) {
        if (store.existing(videoId) != null || File(getDownloadPath(), "$videoId.webm").isFile) return
        if (!pendingIds.add(videoId)) return
        Logger.i("Offline", "cache $videoId queued")
        queue.trySend(Request(videoId, url))
    }

    private suspend fun transfer(request: Request) {
        val videoId = request.videoId
        val started = System.nanoTime()
        store.partialDir.mkdirs()
        store.deletePartial(videoId)
        var done = false
        var error: String? = null
        try {
            songRepository
                .downloadStreamToFile(request.url, store.partialBase(videoId) + ".webm")
                .collect { state ->
                    if (state.isError) error = state.errorMessage ?: "unknown" else if (state.isDone) done = true
                }
        } catch (e: CancellationException) {
            store.deletePartial(videoId)
            Logger.i("Offline", "cache $videoId cancelled")
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        if (done && error == null && store.publish(videoId)) {
            Logger.i("Offline", "cache $videoId saved ${store.file(videoId).length()} bytes $ms ms")
            // A download of the same track may have finished while this one ran.
            if (File(getDownloadPath(), "$videoId.webm").isFile) dropDownloaded(videoId) else evict(keep = videoId)
        } else {
            store.deletePartial(videoId)
            Logger.i("Offline", "cache $videoId failed $ms ms: ${error ?: "the finished file is missing or empty"}")
        }
    }

    private fun cachedFiles(): List<File> =
        store.dir
            .listFiles { file -> file.isFile && file.name.endsWith(".webm") }
            .orEmpty()
            .toList()

    /** Drops the least recently played files until the folder fits the limit; [keep] is never dropped. */
    private suspend fun evict(keep: String) {
        val limitMb = dataStoreManager.maxSongCacheSize.first()
        val limit = if (limitMb <= 0) UNLIMITED_FALLBACK_BYTES else limitMb * 1024L * 1024L
        val files = cachedFiles().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= limit) break
            if (file.name == "$keep.webm") continue
            val size = file.length()
            if (file.delete()) {
                total -= size
                Logger.i("Offline", "cache ${file.nameWithoutExtension} evicted, $total of $limit bytes left")
            }
        }
    }

    /** Removes the cached file of [videoId]; true if there was one. */
    fun drop(videoId: String): Boolean = store.file(videoId).let { it.isFile && it.delete() }

    /**
     * Called once [videoId] is in the downloads folder: the player reads downloads first, so the cached
     * copy would only take space until the size limit pushed it out.
     */
    fun dropDownloaded(videoId: String) {
        if (drop(videoId)) Logger.i("Offline", "cache $videoId dropped (downloaded)")
    }

    /** Bytes the finished files take. */
    fun size(): Long = cachedFiles().sumOf { it.length() }

    /** Cached tracks, last played first. */
    fun videoIds(): List<String> = cachedFiles().sortedByDescending { it.lastModified() }.map { it.nameWithoutExtension }

    /** Drops every cached file and stops the transfer that is running. */
    fun clear() {
        pendingIds.clear()
        currentTransfer?.cancel()
        cachedFiles().forEach { it.delete() }
        Logger.i("Offline", "cache cleared")
    }
}

fun cachePath(): String = System.getProperty("user.home") + File.separator + ".simpmusic" + File.separator + "cache"
