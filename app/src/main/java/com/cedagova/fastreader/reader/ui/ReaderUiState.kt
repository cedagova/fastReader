package com.cedagova.fastreader.reader.ui

import com.cedagova.fastreader.content.BookContent
import com.cedagova.fastreader.content.SkipMarkerToken
import com.cedagova.fastreader.content.Token
import com.cedagova.fastreader.content.WordToken
import com.cedagova.fastreader.reader.ReaderMode
import com.cedagova.fastreader.reader.ReaderSession
import com.cedagova.fastreader.reader.RemainingTimeIndex
import com.cedagova.fastreader.timing.PauseStrength
import kotlin.math.roundToInt

/**
 * Everything the reader screen draws, and nothing else.
 *
 * Plain Kotlin with no Android or Compose types, derived from a [ReaderSession]
 * by [ReaderBookView.present]. The screen is a function of this value, which is
 * what lets the Roborazzi goldens reach every state the reader can be in —
 * including ones that are awkward to reach on a device, such as the end of a book
 * or a mid-book content gap — and what keeps playback semantics testable without
 * a renderer.
 */
sealed interface ReaderUiState {

    /** Always known: the catalog has the title before the book is parsed. */
    val bookTitle: String

    /**
     * The book is being parsed. LEAF201 reports one step per spine item and runs
     * off the main thread, so the app stays responsive while a large EPUB opens.
     */
    data class Opening(
        override val bookTitle: String,
        /** Determinate fraction, or null before the spine count is known. */
        val fraction: Float?,
    ) : ReaderUiState

    /** The book could not be turned into a stream; [message] is LEAF201's plain-language reason. */
    data class Unavailable(
        override val bookTitle: String,
        val message: String,
    ) : ReaderUiState

    /** The reader proper. */
    data class Reading(
        override val bookTitle: String,
        val mode: ReaderMode,
        /** The token on screen — one word, or a skip marker (REQ-015). */
        val word: ReaderWord,
        /**
         * The paragraph around the current word, with that word marked (REQ-010).
         * Non-null exactly when the stream is stopped, because it is what the
         * reader reads to pick the thread back up.
         */
        val context: ReaderContext?,
        val chapterTitle: String,
        /** 1-based, counting only chapters that produced text. */
        val chapterNumber: Int,
        val chapterCount: Int,
        /**
         * The chapter picker's rows. The same instance for the life of a book, so
         * publishing a new state on every word stays allocation-free here.
         */
        val chapters: List<ChapterEntry>,
        val progressPercent: Int,
        val progressFraction: Float,
        /** Time left at the current speed (REQ-017). */
        val remainingMillis: Long,
        val wpm: Int,
        /** Non-blocking comprehension hint above ~450 WPM (REQ-012). */
        val showSpeedHint: Boolean,
        /**
         * Why the reader's place is not being saved, or null when it is.
         *
         * The definition's persistence guardrail is that a write failure is never
         * silent. The library has its own banner for this, but a reader mid-book
         * is not looking at the library, so the reading surface carries it too.
         */
        val persistenceFailure: String? = null,
    ) : ReaderUiState {

        /** True while the stream is stopped, whatever stopped it. */
        val isStopped: Boolean get() = mode != ReaderMode.PLAYING

        /** In-book navigation is offered while stopped (REQ-014), never mid-stream. */
        val canNavigate: Boolean get() = isStopped
    }
}

/** One token as the screen draws it. The presentation slot LEAF301 replaces consumes this. */
data class ReaderWord(
    val text: String,
    /** A skip marker rather than book text, so it can be set apart (REQ-015). */
    val isSkipMarker: Boolean = false,
    /** Part of an `<h1>`–`<h6>`. */
    val isHeading: Boolean = false,
)

/** The paused context view: the current paragraph, and which token in it is current. */
data class ReaderContext(
    val words: List<String>,
    /** Index into [words] of the token the reader is on. */
    val currentOffset: Int,
    /** The paragraph continues before the first shown token. */
    val truncatedStart: Boolean = false,
    /** The paragraph continues after the last shown token. */
    val truncatedEnd: Boolean = false,
)

/** One row of the chapter picker (REQ-014). */
data class ChapterEntry(
    val chapterIndex: Int,
    val title: String,
)

/**
 * The per-book half of the screen state: everything that depends on the parsed
 * book but not on where the reader is in it.
 *
 * It exists for one reason. [present] runs on every word — sixteen times a second
 * at the 1000 WPM ceiling — inside the frame budget the "no visible stutter"
 * guardrail is measured against, so anything proportional to book length has to
 * happen once, here, on the parsing thread rather than on each word. That is the
 * time-remaining index (which would otherwise be a full sweep of the book per
 * word) and the chapter list (which would otherwise be a fresh list per word).
 */
class ReaderBookView(
    val bookTitle: String,
    content: BookContent,
    pauseStrength: PauseStrength = PauseStrength.NORMAL,
) {

    private val remaining = RemainingTimeIndex.build(content, pauseStrength)

    /** Chapters that produced text; an empty spine item is not a place to jump to. */
    private val readableChapters = content.chapters.filter { !it.isEmpty }

    private val chapterEntries = readableChapters.map { ChapterEntry(it.index, it.title) }

    /** Builds the screen state. Pure: the same session always gives the same screen. */
    fun present(session: ReaderSession, persistenceFailure: String? = null): ReaderUiState.Reading {
        val chapter = session.currentChapter
        return ReaderUiState.Reading(
            bookTitle = bookTitle,
            mode = session.mode,
            word = session.currentToken.toReaderWord(),
            context = if (session.mode == ReaderMode.PLAYING) null else session.contextView(),
            chapterTitle = chapter?.title.orEmpty(),
            chapterNumber = chapter?.let { readableChapters.indexOfFirst { c -> c.index == it.index } + 1 } ?: 0,
            chapterCount = readableChapters.size,
            chapters = chapterEntries,
            progressPercent = (session.progressFraction * 100).roundToInt(),
            progressFraction = session.progressFraction,
            remainingMillis = remaining.millisAfter(session.index, session.settings),
            wpm = session.settings.effectiveWpm,
            showSpeedHint = session.settings.effectiveWpm > SPEED_HINT_WPM,
            persistenceFailure = persistenceFailure,
        )
    }
}

/**
 * The paused view shows the paragraph the reader stopped in. A single paragraph
 * can run to several hundred words, which no phone screen shows and no reader
 * needs, so it is windowed around the current token and the screen says the
 * paragraph continues.
 */
private const val CONTEXT_RADIUS = 45

/** Above this speed the reader is told, without being stopped, what the research says (REQ-012). */
const val SPEED_HINT_WPM = 450

private fun Token.toReaderWord(): ReaderWord = when (this) {
    is WordToken -> ReaderWord(text = text, isHeading = isHeading)
    is SkipMarkerToken -> ReaderWord(text = label, isSkipMarker = true)
    else -> ReaderWord(text = displayText)
}

private fun ReaderSession.contextView(): ReaderContext {
    val paragraph = runBounds(Token::paragraphIndex)
    val from = maxOf(paragraph.first, index - CONTEXT_RADIUS)
    val to = minOf(paragraph.last, index + CONTEXT_RADIUS)
    return ReaderContext(
        words = (from..to).map { content.tokens[it].displayText },
        currentOffset = index - from,
        truncatedStart = from > paragraph.first,
        truncatedEnd = to < paragraph.last,
    )
}
