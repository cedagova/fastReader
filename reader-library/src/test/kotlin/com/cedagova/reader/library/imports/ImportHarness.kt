package com.cedagova.reader.library.imports

import com.cedagova.reader.library.Recorded
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Collections

// The two doubles the import tests drive: a storage provider that really
// speaks TUS, and a publication whose bytes really are the ones it says.
//
// Neither of them fakes the module. `PublicationTransferClient` is the real
// client over a mock engine, exactly as `:reader-library`'s existing tests run
// the real `ReaderAuthClient` over one — the point is to watch what the module
// actually puts on the wire, which a stubbed transport could never show.

/** The MIME and format the policy fixture declares for EPUB. */
const val EPUB_MIME: String = "application/epub+zip"

/** The signed creation endpoint; the contract has it ending in `/upload/resumable/sign`. */
const val TUS_ENDPOINT: String = "https://storage.test/storage/v1/upload/resumable/sign"

/** The resumable upload the provider creates. */
const val TUS_LOCATION: String = "$TUS_ENDPOINT/reader/0f1e/source.epub"

/**
 * A provider signature, as a grant carries it. It is a test value and nothing
 * else; no real grant, token or key appears anywhere in this module's tests.
 */
val GRANT_HEADERS: Map<String, String> = mapOf(
    "x-signature" to "test-signature-not-a-credential",
    "x-upsert" to "false",
)

/**
 * The failure a suspending call is expected to raise.
 *
 * `assertThrows` cannot take a suspending lambda and nesting `runBlocking`
 * inside `runTest` invites a deadlock, so this inline helper carries the
 * calling coroutine context into the block instead.
 */
inline fun <reified T : Throwable> assertRaises(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("expected ${T::class.simpleName}, got ${e::class.simpleName}: ${e.message}", e)
    }
    throw AssertionError("expected ${T::class.simpleName}, but nothing was thrown")
}

/** Deterministic bytes: every position is its own value, so a misplaced chunk is visible. */
fun publicationBytes(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * A publication whose bytes live in memory and which cannot seek: the worst
 * case, where a resume has to wind the stream forward to its offset.
 *
 * [opens] counts how often the module asked for the bytes, which is how
 * "nothing is copied" is visible: the module never takes the whole file.
 */
open class InMemoryPublication(
    val bytes: ByteArray,
    override val mimeType: String = EPUB_MIME,
    override val fileName: String? = "wittgenstein.epub",
) : PublicationSource {
    var opens: Int = 0
        private set

    override val sizeBytes: Long get() = bytes.size.toLong()
    override val sha256: String = sha256Hex(bytes)

    override fun open(): InputStream {
        opens += 1
        return ByteArrayInputStream(bytes)
    }
}

/** The same publication backed by a real file, so [openChannel] is exercised too. */
class FilePublication(bytes: ByteArray) : InMemoryPublication(bytes) {
    private val file: File = File.createTempFile("publication", ".epub").apply {
        writeBytes(bytes)
        deleteOnExit()
    }

    override fun openChannel(): SeekableByteChannel =
        Files.newByteChannel(file.toPath(), StandardOpenOption.READ)
}

/**
 * A storage provider that speaks TUS 1.0.0 over its own mock engine.
 *
 * It behaves the way a provider behaves rather than the way the happy path
 * needs: it refuses a `PATCH` whose `Upload-Offset` is not where it actually
 * is, it can keep part of an interrupted chunk, and it can stop honouring the
 * grant. [received] is everything it ever stored, in order, so a test can
 * assert byte-for-byte that nothing was re-sent and nothing was skipped.
 */
class FakeStorage(
    private val requiredHeaders: Map<String, String> = GRANT_HEADERS,
) {
    val requests: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())

    private val stored = ByteArrayOutputStream()

    /** The provider's durable offset — the only offset that counts. */
    var offset: Long = 0
        private set

    /** Everything the provider ever stored, in the order it arrived. */
    val received: ByteArray get() = stored.toByteArray()

    /** Set to reject every request with this status, as a spent signature would. */
    var rejectWith: Int? = null

    /** Reject only the `HEAD`, as a provider that discarded the upload would. */
    var rejectHeadWith: Int? = null

    /** Refuse the creation `POST` outright, as a provider over its limit does (413). */
    var refuseCreateWith: Int? = null

    /** The `Location` the creation `POST` answers with. */
    var location: String = TUS_LOCATION

    /** Answer the next `PATCH` with `409`, as a provider that has moved on does. */
    var conflictNextPatch: Boolean = false

    /**
     * Stop the connection during the next `PATCH`, after storing this many of
     * its bytes. Null after it fires, so the resume succeeds.
     */
    var interruptNextPatchAfter: Int? = null

    /** The `Upload-Offset` each `PATCH` declared, in the order they arrived. */
    val patchOffsets: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    /** The body size of each `PATCH` the provider read, in order. */
    val patchSizes: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    /** How many `POST`, `HEAD` and `PATCH` requests the provider has seen. */
    fun countOf(method: String): Int = requests.count { it.method == method }

    val engine = MockEngine { data ->
        val recorded = data.record()
        requests += recorded
        rejectWith?.let { return@MockEngine respond("", HttpStatusCode.fromValue(it)) }
        missingHeader(recorded)?.let { name ->
            // A provider with no signature answers the way a provider with no
            // signature answers: it does not create anyone's object.
            error("the request carried no $name header: $recorded")
        }
        when (recorded.method) {
            "POST" -> {
                refuseCreateWith?.let { return@MockEngine respond("", HttpStatusCode.fromValue(it)) }
                stored.reset()
                offset = 0
                respond(
                    "",
                    HttpStatusCode.Created,
                    headersOf(HttpHeaders.Location to listOf(location)),
                )
            }
            "HEAD" -> {
                rejectHeadWith?.let { return@MockEngine respond("", HttpStatusCode.fromValue(it)) }
                respond("", HttpStatusCode.OK, uploadOffset(offset))
            }
            "PATCH" -> {
                val declared = recorded.headers[PublicationTransferClient.HEADER_UPLOAD_OFFSET]?.toLong()
                declared?.let { patchOffsets += it }
                if (conflictNextPatch) {
                    conflictNextPatch = false
                    return@MockEngine respond("", HttpStatusCode.Conflict)
                }
                if (declared != offset) {
                    return@MockEngine respond("", HttpStatusCode.Conflict, uploadOffset(offset))
                }
                val body = data.body.toByteArray()
                patchSizes += body.size
                val cut = interruptNextPatchAfter
                if (cut != null) {
                    interruptNextPatchAfter = null
                    // The provider kept what reached it and then the connection
                    // died: the durable offset is mid-chunk, which is exactly
                    // the offset a resume must start from.
                    val kept = minOf(cut, body.size)
                    stored.write(body, 0, kept)
                    offset += kept
                    throw IOException("connection reset by peer")
                }
                stored.write(body)
                offset += body.size
                respond("", HttpStatusCode.NoContent, uploadOffset(offset))
            }
            else -> error("a TUS client does not send ${recorded.method}")
        }
    }

    private fun uploadOffset(value: Long) =
        headersOf(PublicationTransferClient.HEADER_UPLOAD_OFFSET to listOf(value.toString()))

    private fun missingHeader(recorded: Recorded): String? =
        requiredHeaders.keys.firstOrNull { name ->
            recorded.headers.none { it.key.equals(name, ignoreCase = true) }
        }

    private suspend fun HttpRequestData.record(): Recorded = Recorded(
        method = method.value,
        host = url.host,
        path = url.encodedPath,
        query = url.encodedQuery,
        headers = headers.entries().associate { (k, v) -> k to v.joinToString(",") } +
            (body.contentType?.let { mapOf(HttpHeaders.ContentType to it.toString()) } ?: emptyMap()),
        body = "",
    )
}
