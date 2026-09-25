package com.cedagova.reader.library.imports

import com.cedagova.reader.library.model.PublicationTransferGrant
import com.cedagova.reader.library.testing.publicationTransferClientOver
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resumable transfer, against a provider that behaves like one.
 *
 * Everything here is the real [PublicationTransferClient] over
 * [FakeStorage]'s mock engine, so each assertion is about bytes and headers
 * that were actually sent. Three of these tests are acceptance criteria of
 * #116 in their own right: a full transfer in more than one chunk, a resume
 * from a `HEAD` offset that re-sends nothing, and the absence of any bearer on
 * a storage request.
 */
class PublicationTransferClientTest {

    private val bytes = publicationBytes(SIZE)
    private val storage = FakeStorage()
    private val client = publicationTransferClientOver(storage.engine)

    private fun grant(
        chunkSizeBytes: Long = CHUNK,
        endpoint: String = TUS_ENDPOINT,
        expiresAt: String = "2026-09-17T12:00:00Z",
    ) = PublicationTransferGrant(
        endpoint = endpoint,
        headers = GRANT_HEADERS,
        metadata = mapOf("bucket" to "publications", "objectName" to "reader/0f1e/source.epub"),
        chunkSizeBytes = chunkSizeBytes,
        expiresAt = expiresAt,
        checksum = "sha256:${sha256Hex(bytes)}",
        sizeBytes = SIZE.toLong(),
    )

    @Test
    fun `a full transfer goes out in chunks of at most the grant's size`() = runTest {
        val grant = grant()
        val location = client.create(grant)
        val end = client.transfer(grant, location, InMemoryPublication(bytes))

        assertEquals(SIZE.toLong(), end)
        assertArrayEquals("the provider holds exactly the file", bytes, storage.received)
        assertTrue(
            "a full transfer of $SIZE bytes in $CHUNK-byte chunks is more than one PATCH",
            storage.patchSizes.size >= 2,
        )
        assertEquals(listOf(CHUNK.toInt(), CHUNK.toInt(), (SIZE - 2 * CHUNK).toInt()), storage.patchSizes.toList())
        assertTrue(
            "no chunk may exceed the grant's published size",
            storage.patchSizes.all { it <= CHUNK },
        )
        assertEquals(listOf(0L, CHUNK, 2 * CHUNK), storage.patchOffsets.toList())
    }

    /**
     * The acceptance criterion the whole design of this class exists for.
     *
     * The client cannot send a bearer — it holds no session to take one from —
     * and this is the behavioural proof: every header of every request the
     * provider saw, checked for `Authorization` and for the session value the
     * authenticated client would have used.
     */
    @Test
    fun `no storage request carries a bearer or any session value`() = runTest {
        val grant = grant()
        val location = client.create(grant)
        client.transfer(grant, location, InMemoryPublication(bytes))
        client.offset(grant, location)

        assertTrue("the provider must have seen requests to judge", storage.requests.size >= 5)
        storage.requests.forEach { request ->
            assertFalse(
                "a storage request carried an Authorization header: ${request.method} ${request.path}",
                request.headers.keys.any { it.equals("Authorization", ignoreCase = true) },
            )
            assertFalse(
                "a storage request carried a session value: ${request.method} ${request.path}",
                request.headers.values.any { it.contains("Bearer", ignoreCase = true) },
            )
        }
    }

    @Test
    fun `every storage request carries the grant's own headers`() = runTest {
        val grant = grant()
        val location = client.create(grant)
        client.transfer(grant, location, InMemoryPublication(bytes))
        client.offset(grant, location)

        storage.requests.forEach { request ->
            GRANT_HEADERS.forEach { (name, value) ->
                val sent = request.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                assertNotNull("${request.method} ${request.path} carried no $name", sent)
                assertEquals(value, sent!!.value)
            }
            assertEquals(
                PublicationTransferClient.TUS_VERSION,
                request.headers.entries.first {
                    it.key.equals(PublicationTransferClient.HEADER_TUS_RESUMABLE, true)
                }.value,
            )
        }
    }

    /**
     * An interrupted transfer, resumed from the provider's own offset.
     *
     * The provider keeps part of the chunk it was reading when the connection
     * died, so its durable offset is not on a chunk boundary — which is the
     * case a client that resumed from "chunks sent so far" would get wrong. The
     * proof is [FakeStorage.received]: it is the file, byte for byte, which it
     * could not be if anything before the offset had been sent twice.
     */
    @Test
    fun `an interrupted transfer resumes from the HEAD offset and re-sends nothing`() = runTest {
        val grant = grant()
        val location = client.create(grant)

        storage.interruptNextPatchAfter = KEPT
        assertRaises<PublicationTransferException.Unavailable> {
            client.transfer(grant, location, InMemoryPublication(bytes))
        }
        assertEquals("the provider kept what reached it", KEPT.toLong(), storage.offset)

        // A new process, a new client, the same location: exactly what a resume
        // after app death has to work with.
        val resumed = publicationTransferClientOver(storage.engine)
        val from = resumed.offset(grant, location)
        assertEquals(KEPT.toLong(), from)

        val end = resumed.transfer(grant, location, InMemoryPublication(bytes), from)

        assertEquals(SIZE.toLong(), end)
        assertArrayEquals("no byte was re-sent and none was skipped", bytes, storage.received)
        assertTrue(
            "the resumed transfer started at the provider's offset",
            storage.patchOffsets.drop(1).all { it >= KEPT },
        )
    }

    /** The same resume, on a source that can seek: the bytes are read by position. */
    @Test
    fun `a seekable source resumes by position`() = runTest {
        val grant = grant()
        val location = client.create(grant)
        val source = FilePublication(bytes)

        storage.interruptNextPatchAfter = KEPT
        assertRaises<PublicationTransferException.Unavailable> { client.transfer(grant, location, source) }
        val end = client.transfer(grant, location, source, client.offset(grant, location))

        assertEquals(SIZE.toLong(), end)
        assertArrayEquals(bytes, storage.received)
        assertEquals("a seekable source is never streamed from the start", 0, source.opens)
    }

    @Test
    fun `an offset conflict is recovered by re-reading the provider's offset`() = runTest {
        val grant = grant()
        val location = client.create(grant)
        storage.conflictNextPatch = true

        val end = client.transfer(grant, location, InMemoryPublication(bytes))

        assertEquals(SIZE.toLong(), end)
        assertArrayEquals(bytes, storage.received)
        assertTrue("the client re-read the offset rather than guessing", storage.countOf("HEAD") >= 1)
    }

    @Test
    fun `a spent grant is reported as a rejected grant, never retried with anything else`() = runTest {
        val grant = grant()
        val location = client.create(grant)
        storage.rejectWith = 401

        val rejected = assertRaises<PublicationTransferException.GrantRejected> {
            client.transfer(grant, location, InMemoryPublication(bytes))
        }
        assertEquals(401, rejected.status)
        storage.requests.forEach { request ->
            assertFalse(
                "a rejected grant must never be retried with a bearer",
                request.headers.keys.any { it.equals("Authorization", ignoreCase = true) },
            )
        }
    }

    @Test
    fun `a provider refusal is reported with its status and nothing of its body`() = runTest {
        storage.refuseCreateWith = 413

        val refused = assertRaises<PublicationTransferException.Refused> { client.create(grant()) }
        assertEquals(413, refused.status)
    }

    @Test
    fun `metadata goes out as TUS key base64 pairs`() {
        val encoded = PublicationTransferClient.encodeMetadata(
            mapOf("bucket" to "publications", "objectName" to "reader/0f1e/source.epub"),
        )
        val decoded = encoded.split(",").associate { pair ->
            val (key, value) = pair.split(" ")
            key to String(Base64.getDecoder().decode(value))
        }
        assertEquals(mapOf("bucket" to "publications", "objectName" to "reader/0f1e/source.epub"), decoded)
    }

    @Test
    fun `a relative Location is resolved against the creation endpoint`() {
        assertEquals(
            "https://storage.test/storage/v1/upload/resumable/sign/abc",
            PublicationTransferClient.resolve(TUS_ENDPOINT, "sign/abc"),
        )
        assertEquals(
            "https://elsewhere.test/x",
            PublicationTransferClient.resolve(TUS_ENDPOINT, "https://elsewhere.test/x"),
        )
    }

    /**
     * #142: the grant's headers never leave the grant endpoint's origin. The
     * old client returned a foreign `Location` and PATCHed the book to it with
     * the signature attached.
     */
    @Test
    fun `a creation Location on another origin is refused and nothing is sent to it`() = runTest {
        storage.location = "https://elsewhere.test/storage/v1/upload/resumable/sign/reader/0f1e/source.epub"

        val refused = assertRaises<PublicationTransferException.ForeignLocation> {
            client.create(grant())
        }

        assertEquals("only the creation POST was sent", listOf("POST"), storage.requests.map { it.method })
        assertEquals(listOf("storage.test"), storage.requests.map { it.host })
        assertFalse("the location never travels in the error", refused.message!!.contains("elsewhere"))
    }

    /** A location stored by an earlier attempt is checked too, before HEAD or PATCH carry the grant. */
    @Test
    fun `a stored location on another origin is never HEADed or PATCHed`() = runTest {
        val foreign = "https://elsewhere.test/upload/abc"

        assertRaises<PublicationTransferException.ForeignLocation> { client.offset(grant(), foreign) }
        assertRaises<PublicationTransferException.ForeignLocation> {
            client.patch(grant(), foreign, 0, bytes.copyOf(CHUNK.toInt()))
        }
        assertRaises<PublicationTransferException.ForeignLocation> {
            client.offset(grant(), "http://storage.test/storage/v1/upload/resumable/sign/abc")
        }

        assertEquals("nothing reached any host", 0, storage.requests.size)
    }

    /** An absolute `Location` on the grant's own origin (explicit default port included) still works. */
    @Test
    fun `a same-origin Location carries the whole transfer with the grant's headers`() = runTest {
        storage.location = "https://STORAGE.test:443/storage/v1/upload/resumable/sign/reader/0f1e/other.epub"
        val grant = grant()

        val location = client.create(grant)
        val end = client.transfer(grant, location, InMemoryPublication(bytes))

        assertEquals(SIZE.toLong(), end)
        assertArrayEquals(bytes, storage.received)
        assertTrue(storage.requests.all { it.host.equals("storage.test", ignoreCase = true) })
        storage.requests.forEach { request ->
            GRANT_HEADERS.forEach { (name, value) -> assertEquals(value, request.headers[name]) }
        }
    }

    /** A book is not POSTed to an unsigned endpoint, whatever the grant says. */
    @Test
    fun `an unsigned creation endpoint is refused before anything is sent`() = runTest {
        assertRaises<IllegalArgumentException> {
            client.create(grant(endpoint = "https://storage.test/storage/v1/upload/resumable"))
        }
        assertEquals("nothing may be sent to an unsigned endpoint", 0, storage.requests.size)
    }

    /** A chunk larger than the grant allows is a caller bug, refused locally. */
    @Test
    fun `a chunk above the grant's size is refused locally`() = runTest {
        assertRaises<IllegalArgumentException> {
            client.patch(grant(), TUS_LOCATION, 0, ByteArray((CHUNK + 1).toInt()))
        }
    }

    private companion object {
        const val SIZE = 5_000
        const val CHUNK = 2_048L

        /**
         * Bytes the provider keeps of the chunk it was reading when the connection
         * died. Deliberately not a multiple of [CHUNK]: a client that resumed from
         * "chunks I sent" rather than from the provider's offset would get this
         * case wrong, and the byte-for-byte assertion would catch it.
         */
        const val KEPT = 700
    }
}
