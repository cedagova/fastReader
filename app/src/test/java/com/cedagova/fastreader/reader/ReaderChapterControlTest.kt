package com.cedagova.fastreader.reader

import com.cedagova.fastreader.content.ContentFixtures
import com.cedagova.fastreader.content.EpubContentPipeline
import com.cedagova.fastreader.content.TokenPosition
import com.cedagova.fastreader.reader.ui.ReaderUiState
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The two halves of #51 where they meet the open book: the chapter-pause setting
 * reaching a session (REQ-201), and the one-time front-matter offer (REQ-202).
 *
 * `ReaderSessionTest` proves what the pause does by arithmetic and
 * `FrontMatterTest` proves what detection concludes; this proves the ViewModel
 * carries both to a real parsed book — including the case a pure session test
 * cannot reach, where the setting changed while the book was still parsing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderChapterControlTest {

    private val dispatcher = StandardTestDispatcher()
    private val positions = FakePositions()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // --- REQ-201: the setting reaches the session ------------------------------

    /**
     * The window a session test cannot see: the reader turns the pause off, then
     * opens a book, and the session is built after that. Applying the setting only
     * to an *existing* session would leave this book pausing at every chapter.
     */
    @Test
    fun `a book opened after the setting changed is built with it`() = runTest(dispatcher) {
        val reader = reader()
        reader.setChapterPause(false)

        reader.openLibraryBook(BOOK_ID)
        advanceUntilIdle()

        assertEquals(ReaderMode.PLAYING, playToTheFirstBoundary(reader).mode)
    }

    @Test
    fun `turning the pause off mid-book crosses the next boundary`() = runTest(dispatcher) {
        val reader = openedReader()

        reader.setChapterPause(false)
        advanceUntilIdle()

        assertEquals(ReaderMode.PLAYING, playToTheFirstBoundary(reader).mode)
    }

    /** The default is v1's behaviour: nothing was asked for, so the chapter still holds. */
    @Test
    fun `the default still stops at a chapter`() = runTest(dispatcher) {
        val reader = openedReader()

        assertEquals(ReaderMode.CHAPTER_PAUSE, playToTheFirstBoundary(reader).mode)
    }

    // --- REQ-202: the one-time offer -------------------------------------------

    @Test
    fun `a book that opens on front matter offers its first chapter`() = runTest(dispatcher) {
        val offer = requireNotNull(openedReader().frontMatterOffer.value)

        assertEquals(BOOK_ID, offer.positionKey)
        assertEquals("Chapter One: The Approach", offer.chapterTitle)
        assertEquals(FIRST_CHAPTER_TOKEN, offer.startTokenIndex)
    }

    /** REQ-202's acceptance: the action lands on the first chapter's first word. */
    @Test
    fun `taking the offer lands on the first chapter's first word`() = runTest(dispatcher) {
        val reader = openedReader()

        reader.skipFrontMatter()
        advanceUntilIdle()

        val state = reader.state.value as ReaderUiState.Reading
        assertEquals("Chapter One: The Approach", state.chapterTitle)
        assertEquals(FIRST_CHAPTER_TOKEN, positions.recorded.last().position.tokenIndex)
        assertNull("the offer is answered", reader.frontMatterOffer.value)
    }

    /** Declining is an answer too, and it must not move the reader. */
    @Test
    fun `declining the offer leaves the reader on the first word`() = runTest(dispatcher) {
        val reader = openedReader()

        reader.dismissFrontMatterOffer()

        assertNull(reader.frontMatterOffer.value)
        assertEquals(0, positions.recorded.last().position.tokenIndex)
    }

    /** Answered once, gone for this session — a later transition cannot raise it again. */
    @Test
    fun `an answered offer is not raised again`() = runTest(dispatcher) {
        val reader = openedReader()
        reader.dismissFrontMatterOffer()

        reader.scrubTo(0.5f)
        reader.scrubTo(0f)
        advanceUntilIdle()

        assertNull(reader.frontMatterOffer.value)
    }

    /** Reading on past the first word answers it as surely as the buttons do. */
    @Test
    fun `reading on drops the offer`() = runTest(dispatcher) {
        val reader = openedReader()
        assertNotNull(reader.frontMatterOffer.value)

        reader.togglePlay()
        reader.advance()
        advanceUntilIdle()

        assertNull(reader.frontMatterOffer.value)
    }

    /**
     * The issue's edge case: a reader coming back to a book they are part way
     * through is not on a first open, and moving them would be losing their place.
     */
    @Test
    fun `a stored position past the first word raises no offer`() = runTest(dispatcher) {
        positions.stored = ReaderPosition(
            position = TokenPosition(BOOK_ID, tokenIndex = FIRST_CHAPTER_TOKEN + 2),
            progressFraction = 0.4f,
            wpm = 400,
        )

        assertNull(openedReader().frontMatterOffer.value)
    }

    @Test
    fun `a book with no front matter offers nothing`() = runTest(dispatcher) {
        val reader = reader(ContentFixtures.untitledSections())

        reader.openLibraryBook(BOOK_ID)
        advanceUntilIdle()

        assertNull(reader.frontMatterOffer.value)
    }

    /** Opening another book starts the question over: the offer belongs to a book. */
    @Test
    fun `opening a second book clears the previous book's offer`() = runTest(dispatcher) {
        val reader = openedReader()
        reader.dismissFrontMatterOffer()

        reader.openLibraryBook("sha256:${"b".repeat(64)}")
        advanceUntilIdle()

        assertNotNull("the new book asks for itself", reader.frontMatterOffer.value)
    }

    /**
     * Plays from the book's first token onto the first word of the second spine
     * item — the cover-to-title-page boundary, the first one the stream meets.
     */
    private fun TestScope.playToTheFirstBoundary(reader: ReaderViewModel): ReaderUiState.Reading {
        reader.scrubTo(0f)
        reader.togglePlay()
        repeat(FIRST_BOUNDARY_TOKEN) { reader.advance() }
        advanceUntilIdle()
        return reader.state.value as ReaderUiState.Reading
    }

    private fun TestScope.openedReader(): ReaderViewModel {
        val reader = reader()
        reader.openLibraryBook(BOOK_ID)
        advanceUntilIdle()
        return reader
    }

    private fun reader(bytes: ByteArray = frontMatterBytes) = ReaderViewModel(
        books = object : ReaderBooks {
            override fun libraryBook(bookId: String) = BookOpenRequest.library(
                bookId = bookId,
                title = "The Long Approach",
                bytes = ContentFixtures.source(bytes),
            )
        },
        positions = positions,
        pipeline = EpubContentPipeline(Dispatchers.Unconfined),
        indexDispatcher = Dispatchers.Unconfined,
    )

    private class FakePositions : ReaderPositions {
        var stored: ReaderPosition? = null
        val recorded = mutableListOf<ReaderPosition>()

        override val failure: StateFlow<String?> = MutableStateFlow(null)

        override fun restore(bookId: String): ReaderPosition? = stored

        override fun record(bookId: String, position: ReaderPosition) {
            recorded += position
        }

        override fun flush() = Unit
    }

    private companion object {
        val BOOK_ID = "sha256:a" + "0".repeat(63)

        val frontMatterBytes: ByteArray by lazy { ContentFixtures.frontMatterBook() }

        private val frontMatterBook by lazy { ReaderFixtures.parse(frontMatterBytes, BOOK_ID) }

        /** First token of "Chapter One" — what the offer lands on. */
        val FIRST_CHAPTER_TOKEN: Int by lazy { frontMatterBook.chapters[3].startTokenIndex }

        /** First token of the title page — the first boundary the stream reaches. */
        val FIRST_BOUNDARY_TOKEN: Int by lazy { frontMatterBook.chapters[1].startTokenIndex }
    }
}
