package com.cedagova.fastreader.content

import com.cedagova.fastreader.epub.ArchiveOpen
import com.cedagova.fastreader.epub.ArchiveReadStrategy
import com.cedagova.fastreader.epub.EpubArchives
import com.cedagova.fastreader.epub.EpubFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What opening a book costs, and what identity it is opened under (REQ-110,
 * AD-8/AD-9).
 *
 * The claim under test is not "opening is fast" — that is a stopwatch on a
 * device, and the measurement protocol in `docs/evidence/43/` is where it is
 * made. The claim here is the *mechanism* the stopwatch depends on, which a
 * stopwatch cannot establish: that a book's images are never read at all, and
 * that the number of bytes an open takes off storage tracks the text rather than
 * the file. Both are exact, and both are invisible from the parsed result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderOpenCostTest {

    private val pipeline = EpubContentPipeline(UnconfinedTestDispatcher())
    private val identity = BookIdentity("sha256:illustrated")

    // --- The mechanism: image entries are never read ---

    @Test
    fun `image entries are never read on open`() = runTest {
        val (bytes, spans) = ContentFixtures.illustratedNovel()
        val plates = spans.filterKeys { it.startsWith("OEBPS/images/") }.values.toList()
        assertEquals(4, plates.size)

        // The channel throws if anything so much as touches a plate's bytes, so
        // this passes only if the open genuinely never visits them.
        val (source, _) = ContentFixtures.watchedSource(bytes, poisoned = plates)
        val result = pipeline.parse(source, identity)

        val content = (result as BookContentResult.Parsed).content
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), content.chapters.map { it.title })
        assertTrue(content.gaps.isEmpty())
    }

    @Test
    fun `opening costs the book's text, not the file`() = runTest {
        val (illustrated, _) = ContentFixtures.illustratedNovel()
        val (stripped, _) = ContentFixtures.illustratedNovel(withImages = false)
        // The fixture is honest about being mostly pictures.
        assertTrue(illustrated.size > stripped.size * 100)

        val illustratedBytes = bytesReadOpening(illustrated)
        val strippedBytes = bytesReadOpening(stripped)

        // The whole of REQ-110's mechanism: the two opens read almost the same
        // amount, even though one file is a hundred times the other.
        assertTrue(
            "illustrated read $illustratedBytes bytes, stripped read $strippedBytes",
            illustratedBytes < strippedBytes * 2,
        )
        assertTrue(
            "illustrated read $illustratedBytes of ${illustrated.size} bytes",
            illustratedBytes < illustrated.size / 20,
        )
    }

    // --- The fallback: a source that cannot seek ---

    @Test
    fun `a source that cannot seek reads the same book by streaming`() = runTest {
        val (bytes, _) = ContentFixtures.illustratedNovel(imageCount = 2, imageBytes = 64 * 1024)

        val seekable = parsed(ContentFixtures.source(bytes))
        val streaming = parsed(ContentFixtures.streamingSource(bytes))

        assertEquals(ArchiveReadStrategy.DIRECTORY, strategyFor(ContentFixtures.source(bytes)))
        assertEquals(ArchiveReadStrategy.STREAMING, strategyFor(ContentFixtures.streamingSource(bytes)))
        assertEquals(seekable.tokens, streaming.tokens)
        assertEquals(seekable.chapters, streaming.chapters)
    }

    @Test
    fun `both strategies agree on the fixture books`() = runTest {
        val books = mapOf(
            "english" to ContentFixtures.englishNovel(),
            "spanish" to ContentFixtures.spanishNovel(),
            "latin-1" to ContentFixtures.latin1Book(),
            "percent-encoded spine" to EpubFixtures.percentEncodedSpineEpub(),
            "raw encoded entry name" to EpubFixtures.rawEncodedEntryNameEpub(),
            "epub 2 cover" to EpubFixtures.epub2CoverEpub(),
        )

        books.forEach { (name, bytes) ->
            val seekable = parsed(ContentFixtures.source(bytes))
            val streaming = parsed(ContentFixtures.streamingSource(bytes))
            assertEquals("$name tokens", streaming.tokens, seekable.tokens)
            assertEquals("$name chapters", streaming.chapters, seekable.chapters)
        }
    }

    @Test
    fun `an archive with no readable directory falls back rather than failing`() = runTest {
        // A file cut in half has no central directory at the end of it, so the
        // directory reader cannot be used. The fallback must reach the same verdict
        // the pipeline always reached rather than inventing a new failure.
        val truncated = EpubFixtures.truncatedZip()

        assertEquals(ArchiveReadStrategy.STREAMING, strategyFor(ContentFixtures.source(truncated)))
        val result = pipeline.parse(ContentFixtures.source(truncated), identity)
        assertEquals(
            ContentFailureReason.CORRUPT_ARCHIVE,
            (result as BookContentResult.Failed).reason,
        )
    }

    @Test
    fun `a download missing its later chapters still reports them as gaps`() = runTest {
        // This one *does* have a directory — it is a complete archive that simply
        // lacks entries — so it proves the directory path degrades a partial book
        // the same way the streaming path always did.
        val partial = ContentFixtures.interruptedMidBook()

        assertEquals(ArchiveReadStrategy.DIRECTORY, strategyFor(ContentFixtures.source(partial)))
        val content = parsed(ContentFixtures.source(partial))
        assertTrue(content.words().contains("survived"))
        assertEquals(2, content.gaps.size)
        assertTrue(content.gaps.all { it.reason == GapReason.MISSING_FROM_ARCHIVE })
    }

    @Test
    fun `an entry shorter than the directory promises becomes a gap, not half a chapter`() = runTest {
        // The directory is the only thing the seeking reader trusts, so an entry
        // that does not deliver what the directory declares is damaged. Half a
        // chapter of XHTML read as if it were whole would be a silent hole in the
        // book; a recorded gap is what the streaming reader would produce.
        val (bytes, _) = ContentFixtures.illustratedNovel(imageCount = 1, imageBytes = 1024)
        val forged = withOverstatedSize(bytes, "OEBPS/chapter1.xhtml", extraBytes = 512)

        val content = parsed(ContentFixtures.source(forged))

        assertEquals(1, content.gaps.size)
        assertEquals(0, content.gaps.single().chapterIndex)
        assertEquals(listOf("Chapter 2", "Chapter 3"), content.chapters.drop(1).map { it.title })
    }

    // --- Identity is an input, never derived (AD-8) ---

    @Test
    fun `the parsed book carries the identity it was handed`() = runTest {
        val bytes = ContentFixtures.englishNovel()
        val first = BookIdentity("sha256:aaaa")
        val second = BookIdentity("sha256:bbbb")

        assertEquals(first.value, parsed(ContentFixtures.source(bytes), first).bookDigest)
        // Nothing about the bytes decides the digest: the same file opened under
        // another id carries that one.
        assertEquals(second.value, parsed(ContentFixtures.source(bytes), second).bookDigest)
    }

    @Test
    fun `an unknown identity leaves the digest empty rather than inventing one`() = runTest {
        // The external session-only case (#44) before its digest has been computed.
        val content = parsed(ContentFixtures.source(ContentFixtures.englishNovel()), identity = null)

        assertEquals("", content.bookDigest)
    }

    @Test
    fun `a poisoned cover image does not stop the book opening`() = runTest {
        // The cover is a manifest item outside the spine — exactly what ingestion
        // reads and the reader must not.
        val (bytes, spans) = EpubFixtures.buildArchiveWithSpans(
            listOf(
                "META-INF/container.xml" to CONTAINER.toByteArray(Charsets.UTF_8),
                "OEBPS/content.opf" to COVER_OPF.toByteArray(Charsets.UTF_8),
                "OEBPS/images/cover.png" to EpubFixtures.TINY_PNG,
                "OEBPS/chapter1.xhtml" to CHAPTER.toByteArray(Charsets.UTF_8),
            ),
        )
        val (source, _) = ContentFixtures.watchedSource(
            bytes,
            poisoned = listOf(spans.getValue("OEBPS/images/cover.png")),
        )

        val result = pipeline.parse(source, identity)

        assertNull((result as? BookContentResult.Failed)?.reason)
        assertTrue((result as BookContentResult.Parsed).content.words().contains("Body"))
    }

    // --- helpers ---

    /**
     * Adds [extraBytes] to one entry's uncompressed-size field in the central
     * directory, leaving its data alone — an archive whose table lies about how
     * big an entry is.
     */
    private fun withOverstatedSize(bytes: ByteArray, entryName: String, extraBytes: Int): ByteArray {
        val patched = bytes.copyOf()
        val name = entryName.toByteArray(Charsets.UTF_8)
        for (index in 0..(patched.size - CENTRAL_HEADER_BYTES - name.size)) {
            val signature = patched[index] == 0x50.toByte() && patched[index + 1] == 0x4B.toByte() &&
                patched[index + 2] == 0x01.toByte() && patched[index + 3] == 0x02.toByte()
            if (!signature) continue
            val nameStart = index + CENTRAL_HEADER_BYTES
            if (!patched.copyOfRange(nameStart, nameStart + name.size).contentEquals(name)) continue
            val field = index + UNCOMPRESSED_SIZE_OFFSET
            var declared = 0
            for (byte in 0 until 4) declared = declared or ((patched[field + byte].toInt() and 0xFF) shl (8 * byte))
            val overstated = declared + extraBytes
            for (byte in 0 until 4) patched[field + byte] = (overstated shr (8 * byte)).toByte()
            return patched
        }
        throw AssertionError("no central-directory record for $entryName")
    }

    private fun bytesReadOpening(bytes: ByteArray): Long {
        val (source, readSoFar) = ContentFixtures.watchedSource(bytes)
        kotlinx.coroutines.runBlocking { pipeline.parse(source, identity) }
        return readSoFar()
    }

    private fun strategyFor(source: com.cedagova.fastreader.epub.EpubByteSource): ArchiveReadStrategy =
        (EpubArchives.open(source) as ArchiveOpen.Opened).archive.use { it.strategy }

    private suspend fun parsed(
        source: com.cedagova.fastreader.epub.EpubByteSource,
        identity: BookIdentity? = this.identity,
    ): BookContent {
        val result = pipeline.parse(source, identity)
        assertTrue("expected parsed content but got $result", result is BookContentResult.Parsed)
        return (result as BookContentResult.Parsed).content
    }

    private fun BookContent.words(): List<String> =
        tokens.filterIsInstance<WordToken>().map { it.text }

    private companion object {
        const val CENTRAL_HEADER_BYTES = 46
        const val UNCOMPRESSED_SIZE_OFFSET = 24

        const val CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

        const val COVER_OPF = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="pub-id">urn:uuid:covered</dc:identifier>
    <dc:title>Covered</dc:title>
    <dc:language>en</dc:language>
  </metadata>
  <manifest>
    <item id="cover" href="images/cover.png" media-type="image/png" properties="cover-image"/>
    <item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1"/></spine>
</package>"""

        const val CHAPTER = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Body</p></body></html>"""
    }
}
