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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds one open book for as long as the reader is on screen.
 *
 * A `ViewModel` rather than composition state for one concrete reason: rotating
 * the phone destroys and recreates the activity, and re-parsing a novel every
 * time it turns would be both slow and a lost reading position. Surviving the
 * configuration change here is what makes "rotation preserves position and
 * playback state" true.
 *
 * Everything interesting is delegated: [ReaderSession] owns playback semantics,
 * [ReaderBookView] owns the screen state, and this class owns only the parse, the
 * current session value, and the [ReaderUiState] flow the screen collects.
 * Position is in-session only — durable persistence is LEAF204.
 */
class ReaderViewModel(
    private val bookTitle: String,
    private val bytes: EpubByteSource,
    private val pipeline: EpubContentPipeline = EpubContentPipeline(),
    private val indexDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Opening(bookTitle, null))
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var view: ReaderBookView? = null
    private var session: ReaderSession? = null

    /** How long the token on screen is shown, or null when nothing is streaming. */
    val currentDurationMillis: Long? get() = session?.takeIf { it.isPlaying }?.currentDurationMillis

    init {
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        val result = pipeline.parse(bytes) { progress ->
            _state.value = ReaderUiState.Opening(bookTitle, progress.fraction.takeIf { progress.totalItems > 0 })
        }
        when (result) {
            is BookContentResult.Failed ->
                _state.value = ReaderUiState.Unavailable(bookTitle, result.message())

            is BookContentResult.Parsed -> {
                val content = result.content
                // Building the time-remaining index is one sweep of the book; it
                // belongs on the parsing thread, next to the parse, not on the
                // first frame of the reader.
                val book = withContext(indexDispatcher) {
                    ReaderBookView(bookTitle, content, PauseStrength.NORMAL)
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
