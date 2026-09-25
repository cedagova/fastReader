package com.cedagova.reader.library.downloads

import com.cedagova.reader.library.ReaderLibraryOperations
import com.cedagova.reader.library.model.ReaderAssetGrant
import java.io.OutputStream

/**
 * The asset-download seam, beside `ReaderLibraryGateway` and
 * `PublicationImportGateway` and for the same reason (#118; library-owned
 * since #199, A197-F002).
 *
 * This module owns the grant and the transport; a host owns *when* a book is
 * fetched, and the unit tests that prove "when" have to run with no network,
 * no SDK and no Keystore. [ReaderApiAssetDownloadGateway] is the one
 * production implementation; a host's tests substitute the scripted
 * `com.cedagova.reader.library.testing.FakeAssetDownloadGateway` from this
 * module's test fixtures.
 *
 * ## The two halves are separate on purpose
 *
 * [downloadGrant] carries the account's session, because reader-api is the only
 * thing that may decide which asset this account can read. [download] carries
 * no session at all, because the storage provider authenticates the fetch with
 * the signature reader-api put in the grant. Keeping them as two methods over
 * two different clients is what makes "the bearer never reaches the storage
 * host" a fact about the wiring rather than a rule somebody has to remember.
 *
 * The address, the length, the checksum and the TTL all come from the grant.
 * Nothing here is a constant.
 */
public interface AssetDownloadGateway {

    /** `POST /v1/reader/assets/{asset_id}/download-grant`: a short-lived signed fetch. */
    public suspend fun downloadGrant(assetId: String): ReaderAssetGrant

    /**
     * Fetch the grant's object into [sink], reporting progress as it goes.
     *
     * Returns the number of bytes written. [sink] is not closed here; the caller
     * owns it, because the caller is the one that deletes the file behind it
     * when this throws.
     */
    public suspend fun download(
        grant: ReaderAssetGrant,
        sink: OutputStream,
        onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
    ): Long
}

/**
 * The production gateway: the grant from the one authenticated client the host
 * owns, the bytes from the one session-less transport beside it.
 */
public class ReaderApiAssetDownloadGateway(
    private val operations: ReaderLibraryOperations,
    private val transport: AssetDownloadClient,
) : AssetDownloadGateway {

    override suspend fun downloadGrant(assetId: String): ReaderAssetGrant = operations.assetDownloadGrant(assetId).grant

    override suspend fun download(
        grant: ReaderAssetGrant,
        sink: OutputStream,
        onProgress: (written: Long, total: Long) -> Unit,
    ): Long = transport.download(grant, sink, onProgress)
}
