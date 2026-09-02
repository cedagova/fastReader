package com.cedagova.fastreader.content

import com.cedagova.fastreader.epub.EpubFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Acceptance-level tests for the content pipeline, over the fixture books. */
@OptIn(ExperimentalCoroutinesApi::class)
class EpubContentPipelineTest {

    private val pipeline = EpubContentPipeline(UnconfinedTestDispatcher())

    // --- English book: structure, chapters, markers, classification ---

    @Test
    fun `english book extracts front matter chapters and back matter in book order`() = runTest {
        val content = parse(ContentFixtures.englishNovel())

        assertEquals(
            listOf("Title Page", "Chapter One: The Arrival", "Chapter Two: The Departure", "Colophon"),
            content.chapters.map { it.title },
        )
        assertTrue(content.chapters.all { it.titleSource == ChapterTitleSource.TOC })
        // Chapters cover the stream once, contiguously, in spine order.
        assertEquals(0, content.chapters.first().startTokenIndex)
        assertEquals(content.totalTokens, content.chapters.last().endTokenIndex)
        content.chapters.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endTokenIndex, right.startTokenIndex)
        }
    }

    @Test
    fun `inline image becomes a skip marker and a table is skipped whole`() = runTest {
        val content = parse(ContentFixtures.englishNovel())
        val markers = content.tokens.filterIsInstance<SkipMarkerToken>()

        assertEquals(listOf(SkipKind.IMAGE, SkipKind.TABLE), markers.map { it.kind })
        assertEquals("[image skipped]", markers.first { it.kind == SkipKind.IMAGE }.label)
        assertEquals("[table skipped]", markers.first { it.kind == SkipKind.TABLE }.label)
        // The table's cells are not streamed as words.
        assertFalse(content.words().contains("Speed"))
        assertFalse(content.words().contains("250"))
    }

    @Test
    fun `footnote markers are absent from the stream and do not split the paragraph`() = runTest {
        val content = parse(ContentFixtures.englishNovel())

        assertFalse(content.words().contains("1"))
        val quiet = content.word("quiet")
        val ada = content.word("Ada")
        // The note reference sat between them; the sentence continues regardless.
        assertEquals(quiet.paragraphIndex, ada.paragraphIndex)
        assertEquals(Boundary.SENTENCE, quiet.boundary)
    }

    @Test
    fun `word classes follow the bounded heuristics`() = runTest {
        val content = parse(ContentFixtures.englishNovel())

        assertTrue(WordClass.LONG in content.word("extraordinarily").classes)
        assertTrue(WordClass.NUMBER in content.word("9").classes)
        assertTrue(WordClass.ALL_CAPS in content.word("NASA").classes)
        assertTrue(WordClass.RARE in content.word("extraordinarily").classes)
        assertFalse(WordClass.RARE in content.word("The").classes)
    }

    @Test
    fun `abbreviations keep their period and do not end a sentence`() = runTest {
        val content = parse(ContentFixtures.englishNovel())

        val doctor = content.word("Dr.")
        assertTrue(WordClass.ABBREVIATION in doctor.classes)
        assertEquals(Boundary.NONE, doctor.boundary)

        val am = content.word("a.m.")
        assertTrue(WordClass.ABBREVIATION in am.classes)
        assertEquals(Boundary.NONE, am.boundary)
    }

    @Test
    fun `heading words are flagged and end with a heading boundary`() = runTest {
        val content = parse(ContentFixtures.englishNovel())
        val headingWords = content.tokens.filterIsInstance<WordToken>().filter { it.isHeading }

        assertTrue(headingWords.isNotEmpty())
        assertEquals(Boundary.HEADING, content.word("One").boundary)
    }

    // --- Spanish book: REQ-019 ---

    @Test
    fun `spanish text keeps its accents and inverted punctuation without mojibake`() = runTest {
        val content = parse(ContentFixtures.spanishNovel())
        val words = content.words()

        assertTrue(words.containsAll(listOf("Cómo", "máquina", "Ramírez", "niña", "Qué")))
        assertTrue(content.tokens.none { it.displayText.contains('�') })
        assertTrue(content.tokens.none { it.displayText.contains("Ã") })
        // The inverted marks open a sentence; they are punctuation, not words.
        assertFalse(words.any { it.startsWith("¿") || it.startsWith("¡") })
    }

    @Test
    fun `spanish question and exclamation marks close a sentence`() = runTest {
        val content = parse(ContentFixtures.spanishNovel())

        assertEquals(Boundary.SENTENCE, content.word("estás").boundary)
        assertEquals(Boundary.SENTENCE, content.word("sorpresa").boundary)
    }

    @Test
    fun `dialogue dashes and commas break a clause`() = runTest {
        val content = parse(ContentFixtures.spanishNovel())

        // "—Muy bien, gracias —respondió ella—."
        assertEquals(Boundary.CLAUSE, content.word("bien").boundary)
        assertEquals(Boundary.CLAUSE, content.word("gracias").boundary)
        assertEquals(Boundary.NONE, content.word("Sr.").boundary)
    }

    @Test
    fun `ncx supplies the chapter titles when there is no navigation document`() = runTest {
        val content = parse(ContentFixtures.spanishNovel())

        assertEquals(listOf("Cubierta", "Capítulo I", "Capítulo II"), content.chapters.map { it.title })
        assertEquals("es", content.language)
    }

    @Test
    fun `a latin-1 chapter decodes without mojibake`() = runTest {
        val content = parse(ContentFixtures.latin1Book())

        assertTrue(content.words().contains("canción"))
    }

    // --- Positions, totals, determinism ---

    @Test
    fun `positions are stable across re-parses of the same book`() = runTest {
        val bytes = ContentFixtures.englishNovel()
        val first = parse(bytes)
        val second = parse(bytes)

        assertEquals(first.tokens, second.tokens)
        assertEquals(first.chapters, second.chapters)
        assertEquals(first.bookDigest, second.bookDigest)
        assertEquals(
            first.tokens.indices.toList(),
            first.tokens.map { it.index },
        )
    }

    @Test
    fun `totals support progress and time-remaining math`() = runTest {
        val content = parse(ContentFixtures.englishNovel())

        assertEquals(content.totalTokens, content.tokens.size)
        assertEquals(content.tokens.count { it is WordToken }, content.totalWords)
        assertEquals(0f, content.progressFraction(-1), 0.0001f)
        assertEquals(1f, content.progressFraction(content.totalTokens - 1), 0.0001f)
        assertEquals(content.totalWords, content.wordsRemaining(-1))
        assertEquals(0, content.wordsRemaining(content.totalTokens - 1))
        // Halving the words left halves the time remaining at a fixed speed.
        val midpoint = content.tokens.indices.first { content.wordsRemaining(it) * 2 <= content.totalWords }
        assertTrue(content.wordsRemaining(midpoint) * 2 <= content.totalWords)
    }

    @Test
    fun `sentence and paragraph starts anchor navigation`() = runTest {
        val content = parse(ContentFixtures.spanishNovel())
        val word = content.word("silenciosa")

        val sentenceStart = content.sentenceStart(word.index)
        assertEquals(word.sentenceIndex, content.tokens[sentenceStart].sentenceIndex)
        assertTrue(sentenceStart == 0 || content.tokens[sentenceStart - 1].sentenceIndex != word.sentenceIndex)

        val paragraphStart = content.paragraphStart(word.index)
        assertEquals(word.paragraphIndex, content.tokens[paragraphStart].paragraphIndex)
    }

    @Test
    fun `parse reports progress for every spine item`() = runTest {
        val seen = mutableListOf<ContentProgress>()
        val result = pipeline.parse(ContentFixtures.source(ContentFixtures.englishNovel())) { seen += it }

        assertTrue(result is BookContentResult.Parsed)
        assertEquals(0, seen.first().completedItems)
        assertEquals(4, seen.first().totalItems)
        assertEquals(listOf(0, 1, 2, 3, 4), seen.map { it.completedItems })
        assertEquals(1f, seen.last().fraction, 0.0001f)
    }

    // --- Degraded and failing books ---

    @Test
    fun `a download interrupted mid-book keeps what it has and marks the gaps`() = runTest {
        val content = parse(ContentFixtures.interruptedMidBook())

        assertTrue(content.words().contains("survived"))
        assertEquals(2, content.gaps.size)
        assertTrue(content.gaps.all { it.reason == GapReason.MISSING_FROM_ARCHIVE })
        assertEquals(listOf(1, 2), content.gaps.map { it.chapterIndex })
        val missing = content.tokens.filterIsInstance<SkipMarkerToken>()
            .filter { it.kind == SkipKind.MISSING_CONTENT }
        assertEquals(2, missing.size)
        assertEquals("[content unavailable]", missing.first().label)
    }

    @Test
    fun `chapter titles fall back to a heading and then to position`() = runTest {
        val content = parse(ContentFixtures.untitledSections())

        assertEquals(
            listOf("The Arrival", "Section 2", "Section 3"),
            content.chapters.map { it.title },
        )
        assertEquals(
            listOf(ChapterTitleSource.HEADING, ChapterTitleSource.FALLBACK, ChapterTitleSource.FALLBACK),
            content.chapters.map { it.titleSource },
        )
        // The empty third section is recorded rather than silently dropped.
        assertTrue(content.chapters.last().isEmpty)
        assertEquals(GapReason.NO_TEXT, content.gaps.single().reason)
    }

    @Test
    fun `sloppy markup still yields its text`() = runTest {
        val content = parse(ContentFixtures.malformedMarkupBook())
        val words = content.words()

        assertTrue(words.contains("Marks"))
        assertTrue(words.contains("Charing"))
        assertTrue(words.contains("shop’s"))
        assertTrue(words.contains("84"))
    }

    @Test
    fun `malformed books fail with a typed reason rather than throwing`() = runTest {
        assertEquals(ContentFailureReason.CORRUPT_ARCHIVE, failure(EpubFixtures.notAZip()))
        assertEquals(ContentFailureReason.CORRUPT_ARCHIVE, failure(EpubFixtures.truncatedZip()))
        assertEquals(ContentFailureReason.INVALID_STRUCTURE, failure(EpubFixtures.zipWithoutContainer()))
        assertEquals(ContentFailureReason.INVALID_STRUCTURE, failure(EpubFixtures.missingPackageDocument()))
        assertEquals(ContentFailureReason.INVALID_STRUCTURE, failure(EpubFixtures.emptySpineEpub()))
        assertEquals(ContentFailureReason.NO_READABLE_CONTENT, failure(ContentFixtures.textlessBook()))
    }

    @Test
    fun `an unopenable source fails without throwing`() = runTest {
        val result = pipeline.parse({ throw java.io.IOException("permission revoked") })

        assertEquals(
            ContentFailureReason.UNREADABLE_SOURCE,
            (result as BookContentResult.Failed).reason,
        )
    }

    @Test
    fun `a percent-encoded spine href still resolves to its entry`() = runTest {
        val content = parse(EpubFixtures.percentEncodedSpineEpub())

        assertTrue(content.words().contains("Body"))
    }

    // --- helpers ---

    private suspend fun parse(bytes: ByteArray): BookContent {
        val result = pipeline.parse(ContentFixtures.source(bytes))
        assertNotNull(result)
        assertTrue("expected parsed content but got $result", result is BookContentResult.Parsed)
        return (result as BookContentResult.Parsed).content
    }

    private suspend fun failure(bytes: ByteArray): ContentFailureReason {
        val result = pipeline.parse(ContentFixtures.source(bytes))
        assertTrue("expected a failure but got $result", result is BookContentResult.Failed)
        return (result as BookContentResult.Failed).reason
    }

    private fun BookContent.words(): List<String> =
        tokens.filterIsInstance<WordToken>().map { it.text }

    private fun BookContent.word(text: String): WordToken =
        tokens.filterIsInstance<WordToken>().firstOrNull { it.text == text }
            ?: throw AssertionError("no word '$text' in ${words()}")
}
