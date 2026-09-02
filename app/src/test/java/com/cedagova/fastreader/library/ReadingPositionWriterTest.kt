package com.cedagova.fastreader.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The carry-forward risk from LEAF203: one durable write per word would put an
 * `fsync` between two frames at the speed the reader was measured smooth at.
 * These tests pin the two properties that keep it off that path — a burst costs
 * one write, and nothing is lost by coalescing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingPositionWriterTest {

    private val written = mutableListOf<Pair<String, ReadingState>>()

    @Test
    fun `a burst of positions costs one write, and it is the newest`() = runTest {
        val writer = writer(this)

        // 1000 WPM for a second: sixteen words, sixteen recorded positions.
        repeat(16) { index -> writer.record("book", state(index)) }
        advanceUntilIdle()

        assertEquals(1, written.size)
        assertEquals(15, written.single().second.tokenIndex)
    }

    @Test
    fun `reading on keeps producing writes rather than postponing them forever`() = runTest {
        val writer = writer(this)

        // A debounce would never write while the reader keeps reading; a trailing
        // throttle writes once per window, which is what bounds a force-stop.
        repeat(20) { index ->
            writer.record("book", state(index))
            advanceTimeBy(100)
        }
        advanceUntilIdle()

        assertTrue("expected roughly one write per 500 ms window", written.size in 3..5)
        assertEquals(19, written.last().second.tokenIndex)
    }

    @Test
    fun `a flush writes the newest position immediately`() = runTest {
        val writer = writer(this)

        writer.record("book", state(7))
        writer.flush()
        advanceUntilIdle()

        assertEquals(listOf("book" to 7), written.map { it.first to it.second.tokenIndex })
    }

    @Test
    fun `a flush with nothing new writes nothing`() = runTest {
        val writer = writer(this)

        writer.record("book", state(3))
        writer.flush()
        advanceUntilIdle()
        writer.flush()
        advanceUntilIdle()

        assertEquals(1, written.size)
    }

    @Test
    fun `switching books flushes the book being left under its own id`() = runTest {
        val writer = writer(this)

        writer.record("first", state(11))
        writer.flush()
        writer.record("second", state(0))
        writer.flush()
        advanceUntilIdle()

        assertEquals(listOf("first" to 11, "second" to 0), written.map { it.first to it.second.tokenIndex })
    }

    private fun TestScope.writer(scope: CoroutineScope = this) =
        ReadingPositionWriter(scope, intervalMillis = 500) { bookId, state ->
            written += bookId to state
        }

    private fun state(tokenIndex: Int) = ReadingState(bookDigest = "sha256:a", tokenIndex = tokenIndex)
}
