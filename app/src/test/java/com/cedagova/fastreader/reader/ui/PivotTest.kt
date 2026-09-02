package com.cedagova.fastreader.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The recognition point (REQ-020), which is pure arithmetic and therefore proven
 * here rather than by looking at a render.
 */
class PivotTest {

    @Test
    fun `the pivot follows the open-source RSVP convention`() {
        assertEquals(0, pivotIndex(1))
        assertEquals(1, pivotIndex(2))
        assertEquals(1, pivotIndex(3))
        assertEquals(1, pivotIndex(4))
        assertEquals(1, pivotIndex(5))
        assertEquals(2, pivotIndex(6))
        assertEquals(6, pivotIndex(15))
    }

    /**
     * From four letters up the point sits strictly left of the word's middle,
     * which is what "recognition point slightly left of center" means and what
     * makes the alignment worth doing.
     *
     * Two- and three-letter words are the convention's deliberate exception: with
     * so few letters there is no room to sit left of centre, and the second
     * character is where the open-source convention puts them.
     */
    @Test
    fun `from four letters up the pivot sits left of the middle`() {
        for (length in 4..40) {
            val middle = (length - 1) / 2.0
            assert(pivotIndex(length) < middle) { "length $length: ${pivotIndex(length)} >= $middle" }
        }
    }

    @Test
    fun `a single letter pivots on itself`() {
        assertEquals(0, ReaderWord("a").pivotOffset())
    }

    /**
     * The regression this exists for: with punctuation now drawn around the word,
     * a naive midpoint would put the pivot of `—¿Quién` on the `Q` or the dash.
     * It belongs on a letter of "Quién".
     */
    @Test
    fun `punctuation around the word does not move the recognition point`() {
        val plain = ReaderWord("Quién")
        val quoted = ReaderWord("—¿Quién?", coreStart = 2, coreEnd = 7)
        assertEquals(1, plain.pivotOffset())
        assertEquals(3, quoted.pivotOffset())
        assertEquals("u", quoted.text.substring(3, 4))
    }

    @Test
    fun `a token with no letters at all has no pivot`() {
        assertNull(ReaderWord("[image skipped]", coreStart = 0, coreEnd = 0).pivotOffset())
    }
}
