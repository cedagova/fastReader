package com.cedagova.reader.account.library

import java.io.File

/**
 * The host's device catalog, as the download direction needs it (#200,
 * A197-F003): the one host seam of [AccountBookCopies], beside
 * `ReaderLibraryGateway` and for the same reason.
 *
 * This module verifies and places an account book's bytes; a *host* decides
 * what makes a placed file readable — a row in its own catalog, a source on
 * that row, whatever its reader opens. [AccountBookCopies] calls these three
 * operations at exactly the points the download order requires (a row only
 * after a verified placement, the row before the bytes on removal), so a host
 * implements the catalog half and nothing about ordering.
 *
 * FastReader's implementation writes the `ACCOUNT_COPY` source of its
 * `LibraryRepository`. A host's tests substitute the recording
 * `com.cedagova.reader.account.testing.FakeAccountCopyCatalog` from this
 * module's test fixtures.
 */
public interface AccountCopyCatalog {

    /**
     * Makes a verified, placed [file] readable as the book with this content
     * identity, and returns the id of the device book it now belongs to — the
     * same id a device book of the same content already has, when there is
     * one — or null when the file cannot be read as a book at all.
     *
     * [file] has already been verified by [AccountCopyStore.place]: a row is a
     * promise that the bytes are the book.
     */
    public suspend fun addAccountCopy(contentSha256: String, file: File, displayName: String): String?

    /**
     * Drops the device catalog's source for the copy of [contentSha256], and
     * the device book with it when no other source remains. Does nothing when
     * there is none. The bytes are [AccountCopyStore]'s to delete.
     */
    public suspend fun removeAccountCopy(contentSha256: String)

    /**
     * Re-checks each account-copy source against [exists], called with the
     * source's file path, so a copy whose bytes are gone stops claiming to be
     * readable (the start-up sweep).
     */
    public suspend fun reconcileAccountCopies(exists: (path: String) -> Boolean)
}
