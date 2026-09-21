package com.cedagova.reader.library.imports

import com.cedagova.reader.library.model.PublicationTransferGrant
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

/**
 * The resumable transfer: a small, plain TUS 1.0.0 client that speaks only to
 * the storage provider.
 *
 * It is a *separate transport on purpose*, and that is the single most
 * important thing about this class. Publication bytes never pass through
 * reader-api, and the provider authenticates them with the signed headers the
 * admission handed out — the contract says in as many words that bearer
 * authentication is not a substitute. So this client has no session, no
 * [com.cedagova.reader.auth.api.ReaderApiClient], no refresh and no way to
 * reach a token: the only credential it can send is
 * [PublicationTransferGrant.headers], because it holds nothing else.
 * `PublicationTransferClientTest` asserts the absence of `Authorization` on
 * every request it makes, which is a claim about behaviour; the construction
 * above is why that claim cannot quietly stop being true.
 *
 * The sequence is the one reader-api's own functional tests run
 * (`docs/functional-tests/publication-imports.md`, IMPORT-002): a creation
 * `POST` to the signed endpoint that answers with a `Location`, `PATCH`es of at
 * most [PublicationTransferGrant.chunkSizeBytes] bytes each carrying
 * `Upload-Offset`, and a `HEAD` that recovers the provider's durable offset
 * after any interruption.
 *
 * Bytes come from the caller's [PublicationSource] one chunk at a time; nothing
 * is copied or buffered whole.
 */
class PublicationTransferClient internal constructor(
    private val http: HttpClient,
) : Closeable {

    /** The production client: OkHttp, the same engine `:reader-auth` uses, and nothing else. */
    constructor() : this(httpClient(OkHttp.create()))

    /**
     * The TUS creation `POST`. Returns the resumable upload's absolute URL.
     *
     * The provider may answer with a relative `Location`; it is resolved
     * against the creation endpoint, which is what the TUS specification says
     * to do and what a signed-route provider actually returns.
     */
    suspend fun create(grant: PublicationTransferGrant): String {
        require(grant.protocol == PublicationTransferGrant.PROTOCOL_TUS) {
            "this client speaks tus, not '${grant.protocol}'"
        }
        require(grant.endpoint.endsWith(SIGNED_SUFFIX)) {
            // The contract promises a signed creation endpoint ending in
            // /upload/resumable/sign. Checking the last segment keeps this from
            // breaking on a provider path change while still refusing to POST a
            // book to an unsigned endpoint.
            "a transfer grant's endpoint is the signed creation route"
        }
        require(grant.chunkSizeBytes > 0) { "a grant's chunk size is positive" }
        val response = send(HttpMethod.Post, grant.endpoint, grant, offset = null) {
            header(HEADER_UPLOAD_LENGTH, grant.sizeBytes.toString())
            header(HEADER_UPLOAD_METADATA, encodeMetadata(grant.metadata))
        }
        val location = response.headers[HttpHeaders.Location]
            ?: throw PublicationTransferException.Protocol("the creation response carried no Location")
        return resolve(grant.endpoint, location)
    }

    /** The `HEAD` that recovers the provider's durable offset for a resumable upload. */
    suspend fun offset(grant: PublicationTransferGrant, location: String): Long {
        val response = send(HttpMethod.Head, location, grant, offset = null)
        return response.uploadOffset()
    }

    /**
     * One `PATCH` at [offset]. Returns the offset the provider confirms.
     *
     * A provider that answers without `Upload-Offset` is taken at the arithmetic
     * — offset plus what was sent — because a 204 is the provider saying it
     * stored the body.
     */
    suspend fun patch(
        grant: PublicationTransferGrant,
        location: String,
        offset: Long,
        chunk: ByteArray,
    ): Long {
        require(chunk.isNotEmpty()) { "a PATCH carries bytes" }
        require(chunk.size <= grant.chunkSizeBytes) {
            "a chunk is at most the grant's ${grant.chunkSizeBytes} bytes, not ${chunk.size}"
        }
        val response = send(HttpMethod.Patch, location, grant, offset = offset) {
            setBody(ByteArrayContent(chunk, ContentType.parse(TUS_CONTENT_TYPE)))
        }
        return response.headers[HEADER_UPLOAD_OFFSET]?.toLongOrNull() ?: (offset + chunk.size)
    }

    /**
     * Send [source] from [from] to the end, in chunks of at most the grant's
     * size, and return the final confirmed offset.
     *
     * [from] is the provider's own durable offset, so the first byte this sends
     * is the first byte the provider does not have: a resumed transfer re-sends
     * nothing. When the provider answers a `PATCH` with `409` or `412` — its way
     * of saying "that is not where I am" — the offset is re-read with `HEAD`
     * and the loop continues from there rather than failing the import.
     *
     * [onProgress] is called after each confirmed chunk with the offset the
     * provider acknowledged, never with a local guess.
     */
    suspend fun transfer(
        grant: PublicationTransferGrant,
        location: String,
        source: PublicationSource,
        from: Long = 0,
        onProgress: (Long) -> Unit = {},
    ): Long {
        val total = source.sizeBytes
        require(grant.sizeBytes == total) {
            "the grant is for ${grant.sizeBytes} bytes and the source is $total"
        }
        var position = from.coerceIn(0, total)
        var mismatches = 0
        while (position < total) {
            val length = min(grant.chunkSizeBytes, total - position).toInt()
            val chunk = readChunk(source, position, length)
            if (chunk.isEmpty()) {
                throw PublicationTransferException.Protocol(
                    "the source ended at $position but declares $total bytes",
                )
            }
            position = try {
                patch(grant, location, position, chunk)
            } catch (e: PublicationTransferException.OffsetMismatch) {
                if (++mismatches > MISMATCH_LIMIT) throw e.withOffset(position)
                // The provider is the authority on what it holds. Re-read it and
                // carry on from there; this is the normal answer to a PATCH that
                // was retried after the first attempt had in fact landed.
                offset(grant, location)
            } catch (e: PublicationTransferException) {
                throw e.withOffset(position)
            }
            onProgress(position)
        }
        return position
    }

    override fun close() {
        http.close()
    }

    // ---- one request -----------------------------------------------------------------------

    /**
     * The one place a request is built, and so the one place the credential is
     * decided: `Tus-Resumable`, the grant's own headers, and nothing else. No
     * branch here can add an `Authorization` header, because nothing in this
     * class holds a token to put in one.
     */
    private suspend fun send(
        method: HttpMethod,
        url: String,
        grant: PublicationTransferGrant,
        offset: Long?,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val response = try {
            http.request(url) {
                this.method = method
                header(HEADER_TUS_RESUMABLE, TUS_VERSION)
                grant.headers.forEach { (name, value) -> header(name, value) }
                offset?.let { header(HEADER_UPLOAD_OFFSET, it.toString()) }
                block()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Ktor's connect, socket and request timeouts are all IOException
            // subtypes, so a stalled transfer arrives here too: resumable.
            throw PublicationTransferException.Unavailable(e)
        }
        val status = response.status.value
        return when {
            status in 200..299 -> response
            status in GRANT_REJECTED -> throw PublicationTransferException.GrantRejected(status)
            status in OFFSET_MISMATCH -> throw PublicationTransferException.OffsetMismatch(status)
            status in 500..599 -> throw PublicationTransferException.Unavailable(IOException("provider $status"))
            else -> throw PublicationTransferException.Refused(status)
        }
    }

    private fun HttpResponse.uploadOffset(): Long =
        headers[HEADER_UPLOAD_OFFSET]?.toLongOrNull()
            ?: throw PublicationTransferException.Protocol("the response carried no Upload-Offset")

    companion object {
        const val TUS_VERSION: String = "1.0.0"
        const val TUS_CONTENT_TYPE: String = "application/offset+octet-stream"
        const val HEADER_TUS_RESUMABLE: String = "Tus-Resumable"
        const val HEADER_UPLOAD_OFFSET: String = "Upload-Offset"
        const val HEADER_UPLOAD_LENGTH: String = "Upload-Length"
        const val HEADER_UPLOAD_METADATA: String = "Upload-Metadata"

        /** The signed-route marker the contract and reader-api's functional tests both assert. */
        private const val SIGNED_SUFFIX = "/sign"

        /** The provider has stopped honouring this grant: re-admit for a new one. */
        private val GRANT_REJECTED = setOf(401, 403, 404, 410)

        /** The provider disagrees about where the upload is: re-HEAD and continue. */
        private val OFFSET_MISMATCH = setOf(409, 412)

        /** More than this many disagreements in one transfer is not a race any more. */
        private const val MISMATCH_LIMIT = 3

        private const val READ_BUFFER_BYTES = 64 * 1024

        /** The client tests drive: a real client over a mock engine, same code path. */
        fun createForTests(engine: HttpClientEngine): PublicationTransferClient =
            PublicationTransferClient(httpClient(engine))

        /**
         * The transport. Its timeouts are deliberately not `:reader-auth`'s 10 s:
         * that is a bound for a small JSON round trip, and this sends megabytes.
         * The whole-request timeout is off (a chunk takes as long as the network
         * takes) while the socket timeout still fails a stalled connection, which
         * is the failure a resume is for.
         */
        internal fun httpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = INFINITE_TIMEOUT_MILLIS
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
            }
        }

        /** Ktor's own "no whole-request bound"; a chunk takes as long as it takes. */
        private const val INFINITE_TIMEOUT_MILLIS = Long.MAX_VALUE
        private const val CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val SOCKET_TIMEOUT_MILLIS = 60_000L

        /** TUS `Upload-Metadata`: `key base64(value)` pairs, comma separated. */
        internal fun encodeMetadata(metadata: Map<String, String>): String =
            metadata.entries.joinToString(",") { (key, value) ->
                key + " " + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
            }

        /** An absolute `Location`, or a relative one resolved against the creation endpoint. */
        internal fun resolve(endpoint: String, location: String): String =
            try {
                URI(endpoint).resolve(location).toString()
            } catch (e: IllegalArgumentException) {
                throw PublicationTransferException.Protocol("the creation response Location is not a URL")
            }

        /**
         * [length] bytes of [source] starting at [offset], read straight from
         * the owner's own file.
         *
         * A seekable source reaches the offset by position; a stream-only source
         * is wound forward. Either way nothing before [offset] is sent and
         * nothing is copied to a staging file.
         */
        internal fun readChunk(source: PublicationSource, offset: Long, length: Int): ByteArray {
            source.openChannel()?.use { channel -> return channel.readChunk(offset, length) }
            source.open().use { stream ->
                stream.skipTo(offset)
                return stream.readExactly(length)
            }
        }

        private fun SeekableByteChannel.readChunk(offset: Long, length: Int): ByteArray {
            position(offset)
            val buffer = ByteBuffer.allocate(length)
            while (buffer.hasRemaining()) {
                if (read(buffer) < 0) break
            }
            buffer.flip()
            return ByteArray(buffer.remaining()).also { buffer.get(it) }
        }

        /** `skip` may return short at any time; a discarding read is the only honest fallback. */
        private fun InputStream.skipTo(offset: Long) {
            var remaining = offset
            val scratch = ByteArray(READ_BUFFER_BYTES)
            while (remaining > 0) {
                val skipped = skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                    continue
                }
                val read = read(scratch, 0, min(remaining, scratch.size.toLong()).toInt())
                if (read < 0) throw IOException("the source ended before offset $offset")
                remaining -= read
            }
        }

        private fun InputStream.readExactly(length: Int): ByteArray {
            val buffer = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val read = read(buffer, filled, length - filled)
                if (read < 0) break
                filled += read
            }
            return if (filled == length) buffer else buffer.copyOf(filled)
        }
    }
}

/**
 * Why a transfer stopped. Four answers, because the caller does four different
 * things about them, and none of them is "show the provider's error text": a
 * provider message can carry a signed URL, so only the status travels.
 */
sealed class PublicationTransferException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The provider's confirmed offset when this was thrown, once the transfer knows it. */
    open val offset: Long = 0

    /** The same failure, carrying the offset the transfer had reached. */
    internal open fun withOffset(offset: Long): PublicationTransferException = this

    /**
     * The grant is spent: its signature expired or the object is gone
     * (401/403/404/410). The answer is to replay the idempotent admission for a
     * fresh grant and start a fresh transfer — never to fall back to the
     * session's bearer, which the provider would not accept anyway.
     */
    class GrantRejected(val status: Int, override val offset: Long = 0) :
        PublicationTransferException("the storage grant was rejected ($status)") {
        override fun withOffset(offset: Long) = GrantRejected(status, offset)
    }

    /** 409/412: the provider is not at the offset we sent. Re-`HEAD` and continue. */
    class OffsetMismatch(val status: Int, override val offset: Long = 0) :
        PublicationTransferException("the provider disagrees about the upload offset ($status)") {
        override fun withOffset(offset: Long) = OffsetMismatch(status, offset)
    }

    /** The provider refused outright (for example 413, above its effective limit). */
    class Refused(val status: Int, override val offset: Long = 0) :
        PublicationTransferException("the provider refused the transfer ($status)") {
        override fun withOffset(offset: Long) = Refused(status, offset)
    }

    /**
     * The connection failed or the provider is unwell. Everything already
     * acknowledged is still there: the import resumes from its durable offset.
     */
    class Unavailable(private val reason: Throwable, override val offset: Long = 0) :
        PublicationTransferException("the transfer connection failed", reason) {
        override fun withOffset(offset: Long) = Unavailable(reason, offset)
    }

    /** The provider answered in a way TUS does not allow. Not resumable by retrying blindly. */
    class Protocol(message: String) : PublicationTransferException(message)
}
