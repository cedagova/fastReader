package com.cedagova.fastreader.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The offer to pick up where another device left off (REQ-511).
 *
 * A value rather than a screen state, and the same three-way split
 * [FrontMatterOffer] uses for the same reasons: the reader surface draws it, the
 * route joins it with the durable record of which changes have been answered, and
 * [ReaderViewModel] decides when it applies. Keeping the three apart is what lets
 * the mapping be proven by arithmetic, the banner by a golden and the settled
 * record by a store test, without any of the three needing the other two.
 *
 * ## What it is not
 *
 * It is not a winner rule. `reader.activity-convergence.v1` says the backend
 * decides which position wins by admission order, and this offer is a *question*
 * — the reader answers it, and declining keeps the position this device already
 * had. Nothing in this app adopts a remote position without that answer, and
 * there is no `max`, furthest-wins or latest-wins anywhere on the path that
 * builds it.
 */
data class ResumeOffer(
    /**
     * The **account's** book id — the id the settled record is stored under, and
     * the same id a `reading_progress` mutation for this book quotes.
     *
     * Never the device's content digest: the record lives on the account's row,
     * so it is keyed the way that row is.
     */
    val accountBookId: String,
    /**
     * Which remote change this offer is about, as
     * [com.cedagova.reader.library.sync.AccountRemotePosition.changeKey]
     * names it.
     *
     * The whole of "offered once per remote change": answering settles this key,
     * and a later change from another client carries a different one and is a new
     * question.
     */
    val changeKey: String,
    /** The token accepting moves to: the remote position mapped into this parse. */
    val targetTokenIndex: Int,
    /**
     * The chapter accepting lands in, as *this* parse names it — or null when the
     * remote position named a section this parse does not have.
     *
     * Null is why the offer has two sentences. A record naming a spine path this
     * edition never had cannot be honoured as a chapter, so the offer names the
     * percentage alone rather than a chapter the tap would not land in; the
     * remote record's own `chapter_title` is deliberately not used as a
     * substitute, because it describes a book this device does not have.
     */
    val chapterTitle: String?,
    /**
     * How far through the book the other client was, as a whole percent.
     *
     * The record's own `progress_percent` when it stated one — so the number this
     * reader is shown is the number the other client published — and otherwise
     * the percent of the mapped token in this parse, which is the same fallback
     * the mapping itself makes.
     */
    val percent: Int,
)

/**
 * The open book's resume offer, and the remote changes whose offer has been
 * answered *in this session*, by [ResumeOffer.changeKey].
 *
 * The answered set is the session-lived twin of the durable record on the
 * account row, and it exists for the gap between the two: answering writes to
 * the account document asynchronously, and the offer can be asked for again —
 * by the very sync that carried the answer's own published position — before
 * that write has landed. Keyed by the change rather than by the book, exactly as
 * the durable record is, so a *newer* position from another client is still a
 * new question after this one was declined.
 */
class ResumeOfferSlot {
    private val _offer = MutableStateFlow<ResumeOffer?>(null)
    private val settled = mutableSetOf<String>()

    /** The offer on screen, or null. */
    val offer: StateFlow<ResumeOffer?> = _offer.asStateFlow()

    /** Shows [candidate] unless its change has been answered this session. */
    fun consider(candidate: ResumeOffer?) {
        _offer.value = candidate?.takeIf { it.changeKey !in settled }
    }

    /** Answers the offer on screen, either way; returns it, or null when there was none. */
    fun settle(): ResumeOffer? {
        val offer = _offer.value ?: return null
        settled += offer.changeKey
        _offer.value = null
        return offer
    }

    /**
     * Forgets the offer *and* the answered set: they are the book's, not the
     * session's, and keeping them would carry one book's answers onto the next.
     */
    fun clear() {
        _offer.value = null
        settled.clear()
    }
}
