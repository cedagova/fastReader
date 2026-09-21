package com.cedagova.fastreader.account.library

import com.cedagova.fastreader.content.BookContentResult
import com.cedagova.fastreader.content.BookIdentity
import com.cedagova.fastreader.content.EpubContentPipeline
import com.cedagova.fastreader.epub.ArchiveOpen
import com.cedagova.fastreader.epub.ArchiveReadStrategy
import com.cedagova.fastreader.epub.EpubArchives
import com.cedagova.fastreader.epub.EpubFixtures
import com.cedagova.fastreader.epub.FileEpubByteSource
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The one rule of the copy store, from every side (#118, REQ-510).
 *
 * A copy is never readable before its content SHA-256 matches. Each test below
 * finishes by asking the store what it holds, because "nothing was placed" is
 * the claim, and a refusal that left a file behind would be the bug worth
 * catching.
 */
class AccountCopyStoreTest {

    @get:Rule val temporary = TemporaryFolder()

    private val bytes = EpubFixtures.validEpub()
    private val digest = sha256(bytes)

    private val store: AccountCopyStore by lazy {
        AccountCopyStore(File(temporary.root, AccountCopyStore.DIRECTORY_NAME))
    }

    @Test
    fun `bytes that match their identity are verified and placed`() = runTest {
        val placement = store.place(digest, bytes.size.toLong()) { sink -> sink.write(bytes) }

        val placed = (placement as CopyPlacement.Placed).file
        assertTrue("the placed copy is a file", placed.isFile)
        assertArrayEquals("the copy is the book, byte for byte", bytes, placed.readBytes())
        assertTrue(store.has(digest))
        assertEquals(placed, store.copy(digest))
        assertEquals(bytes.size.toLong(), store.sizeBytes(digest))
        assertEquals(setOf(digest), store.contents())
        assertEquals("nothing partial may survive a success", 0, partials().size)
    }

    /** The prefix the contract makes optional, and case, must not decide anything. */
    @Test
    fun `an identity is the same identity with or without the sha256 prefix`() = runTest {
        store.place("sha256:" + digest.uppercase(), bytes.size.toLong()) { sink -> sink.write(bytes) }

        assertTrue(store.has(digest))
        assertTrue(store.has("sha256:$digest"))
        assertEquals(setOf(digest), store.contents())
    }

    /**
     * The acceptance criterion: tampered bytes are refused and nothing is
     * placed.
     *
     * One byte is changed in the middle of a real EPUB, so the archive is still
     * perfectly openable — which is the point. Nothing about this file is wrong
     * except that it is not the file the account holds, and the digest is the
     * only thing that can tell.
     */
    @Test
    fun `tampered bytes are refused with both digests and nothing is placed`() = runTest {
        val tampered = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }

        val placement = store.place(digest, bytes.size.toLong()) { sink -> sink.write(tampered) }

        val mismatch = placement as CopyPlacement.Mismatched
        assertEquals(digest, mismatch.expected)
        assertEquals(sha256(tampered), mismatch.actual)
        assertFalse("a mismatched download must never be readable", store.has(digest))
        assertNull(store.copy(digest))
        assertEquals(emptySet<String>(), store.contents())
        assertEquals("the temporary file must be gone before place returns", 0, partials().size)
    }

    /** A truncated body has the same answer: it is not the book. */
    @Test
    fun `a short body is refused and nothing is placed`() = runTest {
        val placement = store.place(digest, bytes.size.toLong()) { sink -> sink.write(bytes, 0, bytes.size / 2) }

        assertTrue(placement is CopyPlacement.Mismatched)
        assertFalse(store.has(digest))
        assertEquals(0, partials().size)
    }

    /**
     * A body whose digest is right but whose length is not is a contradiction
     * in the grant, refused rather than adopted.
     *
     * It cannot be reached by ordinary means — the digest fixes the bytes and
     * the bytes fix the length — which is exactly why the check is here: it
     * catches a grant that describes a different object from the one it signs.
     */
    @Test
    fun `a length the grant contradicts is refused even when the digest agrees`() = runTest {
        val placement = store.place(digest, bytes.size.toLong() + 1) { sink -> sink.write(bytes) }

        assertTrue(placement is CopyPlacement.Refused)
        assertFalse(store.has(digest))
        assertEquals(0, partials().size)
    }

    @Test
    fun `an identity that is not a content digest is refused before anything is written`() = runTest {
        val placement = store.place("not-a-digest", 0) { sink -> sink.write(bytes) }

        assertTrue(placement is CopyPlacement.Refused)
        assertEquals(emptySet<String>(), store.contents())
    }

    /** The storage-full refusal REQ-510 names, with the temporary file deleted. */
    @Test
    fun `a full disk is reported as no storage and leaves nothing behind`() = runTest {
        val placement = store.place(digest, bytes.size.toLong()) {
            throw IOException("write failed: ENOSPC (No space left on device)")
        }

        assertTrue(placement is CopyPlacement.NoStorage)
        assertFalse(store.has(digest))
        assertEquals(0, partials().size)
    }

    /** A full disk reported through a wrapper is still a full disk. */
    @Test
    fun `a full disk wrapped in another failure is still recognised`() = runTest {
        val placement = store.place(digest, bytes.size.toLong()) {
            throw IllegalStateException("the copy failed", IOException("No space left on device"))
        }

        assertTrue(placement is CopyPlacement.NoStorage)
        assertEquals(0, partials().size)
    }

    @Test
    fun `a dropped connection is a failed attempt, and nothing is placed`() = runTest {
        val placement = store.place(digest, bytes.size.toLong()) { sink ->
            sink.write(bytes, 0, 64)
            throw IOException("connection reset")
        }

        val failed = placement as CopyPlacement.Failed
        assertEquals("connection reset", failed.error.message)
        assertFalse(store.has(digest))
        assertEquals(0, partials().size)
    }

    /**
     * The app-death case: a `.part` file with no process behind it.
     *
     * It is written by hand rather than by interrupting a real placement,
     * because what is being proved is that a file the store never finished with
     * is unreachable and swept — not how it came to exist.
     */
    @Test
    fun `a partial download left by a dead process is unreachable and swept`() = runTest {
        val directory = File(temporary.root, AccountCopyStore.DIRECTORY_NAME).apply { mkdirs() }
        val orphan = File(directory, AccountCopyStore.PREFIX + digest + AccountCopyStore.PARTIAL_SUFFIX)
        orphan.writeBytes(bytes.copyOf(100))

        assertFalse("a partial must never be readable", store.has(digest))
        assertNull(store.copy(digest))
        assertEquals("a partial is not a copy this device holds", emptySet<String>(), store.contents())

        assertEquals(1, store.discardPartials())
        assertFalse(orphan.exists())
    }

    @Test
    fun `deleting a copy removes the file and leaves the rest alone`() = runTest {
        val other = EpubFixtures.spanishEpub()
        store.place(digest, bytes.size.toLong()) { sink -> sink.write(bytes) }
        store.place(sha256(other), other.size.toLong()) { sink -> sink.write(other) }

        assertTrue(store.delete(digest))

        assertFalse(store.has(digest))
        assertEquals("only the one copy goes", setOf(sha256(other)), store.contents())
        assertFalse("deleting a copy that is gone is not a failure", store.delete(digest))
    }

    /**
     * The verified copy opens through the reading pipeline's own archive path
     * and yields a structural fingerprint (#118's acceptance).
     *
     * The fingerprint is what a stored reading position is guarded by (AD-18),
     * and it is computed from the zip central directory — which only the
     * *directory* strategy reads. A copy that opened by streaming would produce
     * no fingerprint at all, so this is one assertion about two things: the copy
     * is readable, and it is readable the cheap way (REQ-110).
     */
    @Test
    fun `a placed copy opens through the pipeline with a structural fingerprint`() = runTest {
        val placed = (store.place(digest, bytes.size.toLong()) { sink -> sink.write(bytes) } as CopyPlacement.Placed)
        val source = FileEpubByteSource(placed.file)

        assertNotNull("a file-backed source must be seekable", source.openChannel())
        assertEquals(
            "a private copy must open the cheap way, not by streaming",
            ArchiveReadStrategy.DIRECTORY,
            (EpubArchives.open(source) as ArchiveOpen.Opened).archive.use { it.strategy },
        )

        val result = EpubContentPipeline().parse(source, BookIdentity.ofSha256Hex(digest))
        val content = (result as BookContentResult.Parsed).content

        assertEquals("the position's identity is the copy's own", "sha256:$digest", content.bookDigest)
        assertNotNull("a copy must yield the fingerprint a stored position is guarded by", content.structuralFingerprint)
        assertTrue(content.structuralFingerprint!!.isNotBlank())
        assertTrue("the copy really is the book", content.tokens.isNotEmpty())
    }

    private fun partials(): List<File> =
        (File(temporary.root, AccountCopyStore.DIRECTORY_NAME).listFiles() ?: emptyArray())
            .filter { it.name.endsWith(AccountCopyStore.PARTIAL_SUFFIX) }

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
}
