package com.cedagova.reader.library.sync

import com.cedagova.reader.library.model.LOCATOR_FORMAT_EPUB
import com.cedagova.reader.library.model.MEDIA_TYPE_EPUB
import com.cedagova.reader.library.model.PORTABLE_SEMANTICS_VERSION
import com.cedagova.reader.library.model.PUBLICATION_SOURCE_ACCOUNT
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client-generic half of the portable position (#120, #147): which book a
 * `reading_progress` record is about, and what a canonical payload states.
 *
 * The host-specific half — mapping a position to and from a host's own reading
 * unit — is the host's, and the exact-key-set proof over a whole parsed book
 * stays beside the host's mapping.
 */
class PortableProgressTest {

    // ------------------------------------------------- which book a record is about

    /**
     * The derivation the whole file is built to make correctable: the payload's
     * own `book_id` is preferred over the resource id, because the document marks
     * that field required and it is the backend's own answer.
     */
    @Test
    fun `a record is placed by the payload's book_id before its resource id`() {
        val record = PortableProgress.recordFor(
            resourceId = "progress-row-91",
            payload = progressPayload(bookId = "book-1"),
            knownBook = { it == "book-1" },
        )

        assertEquals(ProgressRecord.Recognized::class, record::class)
        assertEquals("book-1", (record as ProgressRecord.Recognized).bookId)
    }

    /** With no `book_id` in the payload, the resource id is what the identity is derived to be. */
    @Test
    fun `a record with no book_id falls back to the resource id`() {
        val record = PortableProgress.recordFor(
            resourceId = "book-1",
            payload = progressPayload(bookId = null),
            knownBook = { it == "book-1" },
        )

        assertEquals("book-1", (record as ProgressRecord.Recognized).bookId)
    }

    /**
     * The assumption failing, made visible.
     *
     * This is the case nobody has observed on stage: a resource id that is not a
     * book id and a payload that does not name one either. It must not be a
     * dropped record — it must be a value carrying both halves of what went
     * wrong, so the mapping can be corrected in one file.
     */
    @Test
    fun `a record naming no book this account holds is unrecognized, not dropped`() {
        val record = PortableProgress.recordFor(
            resourceId = "progress:book-1:v3",
            payload = progressPayload(bookId = null),
            knownBook = { it == "book-1" },
        )

        val unrecognized = record as ProgressRecord.Unrecognized
        assertEquals("progress:book-1:v3", unrecognized.resourceId)
        assertNull(unrecognized.payloadBookId)
        assertTrue(unrecognized.reason.isNotBlank())
    }

    /** Both ids present and neither known: the payload's id is carried too, for the fix. */
    @Test
    fun `an unrecognized record carries the book_id the payload claimed`() {
        val record = PortableProgress.recordFor(
            resourceId = "row-9",
            payload = progressPayload(bookId = "book-elsewhere"),
            knownBook = { it == "book-1" },
        )

        val unrecognized = record as ProgressRecord.Unrecognized
        assertEquals("row-9", unrecognized.resourceId)
        assertEquals("book-elsewhere", unrecognized.payloadBookId)
    }

    // ----------------------------------------------------------- reading a payload

    /** Every field of a remote position is read off the payload, and none is computed. */
    @Test
    fun `a remote position is read from the payload exactly as it arrived`() {
        val chapter = SPINE_PATH to CHAPTER_TITLE
        val position = PortableProgress.positionOf(
            buildJsonObject {
                put("progress_percent", JsonPrimitive(41.5))
                put("updated_at", JsonPrimitive("2026-09-20T10:00:00Z"))
                put("chapter_title", JsonPrimitive(chapter.second))
                put(
                    "location",
                    location(
                        buildJsonObject {
                            put("format", JsonPrimitive(LOCATOR_FORMAT_EPUB))
                            put("href", JsonPrimitive(chapter.first))
                            put("progression", JsonPrimitive(0.415))
                            put("contract_version", JsonPrimitive(PORTABLE_SEMANTICS_VERSION))
                        },
                    ),
                )
            },
        )

        assertEquals(chapter.first, position.href)
        assertEquals(chapter.second, position.chapterTitle)
        assertEquals(0.415, position.progression!!, 1e-9)
        assertEquals(41.5, position.percent!!, 1e-9)
        assertEquals("2026-09-20T10:00:00Z", position.updatedAt)
    }

    /**
     * A malformed locator costs the section and not the percentage — the
     * documented fallback. The record still says how far through the book the
     * other client was, and that is worth keeping.
     */
    @Test
    fun `a malformed locator keeps the percentage and drops only the section`() {
        val position = PortableProgress.positionOf(
            buildJsonObject {
                put("progress_percent", JsonPrimitive(62.0))
                put("location", location(buildJsonObject { put("href", JsonPrimitive(listOf(1, 2).toString())) }))
            },
        )

        assertEquals(62.0, position.percent!!, 1e-9)
        assertNull(position.progression)
        assertNotNull("an href that is a string is still read", position.href)
    }

    /** A locator for another format states nothing about this EPUB's spine. */
    @Test
    fun `a non-epub locator contributes no href`() {
        val position = PortableProgress.positionOf(
            buildJsonObject {
                put("progress_percent", JsonPrimitive(10.0))
                put(
                    "location",
                    location(
                        buildJsonObject {
                            put("format", JsonPrimitive("pdf"))
                            put("href", JsonPrimitive("page-4"))
                            put("progression", JsonPrimitive(0.1))
                        },
                    ),
                )
            },
        )

        assertNull("a pdf locator's href is not a spine path", position.href)
        assertEquals(0.1, position.progression!!, 1e-9)
    }

    /**
     * A pre-#139 payload — a bare top-level `locator`, no `location` — is not a
     * shape the pinned contract has any more. It is read as what it still
     * reliably says, the percentage, rather than kept alive by a second parser.
     */
    @Test
    fun `a pre-cutover payload with a bare locator is read as its percentage alone`() {
        val position = PortableProgress.positionOf(
            buildJsonObject {
                put("progress_percent", JsonPrimitive(33.0))
                put(
                    "locator",
                    buildJsonObject {
                        put("format", JsonPrimitive(LOCATOR_FORMAT_EPUB))
                        put("href", JsonPrimitive(SPINE_PATH))
                        put("progression", JsonPrimitive(0.33))
                    },
                )
            },
        )

        assertNull(position.href)
        assertNull(position.progression)
        assertEquals(33.0, position.percent!!, 1e-9)
    }

    /** A payload with no locator at all is a percentage, and that is legal. */
    @Test
    fun `a payload with no locator is still a usable percentage`() {
        val position = PortableProgress.positionOf(
            buildJsonObject { put("progress_percent", JsonPrimitive(7.0)) },
        )

        assertNull(position.href)
        assertNull(position.progression)
        assertEquals(7.0, position.percent!!, 1e-9)
    }

    // ------------------------------------------------------------------- helpers

    /** A canonical `location` around [locator], as reader-api returns one. */
    private fun location(locator: JsonObject): JsonObject = buildJsonObject {
        put("contract_version", JsonPrimitive(PORTABLE_SEMANTICS_VERSION))
        put(
            "publication",
            buildJsonObject {
                put("publication_id", JsonPrimitive(BOOK_ID))
                put("format", JsonPrimitive(LOCATOR_FORMAT_EPUB))
                put("media_type", JsonPrimitive(MEDIA_TYPE_EPUB))
                put("source", JsonPrimitive(PUBLICATION_SOURCE_ACCOUNT))
            },
        )
        put("locator", locator)
    }

    private fun progressPayload(bookId: String?): JsonObject = buildJsonObject {
        bookId?.let { put("book_id", JsonPrimitive(it)) }
        put("progress_percent", JsonPrimitive(12.0))
        put("updated_at", JsonPrimitive("2026-09-20T10:00:00Z"))
    }

    private companion object {
        const val BOOK_ID: String = "7f1c7a0e-0b8e-4d8a-9a52-3f0f7c1d2e11"
        const val SPINE_PATH: String = "OEBPS/chapter-01.xhtml"
        const val CHAPTER_TITLE: String = "Chapter One"
    }
}
