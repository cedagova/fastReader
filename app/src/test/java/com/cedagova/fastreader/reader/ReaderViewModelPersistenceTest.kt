package com.cedagova.fastreader.reader

import com.cedagova.fastreader.content.ContentFixtures
import com.cedagova.fastreader.content.EpubContentPipeline
import com.cedagova.fastreader.content.TokenPosition
import com.cedagova.fastreader.epub.EpubByteSource
import com.cedagova.fastreader.reader.ui.ReaderUiState
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The durability contract of the reader's state machine (REQ-016).
 *
 * Two properties matter and neither is visible from the session type alone:
 * a book reopens where it was left, and the *cost* of persisting is paid on
 * discrete acts rather than on every word.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelPersistenceTest {

    private val dispatcher = StandardTestDispatcher()
    private val positions = RecordingPositions()
    private val book = ReaderFixtures.englishNovel

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a book with no stored position opens at the beginning, paused`() = runTest(dispatcher) {
        val reader = openedReader()

        val state = reader.state.value as ReaderUiState.Reading

        assertEquals(ReaderMode.PAUSED, state.mode)
        assertEquals(book.tokens.first().displayText, state.word.text)
    }

    // REQ-016's acceptance, minus the emulator: same position, same speed.
    @Test
    fun `a stored position reopens on the same word at the same speed`() = runTest(dispatcher) {
        positions.stored = ReaderPosition(
            position = TokenPosition(book.bookDigest, tokenIndex = 24, pipelineVersion = book.pipelineVersion),
            progressFraction = book.progressFraction(24),
            wpm = 550,
        )

        val state = openedReader().state.value as ReaderUiState.Reading

        assertEquals(book.tokens[24].displayText, state.word.text)
        assertEquals(550, state.wpm)
        assertEquals(ReaderMode.PAUSED, state.mode)
    }

    // REQ-009: launch has to know which book to come back to, even if nothing is read.
    @Test
    fun `opening a book records it straight away`() = runTest(dispatcher) {
        openedReader()

        assertEquals(1, positions.flushes)
        assertEquals(0, positions.recorded.last().position.tokenIndex)
    }

    /**
     * The carry-forward risk from LEAF203. At 1000 WPM this path runs about
     * sixteen times a second; a flush here is a full catalog rewrite and an
     * `fsync` between two frames.
     */
    @Test
    fun `streaming words records without forcing a write`() = runTest(dispatcher) {
        // The longest run of words with nothing between them: no chapter boundary,
        // no end of book, exactly what the scheduler drives at full speed.
        val chapter = book.chapters.filter { !it.isEmpty }.maxBy { it.tokenCount }
        val reader = openedReader()
        reader.jumpToChapter(chapter.index)
        reader.togglePlay()
        val flushesBefore = positions.flushes
        val recordedBefore = positions.recorded.size

        repeat(chapter.tokenCount - 1) { reader.advance() }
        advanceUntilIdle()

        val state = reader.state.value as ReaderUiState.Reading
        assertEquals(ReaderMode.PLAYING, state.mode)
        assertEquals(chapter.tokenCount - 1, positions.recorded.size - recordedBefore)
        assertEquals("streaming must not force a write", flushesBefore, positions.flushes)
        assertEquals(chapter.endTokenIndex - 1, positions.recorded.last().position.tokenIndex)
    }

    // REQ-015's chapter pause is a stop, and a stop is a place to come back to.
    @Test
    fun `a chapter boundary is written without waiting`() = runTest(dispatcher) {
        val chapter = book.chapters.filter { !it.isEmpty }.first { it.index < book.chapters.last().index }
        val reader = openedReader()
        reader.jumpToChapter(chapter.index)
        reader.togglePlay()
        repeat(chapter.tokenCount - 1) { reader.advance() }
        val before = positions.flushes

        reader.advance()
        advanceUntilIdle()

        assertEquals(ReaderMode.CHAPTER_PAUSE, (reader.state.value as ReaderUiState.Reading).mode)
        assertEquals(before + 1, positions.flushes)
    }

    @Test
    fun `pausing, jumping and changing speed each become durable at once`() = runTest(dispatcher) {
        val reader = openedReader()
        reader.togglePlay()
        val before = positions.flushes

        reader.togglePlay()
        reader.forwardParagraph()
        reader.setWpm(600)
        reader.scrubTo(0.5f)
        advanceUntilIdle()

        assertEquals(before + 4, positions.flushes)
        assertEquals(600, positions.recorded.last().wpm)
    }

    // REQ-018: the end of the book is a place the reader comes back to.
    @Test
    fun `reaching the end of the book is written without waiting`() = runTest(dispatcher) {
        val reader = openedReader()
        reader.scrubTo(1f)
        reader.togglePlay()
        val before = positions.flushes

        reader.advance()
        advanceUntilIdle()

        val state = reader.state.value as ReaderUiState.Reading
        assertEquals(ReaderMode.FINISHED, state.mode)
        assertEquals(before + 1, positions.flushes)
        assertEquals(1f, positions.recorded.last().progressFraction, 0f)
    }

    // The definition's guardrail: a write failure is never silent.
    @Test
    fun `a store that refuses writes says so on the reading surface`() = runTest(dispatcher) {
        val reader = openedReader()
        assertNull((reader.state.value as ReaderUiState.Reading).persistenceFailure)

        positions.failureState.value = "there is no space left on the device"
        advanceUntilIdle()

        val state = reader.state.value as ReaderUiState.Reading
        assertEquals("there is no space left on the device", state.persistenceFailure)
    }

    @Test
    fun `switching books writes the one being left before it is dropped`() = runTest(dispatcher) {
        val reader = openedReader()
        reader.forwardParagraph()
        advanceUntilIdle()
        val leftAt = positions.recorded.last().position.tokenIndex
        assertTrue(leftAt > 0)

        reader.open("second")
        advanceUntilIdle()

        assertEquals(leftAt, positions.recordedFor("first").last().position.tokenIndex)
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

    private class RecordingPositions : ReaderPositions {
        var stored: ReaderPosition? = null
        var flushes = 0
        val calls = mutableListOf<Pair<String, ReaderPosition>>()
        val failureState = MutableStateFlow<String?>(null)

        val recorded: List<ReaderPosition> get() = calls.map { it.second }

        fun recordedFor(bookId: String): List<ReaderPosition> =
            calls.filter { it.first == bookId }.map { it.second }

        override val failure: StateFlow<String?> get() = failureState

        override fun restore(bookId: String): ReaderPosition? = stored

        override fun record(bookId: String, position: ReaderPosition) {
            calls += bookId to position
        }

        override fun flush() {
            flushes++
        }
    }
}
