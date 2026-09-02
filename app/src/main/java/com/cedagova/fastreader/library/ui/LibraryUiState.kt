package com.cedagova.fastreader.library.ui

import com.cedagova.fastreader.library.Book
import com.cedagova.fastreader.library.BookStatus
import com.cedagova.fastreader.library.Catalog
import com.cedagova.fastreader.library.IngestionState
import com.cedagova.fastreader.library.ResumeBlocked
import com.cedagova.fastreader.library.ResumeBlockedReason
import com.cedagova.fastreader.library.ScanTrigger
import com.cedagova.fastreader.library.SourceAvailability
import com.cedagova.fastreader.library.SourceOrigin
import java.text.Collator
import java.text.Normalizer
import kotlin.math.roundToInt

/**
 * Everything the library screen renders, derived from the catalog, the current
 * ingestion state, and what the reader has typed into the search field.
 *
 * This is deliberately plain Kotlin with no Android or Compose types: the
 * filtering, ordering, progress, and state-selection rules behind REQ-001–REQ-005
 * are proven by fast unit tests, and the Compose layer only lays this out.
 */
data class LibraryUiState(
    val query: String,
    val books: List<LibraryBookItem>,
    val content: LibraryContent,
    val scan: LibraryScan? = null,
    val failureMessage: String? = null,
    /**
     * Set when the app opened here instead of in the last-read book (REQ-009), so
     * the reader is told why reading did not resume rather than being dropped in
     * the library with no explanation.
     */
    val resumeNotice: ResumeNotice? = null,
)

/** The last-read book the app could not reopen, named so the library can say so. */
data class ResumeNotice(
    val bookId: String,
    /** Null when the book is no longer in the catalog to be named — it was removed. */
    val title: String?,
    val reason: ResumeBlockedReason,
)

/** Which of the three mutually exclusive library bodies to show. */
enum class LibraryContent {
    /** No books at all: show the guidance explaining both ways to add them. */
    EMPTY_LIBRARY,

    /** Books exist but none match the current search. */
    NO_SEARCH_RESULTS,

    /** The filtered book list. */
    BOOKS,
}

/** A folder scan in progress, so the library can show a loading state (REQ-002). */
data class LibraryScan(
    val trigger: ScanTrigger,
    val processed: Int = 0,
    val total: Int = 0,
    val currentName: String? = null,
) {
    /** Determinate progress, or null while the scan does not yet know how much work it has. */
    val fraction: Float? get() = if (total > 0) (processed.toFloat() / total).coerceIn(0f, 1f) else null

    val hasCounts: Boolean get() = total > 0
}

/** One row of the library list. */
data class LibraryBookItem(
    val id: String,
    val title: String,
    val author: String?,
    /** Every file name this book is reachable under; search matches on all of them. */
    val fileNames: List<String>,
    /**
     * Whole-percent share of the book already read.
     *
     * Only a book whose last token has been shown reads 100%: REQ-018 pairs that
     * number with the end-of-book state, so "99.7%, rounded up" must not claim to
     * be finished. Everything below rounds normally.
     */
    val progressPercent: Int,
    val status: BookStatus,
    val hasCover: Boolean,
    /**
     * The added folder to re-open when this book's access was revoked, or null when
     * it was picked directly and must be picked again to restore the grant.
     */
    val regrantTreeUri: String? = null,
) {
    val isReadable: Boolean get() = status == BookStatus.READABLE

    /** The name to show when one is needed; a book is normally reachable from one place. */
    val fileName: String? get() = fileNames.firstOrNull()

    /**
     * The glyph the cover placeholder shows. Spanish titles often open with `¿`
     * or `¡`, and a punctuation mark tells the reader nothing, so this is the
     * first character that actually carries meaning.
     */
    val coverInitial: String
        get() = title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

    /**
     * The title as the list orders it. Leading punctuation is dropped so
     * `¿Quién teme a la máquina?` files under Q, where its cover placeholder
     * already says it is, instead of ahead of every letter.
     */
    internal val sortKey: String
        get() = title.dropWhile { !it.isLetterOrDigit() }.ifEmpty { title }
}

/** Builds the screen state. Pure: same inputs always give the same screen. */
fun buildLibraryUiState(
    catalog: Catalog,
    ingestion: IngestionState,
    query: String,
    resumeBlocked: ResumeBlocked? = null,
): LibraryUiState {
    // Sort the way the reader's language does, not by UTF-16 code unit: raw
    // ordering drops every accented initial below Z, which would put Ñuño and
    // Álvarez under Zola in exactly the Spanish library `matches` folds accents
    // for. The id breaks ties so a rescan cannot reshuffle equal titles.
    val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
    val byTitle = compareBy<LibraryBookItem, String>(collator) { it.sortKey }.thenBy { it.id }
    val all = catalog.books
        .map { book -> book.toItem(catalog.readingStates[book.id]?.progressFraction ?: 0f) }
        .sortedWith(byTitle)
    val matches = all.filter { it.matches(query) }
    val content = when {
        all.isEmpty() -> LibraryContent.EMPTY_LIBRARY
        matches.isEmpty() -> LibraryContent.NO_SEARCH_RESULTS
        else -> LibraryContent.BOOKS
    }
    return LibraryUiState(
        query = query,
        books = matches,
        content = content,
        scan = (ingestion as? IngestionState.Scanning)?.let {
            LibraryScan(it.trigger, it.processed, it.total, it.currentName)
        },
        failureMessage = (ingestion as? IngestionState.Failed)?.message,
        resumeNotice = resumeBlocked?.let {
            val book = catalog.book(it.bookId)
            // Granting access again, or restoring the folder, answers the notice:
            // leaving it up would tell a reader who has just fixed the problem
            // that it is still there. UNREADABLE is deliberately excluded, because
            // it is the one reason the catalog cannot contradict — it is recorded
            // when a book the catalog calls readable turned out not to open, and
            // hiding the notice on the catalog's say-so would leave the reader
            // with no explanation at all.
            if (book?.status == BookStatus.READABLE && it.reason != ResumeBlockedReason.UNREADABLE) {
                null
            } else {
                ResumeNotice(
                    bookId = it.bookId,
                    // A removed book has no catalog entry left to take a title
                    // from; its position survives removal, so the notice still
                    // has to render.
                    title = book?.title,
                    reason = it.reason,
                )
            }
        },
    )
}


private fun Book.toItem(progressFraction: Float): LibraryBookItem = LibraryBookItem(
    id = id,
    title = title,
    author = author?.takeIf { it.isNotBlank() },
    fileNames = fileNames,
    progressPercent = progressPercent(progressFraction),
    status = status,
    hasCover = hasCover,
    regrantTreeUri = sources
        .firstOrNull { it.availability == SourceAvailability.PERMISSION_LOST && it.origin == SourceOrigin.FOLDER }
        ?.folderId,
)

/**
 * Live search over title, author, and file name (REQ-003).
 *
 * Matching folds case and accents so a Spanish library is searchable from an
 * English keyboard: typing `garcia` finds `García`.
 */
internal fun LibraryBookItem.matches(query: String): Boolean {
    val needle = fold(query)
    if (needle.isEmpty()) return true
    return fold(title).contains(needle) ||
        author?.let { fold(it).contains(needle) } == true ||
        fileNames.any { fold(it).contains(needle) }
}

/** 100% means finished: an unfinished book rounds normally but stops one short of it. */
private fun progressPercent(fraction: Float): Int {
    val clamped = fraction.coerceIn(0f, 1f)
    if (clamped >= 1f) return 100
    return (clamped * 100).roundToInt().coerceIn(0, 99)
}

private fun fold(text: String): String =
    Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD).replace(COMBINING_MARKS, "")

private val COMBINING_MARKS = Regex("\\p{Mn}+")
