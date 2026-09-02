package com.cedagova.fastreader.library

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps continuous position persistence (REQ-016) off the playback path.
 *
 * At the 1000 WPM ceiling the reader changes word about sixteen times a second,
 * and one catalog write is a full JSON re-encode followed by an `fsync`. Doing
 * that per word would put a durable write between every pair of frames, which is
 * exactly the jank LEAF203 measured away. So the playback path only ever calls
 * [record], which stores a value in memory and returns; the write itself happens
 * at most once per [intervalMillis] from [scope].
 *
 * Coalescing is a trailing throttle rather than a debounce: a burst of positions
 * is written once, [intervalMillis] after the first of them, instead of being
 * postponed for as long as the reader keeps reading. That bounds how much a
 * *force-stop* can cost — nothing else can, because Android delivers no callback
 * before killing a foreground process. Every event that is not "the next word"
 * — pausing, jumping, changing speed, losing the foreground, closing the book —
 * calls [flush] and is therefore durable immediately.
 */
class ReadingPositionWriter(
    private val scope: CoroutineScope,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val write: suspend (String, ReadingState) -> Unit,
) {

    private class Pending(val bookId: String, val state: ReadingState)

    /** Guards the write itself, so two writes cannot interleave on the store. */
    private val writing = Mutex()

    /**
     * The newest position not yet written. Taken with `getAndSet` rather than read
     * and cleared, because the throttle takes it from its own coroutine while the
     * reader may be recording the next word.
     */
    private val pending = AtomicReference<Pending?>(null)

    private var throttle: Job? = null

    /**
     * Notes the newest position. Called from the playback path: no I/O, no lock,
     * no allocation beyond the holder itself.
     */
    fun record(bookId: String, state: ReadingState) {
        pending.set(Pending(bookId, state))
        if (throttle?.isActive == true) return
        throttle = scope.launch {
            delay(intervalMillis)
            pending.getAndSet(null)?.let { emit(it) }
        }
    }

    /**
     * Writes the newest recorded position now. Returns the job doing it so a
     * caller that can wait — a test, or closing the book — is able to.
     *
     * The value is taken here, synchronously, not inside the coroutine: two
     * discrete acts in quick succession — the last word of one book and the first
     * of the next — would otherwise both resolve to whichever was recorded last,
     * and the earlier one would never be written at all.
     *
     * A pending throttle is deliberately left to fire. Cancelling it could abort a
     * write it had already taken the value for, and a timer that wakes to find
     * nothing costs nothing.
     */
    fun flush(): Job {
        val next = pending.getAndSet(null)
        return scope.launch { next?.let { emit(it) } }
    }

    private suspend fun emit(next: Pending) = writing.withLock { write(next.bookId, next.state) }

    companion object {
        /**
         * Two writes a second at most. At the 250 WPM default that is about four
         * words of exposure to a foreground kill, and about sixteen at the 1000
         * WPM ceiling — inside the sentence the reader was on, which is what
         * REQ-016's acceptance asks for.
         */
        const val DEFAULT_INTERVAL_MILLIS: Long = 500L
    }
}
