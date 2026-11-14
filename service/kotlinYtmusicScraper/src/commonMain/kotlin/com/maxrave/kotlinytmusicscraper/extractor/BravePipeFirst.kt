package com.maxrave.kotlinytmusicscraper.extractor

import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Whether BravePipe is asked before the PipePipe tiers, and for how long. Measured 2026-11-13 (PC)
 * and 2026-11-14 (phone): every PipePipe URL came back 403 at the HEAD check, which spent 10-20 s
 * per track before BravePipe (1.5-4 s) was asked at all. The window is half an hour, so PipePipe
 * gets probed again; outside it a track walks the whole chain.
 *
 * The last probe's outcome outlives the process: the app stores it ([onOutcome]) and hands it back
 * at start ([restore]). Without that the first track after every launch paid for the whole chain
 * (12 s on the PC, 16-20 s on the phone, where the 15 s resolver limit threw the answer away).
 */
@OptIn(ExperimentalTime::class)
object BravePipeFirst {
    /** How long BravePipe goes first once the PipePipe tiers failed and it answered. */
    const val WINDOW_MS = 30 * 60 * 1000L

    @Volatile
    private var until = 0L

    /** Called with true when PipePipe failed and BravePipe answered, false when PipePipe answered. */
    @Volatile
    var onOutcome: ((braveFirst: Boolean) -> Unit)? = null

    private fun now() = Clock.System.now().toEpochMilliseconds()

    val active: Boolean get() = now() < until

    /** At start, from the stored outcome of the last probe. */
    fun restore(braveFirst: Boolean) {
        if (braveFirst && until == 0L) until = now() + WINDOW_MS
    }

    /** Both PipePipe tiers failed and BravePipe answered. */
    fun pipePipeFailed() {
        until = now() + WINDOW_MS
        onOutcome?.invoke(true)
    }

    /** A PipePipe tier answered: it goes first again, here and after the next launch. */
    fun pipePipeAnswered() {
        val wasActive = until != 0L
        until = 0L
        if (wasActive) onOutcome?.invoke(false)
    }
}
