package com.cedagova.reader.library.downloads

import com.cedagova.reader.library.GrantOrigin
import com.cedagova.reader.library.model.ReaderAssetGrant
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

/**
 * The download: a small, plain client that speaks only to the storage
 * provider, and the exact counterpart of
 * `com.cedagova.reader.library.imports.PublicationTransferClient`.
 *
 * It is a *separate transport on purpose*, for the same reason and with the
 * same consequence. Book bytes never pass through reader-api; the provider
 * authenticates the fetch with the signature reader-api put in the grant. So
 * this client has no session, no `com.cedagova.reader.auth.api.ReaderApiClient`,
 * no refresh and no way to reach a token: the only credential it can send is
 * [ReaderAssetGrant.headers], because it holds nothing else.
 * `AssetDownloadClientTest` asserts the absence of `Authorization` — and of the
 * literal session value — on every request it makes, which is a claim about
 * behaviour; the construction above is why that claim cannot quietly stop being
 * true.
 *
 * It composes no address either. [download] takes a whole [ReaderAssetGrant]
 * and fetches [ReaderAssetGrant.url] unchanged, so "bytes come only through the
 * grant the backend issues" is a property of the signature of this method
 * rather than a rule somebody has to remember.
 *
 * What it deliberately does **not** do is decide whether the bytes are good.
 * It streams them to the caller's [OutputStream] and reports what it sent; the
 * digest, the temporary file and the compare-then-place belong to the host's
 * copy store, where the book's identity is actually known. A client that both
 * fetched and blessed its own download would be one place to get wrong instead
 * of two to agree.
 */
class AssetDownloadClient internal constructor(
    private val http: HttpClient,
) : Closeable {

    /** The production client: OkHttp, the same engine `:reader-auth` uses, and nothing else. */
    constructor() : this(httpClient(OkHttp.create()))

    /**
     * Fetch the grant's object and write every byte to [sink], in order.
     *
     * Returns the number of bytes written. Nothing is buffered whole: the body
     * is read in [READ_BUFFER_BYTES] pieces straight into [sink], so a fifty-
     * megabyte book costs a fifty-megabyte temporary file and not a
     * fifty-megabyte heap. [onProgress] is called after each piece with the
     * running total and the grant's declared length, which is what a host draws
     * a progress bar from — the length comes from the grant, never from a
     * constant and never from a guess.
     *
     * [sink] is not closed here; the caller owns it, because the caller is the
     * one that has to delete the file behind it when this throws.
     *
     * @throws IllegalArgumentException when the grant is not a readable
     *   download — a caller bug refused before a request exists.
     */
    suspend fun download(
        grant: ReaderAssetGrant,
        sink: OutputStream,
        onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
    ): Long {
        require(grant.isDownload) {
            "this client spends download grants, not ${grant.direction}/${grant.method} ones"
        }
        require(grant.url.isNotBlank()) { "a download grant carries the provider's own URL" }
        var written = 0L
        var url = grant.url
        var redirects = 0
        try {
            while (true) {
                val next = http.prepareGet(url) {
                    grant.headers.forEach { (name, value) -> header(name, value) }
                }.execute<String?> { response ->
                    redirectTarget(url, response)?.let { return@execute it }
                    verify(response)
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(READ_BUFFER_BYTES)
                    while (true) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read < 0) break
                        if (read == 0) continue
                        sink.write(buffer, 0, read)
                        written += read
                        onProgress(written, grant.sizeBytes)
                    }
                    null
                } ?: break
                // The grant's headers are about to be sent again. They go only
                // to the origin the grant named (#142): a redirect to another
                // host, port or scheme is refused before any request exists.
                if (!GrantOrigin.sameAs(grant.url, next)) throw AssetDownloadException.ForeignRedirect()
                if (++redirects > MAX_REDIRECTS) {
                    throw AssetDownloadException.Protocol("the provider redirected more than $MAX_REDIRECTS times")
                }
                url = next
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AssetDownloadException) {
            throw e
        } catch (e: IOException) {
            // Ktor's connect and socket timeouts are IOException subtypes, so a
            // stalled download arrives here too, and so does a full disk: the
            // sink's own write throws, and the caller deletes the partial file.
            throw AssetDownloadException.Unavailable(e)
        }
        if (written != grant.sizeBytes) {
            throw AssetDownloadException.Protocol(
                "the provider sent $written bytes for a grant of ${grant.sizeBytes}",
            )
        }
        return written
    }

    override fun close() {
        http.close()
    }

    /**
     * The absolute address a redirect names, or null when [response] is not a
     * redirect. A relative `Location` is resolved against [current], the URL
     * that answered. The address is only *read* here; whether the grant's
     * headers may follow it is decided by the caller, against the grant.
     */
    private fun redirectTarget(current: String, response: HttpResponse): String? {
        if (response.status.value !in REDIRECTS) return null
        val location = response.headers[HttpHeaders.Location]
            ?: throw AssetDownloadException.Protocol("the provider redirected without a Location")
        return try {
            URI(current).resolve(location).toString()
        } catch (e: IllegalArgumentException) {
            throw AssetDownloadException.Protocol("the provider's redirect Location is not a URL")
        }
    }

    /**
     * The one place a provider status becomes a failure, and so the one place
     * the four answers a host acts on differently are decided.
     *
     * A provider's error *body* is never read: it can carry a signed URL, so
     * only the status travels, exactly as the upload side does it.
     */
    private fun verify(response: HttpResponse) {
        val status = response.status.value
        when {
            status in 200..299 -> Unit
            status in GRANT_REJECTED -> throw AssetDownloadException.GrantRejected(status)
            status in 500..599 -> throw AssetDownloadException.Unavailable(IOException("provider $status"))
            else -> throw AssetDownloadException.Refused(status)
        }
    }

    companion object {
        /**
         * The grant is spent: its signature expired, or the object is gone
         * (401/403/404/410). The answer is to ask reader-api for a fresh grant —
         * never to fall back to the session's bearer, which the provider would
         * not accept and which this client could not produce anyway.
         */
        private val GRANT_REJECTED = setOf(401, 403, 404, 410)

        private const val READ_BUFFER_BYTES = 64 * 1024

        /** The statuses a GET follows: the provider's object lives at another path. */
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        /** More same-origin hops than this is a loop, not a CDN. */
        private const val MAX_REDIRECTS = 10

        /** The client tests drive: the real client over a mock engine, same code path. */
        fun createForTests(engine: HttpClientEngine): AssetDownloadClient =
            AssetDownloadClient(httpClient(engine))

        /**
         * The transport. Its timeouts are deliberately not `:reader-auth`'s 10 s:
         * that is a bound for a small JSON round trip, and this receives
         * megabytes. The whole-request timeout is off (a book takes as long as
         * the network takes) while the socket timeout still fails a stalled
         * connection, which is the failure a re-fetched grant is for.
         */
        internal fun httpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
            expectSuccess = false
            // A download is a plain GET of a signed object URL, and object
            // storage answers one with a redirect often enough that following
            // it is ordinary. But Ktor's own follower would re-send the grant's
            // headers to whatever host the redirect names, so `download`
            // follows redirects itself and only within the grant URL's origin
            // (#142) — the same rule the upload side applies to its Location.
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = INFINITE_TIMEOUT_MILLIS
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
            }
        }

        /** Ktor's own "no whole-request bound"; a book takes as long as it takes. */
        private const val INFINITE_TIMEOUT_MILLIS = Long.MAX_VALUE
        private const val CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val SOCKET_TIMEOUT_MILLIS = 60_000L
    }
}

/**
 * Why a download stopped. Four answers, because the caller does four different
 * things about them, and none of them is "show the provider's error text": a
 * provider message can carry a signed URL, so only the status travels.
 */
sealed class AssetDownloadException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * The grant is spent: the signature expired or the object moved
     * (401/403/404/410). The answer is one fresh `POST .../download-grant` and
     * a new attempt, which is exactly what the TTL assumption in the plan says
     * a long download on a phone network will need.
     */
    class GrantRejected(val status: Int) :
        AssetDownloadException("the storage grant was rejected ($status)")

    /** The provider refused outright (for example 416). Retrying the same grant will not help. */
    class Refused(val status: Int) :
        AssetDownloadException("the provider refused the download ($status)")

    /** The connection failed, stalled, or the provider is unwell. A later attempt may work. */
    class Unavailable(reason: Throwable) :
        AssetDownloadException("the download connection failed", reason)

    /** The provider answered in a way the grant does not allow — a short or over-long body. */
    class Protocol(message: String) : AssetDownloadException(message)

    /**
     * The provider redirected to a different origin (host, port or scheme),
     * and the grant's headers were not sent there (#142). Nothing of the
     * target travels in this error: it can carry a signed URL. Retrying the
     * same grant would meet the same redirect, so this is not a retry case.
     */
    class ForeignRedirect : AssetDownloadException("the provider redirected the download to another origin")
}
