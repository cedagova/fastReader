package com.cedagova.fastreader.library

/**
 * Where the app opens (REQ-009).
 *
 * A pure function of the stored catalog, so the routing rule is provable by unit
 * test rather than by restarting an emulator: the reader either goes straight
 * back into the book they were in, or lands in the library able to see why they
 * did not.
 */
sealed interface LaunchDestination {

    /** The last-read book is readable: open it, paused, in one step. */
    data class Reader(val bookId: String) : LaunchDestination

    /**
     * The library. [blocked] is non-null when a last-read book exists but cannot
     * be opened, and names it so the library can say so.
     */
    data class Library(val blocked: ResumeBlocked? = null) : LaunchDestination
}

/** A last-read book that could not be resumed, and why. */
data class ResumeBlocked(val bookId: String, val reason: ResumeBlockedReason)

/** Why reading did not resume. One case per book state the library can show. */
enum class ResumeBlockedReason {
    /** The file was moved or deleted; the position is kept for its return (REQ-005). */
    MISSING,

    /** Folder access was revoked; the library offers the re-grant path (REQ-005). */
    PERMISSION_LOST,

    /** The file is there but unusable — damaged, or copy protected. */
    UNREADABLE,

    /** The book is no longer in the library at all; it was removed (REQ-004). */
    REMOVED,
}

/**
 * Picks the launch destination from the stored catalog.
 *
 * Note what does *not* appear here: an empty library and a first launch are the
 * same case as "no book was ever read", because the library's own empty state is
 * already the guidance screen the definition asks for.
 */
fun launchDestination(catalog: Catalog): LaunchDestination {
    val bookId = catalog.lastReadBookId ?: return LaunchDestination.Library()
    val book = catalog.book(bookId)
        ?: return LaunchDestination.Library(
            // A position survives removal (REQ-004), so the id can outlive the entry.
            if (catalog.readingStates.containsKey(bookId)) {
                ResumeBlocked(bookId, ResumeBlockedReason.REMOVED)
            } else {
                null
            },
        )
    val reason = when (book.status) {
        BookStatus.READABLE -> return LaunchDestination.Reader(bookId)
        BookStatus.MISSING -> ResumeBlockedReason.MISSING
        BookStatus.PERMISSION_LOST -> ResumeBlockedReason.PERMISSION_LOST
        BookStatus.DRM_PROTECTED, BookStatus.CORRUPT -> ResumeBlockedReason.UNREADABLE
    }
    return LaunchDestination.Library(ResumeBlocked(bookId, reason))
}
