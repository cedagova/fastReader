package com.cedagova.fastreader.reader.catalog

import com.cedagova.fastreader.account.library.PortableReadingPosition
import com.cedagova.fastreader.library.ReadingPositions
import com.cedagova.fastreader.library.ReadingState
import com.cedagova.fastreader.library.ui.accountBookIdForDevice
import com.cedagova.fastreader.reader.ReaderPosition
import com.cedagova.fastreader.reader.ReaderPositions
import com.cedagova.fastreader.reader.ResumeOffer
import com.cedagova.reader.account.library.AccountShelf
import com.cedagova.reader.engine.content.BookContent
import com.cedagova.reader.engine.content.TokenPosition
import com.cedagova.reader.library.sync.RemoteReadingPosition
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow

/**
 * The catalog store, as durability needs it (LEAF204).
 *
 * The only place the reader's [ReaderPosition] and the catalog's [ReadingState]
 * meet, so neither package has to know the other's shape.
 */
internal class CatalogPositions(
    private val positions: ReadingPositions,
    /**
     * Where a portable position goes, or null when this build has no account
     * surface at all (#120).
     *
     * Deliberately the whole shelf rather than a book id: whether the open book
     * is an account book is a question about the account's *current* rows, and
     * the answer changes while the reader is reading — a book added to the
     * account from the shelf, a sign-out mid-chapter. Resolving it at each flush
     * asks the live state; resolving it once at open would answer from a shelf
     * that has since changed.
     */
    private val account: AccountShelf? = null,
) : ReaderPositions {

    override val failure: StateFlow<String?> get() = positions.persistenceFailure

    override fun restore(bookId: String): ReaderPosition? {
        val stored = positions.readingState(bookId) ?: return null
        return ReaderPosition(
            position = TokenPosition(stored.bookDigest, stored.tokenIndex, stored.pipelineVersion),
            progressFraction = stored.progressFraction,
            wpm = stored.wpm,
            structuralFingerprint = stored.structuralFingerprint,
        )
    }

    override fun record(bookId: String, position: ReaderPosition) {
        positions.record(
            bookId,
            ReadingState(
                bookDigest = position.position.bookDigest,
                tokenIndex = position.position.tokenIndex,
                pipelineVersion = position.position.pipelineVersion,
                progressFraction = position.progressFraction,
                wpm = position.wpm,
                // Null when this open read no central directory. The store keeps
                // whatever it already holds rather than clearing it (AD-18).
                structuralFingerprint = position.structuralFingerprint,
            ),
        )
    }

    override fun flush() {
        positions.flush()
    }

    /**
     * Publishes the portable position, for an account book only.
     *
     * Two gates, and a device book fails the second exactly as REQ-512 requires.
     * `accountBookIdForDevice` is the same content-identity bridge the shelf uses
     * to tell the reader's open apart from the account's row (AD-23), and it
     * returns null for every book the account does not hold — so a device-only
     * book never names itself to the Reader API from here, any more than it does
     * from the open that `MainActivity` reports.
     *
     * Signed out there is no shelf state to resolve against and `books` is empty,
     * so the same null comes back and nothing is sent (D4).
     */
    override fun publishPortable(bookId: String, content: BookContent, tokenIndex: Int) {
        val shelf = account ?: return
        val accountBookId = accountBookIdForDevice(bookId, shelf.state.value) ?: return
        val portable = PortableReadingPosition.of(content, tokenIndex) ?: return
        shelf.recordPosition(accountBookId, portable)
    }

    /**
     * The resume offer for this book, or null when there is nothing to ask
     * (REQ-511).
     *
     * The same two gates [publishPortable] has, in the same order and for the
     * same reasons — a device book resolves to no account id, and signed out
     * there are no rows to resolve against — and then four of its own:
     *
     * 1. **The account holds no position for this book.** Nothing has been said
     *    about it by anybody, so there is nothing to offer.
     * 2. **The position maps to where the reader already is, or behind it.**
     * 3. **The book has no tokens.** There is no word to land on.
     * 4. **The position is this device's own** (#140): the backend admitted it
     *    from this device's publish, as
     *    [com.cedagova.reader.library.sync.AccountBook.ownPositionChangeKey]
     *    records. Gate 2 alone does not cover it — publish at 40 %, rewind to
     *    30 %, and the account's 40 % is ahead of the reader but was never
     *    another device's.
     *
     * ## Gate 2 is a question about whether to ask, not about who wins
     *
     * This is the one comparison in the app that puts a remote position next to
     * a local one, and the distinction matters enough to state twice. It decides
     * whether a *question* is worth putting on the screen; it never selects a
     * position. Nothing downstream of it reads the comparison: accepting always
     * moves to [ResumeOffer.targetTokenIndex] exactly as the mapping produced
     * it, declining always keeps the local position untouched, and the position
     * this device publishes is unaffected either way — a backward move still
     * goes out like any other, and `reader.activity-convergence.v1` still
     * decides which position the account ends up holding.
     *
     * What it rules out is the case that made the gate necessary: this device's
     * *own* published position comes back through the change stream, so without
     * it every pause would be followed by an offer to resume at the percent the
     * reader is already reading. The strict `>` is what makes "the same place"
     * silent, and #121's "a remote position older than local produces no offer"
     * is the same `>` seen from the other side.
     */
    override fun remoteOffer(bookId: String, content: BookContent, tokenIndex: Int): ResumeOffer? {
        if (content.isEmpty) return null
        val shelf = account ?: return null
        val state = shelf.state.value
        val accountBookId = accountBookIdForDevice(bookId, state) ?: return null
        val row = state.books.firstOrNull { it.bookId == accountBookId } ?: return null
        val remote = row.remotePosition ?: return null
        // Gate 4 (#140): the account's position is this device's own admitted
        // publish. It is not another device's place, whatever the reader has done
        // since — a rewind below it included — so it is never offered as one.
        if (remote.changeKey == row.ownPositionChangeKey) return null
        val position = RemoteReadingPosition(
            href = remote.href,
            chapterTitle = remote.chapterTitle,
            progression = remote.progression,
            percent = remote.percent,
            updatedAt = remote.updatedAt,
        )
        val target = PortableReadingPosition.tokenIndexFor(content, position)
        if (target <= tokenIndex) return null
        return ResumeOffer(
            accountBookId = accountBookId,
            changeKey = remote.changeKey,
            targetTokenIndex = target,
            // This parse's own title for the section the record named, and null
            // for a section it does not have — the record's own chapter title is
            // not a substitute, because it describes an edition this device
            // cannot land in.
            chapterTitle = remote.href
                ?.let { href -> content.chapters.firstOrNull { it.spinePath == href } }
                ?.title
                ?.takeIf { it.isNotBlank() },
            // The other client's own number when it stated one, so the reader
            // sees what was published rather than a re-derivation of it; the
            // mapped token's percent otherwise, which is the same fallback the
            // mapping made to get there.
            percent = remote.percent?.roundToInt()?.coerceIn(0, 100)
                ?: PortableReadingPosition.of(content, target)?.percent
                ?: return null,
        )
    }
}
