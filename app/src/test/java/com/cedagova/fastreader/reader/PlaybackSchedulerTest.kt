package com.cedagova.fastreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The smoothness guardrail, as arithmetic.
 *
 * The plan calls out naive delay-based scheduling as the real jank risk at
 * 1000 WPM, where a word lasts 60 ms — under four frames at 60 Hz. Frame-aligned
 * scheduling is the mitigation, and these tests pin the two properties that make
 * it one: word changes land on frame boundaries in a tight cadence, and a long run
 * accumulates no drift. The device pass measures the frames themselves; this
 * measures the schedule that drives them.
 */
class PlaybackSchedulerTest {

    private val frameNanos = 16_666_667L
    private val maxWpmWordMillis = 60L

    @Test
    fun `a minute at 1000 WPM shows exactly a minute's worth of words`() {
        val changes = run(seconds = 60, durationMillis = maxWpmWordMillis)
        // 60 s at 60 ms a word. The last change may land just past the window.
        assertTrue("showed ${changes.size} words", changes.size in 999..1000)
    }

    @Test
    fun `word changes never drift away from the schedule they promise`() {
        val changes = run(seconds = 60, durationMillis = maxWpmWordMillis)
        changes.forEachIndexed { position, atNanos ->
            val ideal = (position + 1) * maxWpmWordMillis * PlaybackScheduler.NANOS_PER_MILLI
            assertTrue(
                "word ${position + 1} changed ${(atNanos - ideal) / 1_000_000.0} ms off schedule",
                abs(atNanos - ideal) < frameNanos,
            )
        }
    }

    @Test
    fun `at 1000 WPM every word is held three or four frames, never one and never five`() {
        val changes = run(seconds = 30, durationMillis = maxWpmWordMillis)
        val heldFrames = changes.zipWithNext { previous, next -> ((next - previous) / frameNanos).toInt() }
        val outliers = heldFrames.filter { it !in 3..4 }
        assertTrue("frames per word outside 3..4: ${outliers.take(5)}", outliers.isEmpty())
    }

    @Test
    fun `at the default speed a word spans about fifteen frames`() {
        val changes = run(seconds = 20, durationMillis = 240L)
        val heldFrames = changes.zipWithNext { previous, next -> ((next - previous) / frameNanos).toInt() }
        assertTrue("frames per word outside 14..15: ${heldFrames.distinct()}", heldFrames.all { it in 14..15 })
    }

    @Test
    fun `a stall gives up on catching up instead of flashing the lost words past`() {
        val scheduler = PlaybackScheduler()
        scheduler.start(0L)
        // One frame arrives a full second late: the app was somewhere else.
        val late = 1_000L * PlaybackScheduler.NANOS_PER_MILLI
        assertTrue(scheduler.isDue(late, maxWpmWordMillis))
        scheduler.advanced(late, maxWpmWordMillis)
        assertEquals(1, scheduler.resyncCount)
        // The next word starts from the stall, not from sixteen words ago.
        assertTrue(scheduler.isDue(late + 60 * PlaybackScheduler.NANOS_PER_MILLI, maxWpmWordMillis))
        assertTrue(!scheduler.isDue(late + 30 * PlaybackScheduler.NANOS_PER_MILLI, maxWpmWordMillis))
    }

    @Test
    fun `an ordinary sub-frame overshoot is carried, not resynced away`() {
        val scheduler = PlaybackScheduler()
        scheduler.start(0L)
        scheduler.advanced(frameNanos * 4, maxWpmWordMillis)
        assertEquals(0, scheduler.resyncCount)
        assertTrue(scheduler.lastOvershootNanos in 1 until frameNanos)
    }

    /** Runs a synthetic 60 Hz frame clock and returns the frame time of every word change. */
    private fun run(seconds: Int, durationMillis: Long): List<Long> {
        val scheduler = PlaybackScheduler()
        var frame = 0L
        scheduler.start(frame)
        val changes = ArrayList<Long>()
        repeat(seconds * 60) {
            frame += frameNanos
            if (scheduler.isDue(frame, durationMillis)) {
                changes += frame
                scheduler.advanced(frame, durationMillis)
            }
        }
        return changes
    }
}
