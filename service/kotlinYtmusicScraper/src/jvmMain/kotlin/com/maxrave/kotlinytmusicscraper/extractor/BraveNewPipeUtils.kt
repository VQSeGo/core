package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.common.ITAG
import com.maxrave.kotlinytmusicscraper.models.YouTubeClient
import com.maxrave.kotlinytmusicscraper.utils.NoNetworkException
import com.maxrave.kotlinytmusicscraper.utils.isNoNetwork
import com.maxrave.logger.Logger
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.net.Proxy

internal fun List<Pair<Int, String>>.hasRequiredItags(): Boolean {
    val itags = this.mapTo(HashSet()) { it.first }
    val hasAudio = ITAG.AUDIO.any { it in itags }
    val hasVideo = ITAG.VIDEO.any { it in itags }
    return hasAudio && hasVideo
}

private val streamHealthCheckClient: OkHttpClient by lazy {
    OkHttpClient
        .Builder()
        .build()
}

/**
 * Pick one random URL whose itag belongs to [ITAG.AUDIO] or [ITAG.VIDEO] and HEAD it. Returns true
 * only when the response code is in the 200..299 range. We only health-check those URLs because
 * they are the ones the player will actually use; lower-quality extras can stay unverified.
 */
internal fun List<Pair<Int, String>>.headCheckRandomStream(): Boolean {
    val required = ITAG.AUDIO + ITAG.VIDEO
    val candidate = this.filter { it.first in required }.randomOrNull() ?: return false
    return runCatching {
        val request =
            okhttp3.Request
                .Builder()
                .head()
                .url(candidate.second)
                .build()
        streamHealthCheckClient.newCall(request).execute().use { response ->
            Logger.i("Offline", "head itag=${candidate.first} code=${response.code}")
            response.code in 200..299
        }
    }.onFailure {
        Logger.i("Offline", "head itag=${candidate.first} failed: ${it::class.simpleName} ${it.message}")
        // Not a rejected URL: the network went away between the extraction and this check. Saying so
        // keeps the caller from judging the decoder (and the next tiers) on a dead link.
        if (isNoNetwork(it)) throw NoNetworkException(it)
    }.getOrDefault(false)
}

private val refusalCheckClient: OkHttpClient by lazy {
    streamHealthCheckClient
        .newBuilder()
        .callTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

/**
 * HEAD one random audio or video URL of BravePipe's answer: only a 4xx counts as refused. A 5xx
 * passes, unlike [headCheckRandomStream] (seen 2026-11-13: googlevideo answered 503 to a HEAD during
 * a quick run of skips), and so does no answer within 3 s: the extraction itself has just gone
 * through, so a slow HEAD says the network is busy, not that the URL is bad. Counting it as refused
 * turned a good answer into `No URL found` (seen 2026-11-14 on the phone, five resolves at once after
 * a launch), and in the first tier it sent the track through the PipePipe tiers for 10-20 s.
 */
internal fun List<Pair<Int, String>>.refusedByHead(): Boolean {
    val required = ITAG.AUDIO + ITAG.VIDEO
    val candidate = (this.filter { it.first in required }.ifEmpty { this }).randomOrNull() ?: return true
    return runCatching {
        val request =
            okhttp3.Request
                .Builder()
                .head()
                .url(candidate.second)
                .build()
        refusalCheckClient.newCall(request).execute().use { response ->
            Logger.i("Offline", "head itag=${candidate.first} code=${response.code}")
            response.code in 400..499
        }
    }.onFailure {
        Logger.i("Offline", "head itag=${candidate.first} failed: ${it::class.simpleName} ${it.message}, not counted as refused")
    }.getOrDefault(false)
}

class BraveNewPipeDownloaderImpl(
    proxy: Proxy?,
) : Downloader() {
    private val client =
        OkHttpClient
            .Builder()
            .proxy(proxy)
            .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val response = client.newCall(buildOkHttpRequest(request)).execute()

        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        }

        val body = response.body?.string()
        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            body,
            response.request.url.toString(),
        )
    }

    private fun buildOkHttpRequest(request: Request): okhttp3.Request {
        val builder =
            okhttp3.Request
                .Builder()
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .url(request.url())
                .addHeader("User-Agent", YouTubeClient.USER_AGENT_WEB)

        request.headers().forEach { (headerName, headerValueList) ->
            if (headerValueList.size > 1) {
                builder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    builder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                builder.header(headerName, headerValueList[0])
            }
        }
        return builder.build()
    }
}
