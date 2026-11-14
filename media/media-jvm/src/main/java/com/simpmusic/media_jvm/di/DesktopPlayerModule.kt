package com.simpmusic.media_jvm.di

import com.maxrave.common.Config.SERVICE_SCOPE
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.notification.DesktopNotificationManager
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.common.Config
import com.simpmusic.media_jvm.cache.ListenCache
import com.simpmusic.media_jvm.download.DownloadUtils
import com.simpmusic.media_jvm.mpv.MpvPlayerAdapter
import com.simpmusic.media_jvm.notification.NucleusDesktopNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module

private val desktopPlayerModule =
    module {
        single<CoroutineScope>(qualifier = named(SERVICE_SCOPE)) {
            // Single-thread dispatcher: serializes all player operations onto one thread so the
            // adapter's state machine never races with itself. libmpv is thread-safe on its own,
            // but the adapter's playlist/crossfade state still assumes a single writer.
            // UI listener notifications are dispatched to Dispatchers.Main separately.
            val playerDispatcher = Executors.newSingleThreadExecutor { r ->
                Thread(r, "Desktop-Player-Thread").apply { isDaemon = true }
            }.asCoroutineDispatcher()
            CoroutineScope(playerDispatcher + SupervisorJob())
        }

        single<MpvPlayerAdapter> {
            MpvPlayerAdapter(
                coroutineScope = get(named(SERVICE_SCOPE)),
                dataStoreManager = get(),
                streamRepository = get(),
                downloadHandler = get(),
                listenCache = get(),
            )
        }

        single<ListenCache> {
            ListenCache(
                songRepository = get(),
                dataStoreManager = get(),
            )
        }

        // ---- Active playback backend ----
        single<MediaPlayerInterface> {
            get<MpvPlayerAdapter>()
        }

        // The player cache is the listening cache in ~/.simpmusic/cache; downloads are not a cache
        // here (they are files the download screens manage), so DOWNLOAD_CACHE stays empty.
        single<CacheRepository> {
            val listenCache = get<ListenCache>()
            object : CacheRepository {
                override suspend fun getCacheSize(cacheName: String): Long =
                    if (cacheName == Config.PLAYER_CACHE) withContext(Dispatchers.IO) { listenCache.size() } else 0L

                override fun clearCache(cacheName: String) {
                    if (cacheName == Config.PLAYER_CACHE) listenCache.clear()
                }

                override suspend fun getAllCacheKeys(cacheName: String): List<String> =
                    if (cacheName == Config.PLAYER_CACHE) withContext(Dispatchers.IO) { listenCache.videoIds() } else emptyList()

                override suspend fun getFullyCachedSongIds(): List<String> = withContext(Dispatchers.IO) { listenCache.videoIds() }

                override suspend fun removeCachedSong(videoId: String) {
                    withContext(Dispatchers.IO) { listenCache.drop(videoId) }
                }
            }
        }
        single<DesktopNotificationManager> {
            NucleusDesktopNotificationManager(
                dataStoreManager = get(),
            )
        }
        single<DownloadHandler> {
            DownloadUtils(
                dataStoreManager = get(),
                streamRepository = get(),
                songRepository = get(),
                desktopNotificationManager = get(),
                listenCache = get(),
            )
        }
    }

fun loadDesktopPlayerModule() = loadKoinModules(desktopPlayerModule)
