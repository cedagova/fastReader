package com.cedagova.fastreader.reader

import com.cedagova.fastreader.content.Boundary
import com.cedagova.fastreader.content.ContentFixtures
import com.cedagova.fastreader.content.EpubContentPipeline
import com.cedagova.fastreader.reader.ui.ReaderUiState
import com.cedagova.fastreader.timing.PauseStrength
import com.cedagova.fastreader.timing.RsvpTimingEngine
import com.cedagova.fastreader.timing.TimingSettings
import com.cedagova.fastreader.timing.TimingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pause strength as a *setting* (LEAF302), which increment 002 left hard-coded.
 *
 * The interesting property is not that the engine honours the value — LEAF202
 * proved that — but that changing it mid-book moves both things that depend on
 * it: the word durations and the book's mean multiplier, which the time-remaining
 * index measures at the strength in force. Since #81 the dial names the average
 * speed of the book, so changing the strength redistributes time between pauses
 * and plain words *without changing how long the book takes*; a rebuild that
 * updated the index but left the session's old mean in place would have the
 * time remaining and the pacing describe two different books.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPauseStrengthTest {

    private val dispatcher = StandardTestDispatcher()
    private val positions = SilentPositions()
    private val book = ReaderFixtures.englishNovel

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** The premise: the two strengths really do describe different books-worth of time. */
    @Test
    fun `pause time is a large enough share of a book to be worth rebuilding`() {
        val plain = RsvpTimingEngine.estimatedMillis(
            book.tokens,
            TimingSettings(pauseStrength = PauseStrength.OFF, rampEnabled = false),
        )
        val normal = RsvpTimingEngine.estimatedMillis(
            book.tokens,
            TimingSettings(pauseStrength = PauseStrength.NORMAL, rampEnabled = false),
        )

        assertTrue("pauses should add real time, got $plain then $normal", normal > plain * 1.1)
    }

    /**
     * #81: the strength moves the pauses around inside a fixed budget, not the
     * budget. The reader sits on the first word, so what it is shown is the book
     * *after* that word; adding the first word's own duration back gives the whole
     * book, which must be `tokens × 60000 / wpm` at every strength. A rebuild that
     * left the session's old mean in place would miss by the ratio of the means.
     */
    @Test
    fun `changing the strength keeps how long the book takes`() = runTest(dispatcher) {
        val reader = openedReader()
        val steady = TimingState(elapsedPlaybackMillis = 60_000L, reorientationPending = false)
        val wpm = (reader.state.value as ReaderUiState.Reading).wpm
        val budget = book.totalTokens * 60_000.0 / wpm

        for (strength in listOf(PauseStrength.NORMAL, PauseStrength.OFF, PauseStrength.STRONG, PauseStrength.SUBTLE)) {
            reader.setPauseStrength(strength)
            advanceUntilIdle()
            val remaining = (reader.state.value as ReaderUiState.Reading).remainingMillis
            val mean = RemainingTimeIndex.build(book, strength).meanMultiplier
            val firstWord = RsvpTimingEngine.durationMillis(
                book.tokens[0],
                TimingSettings(wpm = wpm, pauseStrength = strength, rampEnabled = false, meanMultiplier = mean),
                steady,
            )
            assertEquals("$strength (mean $mean)", budget, (remaining + firstWord).toDouble(), budget * 0.01)
        }
    }

    /**
     * The rebuilt index has to be the *right* one, not merely a different one: it
     * must agree with the engine's own estimate of the rest of the book at the
     * strength now in force.
     */
    @Test
    fun `the rebuilt estimate matches the engine at the new strength`() = runTest(dispatcher) {
        val reader = openedReader()

        reader.setPauseStrength(PauseStrength.STRONG)
        advanceUntilIdle()

        val state = reader.state.value as ReaderUiState.Reading
        // The mean must have travelled with the rebuild: an estimate at the new
        // strength but the old mean would be off by the ratio of the two means.
        val mean = RemainingTimeIndex.build(book, PauseStrength.STRONG).meanMultiplier
        val expected = RsvpTimingEngine.estimatedMillis(
            book.tokens.drop(1),
            TimingSettings(pauseStrength = PauseStrength.STRONG, rampEnabled = false, meanMultiplier = mean),
        )
        // The index sums per-token multipliers rounded to whole milliseconds at a
        // reference speed, so it lands within a fraction of a percent rather than
        // exactly on the engine's own sum. `RemainingTimeTest` pins that tolerance.
        assertTrue(
            "index said ${state.remainingMillis}, engine says $expected",
            kotlin.math.abs(state.remainingMillis - expected) < expected / 100,
        )
    }

    /** REQ-011: the change reaches playback itself, not only the estimate. */
    @Test
    fun `the very next word is held for the new strength`() = runTest(dispatcher) {
        val reader = openedReader()
        // A plain word is held for the same time at every strength, so the token
        // this lands on has to be one that carries a pause.
        val sentenceEnd = book.tokens.first { it.boundary == Boundary.SENTENCE }.index
        reader.scrubTo(sentenceEnd.toFloat() / book.tokens.lastIndex)
        reader.togglePlay()
        advanceUntilIdle()
        val normal = reader.currentDurationMillis

        reader.setPauseStrength(PauseStrength.OFF)
        advanceUntilIdle()

        assertEquals(Boundary.SENTENCE, book.tokens[sentenceEnd].boundary)
        assertNotEquals(normal, reader.currentDurationMillis)
    }

    /** Re-running the settings effect must not sweep the book again for no reason. */
    @Test
    fun `setting the same strength again changes nothing`() = runTest(dispatcher) {
        val reader = openedReader()
        val before = (reader.state.value as ReaderUiState.Reading).remainingMillis

        reader.setPauseStrength(PauseStrength.NORMAL)
        advanceUntilIdle()

        assertEquals(before, (reader.state.value as ReaderUiState.Reading).remainingMillis)
    }

    /** A book opened while a non-default strength is already stored opens at that strength. */
    @Test
    fun `a book opened after the setting changed uses it from the first frame`() = runTest(dispatcher) {
        val reader = ReaderViewModel(
            books = FixtureBooks,
            positions = positions,
            pipeline = EpubContentPipeline(Dispatchers.Unconfined),
            indexDispatcher = Dispatchers.Unconfined,
        )
        reader.setPauseStrength(PauseStrength.OFF)
        reader.openLibraryBook(ReaderFixtures.ENGLISH_NOVEL_ID)
        advanceUntilIdle()

        // Time remaining no longer tells the strengths apart (#81), so the proof
        // is the pacing itself: at OFF a sentence end is held like any plain word
        // — three plain words, for the re-orientation hold, at the ramp's opening
        // 300 ms — with a mean of exactly one; at NORMAL the same token carries
        // its pause against the book's mean, and the two cannot coincide.
        val sentenceEnd = book.tokens.first { it.boundary == Boundary.SENTENCE }.index
        fun ReaderViewModel.durationAtSentenceEnd(): Long? {
            scrubTo(sentenceEnd.toFloat() / book.tokens.lastIndex)
            togglePlay()
            advanceUntilIdle()
            return currentDurationMillis
        }
        assertEquals(3 * 300L, reader.durationAtSentenceEnd())
        assertNotEquals(3 * 300L, openedReader().durationAtSentenceEnd())
    }

    private fun TestScope.openedReader(): ReaderViewModel {
        val reader = ReaderViewModel(
            books = FixtureBooks,
            positions = positions,
            pipeline = EpubContentPipeline(Dispatchers.Unconfined),
            indexDispatcher = Dispatchers.Unconfined,
        )
        reader.openLibraryBook(ReaderFixtures.ENGLISH_NOVEL_ID)
        advanceUntilIdle()
        return reader
    }

    private object FixtureBooks : ReaderBooks {
        private val bytes by lazy { ContentFixtures.englishNovel() }

        override fun libraryBook(bookId: String) = BookOpenRequest.library(
            bookId = bookId,
            title = "The Long Signal",
            bytes = ContentFixtures.source(bytes),
        )
    }

    private class SilentPositions : ReaderPositions {
        override val failure: StateFlow<String?> = MutableStateFlow(null)

        override fun restore(bookId: String): ReaderPosition? = null

        override fun record(bookId: String, position: ReaderPosition) = Unit

        override fun flush() = Unit
    }
}
