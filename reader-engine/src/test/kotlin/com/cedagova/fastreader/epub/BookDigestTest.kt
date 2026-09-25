package com.cedagova.fastreader.epub

import com.cedagova.fastreader.content.ContentFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one property [BookDigest] has to have: it is the *same* identity the
 * catalog already uses (AD-2, AD-8).
 *
 * A book opened from outside the app is hashed here, and its reading position is
 * stored under the result. If that value ever drifted from what
 * [EpubInspector] computes when the same file is added to the library, the
 * position written while reading it would be invisible to the row that appeared
 * afterwards — which is the exact promise REQ-103 makes ("the position is kept by
 * book identity either way"), failing silently.
 *
 * So this is not a hash test. It is a test that two code paths agree, over the
 * kinds of file the catalog actually meets, including the ones that are not
 * readable books at all — because an unusable file still has an identity.
 */
class BookDigestTest {

    @Test
    fun `the digest is the identity the catalog would give the same file`() {
        val files = mapOf(
            "a plain book" to EpubFixtures.validEpub(),
            "a book with a cover" to EpubFixtures.epub2CoverEpub(),
            "a Spanish book" to EpubFixtures.spanishEpub(withCover = true),
            "a novel with an image and a table" to ContentFixtures.englishNovel(),
            "an illustrated book" to ContentFixtures.illustratedNovel().first,
            "a DRM-protected book" to EpubFixtures.drmProtectedEpub(),
            "a damaged archive" to EpubFixtures.truncatedZip(),
            "a file that is not a zip at all" to EpubFixtures.notAZip(),
        )

        files.forEach { (what, bytes) ->
            val source = ContentFixtures.source(bytes)
            val expected = EpubInspector.inspect(source).contentDigest

            assertEquals(what, expected, BookDigest.of(source)?.value)
        }
    }

    @Test
    fun `two different books never share an identity`() {
        val first = BookDigest.of(ContentFixtures.source(EpubFixtures.validEpub()))
        val second = BookDigest.of(ContentFixtures.source(EpubFixtures.spanishEpub()))

        assertEquals("sha256:", first?.value?.take(7))
        assertEquals(71, first?.value?.length)
        assert(first != second)
    }

    /**
     * A grant that was revoked between the open and the hash. Nothing to key a
     * position by, and nothing worth interrupting the reader for: the book on
     * screen was already parsed and goes on being readable.
     */
    @Test
    fun `bytes that cannot be read have no identity`() {
        assertNull(BookDigest.of(EpubByteSource { throw java.io.IOException("access was revoked") }))
    }
}
