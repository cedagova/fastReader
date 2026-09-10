package com.cedagova.fastreader.reader

import com.cedagova.fastreader.external.ExternalOpen

/**
 * What the app asked the reader to open.
 *
 * [BookOpenRequest] is the reader's *entry contract* — bytes, identity, origin —
 * and building one needs the catalog, the asset manager or a document grant.
 * This is the thin thing that travels through navigation instead: a name for the
 * book that [com.cedagova.fastreader.reader.ui.ReaderRoute] turns into a request.
 *
 * Before v1.1.0 the reader took a bare catalog id, which said "there is exactly
 * one kind of book" — no longer true the moment a book arrives from another app
 * (#44).
 */
sealed interface ReaderTarget {

    /**
     * What "the same book" means here — the value [BookOpenRequest.openKey]
     * carries.
     *
     * The reader's open effect is keyed on this rather than on the target value,
     * because a target can change while naming the same book: an external one
     * changes whenever its identity lands or its notice is dismissed, and
     * neither is a different book to open.
     */
    val openKey: String

    /** A book in the catalog, opened from the library list or by launch routing. */
    data class Library(val bookId: String) : ReaderTarget {
        override val openKey: String get() = bookId
    }

    /** A book handed over by another app through "Open with" or the share sheet (REQ-103). */
    data class External(val open: ExternalOpen) : ReaderTarget {
        override val openKey: String get() = open.uri
    }
}
