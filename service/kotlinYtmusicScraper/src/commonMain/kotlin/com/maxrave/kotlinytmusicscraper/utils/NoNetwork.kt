package com.maxrave.kotlinytmusicscraper.utils

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The device cannot reach the network at all: the name does not resolve, the connection is refused or
 * unreachable, or the connection attempt itself ran out of time. One answer for every layer that
 * talks to YouTube, so a resolution stops at the first sign of it instead of walking the remaining
 * extractor tiers (each of which would fail the same way, and in a network that swallows packets
 * each would wait out its own connect timeout).
 *
 * Not for a 403, a 404 or an answer that does not parse: those say the network is there and the
 * answer is bad, and the next tier may well do better.
 */
class NoNetworkException(
    cause: Throwable? = null,
) : RuntimeException("No network", cause)

private val noNetworkClassNames =
    setOf(
        "UnknownHostException",
        "ConnectException",
        "NoRouteToHostException",
        "PortUnreachableException",
        // Ktor: the connection was not established within connectTimeoutMillis.
        "ConnectTimeoutException",
    )

/** A mirror of the extraction service, down on its own while YouTube is fine: not a lost network. */
private const val EXTRACTION_SERVICE_HOST = "pipepipe.dev"

/**
 * Whether [throwable], or anything it was caused by, says there is no network. The classes are matched
 * by name because this file is common code and the exceptions are the JVM's and Ktor's own.
 *
 * A read that timed out is NOT a lost network (a slow answer on a bad link is still a link); only a
 * timeout while connecting is, and the JVM words that in the message ("connect timed out",
 * "failed to connect to ... after 10000ms").
 *
 * A failure that names the extraction service (`api.pipepipe.dev`) is skipped: that mirror being
 * unreachable must still let the next tier try YouTube directly.
 */
fun isNoNetwork(throwable: Throwable?): Boolean {
    var current = throwable
    var depth = 0
    while (current != null && depth < 8) {
        if (current is NoNetworkException) return true
        val message = current.message.orEmpty()
        if (message.contains(EXTRACTION_SERVICE_HOST, ignoreCase = true)) return false
        val name = current::class.simpleName
        if (name in noNetworkClassNames) return true
        if (name == "SocketTimeoutException" && message.contains("connect", ignoreCase = true)) return true
        if (name == "SocketException" &&
            (message.contains("unreachable", ignoreCase = true) || message.contains("refused", ignoreCase = true))
        ) {
            return true
        }
        current = current.cause?.takeIf { it !== current }
        depth++
    }
    return false
}

/**
 * Whether [throwable] is a request that got no answer in time (connecting, or waiting for the reply):
 * no network, or one so silent that the next try a moment later would wait just as long. Unlike
 * [isNoNetwork] this does not end an extraction (a slow link still delivers), it only lets
 * [NetworkGate] spare the retry a second wait.
 */
fun isNoAnswer(throwable: Throwable?): Boolean {
    var current = throwable
    var depth = 0
    while (current != null && depth < 8) {
        if (current::class.simpleName?.contains("Timeout") == true) return true
        current = current.cause?.takeIf { it !== current }
        depth++
    }
    return isNoNetwork(throwable)
}

/**
 * What the requests to YouTube remember of the last "no network": for a few seconds after one, the
 * next ask answers at once instead of waiting out its own timeout. Without it the player's retry of a
 * track and the tracks queued behind it (each a link check, then a request) paid one connect timeout
 * apiece, one after another, on a network that swallows packets.
 *
 * Short on purpose: the first request after the window finds out for itself, so a network that came
 * back is never refused for long. Cleared by any answer from YouTube.
 *
 * The platform can also say outright that the device has no network ([setDeviceOffline], Android).
 * That needs no first request to find out: with a VPN tunnel left up in airplane mode every request
 * waits out its DNS timeout (8-18 s measured 2026-11-16 on the phone) before the window above opens.
 */
object NetworkGate {
    private const val WINDOW_MS = 4_000L

    @Volatile
    private var lostAt: TimeMark? = null

    @Volatile
    private var deviceOffline = false

    private val _restored = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Emits when the network is back after it was gone: the platform reports a link again (Android), or an
     * answer arrives after a request that got none. A screen showing a saved copy for want of a network
     * refreshes itself on it.
     */
    val restored: SharedFlow<Unit> = _restored

    fun markLost() {
        lostAt = TimeSource.Monotonic.markNow()
    }

    fun markReachable() {
        if (lostAt != null) _restored.tryEmit(Unit)
        lostAt = null
    }

    /** From the platform's own view of the networks; true only when there is none to carry a request. */
    fun setDeviceOffline(offline: Boolean) {
        val wasOffline = deviceOffline
        deviceOffline = offline
        if (wasOffline && !offline) _restored.tryEmit(Unit)
    }

    fun isLost(): Boolean = deviceOffline || lostAt?.let { it.elapsedNow().inWholeMilliseconds < WINDOW_MS } == true
}
