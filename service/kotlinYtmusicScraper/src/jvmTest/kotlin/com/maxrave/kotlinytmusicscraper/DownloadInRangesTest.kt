package com.maxrave.kotlinytmusicscraper

import com.maxrave.ktorext.getEngine
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DownloadInRangesTest {
    private val file = Random(1).nextBytes(1024 * 1024)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val client = HttpClient(getEngine()) { expectSuccess = true }
    private val path = Files.createTempDirectory("download").resolve("track.webm")
    private val requests = AtomicInteger()

    private val url get() = "http://127.0.0.1:${server.address.port}/track"

    @AfterTest
    fun stop() {
        server.stop(0)
        client.close()
    }

    private fun serve(handle: (HttpExchange, IntRange, Int) -> Unit) {
        server.createContext("/track") { exchange ->
            val (from, to) =
                exchange.requestHeaders
                    .getFirst("Range")
                    .removePrefix("bytes=")
                    .split('-')
                    .map(String::toInt)
            val range = from..minOf(to, file.lastIndex)
            handle(exchange, range, requests.incrementAndGet())
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    private fun HttpExchange.sendRange(
        range: IntRange,
        bytes: Int = range.count(),
    ) {
        responseHeaders.add("Content-Range", "bytes ${range.first}-${range.last}/${file.size}")
        sendResponseHeaders(206, range.count().toLong())
        responseBody.write(file, range.first, bytes)
        responseBody.flush()
        // A cut lands mid-stream, after the client has read what came before it.
        if (bytes < range.count()) Thread.sleep(50)
        close()
    }

    private fun download(
        target: String = url,
        rangeTimeoutMs: Long = 2_000,
    ) = runBlocking {
        client.downloadInRanges(
            url = target,
            path = path.toOkioPath(),
            userAgent = "test",
            maxRetries = 2,
            bufferSize = 8 * 1024,
            rangeSize = 256 * 1024L,
            rangeTimeoutMs = rangeTimeoutMs,
            onProgress = { _, _ -> },
        )
    }

    @Test
    fun `a stream cut every time still ends as the whole file`() {
        serve { exchange, range, _ -> exchange.sendRange(range, bytes = minOf(range.count(), 100_000)) }

        download()

        assertContentEquals(file, Files.readAllBytes(path))
    }

    @Test
    fun `a request that hangs is abandoned and the file finished`() {
        serve { exchange, range, request ->
            if (request == 2) Thread.sleep(3_000)
            exchange.sendRange(range)
        }

        download(rangeTimeoutMs = 300)

        assertContentEquals(file, Files.readAllBytes(path))
    }

    @Test
    fun `the size in the url saves asking for it`() {
        serve { exchange, range, _ -> exchange.sendRange(range) }

        download(target = "$url?clen=${file.size}")

        assertContentEquals(file, Files.readAllBytes(path))
        assertTrue(requests.get() == 4)
    }

    @Test
    fun `a server that refuses fails instead of waiting for ever`() {
        serve { exchange, _, _ ->
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }

        assertFailsWith<Exception> { download() }
        assertTrue(requests.get() == 3)
    }

    @Test
    fun `a server that ignores ranges gives the whole file at once`() {
        serve { exchange, _, _ ->
            exchange.sendResponseHeaders(200, file.size.toLong())
            exchange.responseBody.write(file)
            exchange.close()
        }

        download()

        assertContentEquals(file, Files.readAllBytes(path))
    }
}
