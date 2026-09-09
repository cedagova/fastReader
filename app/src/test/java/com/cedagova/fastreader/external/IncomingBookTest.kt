package com.cedagova.fastreader.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which intents FastReader will read a book out of (REQ-103).
 *
 * The interesting part of "Open with" is not the reading — that is the same
 * pipeline as every other book — but the unpacking, and it is exactly the part
 * that is expensive to check by hand: every combination needs a sender, a device
 * and a share sheet. Keeping it a pure function over the intent's fields makes
 * each rule one assertion.
 */
class IncomingBookTest {

    private val document = "content://com.android.providers.downloads/document/42"

    @Test
    fun `open with carries the book in the intent's own data`() {
        val book = incomingBook(action = VIEW, dataUri = document, streamUri = null)

        assertEquals(IncomingBook(document), book)
    }

    @Test
    fun `the share sheet carries it in the stream extra instead`() {
        val book = incomingBook(action = SEND, dataUri = null, streamUri = document)

        assertEquals(IncomingBook(document), book)
    }

    /**
     * The two are not interchangeable. A share that put nothing in `EXTRA_STREAM`
     * is a share of text, not of a book, and reading the intent's data instead
     * would open whatever else that intent happened to name.
     */
    @Test
    fun `a share with no stream is not a book`() {
        assertNull(incomingBook(action = SEND, dataUri = document, streamUri = null))
    }

    @Test
    fun `a view with no data is not a book`() {
        assertNull(incomingBook(action = VIEW, dataUri = null, streamUri = document))
    }

    /** Launching the app normally must not look like a hand-over. */
    @Test
    fun `an ordinary launch hands over nothing`() {
        assertNull(incomingBook(action = "android.intent.action.MAIN", dataUri = null, streamUri = null))
        assertNull(incomingBook(action = null, dataUri = null, streamUri = null))
    }

    /**
     * REQ-303 and the manifest, enforced twice on purpose. The filters register
     * only `content://`, but an intent can always name a scheme no filter matched
     * — a `file://` book would need a storage permission this app does not hold,
     * and an `https://` one the network permission it must never request.
     */
    @Test
    fun `only content uris are accepted`() {
        assertNull(incomingBook(VIEW, dataUri = "file:///sdcard/Books/quiet.epub", streamUri = null))
        assertNull(incomingBook(VIEW, dataUri = "https://example.org/quiet.epub", streamUri = null))
        assertNull(incomingBook(SEND, dataUri = null, streamUri = "file:///sdcard/Books/quiet.epub"))
    }

    /**
     * The bytes decide what a file is, not its declared type. A book arriving as
     * `application/octet-stream` from a mail client is the ordinary case; one that
     * turns out not to be an EPUB lands on the reader's existing explanation.
     */
    @Test
    fun `the declared mime type does not gate the hand-over`() {
        assertEquals(IncomingBook(document), incomingBook(VIEW, dataUri = document, streamUri = null))
    }

    private companion object {
        const val VIEW = ExternalOpenActions.VIEW
        const val SEND = ExternalOpenActions.SEND
    }
}
