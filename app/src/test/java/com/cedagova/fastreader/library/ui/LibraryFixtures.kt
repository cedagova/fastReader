package com.cedagova.fastreader.library.ui

import com.cedagova.fastreader.library.Book
import com.cedagova.fastreader.library.BookContentStatus
import com.cedagova.fastreader.library.BookSource
import com.cedagova.fastreader.library.SourceAvailability
import com.cedagova.fastreader.library.SourceOrigin

/** Catalog books for the library-state tests and the Roborazzi renders. */
internal object LibraryFixtures {

    fun readable(
        id: String,
        title: String,
        author: String? = null,
        fileName: String = "$title.epub",
        hasCover: Boolean = false,
        /** When the book entered the catalog; `0` is "before this was kept" (REQ-203). */
        addedAtEpochMs: Long = 0,
    ) = Book(
        id = id,
        title = title,
        author = author,
        hasCover = hasCover,
        contentStatus = BookContentStatus.READABLE,
        sources = listOf(source("content://books/$id", fileName)),
        addedAtEpochMs = addedAtEpochMs,
    )

    /**
     * A book provided by a folder, optionally also picked directly — the second
     * source is what keeps it in the library when that folder is removed.
     */
    fun inFolder(
        id: String,
        title: String,
        folderId: String,
        fileName: String = "$title.epub",
        alsoPickedDirectly: Boolean = false,
    ) = Book(
        id = id,
        title = title,
        contentStatus = BookContentStatus.READABLE,
        sources = listOfNotNull(
            source("content://tree/$id", fileName, SourceOrigin.FOLDER, folderId),
            if (alsoPickedDirectly) source("content://picked/$id", fileName) else null,
        ),
    )

    fun rejected(
        id: String,
        title: String,
        contentStatus: BookContentStatus,
        reason: String,
        author: String? = null,
        fileName: String = "$title.epub",
    ) = Book(
        id = id,
        title = title,
        author = author,
        contentStatus = contentStatus,
        rejectReason = reason,
        rejectDetail = "fixture",
        sources = listOf(source("content://books/$id", fileName)),
    )

    fun unavailable(
        id: String,
        title: String,
        availability: SourceAvailability,
        author: String? = null,
        fileName: String = "$title.epub",
        origin: SourceOrigin = SourceOrigin.FOLDER,
        folderId: String? = "content://tree/books",
    ) = Book(
        id = id,
        title = title,
        author = author,
        contentStatus = BookContentStatus.READABLE,
        sources = listOf(
            source("content://books/$id", fileName, origin, folderId).copy(availability = availability),
        ),
    )

    /**
     * A book whose bytes are a downloaded private copy of an account book
     * (#119, AD-24).
     *
     * The id is the content digest, exactly as a real ingestion derives it, and
     * the source's uri is the copy-store key — which is what the shelf reads
     * the identity to free back off. Nothing about this row says "account": D4
     * is precisely that a copy keeps working when the account rows leave.
     */
    fun accountCopy(
        contentHex: String,
        title: String,
        author: String? = null,
        sizeBytes: Long = 6_291_456,
        hasCover: Boolean = true,
    ) = Book(
        id = "sha256:$contentHex",
        title = title,
        author = author,
        hasCover = hasCover,
        contentStatus = BookContentStatus.READABLE,
        sources = listOf(
            BookSource(
                uri = BookSource.accountCopyUri(contentHex),
                origin = SourceOrigin.ACCOUNT_COPY,
                displayName = "$title.epub",
                sizeBytes = sizeBytes,
                lastModifiedEpochMs = 1_700_000_000_000,
                filePath = "/data/user/0/com.cedagova.fastreader/files/account-copies/copy-$contentHex.epub",
            ),
        ),
    )

    private fun source(
        uri: String,
        displayName: String,
        origin: SourceOrigin = SourceOrigin.DIRECT_PICK,
        folderId: String? = null,
    ) = BookSource(
        uri = uri,
        origin = origin,
        displayName = displayName,
        folderId = folderId,
        sizeBytes = 1_024,
        lastModifiedEpochMs = 1_700_000_000_000,
    )
}
