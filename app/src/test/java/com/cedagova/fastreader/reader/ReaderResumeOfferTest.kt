package com.cedagova.fastreader.reader

import com.cedagova.fastreader.content.BookContent
import com.cedagova.fastreader.content.BookIdentity
import com.cedagova.fastreader.content.ContentFixtures
import com.cedagova.fastreader.content.EpubContentPipeline
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
 * REQ-511's user-facing half, at the state machine rather than on the screen: when
 * the offer is raised, what accepting and declining each do, and that it is made
 * once per remote change.
 *
 * The golden proves the banner looks right and `CatalogPositionsTest` proves which
 * positions are worth offering at all. This proves the part between them — that
 * the offer reaches the reader, that answering it settles it, and that answering
 * it *either way* is a decision the reader made rather than one this app made for
 * them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderResumeOfferTest {

    private val dispatcher = StandardTestDispatcher()
    private val positions = OfferingPositions()
    private val book = ReaderFixtures.englishNovel

    /** A chapter well past the beginning, so "ahead of the reader" is unambiguous. */
    private val target = book.chapters.last { !it.isEmpty }.startTokenIndex

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opening an account book with a place from elsewhere raises the offer`() = runTest(dispatcher) {
        positions.offer = offer(changeKey = "4:t1")

        val reader = openedReader()

        val raised = requireNotNull(reader.resumeOffer.value)
        assertEquals("acct-1", raised.accountBookId)
        assertEquals("4:t1", raised.changeKey)
        assertEquals(target, raised.targetTokenIndex)
        assertEquals(77, raised.percent)
    }

    /** No account, no position, no offer — the v1.6.0 reader, unchanged. */
    @Test
    fun `a book the account holds no place for raises nothing`() = runTest(dispatcher) {
        positions.offer = null

        assertNull(openedReader().resumeOffer.value)
    }

    /**
     * Accepting lands on the mapped token, and does it through the one writer that
     * already writes positions: the token is recorded and flushed like any jump.
     */
    @Test
    fun `accepting moves the reader to the mapped token through the existing writer`() = runTest(dispatcher) {
        positions.offer = offer(changeKey = "4:t1")
        val reader = openedReader()
        val flushesBefore = positions.flushes

        reader.acceptResumeOffer()
        advanceUntilIdle()

        assertEquals(book.tokens[target].displayText, (reader.state.value as ReaderUiState.Reading).word.text)
        assertEquals(
            "the new place is recorded by the writer, not written around it",
            target,
            positions.recorded.last().position.tokenIndex,
        )
        assertEquals("a jump is durable at once", flushesBefore + 1, positions.flushes)
        assertNull("the question has been answered", reader.resumeOffer.value)
    }

    /**
     * Declining changes nothing at all about the session: the reader stays on the
     * word they were on, and no position is written or published for the decline
     * itself.
     */
    @Test
    fun `declining keeps the local position and writes nothing`() = runTest(dispatcher) {
        positions.offer = offer(changeKey = "4:t1")
        val reader = openedReader()
        val recordedBefore = positions.recorded.size
        val publishedBefore = positions.published.size
        val wordBefore = (reader.state.value as ReaderUiState.Reading).word.text

        reader.dismissResumeOffer()
        advanceUntilIdle()

        assertEquals(wordBefore, (reader.state.value as ReaderUiState.Reading).word.text)
        assertEquals("declining is not a session change", recordedBefore, positions.recorded.size)
        assertEquals("and states nothing to the account", publishedBefore, positions.published.size)
        assertNull(reader.resumeOffer.value)
    }

    /**
     * The "once per remote change" rule, for the window the durable record cannot
     * cover: a sync landing right after the answer re-asks, and the same change
     * must not come back.
     */
    @Test
    fun `the same remote change is not offered again after either answer`() = runTest(dispatcher) {
        positions.offer = offer(changeKey = "4:t1")
        val declined = openedReader()
        declined.dismissResumeOffer()

        declined.considerResumeOffer()

        assertNull("a declined change stays declined", declined.resumeOffer.value)

        val accepted = openedReader()
        accepted.acceptResumeOffer()
        advanceUntilIdle()
        accepted.considerResumeOffer()

        assertNull("and so does an accepted one", accepted.resumeOffer.value)
    }

    /** A *newer* change from another client is a new question, not the same one. */
    @Test
    fun `a newer remote change is offered again`() = runTest(dispatcher) {
        positions.offer = offer(changeKey = "4:t1")
        val reader = openedReader()
        reader.dismissResumeOffer()

        positions.offer = offer(changeKey = "5:t2", percent = 90)
        reader.considerResumeOffer()

        val raised = requireNotNull(reader.resumeOffer.value)
        assertEquals("5:t2", raised.changeKey)
        assertEquals(90, raised.percent)
    }

    /**
     * REQ-502's position clause on a book that is already open: the change arrives
     * on an ordinary foreground sync, long after the open, and the offer still
     * appears.
     */
    @Test
    fun `a place that arrives while the book is open is still offered`() = runTest(dispatcher) {
        positions.offer = null
        val reader = openedReader()
        assertNull(reader.resumeOffer.value)

        positions.offer = offer(changeKey = "9:t9")
        reader.considerResumeOffer()

        assertNotNull(reader.resumeOffer.value)
    }

    /**
     * Reading on does not answer it, which is the one place this offer is
     * deliberately unlike the front-matter one. That one is about being on a
     * book's first word, so a single step settles it; this one is about a place
     * elsewhere, and a reader who carries on for a sentence has decided nothing.
     */
    @Test
    fun `reading on leaves the offer standing`() = runTest(dispatcher) {
        positions.offer = offer(changeKey = "4:t1")
        val reader = openedReader()

        reader.forwardSentence()
        advanceUntilIdle()

        assertNotNull(reader.resumeOffer.value)
    }

    /**
     * Opening a different book drops the answered set with it: the set keys on one
     * book's positions, so carrying it over would silence the next book's offer
     * whenever the two happened to share a change key.
     */
    @Test
    fun `switching books starts the answered set again`() = runTest(dispatcher) {
        positions.offer = offer(changeKey = "4:t1")
        val reader = openedReader()
        reader.dismissResumeOffer()

        reader.open(
            BookOpenRequest.library(
                bookId = ReaderFixtures.SECOND_BOOK_ID,
                title = "Another book entirely",
                bytes = ContentFixtures.source(FixtureBooks.bytes),
            ),
        )
        advanceUntilIdle()

        assertNotNull("a different open asks again", reader.resumeOffer.value)
    }

    /**
     * A book with no identity yet cannot resolve an account row, so no offer is
     * raised until the digest lands — and then it is (AD-8).
     */
    @Test
    fun `an external book is offered nothing until its identity lands`() = runTest(dispatcher) {
        positions.offer = offer(changeKey = "4:t1")
        val reader = ReaderViewModel(
            books = FixtureBooks,
            positions = positions,
            pipeline = EpubContentPipeline(Dispatchers.Unconfined),
            indexDispatcher = Dispatchers.Unconfined,
        )
        reader.open(
            BookOpenRequest.external(
                uri = URI,
                title = "Handed over",
                identity = null,
                origin = BookOrigin.EXTERNAL_SESSION_ONLY,
                bytes = ContentFixtures.source(FixtureBooks.bytes),
            ),
        )
        advanceUntilIdle()

        assertNull("no key, so no account row to resolve", reader.resumeOffer.value)

        reader.identityResolved(URI, BookIdentity(book.bookDigest))
        advanceUntilIdle()

        assertNotNull("the digest landed, and with it the offer", reader.resumeOffer.value)
    }

    private fun offer(changeKey: String, percent: Int = 77) = ResumeOffer(
        accountBookId = "acct-1",
        changeKey = changeKey,
        targetTokenIndex = target,
        chapterTitle = "Chapter Four: The Signal",
        percent = percent,
    )

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
        val bytes: ByteArray by lazy { ContentFixtures.englishNovel() }

        override fun libraryBook(bookId: String) = BookOpenRequest.library(
            bookId = bookId,
            title = "The Long Signal",
            bytes = ContentFixtures.source(bytes),
        )
    }

    /**
     * The seam, faked: [offer] is whatever the account currently holds, so a test
     * can change it between calls the way a foreground sync does.
     */
    private class OfferingPositions : ReaderPositions {
        var offer: ResumeOffer? = null
        var stored: ReaderPosition? = null
        var flushes = 0
        val recorded = mutableListOf<ReaderPosition>()
        val published = mutableListOf<Int>()
        private val failureState = MutableStateFlow<String?>(null)

        override val failure: StateFlow<String?> get() = failureState

        override fun restore(bookId: String): ReaderPosition? = stored

        override fun record(bookId: String, position: ReaderPosition) {
            recorded += position
        }

        override fun flush() {
            flushes++
        }

        override fun publishPortable(bookId: String, content: BookContent, tokenIndex: Int) {
            published += tokenIndex
        }

        override fun remoteOffer(bookId: String, content: BookContent, tokenIndex: Int): ResumeOffer? =
            offer?.takeIf { it.targetTokenIndex > tokenIndex }
    }

    private companion object {
        const val URI = "content://share/document/signal.epub"
    }
}
