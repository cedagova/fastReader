package com.cedagova.fastreader.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The paused paragraph's line sections (REQ-010), which are pure arithmetic and
 * therefore proven here; the renders in [ReaderScreenScreenshotTest] show the
 * result on a real layout.
 */
class ParagraphSectionsTest {

    /** Uniform 10 px lines in a 25 px viewport: sections of two lines, cut from the top. */
    private fun uniform(lineCount: Int, line: Int, viewport: Float = 25f) =
        sectionStartLine(lineCount, viewport, line, lineTop = { it * 10f }, lineBottom = { it * 10f + 10f })

    @Test
    fun `the first section starts at the top of the paragraph`() {
        assertEquals(0, uniform(6, 0))
        assertEquals(0, uniform(6, 1))
    }

    @Test
    fun `a line that does not fit starts the next section`() {
        assertEquals(2, uniform(6, 2))
        assertEquals(2, uniform(6, 3))
        assertEquals(4, uniform(6, 4))
        assertEquals(4, uniform(6, 5))
    }

    @Test
    fun `a paragraph that fits never scrolls`() {
        assertEquals(0, uniform(3, 2, viewport = 100f))
    }

    @Test
    fun `a line exactly filling the viewport still fits`() {
        assertEquals(0, uniform(4, 1, viewport = 20f))
        assertEquals(2, uniform(4, 2, viewport = 20f))
    }

    @Test
    fun `a viewport shorter than a line shows one line at a time`() {
        assertEquals(3, uniform(6, 3, viewport = 5f))
    }

    @Test
    fun `sections respect uneven line heights`() {
        val tops = floatArrayOf(0f, 10f, 30f, 40f)
        val bottoms = floatArrayOf(10f, 30f, 40f, 50f)
        val start = { line: Int -> sectionStartLine(4, 30f, line, { tops[it] }, { bottoms[it] }) }
        assertEquals(0, start(1))
        assertEquals(2, start(2))
        assertEquals(2, start(3))
    }
}
