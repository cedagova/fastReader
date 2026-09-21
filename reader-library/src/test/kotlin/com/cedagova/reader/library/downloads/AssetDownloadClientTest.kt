package com.cedagova.reader.library.downloads

import com.cedagova.reader.library.imports.assertRaises
import com.cedagova.reader.library.model.ReaderAssetDirection
import com.cedagova.reader.library.model.ReaderAssetMethod
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download, against a provider that behaves like one.
 *
 * Everything here is the real [AssetDownloadClient] over [FakeObjectStorage]'s
 * mock engine, so each assertion is about bytes and headers that were actually
 * sent. Two of these are non-negotiables of #118 in their own right: the
 * account bearer never reaches the storage host, and the only address this
 * client will fetch is the one the grant carries.
 */
class AssetDownloadClientTest {

    private val bytes = bookBytes(SIZE)
    private val storage = FakeObjectStorage(bytes)
    private val client = AssetDownloadClient.createForTests(storage.engine)

    @Test
    fun `the whole object arrives, byte for byte, without being buffered whole`() = runTest {
        val sink = ByteArrayOutputStream()
        val progress = mutableListOf<Pair<Long, Long>>()

        val written = client.download(downloadGrant(bytes), sink) { done, total -> progress += done to total }

        assertEquals(SIZE.toLong(), written)
        assertArrayEquals("the device holds exactly the object", bytes, sink.toByteArray())
        assertEquals("one GET, no retries", 1, storage.requests.size)
        assertEquals("GET", storage.requests.single().method)
        assertTrue("progress must be reported at least once", progress.isNotEmpty())
        assertEquals(
            "the last progress report is the whole object",
            SIZE.toLong() to SIZE.toLong(),
            progress.last(),
        )
        assertTrue(
            "every progress total is the grant's own size, never a guess",
            progress.all { it.second == SIZE.toLong() },
        )
    }

    /**
     * The non-negotiable this whole class exists for.
     *
     * The client cannot send a bearer — it holds no session to take one from —
     * and this is the behavioural proof: every header of every request the
     * provider saw, checked for `Authorization`, for the word `Bearer` and for
     * the literal session value an authenticated client would have used.
     */
    @Test
    fun `no storage request carries a bearer or the account's session value`() = runTest {
        client.download(downloadGrant(bytes), ByteArrayOutputStream())

        assertTrue("the provider must have seen a request to judge", storage.requests.isNotEmpty())
        storage.requests.forEach { request ->
            assertFalse(
                "a storage request carried an Authorization header: ${request.method} ${request.path}",
                request.headers.keys.any { it.equals("Authorization", ignoreCase = true) },
            )
            assertFalse(
                "a storage request carried a session value: ${request.method} ${request.path}",
                request.headers.values.any { it.contains("Bearer", ignoreCase = true) },
            )
            assertFalse(
                "a storage request carried the account's access token",
                request.headers.values.any { it.contains(ACCOUNT_BEARER) },
            )
        }
    }

    @Test
    fun `the request goes to the grant's own URL with the grant's own headers`() = runTest {
        client.download(downloadGrant(bytes), ByteArrayOutputStream())

        val request = storage.requests.single()
        assertEquals("storage.test", request.host)
        assertEquals("/storage/v1/object/sign/reader/0f1e/book.epub", request.path)
        assertEquals("the signature in the URL must survive untouched", "token=signed", request.query)
        DOWNLOAD_HEADERS.forEach { (name, value) ->
            val sent = request.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            assertNotNull("${request.method} ${request.path} carried no $name", sent)
            assertEquals(value, sent!!.value)
        }
    }

    /** A grant for the other direction is a grant this client must not spend. */
    @Test
    fun `an upload grant is refused before anything is requested`() = runTest {
        assertRaises<IllegalArgumentException> {
            client.download(
                downloadGrant(bytes, direction = ReaderAssetDirection.UPLOAD, method = ReaderAssetMethod.PUT),
                ByteArrayOutputStream(),
            )
        }
        assertEquals("nothing may be requested under the wrong grant", 0, storage.requests.size)
    }

    @Test
    fun `a spent grant is reported as rejected, never retried with anything else`() = runTest {
        storage.rejectWith = 401

        val rejected = assertRaises<AssetDownloadException.GrantRejected> {
            client.download(downloadGrant(bytes), ByteArrayOutputStream())
        }

        assertEquals(401, rejected.status)
        storage.requests.forEach { request ->
            assertFalse(
                "a rejected grant must never be retried with a bearer",
                request.headers.keys.any { it.equals("Authorization", ignoreCase = true) },
            )
        }
    }

    /**
     * The TTL case the plan's assumption names: a grant that expires mid-run.
     *
     * The client does not re-fetch on its own — asking reader-api is the
     * caller's business, and the caller is the only thing that holds a session.
     * What it must do is report the spent grant as *this* branch, so a fresh
     * grant is the obvious answer, and then work when given one.
     */
    @Test
    fun `a fresh grant after an expiry succeeds with no bytes lost`() = runTest {
        storage.rejectWith = 403
        storage.rejectFirst = 1
        val first = ByteArrayOutputStream()

        assertRaises<AssetDownloadException.GrantRejected> { client.download(downloadGrant(bytes), first) }
        assertEquals("nothing may be written from a rejected response", 0, first.size())

        val second = ByteArrayOutputStream()
        val written = client.download(downloadGrant(bytes), second)

        assertEquals(SIZE.toLong(), written)
        assertArrayEquals(bytes, second.toByteArray())
        assertEquals("exactly one retry, under the new grant", 2, storage.requests.size)
    }

    @Test
    fun `a provider refusal is reported with its status and nothing of its body`() = runTest {
        storage.refuseWith = 416

        val refused = assertRaises<AssetDownloadException.Refused> {
            client.download(downloadGrant(bytes), ByteArrayOutputStream())
        }

        assertEquals(416, refused.status)
        assertFalse("a provider message must never travel", refused.message!!.contains("provider says no"))
    }

    @Test
    fun `a provider fault is reported as unavailable, so a later attempt is the answer`() = runTest {
        storage.refuseWith = 503

        assertRaises<AssetDownloadException.Unavailable> {
            client.download(downloadGrant(bytes), ByteArrayOutputStream())
        }
    }

    /** A body shorter than the grant declares is the provider breaking its own promise. */
    @Test
    fun `a short body is a protocol failure, not a shorter book`() = runTest {
        storage.serve = bytes.copyOf(SIZE / 2)

        val failure = assertRaises<AssetDownloadException.Protocol> {
            client.download(downloadGrant(bytes), ByteArrayOutputStream())
        }

        assertTrue(failure.message!!.contains("${SIZE / 2}"))
    }

    /** A full disk surfaces from the sink, and the caller is the one that cleans up. */
    @Test
    fun `a sink that cannot take the bytes fails the download`() = runTest {
        assertRaises<AssetDownloadException.Unavailable> {
            client.download(downloadGrant(bytes), FullDisk())
        }
    }

    private class FullDisk : OutputStream() {
        override fun write(b: Int): Unit = throw IOException("No space left on device")
        override fun write(b: ByteArray, off: Int, len: Int): Unit = throw IOException("No space left on device")
    }

    private companion object {
        /** Larger than the client's 64 KiB read buffer, so more than one read is needed. */
        const val SIZE = 200_000
    }
}
