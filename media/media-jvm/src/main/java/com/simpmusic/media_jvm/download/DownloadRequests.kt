package com.simpmusic.media_jvm.download

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The downloads queued or running, one per track, each with the token of the request that made it.
 *
 * A bare id cannot tell a request from the one that replaced it: a download removed and asked for
 * again at once put the id back, and the old run, finding it there, took the new request for its own
 * and either finished it as a failure or left the new run nothing to do. The token is what a run
 * holds: it works and writes state only while its token is still the current one for the id.
 */
internal class DownloadRequests {
    private val tokens = AtomicLong()
    private val current = ConcurrentHashMap<String, Long>()

    /** A new request for [id]: its token, or null when [id] already has one queued or running. */
    fun register(id: String): Long? {
        val token = tokens.incrementAndGet()
        return if (current.putIfAbsent(id, token) == null) token else null
    }

    operator fun contains(id: String): Boolean = current.containsKey(id)

    /** Whether [token] is still the request of [id], that is, not removed and not replaced since. */
    fun isCurrent(
        id: String,
        token: Long,
    ): Boolean = current[id] == token

    /** Ends whatever request [id] has, whoever holds it. False if there was none. */
    fun cancel(id: String): Boolean = current.remove(id) != null

    /** Ends the request [token] of [id], if it is still the current one: only then does its run finish the track up. */
    fun complete(
        id: String,
        token: Long,
    ): Boolean = current.remove(id, token)

    fun ids(): List<String> = current.keys.toList()
}
