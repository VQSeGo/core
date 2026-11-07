package com.maxrave.kotlinytmusicscraper

import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import kotlin.math.abs
import kotlin.time.TimeSource

private const val TAG = "YouTubeScraperClient"
private const val RANGE_SIZE = 4L * 1024L * 1024L
private const val RANGE_TIMEOUT_MS = 60_000L
private const val MAX_RETRY_DELAY_MS = 10_000L

/**
 * Downloads [url] to [path] one byte range at a time, each one continuing where the file stopped.
 *
 * googlevideo cuts long transfers ("unexpected end of stream") and sometimes stops answering without
 * closing. Asked for in one piece, either meant starting over or hanging for good. Here a cut or a
 * range that outlives [rangeTimeoutMs] keeps what arrived, and only a request that brings nothing
 * counts against [maxRetries], so a slow connection is not mistaken for a broken one.
 *
 * The size comes from the URL's `clen`, then from the responses: a HEAD on these URLs often has none.
 *
 * @throws Exception the last failure, once [maxRetries] requests in a row have brought nothing
 */
internal suspend fun HttpClient.downloadInRanges(
    url: String,
    path: Path,
    userAgent: String,
    maxRetries: Int,
    bufferSize: Int,
    rangeSize: Long = RANGE_SIZE,
    rangeTimeoutMs: Long = RANGE_TIMEOUT_MS,
    onProgress: suspend (Float, Int) -> Unit,
) {
    val fileSystem = FileSystem.SYSTEM
    fileSystem.delete(path, mustExist = false)
    var total = Url(url).parameters["clen"]?.toLongOrNull() ?: 0L
    var downloaded = 0L
    var failedInARow = 0
    var reportedProgress = 0f
    val started = TimeSource.Monotonic.markNow()
    while (total == 0L || downloaded < total) {
        val requestedFrom = downloaded
        var failure: Exception? = null
        var wholeFile = false
        try {
            val answered =
                withTimeoutOrNull(rangeTimeoutMs) {
                    val rangeEnd = requestedFrom + rangeSize - 1
                    val lastByte = if (total > 0L) minOf(rangeEnd, total - 1) else rangeEnd
                    prepareRequest {
                        url(url)
                        header("User-Agent", userAgent)
                        header("Accept", "*/*")
                        header(HttpHeaders.Range, "bytes=$requestedFrom-$lastByte")
                    }.execute { response ->
                        if (response.status == HttpStatusCode.PartialContent) {
                            response.headers[HttpHeaders.ContentRange]
                                ?.substringAfterLast('/')
                                ?.toLongOrNull()
                                ?.let { total = it }
                        } else {
                            // The range was ignored, so this is the file from its first byte.
                            wholeFile = true
                            fileSystem.delete(path, mustExist = false)
                            downloaded = 0L
                        }
                        val channel = response.bodyAsChannel()
                        fileSystem.appendingSink(path).buffer().use { sink ->
                            while (!channel.isClosedForRead) {
                                val packet = channel.readRemaining(bufferSize.toLong())
                                while (!packet.exhausted()) {
                                    val bytes = packet.readByteArray()
                                    sink.write(bytes)
                                    downloaded += bytes.size
                                }
                                if (total > 0L) {
                                    val progress = downloaded.toFloat() / total
                                    if (abs(progress - reportedProgress) >= 0.01f) {
                                        reportedProgress = progress
                                        val seconds = started.elapsedNow().inWholeMilliseconds / 1000.0
                                        onProgress(progress, if (seconds > 0) (downloaded / 1024 / seconds).toInt() else 0)
                                    }
                                }
                            }
                        }
                    }
                    true
                }
            if (answered == null) failure = IOException("No answer in $rangeTimeoutMs ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
        }
        // What is on disk is the truth: a cut can land between a read and the count.
        downloaded = fileSystem.metadataOrNull(path)?.size ?: 0L
        if (failure == null && downloaded > 0L && (wholeFile || (total == 0L && downloaded - requestedFrom < rangeSize))) break
        if (downloaded > requestedFrom) {
            failedInARow = 0
            continue
        }
        failedInARow++
        if (failedInARow > maxRetries) throw failure ?: IOException("Nothing received at byte $requestedFrom")
        Logger.w(TAG, "Download stopped at byte $requestedFrom: ${failure?.message}")
        delay((500L shl (failedInARow - 1)).coerceAtMost(MAX_RETRY_DELAY_MS))
    }
}
