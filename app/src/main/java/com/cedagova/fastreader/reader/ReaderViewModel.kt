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
 * session value, and the [ReaderUiState] flow the screen collects. Position is
 * in-session only — durable persistence is LEAF204.
 */
class ReaderViewModel(
    private val books: ReaderBooks,
    private val pipeline: EpubContentPipeline = EpubContentPipeline(),
    private val indexDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Opening("", null))
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var openBookId: String? = null
    private var parse: Job? = null
    private var view: ReaderBookView? = null
    private var session: ReaderSession? = null

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
                session = ReaderSession(content)
                publish()
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

    /** One word has had its full display time; the scheduler is the only caller. */
    fun advance() = update { it.advance() }

    fun setWpm(wpm: Int) = update { it.withWpm(wpm) }

    fun backSentence() = update { it.backSentence() }

    fun forwardSentence() = update { it.forwardSentence() }

    fun backParagraph() = update { it.backParagraph() }

    fun forwardParagraph() = update { it.forwardParagraph() }

    fun scrubTo(fraction: Float) = update { it.scrubTo(fraction) }

    fun jumpToChapter(chapterIndex: Int) = update { it.jumpToChapter(chapterIndex) }

    private fun update(transform: (ReaderSession) -> ReaderSession) {
        val current = session ?: return
        session = transform(current)
        publish()
    }

    private fun publish() {
        val book = view ?: return
        val current = session ?: return
        _state.value = book.present(current)
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
