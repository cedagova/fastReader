package com.cedagova.fastreader.content

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.epub.EpubFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The content pipeline on a real device.
 *
 * JVM-green is not device-proof here. Increment 001 shipped an XML call that
 * threw on every device while its unit tests passed, and this leaf reaches four
 * platform-backed surfaces whose Android implementations are not the desktop
 * JVM's: `ZipInputStream`, `MessageDigest`, the package document's
 * `DocumentBuilderFactory` parse, and — the subtle one — the Unicode tables
 * behind `Char.isLetterOrDigit`, which decide where a Spanish word ends.
 *
 * So the same fixtures are re-proven on device rather than trusted from the JVM.
 */
@RunWith(AndroidJUnit4::class)
class ContentPipelineDeviceTest {

    private val pipeline = EpubContentPipeline()

    @Test
    fun englishBookParsesOnDevice() = runBlocking {
        val content = parse(ContentFixtures.englishNovel())

        assertEquals(
            listOf("Title Page", "Chapter One: The Arrival", "Chapter Two: The Departure", "Colophon"),
            content.chapters.map { it.title },
        )
        val markers = content.tokens.filterIsInstance<SkipMarkerToken>()
        assertEquals(listOf(SkipKind.IMAGE, SkipKind.TABLE), markers.map { it.kind })
        assertFalse(content.words().contains("1"))
        assertTrue(content.bookDigest.startsWith("sha256:"))
    }

    @Test
    fun spanishTextKeepsItsWordBoundariesOnDevice() = runBlocking {
        val content = parse(ContentFixtures.spanishNovel())
        val words = content.words()

        // Android's Unicode tables, not the desktop JVM's, decide these splits.
        assertTrue(words.containsAll(listOf("Cómo", "máquina", "Ramírez", "niña", "Qué")))
        assertTrue(content.tokens.none { it.displayText.contains('�') })
        assertEquals(Boundary.SENTENCE, content.word("estás").boundary)
        assertEquals(Boundary.NONE, content.word("Sr.").boundary)
        assertEquals(listOf("Cubierta", "Capítulo I", "Capítulo II"), content.chapters.map { it.title })
    }

    @Test
    fun latin1ChapterDecodesOnDevice() = runBlocking {
        assertTrue(parse(ContentFixtures.latin1Book()).words().contains("canción"))
    }

    @Test
    fun malformedBooksFailWithATypedReasonOnDevice() = runBlocking {
        assertEquals(ContentFailureReason.CORRUPT_ARCHIVE, failure(EpubFixtures.notAZip()))
        assertEquals(ContentFailureReason.INVALID_STRUCTURE, failure(EpubFixtures.emptySpineEpub()))
        assertEquals(ContentFailureReason.NO_READABLE_CONTENT, failure(ContentFixtures.textlessBook()))
    }

    @Test
    fun interruptedDownloadDegradesOnDevice() = runBlocking {
        val content = parse(ContentFixtures.interruptedMidBook())

        assertTrue(content.words().contains("survived"))
        assertEquals(2, content.gaps.size)
        assertEquals(
            2,
            content.tokens.filterIsInstance<SkipMarkerToken>().count { it.kind == SkipKind.MISSING_CONTENT },
        )
    }

    @Test
    fun positionsAreStableAcrossReparsesOnDevice() = runBlocking {
        val bytes = ContentFixtures.spanishNovel()

        assertEquals(parse(bytes).tokens, parse(bytes).tokens)
    }

    private suspend fun parse(bytes: ByteArray): BookContent {
        val result = pipeline.parse(ContentFixtures.source(bytes))
        assertTrue("expected parsed content but got $result", result is BookContentResult.Parsed)
        return (result as BookContentResult.Parsed).content
    }

    private suspend fun failure(bytes: ByteArray): ContentFailureReason {
        val result = pipeline.parse(ContentFixtures.source(bytes))
        assertTrue("expected a failure but got $result", result is BookContentResult.Failed)
        return (result as BookContentResult.Failed).reason
    }

    private fun BookContent.words(): List<String> = tokens.filterIsInstance<WordToken>().map { it.text }

    private fun BookContent.word(text: String): WordToken =
        tokens.filterIsInstance<WordToken>().first { it.text == text }
}
