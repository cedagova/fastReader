package com.cedagova.fastreader.reader

import com.cedagova.fastreader.content.Boundary
import com.cedagova.fastreader.content.ContentFixtures
import com.cedagova.fastreader.content.EpubContentPipeline
import com.cedagova.fastreader.epub.EpubByteSource
import com.cedagova.fastreader.reader.ui.ReaderUiState
import com.cedagova.fastreader.timing.PauseStrength
import com.cedagova.fastreader.timing.RsvpTimingEngine
import com.cedagova.fastreader.timing.TimingSettings
import java.io.ByteArrayInputStream
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
 * it. Displayed time remaining comes from the timing engine's estimate, and that
 * estimate includes pause time, so the remaining-time index is a function of pause
 * strength. A build that changed only the word durations would leave a reader who
 * turned pauses off looking at a time remaining that still counted every pause.
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

    @Test
    fun `turning pauses off shortens the time remaining the reader is shown`() = runTest(dispatcher) {
        val reader = openedReader()
        val withPauses = (reader.state.value as ReaderUiState.Reading).remainingMillis

        reader.setPauseStrength(PauseStrength.OFF)
        advanceUntilIdle()

        val withoutPauses = (reader.state.value as ReaderUiState.Reading).remainingMillis
        assertNotEquals(withPauses, withoutPauses)
        assertTrue("$withoutPauses should be well under $withPauses", withoutPauses < withPauses * 0.95)
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
        val expected = RsvpTimingEngine.estimatedMillis(
            book.tokens.drop(1),
            TimingSettings(pauseStrength = PauseStrength.STRONG, rampEnabled = false),
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
        reader.open("first")
        advanceUntilIdle()

        val off = (reader.state.value as ReaderUiState.Reading).remainingMillis
        val normal = openedReader().let { (it.state.value as ReaderUiState.Reading).remainingMillis }
        assertTrue("$off should be under $normal", off < normal * 0.95)
    }

    private fun TestScope.openedReader(): ReaderViewModel {
        val reader = ReaderViewModel(
            books = FixtureBooks,
            positions = positions,
            pipeline = EpubContentPipeline(Dispatchers.Unconfined),
            indexDispatcher = Dispatchers.Unconfined,
        )
        reader.open("first")
        advanceUntilIdle()
        return reader
    }

    private object FixtureBooks : ReaderBooks {
        private val bytes by lazy { ContentFixtures.englishNovel() }

        override fun title(bookId: String) = "The Long Signal"

        override fun bytes(bookId: String) = EpubByteSource { ByteArrayInputStream(bytes) }
    }

    private class SilentPositions : ReaderPositions {
        override val failure: StateFlow<String?> = MutableStateFlow(null)

        override fun restore(bookId: String): ReaderPosition? = null

        override fun record(bookId: String, position: ReaderPosition) = Unit

        override fun flush() = Unit
    }
}
