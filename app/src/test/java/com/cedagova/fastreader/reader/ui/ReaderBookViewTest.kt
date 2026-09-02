package com.cedagova.fastreader.reader.ui

import com.cedagova.fastreader.content.BookContent
import com.cedagova.fastreader.content.Boundary
import com.cedagova.fastreader.content.Chapter
import com.cedagova.fastreader.content.ChapterTitleSource
import com.cedagova.fastreader.content.WordToken
import com.cedagova.fastreader.reader.ReaderFixtures
import com.cedagova.fastreader.reader.ReaderMode
import com.cedagova.fastreader.reader.ReaderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the reader screen is handed, derived from the state machine. */
class ReaderBookViewTest {

    private val book = ReaderFixtures.englishNovel
    private val view = ReaderBookView("The Quiet Machine", book)

    private fun at(index: Int, mode: ReaderMode = ReaderMode.PAUSED): ReaderUiState.Reading {
        val session = ReaderSession(book).jumpTo(index)
        return view.present(if (mode == ReaderMode.PLAYING) session.play() else session)
    }

    @Test
    fun `REQ-010 the paused view is the paragraph around the current word`() {
        // Paragraph 3 runs 7..16: "The machine waited patient and extraordinarily
        // quiet Ada watched it".
        val context = at(12).context!!
        assertEquals(book.tokens.subList(7, 17).map { it.displayText }, context.words)
        assertEquals("extraordinarily", context.words[context.currentOffset])
        assertFalse(context.truncatedStart)
        assertFalse(context.truncatedEnd)
    }

    @Test
    fun `a playing stream shows one word and no paragraph`() {
        val playing = at(12, ReaderMode.PLAYING)
        assertNull(playing.context)
        assertEquals("extraordinarily", playing.word.text)
        assertFalse(playing.canNavigate)
    }

    @Test
    fun `REQ-015 a skip marker is carried through as one, not as book text`() {
        val marker = at(17).word
        assertEquals("[image skipped]", marker.text)
        assertTrue(marker.isSkipMarker)
        assertFalse(at(12).word.isSkipMarker)
    }

    @Test
    fun `a gap left by an interrupted download reads as a marker too`() {
        val interrupted = ReaderFixtures.interrupted
        val gapView = ReaderBookView("Interrupted", interrupted)
        val word = gapView.present(ReaderSession(interrupted).jumpTo(6)).word
        assertEquals("[content unavailable]", word.text)
        assertTrue(word.isSkipMarker)
    }

    @Test
    fun `heading words are marked so the screen can set them apart`() {
        assertTrue(at(6).word.isHeading)
        assertFalse(at(8).word.isHeading)
    }

    @Test
    fun `the chapter is named and numbered among the chapters that have text`() {
        val state = at(12)
        assertEquals("Chapter One: The Arrival", state.chapterTitle)
        assertEquals(2, state.chapterNumber)
        assertEquals(4, state.chapterCount)
        assertEquals(
            listOf("Title Page", "Chapter One: The Arrival", "Chapter Two: The Departure", "Colophon"),
            state.chapters.map { it.title },
        )
    }

    @Test
    fun `the chapter list is one instance for the life of the book`() {
        // Publishing a new state on every word must not rebuild it; at 1000 WPM
        // that is a fresh list sixteen times a second for no reason.
        assertSame(at(3).chapters, at(31).chapters)
    }

    @Test
    fun `REQ-017 progress counts the token on screen as read`() {
        assertEquals(2, at(0).progressPercent)
        assertEquals(100, at(40).progressPercent)
        assertTrue(at(40).remainingMillis == 0L)
        assertTrue(at(0).remainingMillis > 0L)
    }

    @Test
    fun `REQ-012 the hint appears above 450 WPM and never blocks playback`() {
        val session = ReaderSession(book).play()
        assertFalse(view.present(session.withWpm(450)).showSpeedHint)
        assertTrue(view.present(session.withWpm(451)).showSpeedHint)
        assertEquals(ReaderMode.PLAYING, view.present(session.withWpm(1000)).mode)
    }

    @Test
    fun `navigation is offered whenever the stream is stopped and never while it runs`() {
        assertTrue(at(12).canNavigate)
        assertFalse(at(12, ReaderMode.PLAYING).canNavigate)
        val chapterPause = view.present(ReaderSession(book).jumpTo(4).play().advance())
        assertEquals(ReaderMode.CHAPTER_PAUSE, chapterPause.mode)
        assertTrue(chapterPause.canNavigate)
    }

    @Test
    fun `a very long paragraph is windowed and says so`() {
        val long = oneLongParagraph(400)
        val longView = ReaderBookView("Long", long)
        val middle = longView.present(ReaderSession(long).jumpTo(200)).context!!
        assertEquals(91, middle.words.size)
        assertEquals(45, middle.currentOffset)
        assertTrue(middle.truncatedStart)
        assertTrue(middle.truncatedEnd)

        val start = longView.present(ReaderSession(long).jumpTo(2)).context!!
        assertFalse(start.truncatedStart)
        assertTrue(start.truncatedEnd)
    }

    private fun oneLongParagraph(words: Int): BookContent {
        val tokens = (0 until words).map { index ->
            WordToken(
                index = index,
                text = "word$index",
                chapterIndex = 0,
                paragraphIndex = 0,
                sentenceIndex = index / 20,
                boundary = if (index == words - 1) Boundary.PARAGRAPH else Boundary.NONE,
            )
        }
        return BookContent(
            bookDigest = "sha256:long",
            language = "en",
            tokens = tokens,
            chapters = listOf(Chapter(0, "One breath", ChapterTitleSource.HEADING, 0, words, "one.xhtml")),
        )
    }
}
