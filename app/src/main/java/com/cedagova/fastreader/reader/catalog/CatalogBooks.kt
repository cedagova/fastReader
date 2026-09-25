package com.cedagova.fastreader.reader.catalog

import com.cedagova.fastreader.library.BookBytes
import com.cedagova.fastreader.library.LibraryRepository
import com.cedagova.fastreader.reader.BookOpenRequest
import com.cedagova.fastreader.reader.ReaderBooks

/**
 * The catalog, as the reader needs it: one open request per book.
 *
 * The catalog id it hands over *is* the book's whole-file SHA-256 (AD-2), which
 * is exactly why the reader never has to compute one (AD-8).
 */
internal class CatalogBooks(private val repository: LibraryRepository, private val bookBytes: BookBytes) : ReaderBooks {

    override fun libraryBook(bookId: String) = BookOpenRequest.library(
        bookId = bookId,
        title = repository.catalog.value.book(bookId)?.title.orEmpty(),
        // Whether those bytes are a picked file, a folder's file or a verified
        // private copy of an account book is [BookBytes]' business alone
        // (#118): this asks for the book and gets the book.
        bytes = bookBytes.byteSource(bookId),
    )
}
