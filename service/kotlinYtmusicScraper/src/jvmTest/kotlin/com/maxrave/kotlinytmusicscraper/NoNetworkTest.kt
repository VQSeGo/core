package com.maxrave.kotlinytmusicscraper

import com.maxrave.kotlinytmusicscraper.utils.NoNetworkException
import com.maxrave.kotlinytmusicscraper.utils.isNoAnswer
import com.maxrave.kotlinytmusicscraper.utils.isNoNetwork
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoNetworkTest {
    @Test
    fun connectionFailuresAreNoNetwork() {
        assertTrue(isNoNetwork(UnknownHostException("music.youtube.com")))
        assertTrue(isNoNetwork(ConnectException("Connection refused")))
        assertTrue(isNoNetwork(NoRouteToHostException("No route to host")))
        assertTrue(isNoNetwork(SocketException("Network is unreachable")))
        assertTrue(isNoNetwork(SocketTimeoutException("Connect timed out")))
        assertTrue(isNoNetwork(SocketTimeoutException("failed to connect to music.youtube.com/1.2.3.4 (port 443) after 10000ms")))
        assertTrue(isNoNetwork(ConnectTimeoutException("Connect timeout has expired")))
    }

    @Test
    fun theCauseChainIsWalked() {
        assertTrue(isNoNetwork(RuntimeException("extractor failed", IOException("wrapped", UnknownHostException("www.youtube.com")))))
        assertTrue(isNoNetwork(NoNetworkException()))
    }

    @Test
    fun badAnswersAreNotNoNetwork() {
        assertFalse(isNoNetwork(null))
        assertFalse(isNoNetwork(RuntimeException("No URL found")))
        assertFalse(isNoNetwork(IOException("unexpected end of stream")))
        // A slow answer is a link that works.
        assertFalse(isNoNetwork(SocketTimeoutException("Read timed out")))
        assertFalse(isNoNetwork(SocketTimeoutException("timeout")))
        assertFalse(isNoNetwork(SocketException("Connection reset")))
        assertFalse(isNoNetwork(HttpRequestTimeoutException("https://music.youtube.com/youtubei/v1/player", 20_000L)))
    }

    @Test
    fun theExtractionServiceBeingDownDoesNotCountAsNoNetwork() {
        // api.pipepipe.dev unreachable while YouTube works: the next tier must still run.
        assertFalse(isNoNetwork(UnknownHostException("api.pipepipe.dev")))
        assertFalse(isNoNetwork(RuntimeException("decode failed", ConnectException("Failed to connect to api.pipepipe.dev/1.2.3.4:443"))))
        assertEquals(true, isNoNetwork(UnknownHostException("rr3---sn-x.googlevideo.com")))
    }

    @Test
    fun silenceCountsAsNoAnswerButNotAsNoNetwork() {
        val readTimeout = SocketTimeoutException("Read timed out")
        assertTrue(isNoAnswer(readTimeout))
        assertTrue(isNoAnswer(HttpRequestTimeoutException("https://music.youtube.com/youtubei/v1/player", 20_000L)))
        assertTrue(isNoAnswer(RuntimeException("wrapped", readTimeout)))
        assertTrue(isNoAnswer(UnknownHostException("music.youtube.com")))
        assertFalse(isNoAnswer(RuntimeException("No URL found")))
        assertFalse(isNoAnswer(null))
    }
}
