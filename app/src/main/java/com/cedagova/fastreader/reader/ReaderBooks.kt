package com.cedagova.fastreader.reader

/**
 * How the reader gets at a book, so
 * [com.cedagova.fastreader.reader.ui.ReaderViewModel] does not need the
 * catalog's whole API.
 */
interface ReaderBooks {

    /**
     * The open request for a catalog book: its title, its bytes read in place
     * (AD-1), and its catalog id as the identity the reader must not recompute
     * (AD-8).
     */
    fun libraryBook(bookId: String): BookOpenRequest
}
