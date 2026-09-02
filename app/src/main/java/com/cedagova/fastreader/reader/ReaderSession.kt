package com.cedagova.fastreader.reader

import com.cedagova.fastreader.content.BookContent
import com.cedagova.fastreader.content.Chapter
import com.cedagova.fastreader.content.Token
import com.cedagova.fastreader.timing.PauseStrength
import com.cedagova.fastreader.timing.RsvpTiming
import com.cedagova.fastreader.timing.RsvpTimingEngine
import com.cedagova.fastreader.timing.TimingSettings
import com.cedagova.fastreader.timing.TimingState

/**
 * The reader's state machine (LEAF203): where in the book we are, whether the
 * stream is running, and what the next token's display time is.
 *
 * It is an immutable value with pure transitions and no Android, Compose, clock
 * or coroutine anywhere, for the same reason the timing engine is (AD-5):
 * playback, the paused context view and in-book navigation are one state machine,
 * and the interesting behavior — what "back one sentence" means, when a chapter
 * boundary pauses, what happens when you jump while playing — is worth proving by
 * arithmetic instead of by watching words go past.
 *
 * ## Separation from presentation
 *
 * This type knows nothing about how a token is drawn. The screen renders
 * [com.cedagova.fastreader.reader.ui.ReaderUiState], which is derived from a
 * session by a pure function, and the word itself is drawn through a slot the
 * screen exposes. That is the seam LEAF301 restyles (pivot alignment, guide
 * marks, focused mode) without touching a line of playback semantics.
 *
 * ## Contract with the scheduler
 *
 * The scheduler owns wall-clock time and nothing else:
 *
 * 1. read [currentDurationMillis] — how long the token now on screen is shown;
 * 2. when that much time has passed, call [advance];
 * 3. repeat while [isPlaying].
 *
 * [advance] feeds the exact duration it used back into [TimingState.afterShowing],
 * so the ramp the timing engine computes and the deadlines the scheduler runs on
 * describe the same timeline (LEAF202's contract, point 2).
 */
data class ReaderSession(
    val content: BookContent,
    val index: Int = 0,
    val mode: ReaderMode = ReaderMode.PAUSED,
    val settings: TimingSettings = TimingSettings(),
    val timing: TimingState = TimingState.AT_PLAYBACK_START,
) {

    init {
        require(!content.isEmpty) { "a book with no tokens cannot be read" }
    }

    val isPlaying: Boolean get() = mode == ReaderMode.PLAYING

    /** The token currently on screen. Always valid: [index] is clamped on every transition. */
    val currentToken: Token get() = content.tokens[index]

    /** The chapter [index] falls in, or null for a book whose spine produced no chapters. */
    val currentChapter: Chapter? get() = content.chapterAt(index)

    /** How long [currentToken] is shown, from LEAF202. The scheduler's deadline. */
    val currentDurationMillis: Long
        get() = RsvpTimingEngine.durationMillis(currentToken, settings, timing)

    /** Fraction of the book already shown, `0f..1f` (REQ-017). */
    val progressFraction: Float get() = content.progressFraction(index)

    /** Starts or resumes the stream. Finishing the book is terminal until the reader navigates. */
    fun play(): ReaderSession =
        if (mode == ReaderMode.FINISHED) {
            this
        } else {
            // AT_PLAYBACK_START restarts the ramp at 80% and re-arms the
            // re-orientation hold, which is exactly REQ-013's "after any resume".
            copy(mode = ReaderMode.PLAYING, timing = TimingState.AT_PLAYBACK_START)
        }

    /**
     * Stops the stream on the word currently on screen (REQ-014's tap-to-pause and
     * REQ-071's foreground-loss pause are the same transition).
     *
     * A chapter pause and the end state are already stopped, and pausing must not
     * quietly turn either of them into an ordinary pause: the chapter title and
     * the end-of-book state stay on screen.
     */
    fun pause(): ReaderSession = if (mode == ReaderMode.PLAYING) copy(mode = ReaderMode.PAUSED) else this

    /**
     * Moves to the next token because the current one has had its full display
     * time. The only transition the scheduler drives.
     *
     * Two things end a run: the last token of the book (REQ-018's explicit end
     * state) and crossing into a new chapter (REQ-015's auto-pause on a titled
     * screen). Both leave [index] on the token the reader should see.
     */
    fun advance(): ReaderSession {
        val shown = currentDurationMillis
        val next = index + 1
        if (next > content.tokens.lastIndex) {
            return copy(mode = ReaderMode.FINISHED)
        }
        if (content.tokens[next].chapterIndex != currentToken.chapterIndex) {
            // The new chapter's first word is on screen but held: play() resumes
            // from it with the re-orientation hold a fresh chapter deserves. The
            // token that ended the previous chapter still counts towards the ramp
            // clock, like every other token that was actually shown.
            return copy(
                index = next,
                mode = ReaderMode.CHAPTER_PAUSE,
                timing = timing.afterShowing(shown).reorienting(),
            )
        }
        return copy(index = next, timing = timing.afterShowing(shown))
    }

    /**
     * Moves to [tokenIndex] and re-arms the re-orientation hold (REQ-013).
     *
     * A jump out of a chapter pause or the end state lands in an ordinary pause;
     * a jump while playing keeps playing, because the speed-independent scrub is
     * a navigation action and not a stop.
     */
    fun jumpTo(tokenIndex: Int): ReaderSession = copy(
        index = tokenIndex.coerceIn(0, content.tokens.lastIndex),
        mode = if (mode == ReaderMode.PLAYING) ReaderMode.PLAYING else ReaderMode.PAUSED,
        timing = timing.reorienting(),
    )

    /**
     * Back one sentence (REQ-014).
     *
     * Mid-sentence this is the start of the sentence being read, which is what the
     * requirement's acceptance asks for — tap to pause, go back, play, and hear
     * that sentence again. Pressing it again from a sentence start steps to the
     * previous sentence rather than doing nothing.
     */
    fun backSentence(): ReaderSession = jumpTo(previousRunStart(Token::sentenceIndex))

    fun forwardSentence(): ReaderSession = jumpTo(nextRunStart(Token::sentenceIndex))

    /** Back one paragraph, with the same "start of the current one first" rule as [backSentence]. */
    fun backParagraph(): ReaderSession = jumpTo(previousRunStart(Token::paragraphIndex))

    fun forwardParagraph(): ReaderSession = jumpTo(nextRunStart(Token::paragraphIndex))

    /** Jumps to the first token of [chapterIndex] — the chapter picker (REQ-014). */
    fun jumpToChapter(chapterIndex: Int): ReaderSession {
        val chapter = content.chapters.firstOrNull { it.index == chapterIndex && !it.isEmpty } ?: return this
        return jumpTo(chapter.startTokenIndex)
    }

    /** Scrubs to a fraction of the book, `0f..1f` (REQ-014). */
    fun scrubTo(fraction: Float): ReaderSession {
        val target = (fraction.coerceIn(0f, 1f) * content.tokens.lastIndex).toInt()
        return jumpTo(target)
    }

    /**
     * Changes speed mid-stream (REQ-012).
     *
     * Nothing is restarted: durations are a pure function of the settings value,
     * so the very next deadline uses the new speed and the stream never stops.
     */
    fun withWpm(wpm: Int): ReaderSession =
        copy(settings = settings.copy(wpm = wpm.coerceIn(RsvpTiming.MIN_WPM, RsvpTiming.MAX_WPM)))

    /**
     * Changes how much extra pause boundaries get (REQ-011), without moving the
     * reader or stopping the stream.
     *
     * Like [withWpm] this only replaces settings: the ramp clock and the
     * re-orientation flag are untouched, so adjusting pause strength mid-stream
     * changes the very next word's duration and nothing else.
     */
    fun withPauseStrength(pauseStrength: PauseStrength): ReaderSession =
        copy(settings = settings.copy(pauseStrength = pauseStrength))

    /** The token run [index] belongs to, as `start..end` inclusive — the paused context view. */
    fun runBounds(ordinal: (Token) -> Int): IntRange {
        val target = ordinal(currentToken)
        var start = index
        while (start > 0 && ordinal(content.tokens[start - 1]) == target) start--
        var end = index
        while (end < content.tokens.lastIndex && ordinal(content.tokens[end + 1]) == target) end++
        return start..end
    }

    private inline fun previousRunStart(ordinal: (Token) -> Int): Int {
        val start = firstOfRun(index, ordinal)
        if (start < index) return start
        if (start == 0) return 0
        return firstOfRun(start - 1, ordinal)
    }

    private inline fun nextRunStart(ordinal: (Token) -> Int): Int {
        val target = ordinal(currentToken)
        var i = index
        while (i < content.tokens.lastIndex && ordinal(content.tokens[i + 1]) == target) i++
        return minOf(i + 1, content.tokens.lastIndex)
    }

    private inline fun firstOfRun(from: Int, ordinal: (Token) -> Int): Int {
        val target = ordinal(content.tokens[from])
        var start = from
        while (start > 0 && ordinal(content.tokens[start - 1]) == target) start--
        return start
    }
}

/** The four states the reader surface can be in once a book is open. */
enum class ReaderMode {
    /** Stopped, showing the surrounding paragraph with the current word highlighted (REQ-010). */
    PAUSED,

    /** Streaming one word at a time (REQ-011). */
    PLAYING,

    /** Stopped on the first token of a new chapter, showing its title (REQ-015). */
    CHAPTER_PAUSE,

    /** The last token of the book has been shown (REQ-018). */
    FINISHED,
}
