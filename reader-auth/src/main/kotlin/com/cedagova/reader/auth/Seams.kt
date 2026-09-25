package com.cedagova.reader.auth

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.delay

/** Where "now" comes from; the refresh-margin tests substitute a fixed clock. */
public fun interface ReaderClock {
    public fun now(): Instant

    public companion object {
        /** The device clock. */
        public val System: ReaderClock = ReaderClock { Clock.System.now() }
    }
}

/** How the module waits before its one retry; tests record the wait instead of sleeping. */
public fun interface RetryWaiter {
    public suspend fun wait(duration: Duration)

    public companion object {
        /** A real coroutine delay. */
        public val Delay: RetryWaiter = RetryWaiter { delay(it) }
    }
}
