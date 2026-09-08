package com.cedagova.fastreader.reader.ui

import com.cedagova.fastreader.timing.RsvpTiming
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * REQ-108's arithmetic, checked where it is cheapest.
 *
 * The gesture is a rendering-and-device claim, but *what a drag means* is not:
 * the size of a step, where the range ends, and the one bug a stepped drag
 * reliably has — a step emitted twice at the threshold — are all decidable here,
 * in milliseconds, with no renderer and no emulator. What the goldens and the
 * device run then have to establish is only that the drag reaches this code and
 * that the readout appears where it should.
 */
class SpeedGestureTest {

    // --- Stepping ------------------------------------------------------------

    @Test
    fun `one step up is 25 WPM`() {
        assertEquals(275, steppedWpm(250, 1))
    }

    @Test
    fun `one step down is 25 WPM`() {
        assertEquals(225, steppedWpm(250, -1))
    }

    @Test
    fun `several steps in one drag land on the same grid as one at a time`() {
        var one = 250
        repeat(6) { one = steppedWpm(one, 1) }

        assertEquals(one, steppedWpm(250, 6))
        assertEquals(400, one)
    }

    /** The issue's edge behavior: a gesture at the limit shows the limit and does nothing else. */
    @Test
    fun `dragging past the top stops at the maximum`() {
        assertEquals(RsvpTiming.MAX_WPM, steppedWpm(RsvpTiming.MAX_WPM, 1))
        assertEquals(RsvpTiming.MAX_WPM, steppedWpm(975, 40))
    }

    @Test
    fun `dragging past the bottom stops at the minimum`() {
        assertEquals(RsvpTiming.MIN_WPM, steppedWpm(RsvpTiming.MIN_WPM, -1))
        assertEquals(RsvpTiming.MIN_WPM, steppedWpm(125, -40))
    }

    /**
     * A speed restored from the store was not necessarily written by this app's
     * slider, so the first step snaps to the grid instead of carrying an odd
     * number through the whole range.
     */
    @Test
    fun `an off-grid speed is snapped before it is stepped`() {
        assertEquals("263 sits nearest 275", 275, steppedWpm(263, 0))
        assertEquals(300, steppedWpm(263, 1))
        assertEquals(250, steppedWpm(263, -1))
    }

    // --- Accumulating one drag ----------------------------------------------

    @Test
    fun `a drag shorter than one step changes nothing`() {
        val drag = SpeedDrag(stepPixels = 100f).apply { begin() }

        assertEquals(0, drag.drag(-99f))
    }

    @Test
    fun `dragging up a step at a time yields one step at a time`() {
        val drag = SpeedDrag(stepPixels = 100f).apply { begin() }

        assertEquals(1, drag.drag(-100f))
        assertEquals(1, drag.drag(-100f))
        assertEquals(1, drag.drag(-100f))
    }

    @Test
    fun `dragging down yields negative steps`() {
        val drag = SpeedDrag(stepPixels = 100f).apply { begin() }

        assertEquals(-1, drag.drag(120f))
        assertEquals(-1, drag.drag(100f))
    }

    /**
     * The bug this class exists to prevent. A pointer delivers a long drag as many
     * small deltas, and an implementation that carries a remainder can report the
     * same threshold twice; the total travelled is what decides.
     */
    @Test
    fun `many small deltas cross a threshold exactly once`() {
        val drag = SpeedDrag(stepPixels = 100f).apply { begin() }

        val steps = (1..50).sumOf { drag.drag(-4f) }

        assertEquals(2, steps)
    }

    @Test
    fun `one large delta reports every step it crossed`() {
        val drag = SpeedDrag(stepPixels = 100f).apply { begin() }

        assertEquals(3, drag.drag(-320f))
    }

    /** Reversing inside one drag gives the steps back rather than adding more. */
    @Test
    fun `dragging back up the way it came undoes the steps`() {
        val drag = SpeedDrag(stepPixels = 100f).apply { begin() }
        drag.drag(-250f)

        assertEquals(-2, drag.drag(250f))
    }

    @Test
    fun `a new drag does not inherit the last one's travel`() {
        val drag = SpeedDrag(stepPixels = 100f)
        drag.begin()
        drag.drag(-90f)

        drag.begin()

        assertEquals(0, drag.drag(-90f))
    }
}
