package com.maxrave.domain.mediaservice.handler

import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val REMOVED_DOWNLOADS_KEY = "downloads_removed_by_user"

/**
 * What a downloaded playlist may queue for download by itself when it is opened.
 *
 * Opening a playlist marked "downloaded" queues the tracks it does not have yet, which is how a track
 * added to it later arrives. Two things keep that from being a nuisance:
 *
 * - A track whose download the user removed by hand is not queued again ([markRemoved]). It cannot be
 *   told from a new track by its state alone (both read "not downloaded"), so the removals are kept in
 *   the preferences, across launches. An explicit download of the track or of the whole playlist
 *   lifts the mark ([clearRemoved]).
 * - A track is queued by this path at most once per launch. One that cannot be downloaded (gone, closed
 *   in the region, no network) is not tried at every opening, and one still in the queue is not queued
 *   twice. That part lives in memory only, so the next launch tries again.
 */
object AutoDownloadGuard {
    private val lock = Mutex()
    private val queuedThisLaunch = HashSet<String>()

    /** The user removed (or cancelled) the download of these tracks. */
    suspend fun markRemoved(
        dataStore: DataStoreManager,
        videoIds: Collection<String>,
    ) {
        if (videoIds.isEmpty()) return
        lock.withLock {
            val removed = read(dataStore)
            if (removed.addAll(videoIds)) write(dataStore, removed)
        }
    }

    /** The user asked for these tracks to be downloaded: they are not "removed by hand" any more. */
    suspend fun clearRemoved(
        dataStore: DataStoreManager,
        videoIds: Collection<String>,
    ) {
        if (videoIds.isEmpty()) return
        lock.withLock {
            val removed = read(dataStore)
            if (removed.removeAll(videoIds.toSet())) write(dataStore, removed)
        }
    }

    /**
     * Which of [notDownloaded] a playlist may queue now, remembering them as queued for this launch.
     * [downloaded] are the playlist's tracks that have their file: a mark left on one is dropped.
     */
    suspend fun pick(
        dataStore: DataStoreManager,
        notDownloaded: List<String>,
        downloaded: List<String>,
    ): List<String> =
        lock.withLock {
            val removed = read(dataStore)
            if (removed.removeAll(downloaded.toSet())) write(dataStore, removed)
            val picked = selectAutoDownloads(notDownloaded, removed, queuedThisLaunch)
            queuedThisLaunch.addAll(picked)
            picked
        }

    private suspend fun read(dataStore: DataStoreManager): MutableSet<String> =
        dataStore
            .getString(REMOVED_DOWNLOADS_KEY)
            .first()
            .orEmpty()
            .split('\n')
            .filterTo(LinkedHashSet()) { it.isNotBlank() }

    private suspend fun write(
        dataStore: DataStoreManager,
        removed: Set<String>,
    ) {
        dataStore.putString(REMOVED_DOWNLOADS_KEY, removed.joinToString("\n"))
    }
}

/** The tracks of [notDownloaded] that are neither [removed] by hand nor already [queued] this launch. */
internal fun selectAutoDownloads(
    notDownloaded: List<String>,
    removed: Set<String>,
    queued: Set<String>,
): List<String> = notDownloaded.distinct().filter { it !in removed && it !in queued }
