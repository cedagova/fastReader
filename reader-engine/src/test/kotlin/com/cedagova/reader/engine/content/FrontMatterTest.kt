package com.cedagova.fastreader.content

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * REQ-202's detection half: whether a book opens on front matter, and where its
 * body starts.
 *
 * Two layers, proven at the two levels they live at. [FrontMatterDetector] is a
 * pure function over chapter titles, so its rules — including every case that
 * must produce *no* offer — are arithmetic here. The pipeline tests below then
 * prove the two declarations are actually read out of a real archive, through
 * the same fixture the acceptance criterion names: a spine that starts with
 * cover, title and copyright pages.
 *
 * The no-offer cases outnumber the offer cases deliberately. A missed offer costs
 * a reader three taps; a wrong one drops them past the opening of a book they
 * have just started.
 */
class FrontMatterTest {

    // --- the detector's rules -------------------------------------------------

    @Test
    fun `a declared body start is taken as the book states it`() {
        val front = FrontMatterDetector.detect(
            chapters = chapters("Cover", "Title Page", "Chapter One"),
            declaredBodyPath = "OEBPS/c2.xhtml",
            declaredSource = FrontMatterSource.LANDMARKS,
        )

        assertEquals(2, front?.firstBodyChapterIndex)
        assertEquals(FrontMatterSource.LANDMARKS, front?.source)
        assertEquals("Chapter One", front?.bodyChapterTitle)
    }

    /**
     * A declaration this parse cannot place — a landmark pointing at a file that
     * is not in the spine — is not evidence of anything, and must not silently
     * fall back to guessing from titles.
     */
    @Test
    fun `a declared body start that names no spine item makes no offer`() {
        val front = FrontMatterDetector.detect(
            chapters = chapters("Cover", "Title Page", "Chapter One"),
            declaredBodyPath = "OEBPS/nowhere.xhtml",
            declaredSource = FrontMatterSource.GUIDE,
        )

        assertNull(front)
    }

    /** The book already opens on its body: there is nothing to skip. */
    @Test
    fun `a declared body start at the first chapter makes no offer`() {
        val front = FrontMatterDetector.detect(
            chapters = chapters("Chapter One", "Chapter Two"),
            declaredBodyPath = "OEBPS/c0.xhtml",
            declaredSource = FrontMatterSource.LANDMARKS,
        )

        assertNull(front)
    }

    /** Landing the reader in a spine item that produced no words would be a jump into nothing. */
    @Test
    fun `a declared body start with no text makes no offer`() {
        val chapters = listOf(
            chapter(0, "Cover", tokens = 4),
            chapter(1, "Chapter One", tokens = 0),
        )

        val front = FrontMatterDetector.detect(chapters, "OEBPS/c1.xhtml", FrontMatterSource.LANDMARKS)

        assertNull(front)
    }

    @Test
    fun `the title heuristic skips a cover, a title page and a copyright page`() {
        val front = FrontMatterDetector.detect(
            chapters("Cover", "Title Page", "Copyright", "Chapter One", "Chapter Two"),
        )

        assertEquals(3, front?.firstBodyChapterIndex)
        assertEquals(FrontMatterSource.TITLES, front?.source)
    }

    /** The same list in Spanish, which this app treats as an ordinary case (D5). */
    @Test
    fun `the title heuristic reads a Spanish book's front matter too`() {
        val front = FrontMatterDetector.detect(
            chapters("Cubierta", "Portada", "Créditos", "Capítulo uno"),
        )

        assertEquals(3, front?.firstBodyChapterIndex)
    }

    /** Decoration a typesetter adds does not make a title unrecognisable. */
    @Test
    fun `a decorated front-matter title still counts`() {
        val front = FrontMatterDetector.detect(chapters("— CONTENTS —", "Chapter One"))

        assertEquals(1, front?.firstBodyChapterIndex)
    }

    @Test
    fun `a book that opens on its first chapter is offered nothing`() {
        assertNull(FrontMatterDetector.detect(chapters("Chapter One", "Chapter Two")))
    }

    /**
     * The expensive mistake, and the reason the walk fails closed. `Copyright ©
     * 2026 Ada Fielding` is not in the closed list, so a naive walk would call it
     * the body and offer to land the reader on the copyright page.
     */
    @Test
    fun `a title that only resembles front matter ends the walk with no offer`() {
        assertNull(FrontMatterDetector.detect(chapters("Cover", "Copyright © 2026 Ada Fielding", "Chapter One")))
    }

    /** "Section 4" is the pipeline's own invention, so it is evidence of nothing either way. */
    @Test
    fun `positional fallback titles make no offer`() {
        val chapters = listOf(
            chapter(0, "Section 1", source = ChapterTitleSource.FALLBACK),
            chapter(1, "Section 2", source = ChapterTitleSource.FALLBACK),
        )

        assertNull(FrontMatterDetector.detect(chapters))
    }

    /** A book that is all front matter has no body to offer. */
    @Test
    fun `a book of nothing but front matter makes no offer`() {
        assertNull(FrontMatterDetector.detect(chapters("Cover", "Title Page", "Copyright")))
    }

    /**
     * Past [FrontMatterDetector.MAX_FRONT_MATTER_CHAPTERS] the run is more likely
     * to be a book this list happens to match than a book with that many covers.
     */
    @Test
    fun `an implausibly long run of front matter makes no offer`() {
        val titles = List(FrontMatterDetector.MAX_FRONT_MATTER_CHAPTERS + 1) { "Cover" } + "Chapter One"

        assertNull(FrontMatterDetector.detect(chapters(*titles.toTypedArray())))
    }

    /** Preface, foreword and introduction are the author's words, not packaging. */
    @Test
    fun `an introduction is not treated as front matter`() {
        val front = FrontMatterDetector.detect(chapters("Cover", "Introduction", "Chapter One"))

        assertEquals(1, front?.firstBodyChapterIndex)
    }

    @Test
    fun `a book with no chapters at all makes no offer`() {
        assertNull(FrontMatterDetector.detect(emptyList()))
    }

    // --- the pipeline reads the declarations out of a real archive -------------

    /**
     * The acceptance fixture with nothing declared: the offer has to come from
     * the chapter titles the navigation document supplies.
     */
    @Test
    fun `a book with cover, title and copyright pages offers its first chapter`() {
        val content = parse(ContentFixtures.frontMatterBook())

        val front = requireNotNull(content.frontMatter)
        assertEquals(FrontMatterSource.TITLES, front.source)
        assertEquals(3, front.firstBodyChapterIndex)
        assertEquals("Chapter One: The Approach", front.bodyChapterTitle)
        assertEquals(content.chapters[3].startTokenIndex, front.startTokenIndex)
        // REQ-202's acceptance: the action lands on the first chapter's first word.
        assertEquals("Chapter", content.tokens[front.startTokenIndex].displayText)
    }

    /** The same book stating where its body begins, EPUB 3 style. */
    @Test
    fun `an EPUB 3 landmarks bodymatter entry is read through its fragment`() {
        val front = requireNotNull(parse(ContentFixtures.frontMatterBook(landmarks = true)).frontMatter)

        assertEquals(FrontMatterSource.LANDMARKS, front.source)
        assertEquals(3, front.firstBodyChapterIndex)
    }

    /** And EPUB 2 style, where the package guide says it instead. */
    @Test
    fun `an EPUB 2 guide text reference is read when there is no landmark`() {
        val front = requireNotNull(parse(ContentFixtures.frontMatterBook(guide = true)).frontMatter)

        assertEquals(FrontMatterSource.GUIDE, front.source)
        assertEquals(3, front.firstBodyChapterIndex)
    }

    /** The v1 fixture opens on a title page but has only one page of it — still an offer. */
    @Test
    fun `an ordinary book with no front matter carries no offer`() {
        assertNull(parse(ContentFixtures.untitledSections()).frontMatter)
    }

    private fun parse(bytes: ByteArray): BookContent = runBlocking {
        val result = EpubContentPipeline(Dispatchers.Unconfined)
            .parse(ContentFixtures.source(bytes), BookIdentity("sha256:${"f".repeat(64)}"))
        (result as BookContentResult.Parsed).content
    }

    private fun chapters(vararg titles: String): List<Chapter> =
        titles.mapIndexed { index, title -> chapter(index, title) }

    private fun chapter(
        index: Int,
        title: String,
        tokens: Int = 5,
        source: ChapterTitleSource = ChapterTitleSource.TOC,
    ): Chapter = Chapter(
        index = index,
        title = title,
        titleSource = source,
        startTokenIndex = index * 10,
        endTokenIndex = index * 10 + tokens,
        spinePath = "OEBPS/c$index.xhtml",
    )
}
