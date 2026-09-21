package com.cedagova.reader.library.downloads

import com.cedagova.reader.library.Recorded
import com.cedagova.reader.library.model.ReaderAssetDirection
import com.cedagova.reader.library.model.ReaderAssetGrant
import com.cedagova.reader.library.model.ReaderAssetMethod
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.security.MessageDigest
import java.util.Collections

// The double the download tests drive: a storage provider that serves an
// object over a signed URL. As on the upload side, the client under test is
// the REAL AssetDownloadClient over a mock engine — what is being watched is
// what the module actually puts on the wire, which a stubbed transport could
// never show.

/** The provider's signed object URL, as a grant carries one. */
const val DOWNLOAD_URL: String = "https://storage.test/storage/v1/object/sign/reader/0f1e/book.epub?token=signed"

/**
 * A provider signature, as a grant carries it. It is a test value and nothing
 * else; no real grant, token or key appears anywhere in this module's tests.
 */
val DOWNLOAD_HEADERS: Map<String, String> = mapOf(
    "x-signature" to "test-signature-not-a-credential",
)

/**
 * The account bearer the authenticated client would send. It appears here so a
 * test can assert its *absence* from every storage request by value and not
 * only by header name — a client that renamed the header would still fail.
 */
const val ACCOUNT_BEARER: String = "account-access-token-not-a-real-one"

/** Deterministic bytes: every position is its own value, so a misplaced chunk is visible. */
fun bookBytes(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

fun sha256Of(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** A download grant over [bytes], as reader-api would issue one. */
fun downloadGrant(
    bytes: ByteArray,
    url: String = DOWNLOAD_URL,
    direction: ReaderAssetDirection = ReaderAssetDirection.DOWNLOAD,
    method: ReaderAssetMethod = ReaderAssetMethod.GET,
    expiresAt: String = "2026-09-21T12:00:00Z",
): ReaderAssetGrant = ReaderAssetGrant(
    assetId = "11111111-1111-1111-1111-111111111111",
    bookId = "22222222-2222-2222-2222-222222222222",
    direction = direction,
    method = method,
    url = url,
    expiresAt = expiresAt,
    checksum = "sha256:" + sha256Of(bytes),
    sizeBytes = bytes.size.toLong(),
    uploadStatus = "ready",
    headers = DOWNLOAD_HEADERS,
)

/**
 * A storage provider that serves one object over its own mock engine.
 *
 * It behaves the way a provider behaves rather than the way the happy path
 * needs: it can stop honouring a spent signature, refuse outright, or serve a
 * body that is not what the grant promised.
 */
class FakeObjectStorage(private val body: ByteArray) {

    val requests: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())

    /** Reject every request with this status, as a spent signature would (401/403/404/410). */
    var rejectWith: Int? = null

    /** Refuse outright with this status, as a provider that will not serve this range does. */
    var refuseWith: Int? = null

    /** Serve this instead of [body] — a tampered object, or a truncated one. */
    var serve: ByteArray? = null

    /** Rejections stop after this many requests, so a re-fetched grant can succeed. */
    var rejectFirst: Int = Int.MAX_VALUE

    val engine = MockEngine { data ->
        val recorded = data.record()
        requests += recorded
        val reject = rejectWith
        when {
            reject != null && requests.size <= rejectFirst ->
                respond("provider says no", HttpStatusCode.fromValue(reject))

            refuseWith != null ->
                respond("provider says no", HttpStatusCode.fromValue(refuseWith!!))

            else -> respond(
                serve ?: body,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/epub+zip"),
            )
        }
    }

    private suspend fun HttpRequestData.record(): Recorded = Recorded(
        method = method.value,
        host = url.host,
        path = url.encodedPath,
        query = url.encodedQuery,
        headers = headers.entries().associate { (k, v) -> k to v.joinToString(",") },
        body = try { body.toByteArray().decodeToString() } catch (e: Exception) { "" },
    )
}
