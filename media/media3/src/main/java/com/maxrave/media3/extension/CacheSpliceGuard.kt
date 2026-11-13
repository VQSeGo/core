package com.maxrave.media3.extension

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.exoplayer.upstream.Loader
import com.maxrave.domain.mediaservice.StreamSpliceGuard
import com.maxrave.logger.Logger

/**
 * The `clen` of the stream variant whose bytes a cache key holds, written next to them by the
 * resolver that handed out the URL. Media3's own length ([ContentMetadata.KEY_CONTENT_LENGTH]) is
 * a fallback only: the player's 5 MiB chunks are bounded requests, so it is noted at the end of a
 * track at best.
 */
private const val KEY_STREAM_CLEN = "simp_clen"

@UnstableApi
internal fun Cache.recordedStreamLength(key: String): Long? {
    val metadata = getContentMetadata(key)
    val own = metadata.get(KEY_STREAM_CLEN, C.LENGTH_UNSET.toLong())
    if (own > 0L) return own
    return ContentMetadata.getContentLength(metadata).takeIf { it > 0L }
}

@UnstableApi
internal fun Cache.hasCachedBytes(key: String): Boolean = getCachedSpans(key).isNotEmpty()

/** True when the bytes [key] holds come from a stream other than one of length [fresh]; see [StreamSpliceGuard]. */
@UnstableApi
internal fun Cache.isStaleFor(
    key: String,
    fresh: Long?,
): Boolean = StreamSpliceGuard.isStale(recordedStreamLength(key), hasCachedBytes(key), fresh)

/** Notes that the bytes about to be written under [key] come from a stream of length [clen]. */
@UnstableApi
internal fun Cache.recordStreamLength(
    key: String,
    clen: Long,
) {
    if (getContentMetadata(key).get(KEY_STREAM_CLEN, C.LENGTH_UNSET.toLong()) == clen) return
    applyContentMetadataMutations(key, ContentMetadataMutations().set(KEY_STREAM_CLEN, clen))
}

/**
 * Drops everything [key] holds: its spans and the lengths noted for them. The lengths go first:
 * removing the last span also removes the key's entry, unless a writer holds a lock on it, and
 * then the entry must not keep a length that belongs to bytes no longer there (a CacheWriter
 * would stop at it and save a truncated file).
 */
@UnstableApi
internal fun Cache.dropStale(key: String) {
    applyContentMetadataMutations(
        key,
        ContentMetadataMutations()
            .remove(KEY_STREAM_CLEN)
            .remove(ContentMetadata.KEY_CONTENT_LENGTH),
    )
    removeResource(key)
}

/** Logs a splice the guard prevented, in one fixed form the offline run logs grep for. */
@UnstableApi
internal fun Cache.logSpliceGuard(
    key: String,
    fresh: Long,
    where: String,
) {
    Logger.i("Offline", "splice-guard $key cached=${recordedStreamLength(key) ?: "unknown"} fresh=$fresh $where")
}

/**
 * Set as [DataSpec.customData] by a resolver that wants this read to skip the cache layer below
 * it and go to [CacheBypassDataSourceFactory]'s `direct` source instead.
 */
internal object SkipCache

/**
 * Chooses per opened [DataSpec] between a cache-backed source and one without that cache, so a
 * resolver that finds a cache holding another variant's bytes can read around them without
 * deleting them (they may belong to a download still running).
 */
@UnstableApi
internal class CacheBypassDataSourceFactory(
    private val cached: DataSource.Factory,
    private val direct: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = Source(cached.createDataSource(), direct.createDataSource())

    private class Source(
        private val cached: DataSource,
        private val direct: DataSource,
    ) : DataSource {
        private var current: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            cached.addTransferListener(transferListener)
            direct.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val source = if (dataSpec.customData === SkipCache) direct else cached
            current = source
            return source.open(dataSpec)
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = checkNotNull(current) { "read before open" }.read(buffer, offset, length)

        override fun getUri() = current?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

        override fun close() {
            val source = current
            current = null
            source?.close()
        }
    }
}

/**
 * Whether this error says the bytes themselves are unreadable: a container the parser rejects
 * (ParserException, codes 3001 / 3003), a RuntimeException thrown from inside the extractor (the
 * Matroska "No valid varint length mask found" is an IllegalStateException, which the loader wraps
 * as UnexpectedLoaderException and the player reports as 2000), or a read past the end of a file
 * shorter than its noted length (2008). Only meaningful for a track read from disk; from the
 * network the same codes can mean an error page in place of the stream.
 */
@UnstableApi
internal fun PlaybackException.isCorruptMedia(): Boolean {
    when (errorCode) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        -> return true
    }
    var cause: Throwable? = cause
    while (cause != null) {
        if (cause is ParserException || cause is Loader.UnexpectedLoaderException) return true
        cause = cause.cause
    }
    return false
}
