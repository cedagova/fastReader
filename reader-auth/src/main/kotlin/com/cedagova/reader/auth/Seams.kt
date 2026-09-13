package com.cedagova.reader.auth

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.delay

/** Where "now" comes from; the refresh-margin tests substitute a fixed clock. */
fun interface ReaderClock {
    fun now(): Instant

    companion object {
        /** The device clock. */
        val System: ReaderClock = ReaderClock { Clock.System.now() }
    }
}

/** How the module waits before its one retry; tests record the wait instead of sleeping. */
fun interface RetryWaiter {
    suspend fun wait(duration: Duration)

    companion object {
        /** A real coroutine delay. */
        val Delay: RetryWaiter = RetryWaiter { delay(it) }
    }
}
