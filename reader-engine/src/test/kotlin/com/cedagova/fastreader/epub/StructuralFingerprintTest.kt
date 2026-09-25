package com.cedagova.fastreader.epub

import com.cedagova.fastreader.content.ContentFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second stored signal (AD-18): can an open tell that the file changed?
 *
 * Identity cannot answer that any more — since v1.1.0 the reader is handed a
 * book's identity rather than deriving it from the bytes (AD-8), so a file
 * replaced in place under an unchanged catalog entry keeps the same one. These
 * tests go through [EpubArchives.open] rather than at [StructuralFingerprint]
 * directly, because what has to hold is not "SHA-256 works" but that the value
 * reaches the caller off the *directory read the open already does*, and that it
 * is absent exactly where the guard must not fire.
 */
class StructuralFingerprintTest {

    @Test
    fun `the same book read twice fingerprints the same`() {
        val bytes = ContentFixtures.englishNovel()

        assertEquals(fingerprintOf(bytes), fingerprintOf(bytes.copyOf()))
    }

    /**
     * The whole point of retaining the CRC-32.
     *
     * The two books differ in one character of one chapter, and the substitution
     * is the same length — so every entry name matches, every uncompressed size
     * matches, and the only field in the central directory that moved is that
     * entry's CRC-32. A fingerprint built from names and sizes alone would call
     * these the same file.
     */
    @Test
    fun `one edited chapter changes the fingerprint even at the same size`() {
        val original = EpubFixtures.validEpub(bodyText = "One word at a time.")
        val edited = EpubFixtures.validEpub(bodyText = "One word at a tyme.")
        assertEquals("the fixture must differ only in content", original.size, edited.size)

        assertNotEquals(fingerprintOf(original), fingerprintOf(edited))
    }

    @Test
    fun `renaming an entry changes the fingerprint`() {
        val original = EpubFixtures.buildArchive(
            listOf("a.txt" to "x".toByteArray(), "b.txt" to "y".toByteArray()),
        )
        val renamed = EpubFixtures.buildArchive(
            listOf("a.txt" to "x".toByteArray(), "c.txt" to "y".toByteArray()),
        )

        assertNotEquals(fingerprintOf(original), fingerprintOf(renamed))
    }

    /**
     * A change detector, not a tamper check: the same book rebuilt from the same
     * content is the same book, and a reader who re-downloads or re-copies it must
     * resume rather than restart.
     */
    @Test
    fun `a book rebuilt from identical content fingerprints the same`() {
        val first = EpubFixtures.validEpub(title = "The Quiet Machine")
        val second = EpubFixtures.validEpub(title = "The Quiet Machine")
        assertTrue("the fixture must be byte-identical", first.contentEquals(second))

        assertEquals(fingerprintOf(first), fingerprintOf(second))
    }

    @Test
    fun `the fingerprint names its own encoding`() {
        val fingerprint = fingerprintOf(ContentFixtures.englishNovel())!!

        assertTrue(fingerprint, fingerprint.startsWith(StructuralFingerprint.PREFIX))
        assertEquals(StructuralFingerprint.PREFIX.length + 64, fingerprint.length)
    }

    // --- where there is no fingerprint, and therefore no guard ---

    @Test
    fun `a source that cannot seek yields no fingerprint`() {
        val source = ContentFixtures.streamingSource(ContentFixtures.englishNovel())

        val archive = (EpubArchives.open(source) as ArchiveOpen.Opened).archive
        archive.use {
            assertEquals(ArchiveReadStrategy.STREAMING, it.strategy)
            assertNull(it.structuralFingerprint)
        }
    }

    /**
     * The case seekability alone would get wrong. This file has a perfectly good
     * channel; [ZipDirectory] refuses its *layout* — here a spanned-archive marker,
     * which stands for ZIP64 and over-`MAX_ENTRIES` too — and the open falls back
     * to streaming. Keying the guard on the strategy actually used is what covers
     * it.
     */
    @Test
    fun `a seekable archive whose layout the directory reader refuses yields no fingerprint`() {
        val spanned = markAsSpanned(ContentFixtures.englishNovel())
        val source = ContentFixtures.source(spanned)

        val archive = (EpubArchives.open(source) as ArchiveOpen.Opened).archive
        archive.use {
            assertEquals(ArchiveReadStrategy.STREAMING, it.strategy)
            assertNull(it.structuralFingerprint)
        }
    }

    // --- helpers ---

    private fun fingerprintOf(bytes: ByteArray): String? {
        val archive = (EpubArchives.open(ContentFixtures.source(bytes)) as ArchiveOpen.Opened).archive
        return archive.use {
            assertEquals(ArchiveReadStrategy.DIRECTORY, it.strategy)
            it.structuralFingerprint
        }
    }

    /**
     * Sets the end-of-central-directory record's disk number, which makes the
     * archive a multi-disk one as far as [ZipDirectory] is concerned while leaving
     * every byte the forward reader looks at untouched.
     */
    private fun markAsSpanned(bytes: ByteArray): ByteArray {
        val patched = bytes.copyOf()
        for (index in patched.size - END_RECORD_BYTES downTo 0) {
            val signature = patched[index] == 0x50.toByte() && patched[index + 1] == 0x4B.toByte() &&
                patched[index + 2] == 0x05.toByte() && patched[index + 3] == 0x06.toByte()
            if (!signature) continue
            patched[index + 4] = 1
            return patched
        }
        throw AssertionError("no end-of-central-directory record in the fixture")
    }

    private companion object {
        const val END_RECORD_BYTES = 22
    }
}
