package com.cedagova.fastreader.account.library

import com.cedagova.fastreader.reader.ReaderFixtures
import com.cedagova.reader.engine.content.BookContent
import com.cedagova.reader.library.model.LOCATOR_FORMAT_EPUB
import com.cedagova.reader.library.model.MEDIA_TYPE_EPUB
import com.cedagova.reader.library.model.PORTABLE_SEMANTICS_VERSION
import com.cedagova.reader.library.model.PUBLICATION_SOURCE_ACCOUNT
import com.cedagova.reader.library.sync.LocalReadingPosition
import com.cedagova.reader.library.sync.PortableProgress
import com.cedagova.reader.library.sync.RemoteReadingPosition
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one seam between FastReader's position and the account's portable one
 * (#120, REQ-511, REQ-512, AD-25).
 *
 * Driven against a really parsed book rather than a hand-built token list, so
 * the chapter boundaries, spine paths and skip markers are the ones the reader
 * actually receives. Three groups of tests, matching the three derivations the
 * file itself is built around: what goes out, what a record is about, and how a
 * section maps back to a word.
 */
class PortableReadingPositionTest {

    private val book: BookContent = ReaderFixtures.englishNovel
    private val chapters = book.chapters.filter { !it.isEmpty }

    // ------------------------------------------------------ what leaves the device

    /**
     * REQ-512's position clause, as a closed set rather than a spot check.
     *
     * Every non-word event in a whole read of the book is published, every
     * payload is serialized, and the union of all their keys — and of their
     * locators' keys — is asserted to be exactly the contract's. A "does not
     * contain token_index" assertion would pass for a field nobody thought to
     * name; this one fails for *any* key that is not one of these, at every level
     * of the body (#139 added the `location` and its `publication`).
     */
    @Test
    fun `the exact key set of every published position, over a whole book`() {
        val payloads = (0 until book.totalTokens)
            .mapNotNull { PortableReadingPosition.of(book, it) }
            .map { PortableProgress.payloadFor(BOOK_ID, it) }
        assertEquals("every token must yield a position", book.totalTokens, payloads.size)

        val bodyKeys = payloads.flatMap { it.keys }.toSortedSet()
        assertEquals(
            "a published position states its percentage, its location and its chapter — nothing else",
            setOf("chapter_title", "location", "progress_percent").toSortedSet(),
            bodyKeys,
        )

        val locations = payloads.map { it["location"]!!.jsonObject }
        val locationKeys = locations.flatMap { it.keys }.toSortedSet()
        assertEquals(
            "a location states its version, its publication and its locator — nothing else",
            setOf("contract_version", "locator", "publication").toSortedSet(),
            locationKeys,
        )

        // The publication names the account book the envelope already addresses,
        // plus constants — and never a digest of the book (#139).
        val publications = locations.map { it["publication"]!!.jsonObject }
        val publicationKeys = publications.flatMap { it.keys }.toSortedSet()
        assertEquals(
            "a publication states the account book id and three constants — nothing else",
            setOf("format", "media_type", "publication_id", "source").toSortedSet(),
            publicationKeys,
        )
        assertEquals(
            "every publication is the same account EPUB, whatever the position",
            setOf(
                mapOf(
                    "publication_id" to BOOK_ID,
                    "format" to LOCATOR_FORMAT_EPUB,
                    "media_type" to MEDIA_TYPE_EPUB,
                    "source" to PUBLICATION_SOURCE_ACCOUNT,
                ),
            ),
            publications.map { p -> p.mapValues { it.value.jsonPrimitive.content } }.toSet(),
        )

        val locatorKeys = locations.map { it["locator"]!!.jsonObject }.flatMap { it.keys }.toSortedSet()
        assertEquals(
            "a portable locator states its format, version, section and fraction — nothing else",
            setOf("contract_version", "format", "href", "progression").toSortedSet(),
            locatorKeys,
        )

        // The same claim said the other way round, naming the values that must
        // never appear, so the failure message points at the actual mistake.
        val everyKey = bodyKeys + locationKeys + publicationKeys + locatorKeys
        listOf(
            "token_index", "tokenIndex", "wpm", "words_per_minute", "speed",
            "pipeline_version", "pipelineVersion", "structural_fingerprint",
            "structuralFingerprint", "book_digest", "bookDigest", "epub_cfi", "settings",
            "content_identity", "sha256", "title",
        ).forEach { forbidden ->
            assertTrue("\"$forbidden\" must never be published", forbidden !in everyKey)
        }
    }

    /** The locator's two constants are the contract's, not strings typed here. */
    @Test
    fun `a published locator carries the portable-semantics contract`() {
        val location = PortableProgress
            .payloadFor(BOOK_ID, PortableReadingPosition.of(book, 40)!!)["location"]!!
            .jsonObject
        val locator = location["locator"]!!.jsonObject

        assertEquals(PORTABLE_SEMANTICS_VERSION, location["contract_version"]!!.jsonPrimitive.content)
        assertEquals(LOCATOR_FORMAT_EPUB, locator["format"]!!.jsonPrimitive.content)
        assertEquals(PORTABLE_SEMANTICS_VERSION, locator["contract_version"]!!.jsonPrimitive.content)
        assertTrue("epub_cfi is never written", "epub_cfi" !in locator)
        assertEquals(
            "reader-api requires the locator's format to equal the publication's",
            location["publication"]!!.jsonObject["format"]!!.jsonPrimitive.content,
            locator["format"]!!.jsonPrimitive.content,
        )
    }

    /**
     * The acceptance criterion, literally: the published percentage is the one on
     * the reader's own screen.
     *
     * `(fraction * 100).roundToInt()` is `ReaderBookView.present`'s expression;
     * asserting it here for every token means the two cannot drift without this
     * failing.
     */
    @Test
    fun `progress_percent equals the percent the reader is shown, at every word`() {
        (0 until book.totalTokens).forEach { index ->
            val shown = (book.progressFraction(index) * 100).roundToInt()
            val published = PortableReadingPosition.of(book, index)!!.percent
            assertEquals("at token $index", shown, published)
        }
    }

    /** The fraction still travels at full precision, in the locator. */
    @Test
    fun `progression is the book-level token fraction, not the rounded percent`() {
        val index = book.totalTokens / 3
        val payload = PortableProgress.payloadFor(BOOK_ID, PortableReadingPosition.of(book, index)!!)
        val progression = payload["location"]!!.jsonObject["locator"]!!.jsonObject["progression"]!!.jsonPrimitive.double

        assertEquals(book.progressFraction(index).toDouble(), progression, 1e-9)
        assertTrue("a fraction, never a percentage", progression in 0.0..1.0)
    }

    /** A book with no tokens has no position to state, and says nothing. */
    @Test
    fun `an empty book yields no position at all`() {
        val empty = book.copy(tokens = emptyList(), chapters = emptyList())

        assertNull(PortableReadingPosition.of(empty, 0))
    }

    // --------------------------------------------------- mapping a remote position back

    /**
     * The round trip, at every chapter boundary — the places the mapping is most
     * likely to be off by one, because the first token of a chapter is the last
     * token of the previous one plus one.
     *
     * Within one token is what is asserted, and what A2 promises: the fraction is
     * a real number quantised onto a token index, so exact equality is not
     * available in general.
     */
    @Test
    fun `a position round trips within one token at every chapter boundary`() {
        val boundaries = chapters.flatMap { listOf(it.startTokenIndex, it.endTokenIndex - 1) }
            .filter { it in 0 until book.totalTokens }
        assertTrue("the fixture must have boundaries to test", boundaries.size >= 4)

        boundaries.forEach { index ->
            val out = PortableReadingPosition.of(book, index)!!
            val back = PortableReadingPosition.tokenIndexFor(book, out.asRemote())

            assertTrue(
                "token $index round tripped to $back, more than one token away",
                kotlin.math.abs(back - index) <= 1,
            )
        }
    }

    /** And in the body of each chapter, not only at its edges. */
    @Test
    fun `a position round trips within one token in the middle of each chapter`() {
        chapters.forEach { chapter ->
            val index = chapter.startTokenIndex + chapter.tokenCount / 2
            val back = PortableReadingPosition.tokenIndexFor(
                book,
                PortableReadingPosition.of(book, index)!!.asRemote(),
            )

            assertTrue("chapter ${chapter.index}: $index → $back", kotlin.math.abs(back - index) <= 1)
        }
    }

    /**
     * The section is authoritative and the fraction advisory: a fraction that
     * points outside the named chapter is pulled back into it.
     *
     * This is what makes two clients that disagree about where a chapter starts
     * still land in the right chapter.
     */
    @Test
    fun `a fraction outside the named chapter is clamped into it`() {
        val chapter = chapters.last()
        val landed = PortableReadingPosition.tokenIndexFor(
            book,
            RemoteReadingPosition(
                href = chapter.spinePath,
                chapterTitle = chapter.title,
                // Points at the very start of the book, which is not in this chapter.
                progression = 0.0,
                percent = 0.0,
                updatedAt = null,
            ),
        )

        assertTrue(
            "landed at $landed, outside ${chapter.startTokenIndex}..${chapter.endTokenIndex - 1}",
            landed in chapter.startTokenIndex until chapter.endTokenIndex,
        )
        assertEquals(chapter.startTokenIndex, landed)
    }

    /**
     * An href this parse does not have — a different edition, a renamed spine.
     * The fraction alone decides, because a *wrong* chapter's start would be
     * worse than a number that is at least about this book.
     */
    @Test
    fun `an unknown href falls back to the fraction alone`() {
        val landed = PortableReadingPosition.tokenIndexFor(
            book,
            RemoteReadingPosition(
                href = "OEBPS/a-section-this-edition-never-had.xhtml",
                chapterTitle = "Chapter 8",
                progression = 0.5,
                percent = 50.0,
                updatedAt = null,
            ),
        )

        val byFractionAlone = (0.5 * book.totalTokens).toInt()
        assertEquals(byFractionAlone, landed)
    }

    /** No href at all — a client that named no section. The fraction is the whole record. */
    @Test
    fun `a missing href falls back to the fraction alone`() {
        val landed = PortableReadingPosition.tokenIndexFor(
            book,
            RemoteReadingPosition(null, null, progression = 0.25, percent = 25.0, updatedAt = null),
        )

        assertEquals((0.25 * book.totalTokens).toInt(), landed)
    }

    /** Neither href nor fraction: the record said nothing, so the book opens at its start. */
    @Test
    fun `a record with neither section nor fraction maps to the beginning`() {
        val landed = PortableReadingPosition.tokenIndexFor(
            book,
            RemoteReadingPosition(null, null, progression = null, percent = null, updatedAt = null),
        )

        assertEquals(0, landed)
    }

    /**
     * Empty sections are skipped rather than landed in.
     *
     * A chapter that produced no tokens has no word to stop on, so the mapping
     * resolves to its start — which is the first token that actually exists after
     * it — and a published position never names one at all, because no token is
     * inside an empty range.
     */
    @Test
    fun `empty sections are never published and never landed inside`() {
        val emptyChapters = book.chapters.filter { it.isEmpty }
        val publishedHrefs = (0 until book.totalTokens)
            .mapNotNull { PortableReadingPosition.of(book, it)?.href }
            .toSet()

        emptyChapters.forEach { chapter ->
            assertTrue(
                "an empty section must never be published as a locator href",
                chapter.spinePath !in publishedHrefs || book.chapters.any {
                    it.spinePath == chapter.spinePath && !it.isEmpty
                },
            )
            val landed = PortableReadingPosition.tokenIndexFor(
                book,
                RemoteReadingPosition(chapter.spinePath, chapter.title, null, null, null),
            )
            assertTrue("an empty section resolves to a real token", landed in 0 until book.totalTokens)
        }
    }

    // ------------------------------------------------------------------- helpers

    /** A published position, read back as though the backend had returned it. */
    private fun LocalReadingPosition.asRemote(): RemoteReadingPosition =
        PortableProgress.positionOf(PortableProgress.payloadFor(BOOK_ID, this))

    private companion object {
        /** An account book id, the envelope's `resource_id` and so the publication's id. */
        const val BOOK_ID: String = "7f1c7a0e-0b8e-4d8a-9a52-3f0f7c1d2e11"
    }
}
