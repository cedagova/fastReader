package com.cedagova.fastreader.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cedagova.fastreader.content.BookContentResult
import com.cedagova.fastreader.content.ContentFailureReason
import com.cedagova.fastreader.content.EpubContentPipeline
import com.cedagova.fastreader.epub.EpubByteSource
import com.cedagova.fastreader.reader.ui.ReaderBookView
import com.cedagova.fastreader.reader.ui.ReaderUiState
import com.cedagova.fastreader.timing.PauseStrength
import com.cedagova.fastreader.timing.RsvpTiming
import com.cedagova.fastreader.timing.TimingSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How the reader gets at a book, so the ViewModel does not need the catalog's whole API. */
interface ReaderBooks {

    /** The catalog's title for a book, available before it is parsed. */
    fun title(bookId: String): String

    /** The book's bytes, read in place (AD-1). */
    fun bytes(bookId: String): EpubByteSource
}

/**
 * Holds the open book for as long as the reader is on screen.
 *
 * A `ViewModel` rather than composition state for one concrete reason: rotating
 * the phone destroys and recreates the activity, and re-parsing a novel every time
 * it turns would be both slow and a lost reading position. Surviving the
 * configuration change here is what makes "rotation preserves position and
 * playback state" true.
 *
 * **Exactly one book at a time.** A parsed novel is the largest thing this app
 * holds in memory, and a `ViewModel` lives until its activity is destroyed, not
 * until the composable that created it goes away. Keying one per book id would
 * therefore keep every book opened in a session resident — which the 2 GB device
 * in the test matrix will not forgive. [open] instead replaces the current book,
 * dropping the previous token stream and cancelling a parse still in flight.
 *
 * Everything interesting is delegated: [ReaderSession] owns playback semantics and
 * [ReaderBookView] owns the screen state. This class owns the parse, the current
 * session value, and the [ReaderUiState] flow the screen collects.
 *
 * ## Durability (LEAF204)
 *
 * Every state change funnels through [update], which is therefore the one place
 * position and speed reach [ReaderPositions]. Two kinds of change, two costs:
 *
 * - [advance] — the next word, up to sixteen times a second. It only *records*:
 *   an in-memory note that the writer coalesces into at most two writes a second,
 *   so no durable write ever lands between two frames of a running stream.
 * - everything else — pausing, jumping, changing speed, a chapter pause, the end
 *   of the book, losing the foreground, closing the book. Each is a discrete act
 *   at human frequency, so each is flushed immediately and is durable before the
 *   reader can do anything else.
 *
 * The only exposure left is a kill of a *foreground* process mid-stream, which
 * Android gives no callback for; the writer's interval bounds it.
 */
class ReaderViewModel(
    private val books: ReaderBooks,
    private val positions: ReaderPositions,
    private val pipeline: EpubContentPipeline = EpubContentPipeline(),
    private val indexDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Opening("", null))
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var openBookId: String? = null
    private var parse: Job? = null
    private var view: ReaderBookView? = null
    private var session: ReaderSession? = null
    private var persistenceFailure: String? = null

    init {
        // A failing store must be visible on the reading surface, not only on the
        // library's banner: this is where the reader is when their place is lost.
        viewModelScope.launch {
            positions.failure.collect { message ->
                persistenceFailure = message
                publish()
            }
        }
    }

    /** How long the token on screen is shown, or null when nothing is streaming. */
    val currentDurationMillis: Long? get() = session?.takeIf { it.isPlaying }?.currentDurationMillis

    /**
     * Opens [bookId], unless it is already open.
     *
     * Idempotent on purpose: the reader calls it on every composition, and after a
     * rotation that call must find the book already parsed and the position intact.
     */
    fun open(bookId: String) {
        if (bookId == openBookId) return
        // The book being left has to become durable before its session is dropped.
        if (openBookId != null) persist(flush = true)
        openBookId = bookId
        parse?.cancel()
        view = null
        session = null
        val title = books.title(bookId)
        _state.value = ReaderUiState.Opening(title, null)
        parse = viewModelScope.launch { parse(bookId, title) }
    }

    private suspend fun parse(bookId: String, title: String) {
        val result = pipeline.parse(books.bytes(bookId)) { progress ->
            _state.value = ReaderUiState.Opening(title, progress.fraction.takeIf { progress.totalItems > 0 })
        }
        when (result) {
            is BookContentResult.Failed ->
                _state.value = ReaderUiState.Unavailable(title, result.message())

            is BookContentResult.Parsed -> {
                val content = result.content
                // Building the time-remaining index is one sweep of the book; it
                // belongs on the parsing thread, next to the parse, not on the
                // first frame of the reader.
                val book = withContext(indexDispatcher) {
                    ReaderBookView(title, content, PauseStrength.NORMAL)
                }
                view = book
                // Resuming lands paused, on the stored word, at the stored speed
                // (REQ-010, REQ-016). A book never opens playing.
                val stored = positions.restore(bookId)
                session = ReaderSession(
                    content = content,
                    index = stored?.resolveIndex(content) ?: 0,
                    settings = TimingSettings(wpm = stored?.wpm ?: RsvpTiming.DEFAULT_WPM),
                )
                publish()
                // Opening a book is what makes it the last-read one, so launch can
                // come back to it (REQ-009) even if nothing is read this session.
                persist(flush = true)
            }
        }
    }

    fun togglePlay() = update { if (it.isPlaying) it.pause() else it.play() }

    /**
     * REQ-071: the app is no longer in the foreground, so the stream stops on the
     * word that was on screen. Deliberately not [togglePlay]: coming back must not
     * start playing again by itself.
     */
    fun pauseForBackground() = update { it.pause() }

    /**
     * One word has had its full display time; the scheduler is the only caller.
     *
     * The only transition that does not force a write. A chapter pause or the end
     * of the book still does, because both stop the stream on a word the reader
     * will come back to.
     */
    fun advance() = update(flush = false) { it.advance() }

    fun setWpm(wpm: Int) = update { it.withWpm(wpm) }

    fun backSentence() = update { it.backSentence() }

    fun forwardSentence() = update { it.forwardSentence() }

    fun backParagraph() = update { it.backParagraph() }

    fun forwardParagraph() = update { it.forwardParagraph() }

    fun scrubTo(fraction: Float) = update { it.scrubTo(fraction) }

    fun jumpToChapter(chapterIndex: Int) = update { it.jumpToChapter(chapterIndex) }

    /** Everything the reader does, so persistence has exactly one place to sit. */
    private fun update(flush: Boolean = true, transform: (ReaderSession) -> ReaderSession) {
        val current = session ?: return
        val next = transform(current)
        session = next
        publish()
        // A stream that stopped itself — a chapter boundary, the end of the book —
        // is a place the reader returns to, so it is made durable like a tap.
        persist(flush = flush || !next.isPlaying)
    }

    private fun persist(flush: Boolean) {
        val bookId = openBookId ?: return
        val current = session ?: return
        positions.record(bookId, current.toPosition())
        if (flush) positions.flush()
    }

    /** Leaving the reader for good; the last word read must not depend on timing. */
    override fun onCleared() {
        persist(flush = true)
        super.onCleared()
    }

    private fun publish() {
        val book = view ?: return
        val current = session ?: return
        _state.value = book.present(current, persistenceFailure)
    }

    private fun BookContentResult.Failed.message(): String = when (reason) {
        ContentFailureReason.UNREADABLE_SOURCE ->
            "The file could not be read: $detail"

        ContentFailureReason.CORRUPT_ARCHIVE ->
            "This file is damaged and is not a readable EPUB: $detail"

        ContentFailureReason.INVALID_STRUCTURE ->
            "This file is not a usable EPUB: $detail"

        ContentFailureReason.NO_READABLE_CONTENT ->
            "There is no text to read in this book: $detail"
    }
}
