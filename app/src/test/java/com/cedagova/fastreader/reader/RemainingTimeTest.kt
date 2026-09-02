package com.cedagova.fastreader.reader

import com.cedagova.fastreader.content.BookContent
import com.cedagova.fastreader.content.Boundary
import com.cedagova.fastreader.content.WordClass
import com.cedagova.fastreader.content.WordToken
import com.cedagova.fastreader.timing.PauseStrength
import com.cedagova.fastreader.timing.RsvpTimingEngine
import com.cedagova.fastreader.timing.TimingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Time remaining (REQ-017).
 *
 * The index exists to answer in constant time what
 * [RsvpTimingEngine.estimatedMillis] answers in a sweep of the book, so the
 * central test is simply that the two agree — including on a book long enough
 * that per-token rounding would show up if it accumulated.
 */
class RemainingTimeTest {

    private val book = ReaderFixtures.englishNovel

    /** 60,000 tokens: about a 700-page novel, and 60,000 chances for rounding to drift. */
    private val longBook = syntheticBook(60_000)

    @Test
    fun `the index agrees with the timing engine's own estimate at every speed`() {
        for (content in listOf(book, longBook)) {
            val index = RemainingTimeIndex.build(content, PauseStrength.NORMAL)
            for (wpm in listOf(100, 250, 450, 1000)) {
                val settings = TimingSettings(wpm = wpm)
                val exact = RsvpTimingEngine.estimatedMillis(content.tokens, settings)
                val fast = index.millisAfter(-1, settings)
                val error = abs(exact - fast).toDouble() / exact
                assertTrue(
                    "at $wpm WPM over ${content.totalTokens} tokens: $fast vs $exact (${error * 100}%)",
                    error < 0.002,
                )
            }
        }
    }

    @Test
    fun `it agrees from the middle of a book too`() {
        val index = RemainingTimeIndex.build(book, PauseStrength.NORMAL)
        val settings = TimingSettings(wpm = 300)
        val exact = RsvpTimingEngine.estimatedMillis(book.tokens.drop(21), settings)
        assertTrue(abs(exact - index.millisAfter(20, settings)) < 30)
    }

    @Test
    fun `REQ-017 doubling the speed roughly halves the time remaining`() {
        val index = RemainingTimeIndex.build(longBook, PauseStrength.NORMAL)
        val slow = index.millisAfter(-1, TimingSettings(wpm = 250))
        val fast = index.millisAfter(-1, TimingSettings(wpm = 500))
        val ratio = slow.toDouble() / fast
        assertTrue("halving gave a ratio of $ratio", abs(ratio - 2.0) < 0.01)
    }

    @Test
    fun `the pauses are not a rounding error on the naive words-over-wpm figure`() {
        val index = RemainingTimeIndex.build(longBook, PauseStrength.NORMAL)
        val settings = TimingSettings(wpm = 250)
        val naive = longBook.totalWords * 60_000L / settings.wpm
        val real = index.millisAfter(-1, settings)
        assertTrue("modulation should cost real time: $real vs $naive", real > naive * 1.3)
    }

    @Test
    fun `nothing is left after the last token`() {
        val index = RemainingTimeIndex.build(book, PauseStrength.NORMAL)
        assertEquals(0L, index.millisAfter(book.tokens.lastIndex, TimingSettings()))
        assertEquals(0L, index.millisAfter(9_999, TimingSettings()))
    }

    @Test
    fun `pause strength off makes every token cost the same`() {
        val index = RemainingTimeIndex.build(book, PauseStrength.OFF)
        val settings = TimingSettings(wpm = 250, pauseStrength = PauseStrength.OFF)
        val whole = index.millisAfter(-1, settings)
        assertEquals((book.totalTokens * 240L).toDouble(), whole.toDouble(), 2.0)
    }

    /**
     * A book-shaped token stream: mostly plain words, with the punctuation and
     * word classes that carry the multipliers, in roughly the proportions prose
     * has them.
     */
    private fun syntheticBook(tokens: Int): BookContent {
        val random = Random(20260901)
        val words = (0 until tokens).map { index ->
            val roll = random.nextInt(100)
            WordToken(
                index = index,
                text = if (roll < 6) "extraordinariamente" else "palabra",
                chapterIndex = index / 5_000,
                paragraphIndex = index / 60,
                sentenceIndex = index / 12,
                boundary = when {
                    index % 60 == 59 -> Boundary.PARAGRAPH
                    index % 12 == 11 -> Boundary.SENTENCE
                    roll < 18 -> Boundary.CLAUSE
                    else -> Boundary.NONE
                },
                classes = if (roll < 6) setOf(WordClass.LONG) else emptySet(),
            )
        }
        return BookContent(bookDigest = "sha256:synthetic", language = "es", tokens = words, chapters = emptyList())
    }
}
