package com.cedagova.fastreader.account.library

import com.cedagova.fastreader.library.BookSource
import com.cedagova.fastreader.library.LibraryRepository
import com.cedagova.reader.account.library.AccountCopyCatalog
import com.cedagova.reader.account.library.DeviceBookIdentity
import java.io.File

/**
 * FastReader's device catalog as `:reader-account`'s download direction needs
 * it (#200): an account copy is a catalog row with an `ACCOUNT_COPY` source,
 * written by [LibraryRepository].
 *
 * Each method is exactly what `AccountBookCopies` did with the repository
 * before it moved out of `:app`. Account copies are catalog rows, so they
 * stayed on [LibraryRepository] when #204 moved settings, positions and book
 * bytes out of it; this adapter is still the only coupling.
 */
class LibraryAccountCopyCatalog(private val repository: LibraryRepository) : AccountCopyCatalog {

    override suspend fun addAccountCopy(contentSha256: String, file: File, displayName: String): String? =
        repository.addAccountCopy(contentSha256, file, displayName)

    /** Finds the row whose account-copy source is this content's, and drops that source. */
    override suspend fun removeAccountCopy(contentSha256: String) {
        val uri = BookSource.accountCopyUri(contentSha256)
        val bookId = repository.catalog.value.books
            .firstOrNull { book -> book.sources.any { it.isAccountCopy && it.uri == uri } }
            ?.id
        if (bookId != null) repository.removeAccountCopy(bookId)
    }

    override suspend fun reconcileAccountCopies(exists: (path: String) -> Boolean) {
        repository.reconcileAccountCopies(exists)
    }
}

/**
 * FastReader's device-book id for a content identity (#200): the catalog names
 * a book by its whole-file SHA-256 under a `sha256:` prefix (v1 AD-2), and an
 * import record stores the bare lowercase hex.
 */
object CatalogBookIdentity : DeviceBookIdentity {

    private const val SHA256_PREFIX: String = "sha256:"

    override fun deviceBookId(contentSha256: String): String = SHA256_PREFIX + contentSha256

    override fun contentSha256(deviceBookId: String): String = deviceBookId.removePrefix(SHA256_PREFIX).lowercase()
}
