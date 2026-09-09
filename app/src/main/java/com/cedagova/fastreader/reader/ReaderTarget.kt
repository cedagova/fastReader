package com.cedagova.fastreader.reader

import com.cedagova.fastreader.content.BundledSample

/**
 * What the app asked the reader to open.
 *
 * [BookOpenRequest] is the reader's *entry contract* — bytes, identity, origin —
 * and building one needs the catalog or the asset manager. This is the thin
 * thing that travels through navigation instead: a name for the book, small
 * enough to survive process death as saved instance state, that
 * [com.cedagova.fastreader.reader.ui.ReaderRoute] turns into a request.
 *
 * Before v1.1.0 the reader took a bare catalog id, which said "there is exactly
 * one kind of book" — no longer true the moment a text ships inside the APK
 * (#48) or arrives from another app (#44).
 */
sealed interface ReaderTarget {

    /** A book in the catalog, opened from the library list or by launch routing. */
    data class Library(val bookId: String) : ReaderTarget

    /** A text shipped inside the APK, which is never in the catalog (REQ-109). */
    data class Sample(val sample: BundledSample) : ReaderTarget
}
