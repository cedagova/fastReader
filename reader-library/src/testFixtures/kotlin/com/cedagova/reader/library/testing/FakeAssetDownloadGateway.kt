package com.cedagova.reader.library.testing

import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.library.downloads.AssetDownloadException
import com.cedagova.reader.library.downloads.AssetDownloadGateway
import com.cedagova.reader.library.model.ReaderAssetDirection
import com.cedagova.reader.library.model.ReaderAssetGrant
import com.cedagova.reader.library.model.ReaderAssetMethod
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred

/**
 * reader-api and the storage provider as a host's download path sees them
 * (#199, A197-F002): a scripted [AssetDownloadGateway] that serves [body].
 *
 * The grant is re-minted on every ask — a new signed URL each time — which is
 * what makes "the expiry cost one more grant" countable in [grants].
 * [rejectAttempts] spends the signature the way a TTL does, [transportFailure]
 * plays a sink that refuses the bytes (a full disk), and [holdAfterProgress]
 * makes a transfer observable: it reports half the body and then waits, so a
 * test can look at the host's state *while* bytes are arriving.
 */
public class FakeAssetDownloadGateway(private val body: ByteArray, private val bookId: String = BOOK_ID) :
    AssetDownloadGateway {

    /** The asset ids a grant was issued for, one per ask. */
    public val grants: MutableList<String> = mutableListOf()

    /** How many downloads were attempted. */
    public var fetches: Int = 0

    /** reader-api refuses to issue a grant at all. */
    public var grantFailure: ReaderAuthException? = null

    /** The provider rejects the signature on this many attempts, then serves. */
    public var rejectAttempts: Int = 0

    /** The transfer fails with this — a full disk, for instance. */
    public var transportFailure: Throwable? = null

    /** Completed by the test to let a held transfer finish. */
    public var holdAfterProgress: CompletableDeferred<Unit>? = null

    override suspend fun downloadGrant(assetId: String): ReaderAssetGrant {
        grantFailure?.let { throw it }
        grants += assetId
        return ReaderAssetGrant(
            assetId = assetId,
            bookId = bookId,
            direction = ReaderAssetDirection.DOWNLOAD,
            method = ReaderAssetMethod.GET,
            url = "https://storage.test/object/$assetId?token=signed-${grants.size}",
            expiresAt = "2026-09-21T12:00:00Z",
            checksum = "sha256:" + MessageDigest.getInstance("SHA-256")
                .digest(body).joinToString("") { "%02x".format(it) },
            sizeBytes = body.size.toLong(),
            uploadStatus = "ready",
            headers = mapOf("x-signature" to "test-signature-not-a-credential"),
        )
    }

    override suspend fun download(
        grant: ReaderAssetGrant,
        sink: OutputStream,
        onProgress: (written: Long, total: Long) -> Unit,
    ): Long {
        fetches++
        if (fetches <= rejectAttempts) throw AssetDownloadException.GrantRejected(403)
        transportFailure?.let { throw it }
        val hold = holdAfterProgress
        if (hold != null) {
            onProgress(body.size.toLong() / 2, grant.sizeBytes)
            hold.await()
        }
        sink.write(body)
        onProgress(body.size.toLong(), grant.sizeBytes)
        return body.size.toLong()
    }

    public companion object {
        /** The account book id a grant names unless a test passes its own. */
        public const val BOOK_ID: String = "22222222-2222-2222-2222-222222222222"
    }
}
