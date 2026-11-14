package com.maxrave.data.repository

import com.maxrave.data.io.fileDir
import com.maxrave.data.io.fileSystem
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import com.maxrave.logger.Logger
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * Last Home feed as YouTube sent it, so Home can paint before the network answers (and without it).
 *
 * A file, not DataStore: the settings file is read and rewritten whole on every setting change, and
 * this response is hundreds of kilobytes. The raw [BrowseResponse] is stored rather than the parsed
 * shelves because those are not serializable; the reader runs the same parser as the network path.
 *
 * Layout: first line is `v1|<key>`, the rest is the response JSON. The key is checked before the
 * JSON is touched, so another account's or another locale's feed is never even decoded.
 */
internal object HomeFeedCache {
    private const val FILE_NAME = "home_cache.json"
    private const val VERSION = "v1"

    // Same leniency as the client: a response cached by an older build must degrade to a miss,
    // never to a crash. Nulls and defaults are left out to keep the file small.
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private fun path() = "${fileDir()}/$FILE_NAME".toPath()

    /**
     * Language + region + which account and channel. The account part is a hash of the session's
     * SAPISID (stable across cookie refreshes, unlike the rotating `*PSIDTS` values) plus the
     * authuser index, so two Google accounts in one session differ; the cookie itself never lands
     * in the file.
     */
    fun key(
        language: String,
        location: String,
        cookie: String,
        pageId: String,
        authUser: Int,
    ): String {
        val session =
            cookie
                .split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("SAPISID=") }
                ?: cookie
        val account = if (session.isEmpty()) "guest" else session.encodeUtf8().sha256().hex().take(16)
        return "$language|$location|$account|$authUser|$pageId"
    }

    fun read(key: String): BrowseResponse? {
        val text =
            runCatching {
                val p = path()
                if (!fileSystem().exists(p)) null else fileSystem().source(p).buffer().use { it.readUtf8() }
            }.getOrNull()
        if (text == null) {
            Logger.i("Offline", "home cache miss: no file")
            return null
        }
        val newline = text.indexOf('\n')
        val header = if (newline < 0) "" else text.substring(0, newline)
        if (header != "$VERSION|$key") {
            Logger.i("Offline", "home cache stale key")
            return null
        }
        val response =
            runCatching { json.decodeFromString(BrowseResponse.serializer(), text.substring(newline + 1)) }
                .onFailure { Logger.w("Offline", "home cache unreadable: ${it::class.simpleName}") }
                .getOrNull()
        if (response != null) Logger.i("Offline", "home cache hit: ${text.length / 1024} KB")
        return response
    }

    fun write(
        key: String,
        response: BrowseResponse,
    ) {
        runCatching {
            val body = json.encodeToString(BrowseResponse.serializer(), response)
            val target = path()
            val tmp = "${fileDir()}/$FILE_NAME.tmp".toPath()
            target.parent?.let { fileSystem().createDirectories(it) }
            fileSystem().sink(tmp).buffer().use { sink ->
                sink.writeUtf8("$VERSION|$key\n")
                sink.writeUtf8(body)
            }
            // Never leave a half-written file where the next start would read it.
            fileSystem().atomicMove(tmp, target)
            Logger.i("Offline", "home cache written: ${body.length / 1024} KB")
        }.onFailure { Logger.w("Offline", "home cache write failed: ${it::class.simpleName}") }
    }

    fun delete() {
        runCatching {
            if (fileSystem().exists(path())) {
                fileSystem().delete(path())
                Logger.i("Offline", "home cache deleted")
            }
        }
    }
}
