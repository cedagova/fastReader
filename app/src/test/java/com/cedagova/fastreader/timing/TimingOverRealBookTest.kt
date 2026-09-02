package com.cedagova.fastreader.timing

import com.cedagova.fastreader.content.Boundary
import com.cedagova.fastreader.content.BookContent
import com.cedagova.fastreader.content.BookContentResult
import com.cedagova.fastreader.content.ContentFixtures
import com.cedagova.fastreader.content.EpubContentPipeline
import com.cedagova.fastreader.content.WordToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine against a real token stream instead of hand-built tokens.
 *
 * [RsvpTimingEngineTest] proves the arithmetic; this proves the *seam*. The
 * tokens here come out of LEAF201's pipeline parsing an actual EPUB — Spanish,
 * so accents, `¿¡` and dialogue dashes are in play — which is the only way to
 * catch a mismatch between what the pipeline emits and what the engine expects.
 * It also prints the opening of the book as a duration table, which is the
 * closest thing a clock-free engine has to a smoke test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimingOverRealBookTest {

    private val pipeline = EpubContentPipeline(UnconfinedTestDispatcher())

    @Test
    fun `every token of a real book gets a deterministic duration`() = runTest {
        val book = parse(ContentFixtures.spanishNovel())
        val settings = TimingSettings(wpm = 250, pauseStrength = PauseStrength.NORMAL, rampEnabled = true)

        val first = play(book, settings)
        val second = play(book, settings)

        assertTrue("the fixture must be a real book", book.totalTokens > 30)
        assertEquals(book.totalTokens, first.size)
        assertEquals(first, second)
        assertTrue("every token must be showable", first.all { it >= 1L })
    }

    @Test
    fun `pause strength off streams a whole book at exactly one plain word per token`() = runTest {
        val book = parse(ContentFixtures.spanishNovel())
        val uniform = TimingSettings(wpm = 250, pauseStrength = PauseStrength.OFF, rampEnabled = false)
        val steady = TimingState(elapsedPlaybackMillis = 60_000L, reorientationPending = false)

        // REQ-011's acceptance, end to end: mid-stream, with modulation off, the
        // book takes exactly one plain-word duration per token — no exception
        // anywhere in it, however it is punctuated.
        val durations = book.tokens.map { RsvpTimingEngine.durationMillis(it, uniform, steady) }

        assertEquals(setOf(240L), durations.toSet())
        assertEquals(book.totalTokens * 240L, durations.sum())
    }

    @Test
    fun `only the re-orientation hold breaks that uniformity at the start of playback`() = runTest {
        val book = parse(ContentFixtures.spanishNovel())
        val uniform = TimingSettings(wpm = 250, pauseStrength = PauseStrength.OFF, rampEnabled = false)

        val durations = play(book, uniform)

        // Turning modulation off must not quietly disable REQ-013: pressing play
        // still holds the first word for re-orientation, and nothing else moves.
        assertEquals(3 * 240L, durations.first())
        assertEquals(setOf(240L), durations.drop(1).toSet())
    }

    @Test
    fun `real sentence ends hold three times a plain word`() = runTest {
        val book = parse(ContentFixtures.spanishNovel())
        val settings = TimingSettings(wpm = 250, pauseStrength = PauseStrength.NORMAL, rampEnabled = false)
        val steady = TimingState(elapsedPlaybackMillis = 60_000L, reorientationPending = false)

        val sentenceEnds = book.tokens.filter {
            it is WordToken && it.boundary == Boundary.SENTENCE && it.classes.isEmpty()
        }
        val plainWords = book.tokens.filter {
            it is WordToken && it.boundary == Boundary.NONE && it.classes.isEmpty()
        }

        assertTrue("the Spanish fixture must contain sentence ends", sentenceEnds.isNotEmpty())
        assertTrue(plainWords.isNotEmpty())
        assertEquals(setOf(720L), sentenceEnds.map { RsvpTimingEngine.durationMillis(it, settings, steady) }.toSet())
        assertEquals(setOf(240L), plainWords.map { RsvpTimingEngine.durationMillis(it, settings, steady) }.toSet())
    }

    @Test
    fun `time remaining must come from the engine, not from words over wpm`() = runTest {
        val book = parse(ContentFixtures.spanishNovel())
        val settings = TimingSettings(wpm = 250, pauseStrength = PauseStrength.NORMAL, rampEnabled = false)

        val estimated = RsvpTimingEngine.estimatedMillis(book.tokens, settings)
        val naive = book.totalWords * 60_000L / settings.effectiveWpm

        // The gap is the whole reason `estimatedMillis` exists. It is not a rounding
        // error and it is not a constant: it depends on how the book is punctuated,
        // so LEAF203 cannot recover it with a fudge factor.
        assertTrue("estimate ${estimated}ms vs naive ${naive}ms", estimated > naive)

        // Turning modulation off collapses the two, which is the check that the gap
        // really is the pauses and nothing else.
        assertEquals(
            book.totalTokens * 240L,
            RsvpTimingEngine.estimatedMillis(book.tokens, settings.copy(pauseStrength = PauseStrength.OFF)),
        )
    }

    @Test
    fun `estimating a book ignores the ramp and the hold`() = runTest {
        val book = parse(ContentFixtures.spanishNovel())
        val settings = TimingSettings(wpm = 250, pauseStrength = PauseStrength.NORMAL, rampEnabled = true)
        val steady = TimingState(elapsedPlaybackMillis = 60_000L, reorientationPending = false)

        // An estimate for a whole book must not lurch because the reader just
        // jumped, or because the ramp happens to be climbing right now.
        assertEquals(
            book.tokens.sumOf { RsvpTimingEngine.durationMillis(it, settings, steady) },
            RsvpTimingEngine.estimatedMillis(book.tokens, settings),
        )
    }

    @Test
    fun `the opening of a real book as a duration table`() = runTest {
        val book = parse(ContentFixtures.spanishNovel())
        val settings = TimingSettings()
        var state = TimingState.AT_PLAYBACK_START

        val rows = StringBuilder()
        rows.appendLine("idx  ms    token                boundary   classes")
        for (token in book.tokens.take(16)) {
            val duration = RsvpTimingEngine.durationMillis(token, settings, state)
            val classes = (token as? WordToken)?.classes.orEmpty().joinToString(",")
            rows.appendLine(
                "%-4d %-5d %-20s %-10s %s".format(
                    token.index,
                    duration,
                    token.displayText.take(20),
                    token.boundary,
                    classes,
                ),
            )
            state = state.afterShowing(duration)
        }
        println(rows)

        assertTrue(rows.isNotEmpty())
    }

    private fun play(book: BookContent, settings: TimingSettings): List<Long> {
        var state = TimingState.AT_PLAYBACK_START
        return book.tokens.map { token ->
            val duration = RsvpTimingEngine.durationMillis(token, settings, state)
            state = state.afterShowing(duration)
            duration
        }
    }

    private suspend fun parse(bytes: ByteArray): BookContent {
        val result = pipeline.parse(ContentFixtures.source(bytes))
        return (result as BookContentResult.Parsed).content
    }
}
