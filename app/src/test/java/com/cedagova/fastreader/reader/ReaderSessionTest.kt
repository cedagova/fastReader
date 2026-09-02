package com.cedagova.fastreader.reader

import com.cedagova.fastreader.timing.RsvpTimingEngine
import com.cedagova.fastreader.timing.TimingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader state machine, proven against a real parsed book.
 *
 * `ReaderFixtures.englishNovel` is four chapters and 41 tokens: a title page
 * (0-4), "Chapter One" (5-29) containing an `[image skipped]` marker at 17 and a
 * `[table skipped]` marker at 20, "Chapter Two" (30-35), and a colophon (36-40).
 * The indices below are that structure, so a change to LEAF201's tokenization
 * fails here loudly rather than silently moving what "back one sentence" means.
 */
class ReaderSessionTest {

    private val book = ReaderFixtures.englishNovel

    private fun session(index: Int = 0) = ReaderSession(book).jumpTo(index)

    @Test
    fun `a book opens paused on its first token`() {
        val session = ReaderSession(book)
        assertEquals(ReaderMode.PAUSED, session.mode)
        assertEquals(0, session.index)
        assertFalse(session.isPlaying)
    }

    @Test
    fun `play restarts the ramp and re-arms the re-orientation hold`() {
        val playing = session(12).play()
        assertEquals(ReaderMode.PLAYING, playing.mode)
        assertEquals(0L, playing.timing.elapsedPlaybackMillis)
        assertTrue(playing.timing.reorientationPending)
    }

    @Test
    fun `advancing feeds the shown duration back into the timing state`() {
        val playing = ReaderSession(book).play()
        val firstWord = playing.currentDurationMillis
        val next = playing.advance()
        assertEquals(firstWord, next.timing.elapsedPlaybackMillis)
        assertFalse(next.timing.reorientationPending)
    }

    @Test
    fun `REQ-014 tap to pause stops on the word that was on screen`() {
        val paused = session(23).play().pause()
        assertEquals(ReaderMode.PAUSED, paused.mode)
        assertEquals(23, paused.index)
    }

    @Test
    fun `REQ-015 crossing a chapter end pauses on the first token of the new chapter`() {
        // 4 is the last token of the title page; 5 opens "Chapter One".
        val crossed = session(4).play().advance()
        assertEquals(ReaderMode.CHAPTER_PAUSE, crossed.mode)
        assertEquals(5, crossed.index)
        assertEquals("Chapter One: The Arrival", crossed.currentChapter?.title)
        assertTrue("a fresh chapter re-orients", crossed.timing.reorientationPending)
    }

    @Test
    fun `playing on from a chapter pause does not pause again inside the chapter`() {
        val resumed = session(4).play().advance().play().advance()
        assertEquals(ReaderMode.PLAYING, resumed.mode)
        assertEquals(6, resumed.index)
    }

    @Test
    fun `pausing at a chapter boundary or the end leaves that state on screen`() {
        val chapterPause = session(4).play().advance()
        assertSame(chapterPause, chapterPause.pause())
        val finished = session(40).play().advance()
        assertSame(finished, finished.pause())
    }

    @Test
    fun `REQ-018 the last token ends the book explicitly and play does nothing`() {
        val finished = session(40).play().advance()
        assertEquals(ReaderMode.FINISHED, finished.mode)
        assertEquals(40, finished.index)
        assertSame(finished, finished.play())
    }

    @Test
    fun `REQ-014 back one sentence returns to the start of the sentence being read`() {
        // Sentence 3 runs 7..13; 12 is inside it.
        assertEquals(7, session(12).backSentence().index)
    }

    @Test
    fun `back one sentence again steps to the previous sentence`() {
        assertEquals(5, session(12).backSentence().backSentence().index)
    }

    @Test
    fun `forward one sentence lands on the next sentence's first token`() {
        assertEquals(14, session(12).forwardSentence().index)
    }

    @Test
    fun `paragraph navigation uses the paragraph run, not the sentence run`() {
        // Paragraph 3 runs 7..16 and holds two sentences.
        assertEquals(7, session(12).backParagraph().index)
        assertEquals(17, session(12).forwardParagraph().index)
        assertEquals(5, session(7).backParagraph().index)
    }

    @Test
    fun `navigating at either end of the book stays inside it`() {
        assertEquals(0, session(0).backSentence().index)
        assertEquals(0, session(0).backParagraph().index)
        assertEquals(40, session(40).forwardSentence().index)
        assertEquals(40, session(40).forwardParagraph().index)
    }

    @Test
    fun `REQ-014 the chapter picker jumps to a chapter's first token`() {
        assertEquals(30, session(3).jumpToChapter(2).index)
        assertEquals(0, session(3).jumpToChapter(0).index)
    }

    @Test
    fun `an unknown chapter is not a jump`() {
        val before = session(12)
        assertSame(before, before.jumpToChapter(99))
    }

    @Test
    fun `REQ-014 scrubbing maps a fraction of the book onto a token`() {
        assertEquals(0, session(12).scrubTo(0f).index)
        assertEquals(20, session(12).scrubTo(0.5f).index)
        assertEquals(40, session(12).scrubTo(1f).index)
        assertEquals(40, session(12).scrubTo(9f).index)
    }

    @Test
    fun `REQ-013 every jump re-arms the re-orientation hold`() {
        val warm = ReaderSession(book).play().advance().advance()
        assertFalse(warm.timing.reorientationPending)
        assertTrue(warm.backSentence().timing.reorientationPending)
        assertTrue(warm.jumpToChapter(2).timing.reorientationPending)
        assertTrue(warm.scrubTo(0.25f).timing.reorientationPending)
    }

    @Test
    fun `a jump while playing keeps playing and a jump while stopped does not start`() {
        assertEquals(ReaderMode.PLAYING, session(12).play().backSentence().mode)
        assertEquals(ReaderMode.PAUSED, session(12).backSentence().mode)
        val finished = session(40).play().advance()
        assertEquals(ReaderMode.PAUSED, finished.scrubTo(0f).mode)
    }

    @Test
    fun `REQ-012 speed changes mid-stream without stopping and stays inside the range`() {
        val playing = session(12).play()
        val faster = playing.withWpm(600)
        assertEquals(ReaderMode.PLAYING, faster.mode)
        assertEquals(600, faster.settings.effectiveWpm)
        assertEquals(1000, playing.withWpm(4000).settings.effectiveWpm)
        assertEquals(100, playing.withWpm(1).settings.effectiveWpm)
    }

    @Test
    fun `a faster stream shows each word for less time`() {
        val slow = session(12).play().withWpm(250)
        val fast = slow.withWpm(1000)
        assertTrue(fast.currentDurationMillis < slow.currentDurationMillis)
    }

    @Test
    fun `REQ-011 a sentence-final word is held longer than a plain one`() {
        // 13 ends a sentence; 11 ("and") is as plain as a word gets — 12 is long
        // enough to carry the emphasis multiplier of its own. Warmed up, so the
        // ramp is out of it.
        val warm = TimingSettings()
        val plain = RsvpTimingEngine.durationMillis(book.tokens[11], warm, steady())
        val sentenceEnd = RsvpTimingEngine.durationMillis(book.tokens[13], warm, steady())
        assertTrue("$sentenceEnd should be about 3x $plain", sentenceEnd > plain * 2.5)
    }

    @Test
    fun `a skip marker is a position like any other`() {
        val marker = session(17)
        assertEquals("[image skipped]", marker.currentToken.displayText)
        assertTrue(marker.currentDurationMillis > 0)
        assertEquals(18, marker.play().advance().index)
    }

    private fun steady() = ReaderSession(book).play().advance().timing.copy(elapsedPlaybackMillis = 60_000L)
}
