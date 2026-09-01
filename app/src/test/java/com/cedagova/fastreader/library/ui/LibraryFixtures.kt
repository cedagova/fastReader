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
    ) = Book(
        id = id,
        title = title,
        author = author,
        hasCover = hasCover,
        contentStatus = BookContentStatus.READABLE,
        sources = listOf(source("content://books/$id", fileName)),
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
