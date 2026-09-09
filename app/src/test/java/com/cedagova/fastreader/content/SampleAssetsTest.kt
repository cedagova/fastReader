package com.cedagova.fastreader.content

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.epub.ArchiveOpen
import com.cedagova.fastreader.epub.ArchiveReadStrategy
import com.cedagova.fastreader.epub.EpubArchives
import com.cedagova.fastreader.reader.BookOpenRequest
import com.cedagova.fastreader.reader.BookOrigin
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The assets shipped inside the APK, checked against the identities pinned for
 * them and read through the real open path.
 *
 * The digest assertion is the gate that makes "identity is fixed at build time"
 * safe: `scripts/build-sample-epubs.py` rebuilds the assets deterministically,
 * and if a source text changes without [BundledSample] being updated, this test
 * fails with the digest to paste in rather than shipping an identity that
 * matches nothing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SampleAssetsTest {

    private val assets = RuntimeEnvironment.getApplication().assets

    @Test
    fun `every bundled sample asset exists and hashes to its pinned identity`() {
        BundledSample.entries.forEach { sample ->
            val bytes = assets.open(sample.assetPath).use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            assertEquals(
                "${sample.assetPath} changed; update BundledSample.${sample.name}",
                sample.identity.value,
                "sha256:$digest",
            )
        }
    }

    @Test
    fun `a sample stays small enough to be free in the APK`() {
        val total = BundledSample.entries.sumOf { sample ->
            assets.open(sample.assetPath).use { it.readBytes() }.size
        }
        // Both samples together. Deliberately generous: the point is to catch a
        // sample that grew into a book, not to police a few hundred bytes.
        assertTrue("bundled samples total $total bytes", total < 64 * 1024)
    }

    @Test
    fun `a sample opens through the central directory rather than the whole file`() {
        BundledSample.entries.forEach { sample ->
            val source = SampleBookSource(assets, sample)
            assertNotNull("${sample.name} has no seekable view", source.openChannel())
            val opened = EpubArchives.open(source)
            assertTrue("${sample.name} would not open", opened is ArchiveOpen.Opened)
            (opened as ArchiveOpen.Opened).archive.use {
                assertEquals(sample.name, ArchiveReadStrategy.DIRECTORY, it.strategy)
            }
        }
    }

    @Test
    fun `the seekable view reads the same bytes as the stream`() {
        BundledSample.entries.forEach { sample ->
            val source = SampleBookSource(assets, sample)
            val streamed = source.open().use { it.readBytes() }
            val channel = requireNotNull(source.openChannel())
            val seeked = channel.use { open ->
                assertEquals(streamed.size.toLong(), open.size())
                val buffer = java.nio.ByteBuffer.allocate(streamed.size)
                while (buffer.hasRemaining()) {
                    if (open.read(buffer) < 0) break
                }
                buffer.array()
            }
            assertTrue(sample.name, streamed.contentEquals(seeked))
        }
    }

    @Test
    fun `each sample parses into a readable stream in its own language`() = runTest {
        val expected = mapOf(
            BundledSample.ENGLISH to Triple("en", "lighthouse", "About this text"),
            BundledSample.SPANISH to Triple("es", "farero", "Sobre este texto"),
        )
        BundledSample.entries.forEach { sample ->
            val (language, word, aboutTitle) = expected.getValue(sample)
            val result = EpubContentPipeline().parse(SampleBookSource(assets, sample), sample.identity)
            val content = (result as BookContentResult.Parsed).content

            assertEquals(sample.name, sample.identity.value, content.bookDigest)
            assertEquals(sample.name, language, content.language)
            assertTrue(sample.name, content.gaps.isEmpty())
            // Two spine items: the story, then the page that names its source.
            assertEquals(sample.name, 2, content.chapters.size)
            assertEquals(sample.name, aboutTitle, content.chapters.last().title)

            val words = content.tokens.filterIsInstance<WordToken>().map { it.text }
            assertTrue(sample.name, words.size > 200)
            assertTrue("$sample.name is missing '$word'", words.any { it.contains(word, ignoreCase = true) })
        }
    }

    /**
     * REQ-109's privacy half, at the layer that decides it: the sample carries the
     * origin that means "no catalog row, no grant to persist", and its identity is
     * the one pinned for it rather than anything computed while opening.
     */
    @Test
    fun `the open request for a sample is a sample`() {
        BundledSample.entries.forEach { sample ->
            val request = BookOpenRequest.sample(sample, SampleBookSource(assets, sample))
            assertEquals(BookOrigin.SAMPLE, request.origin)
            assertEquals(sample.identity, request.identity)
            assertEquals(sample.openKey, request.openKey)
            assertEquals(sample.title, request.title)
        }
    }

    /** The licence has to be reachable from inside the text, not only from the repo. */
    @Test
    fun `each sample names its own licence in its own text`() = runTest {
        BundledSample.entries.forEach { sample ->
            val result = EpubContentPipeline().parse(SampleBookSource(assets, sample), sample.identity)
            val words = (result as BookContentResult.Parsed).content
                .tokens.filterIsInstance<WordToken>()
                .joinToString(" ") { it.text }
            assertTrue(sample.name, words.contains("CC0"))
            assertTrue(sample.name, words.contains("FastReader"))
        }
    }
}
