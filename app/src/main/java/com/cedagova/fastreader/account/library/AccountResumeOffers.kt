package com.cedagova.fastreader.account.library

import com.cedagova.reader.library.sync.AccountBook
import com.cedagova.reader.library.sync.AccountHostRecords
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The remote change whose resume offer this reader has already answered
 * (schema 5, REQ-511), as `AccountRemotePosition.changeKey` names it — or null
 * while none has been.
 *
 * A note this device makes about itself, so that "offered once per remote
 * change" survives the process that made the offer. It is a host record on the
 * book's row (#147): `:reader-library`'s engine stores it verbatim beside the
 * account's fields, never puts it in a mutation payload — whether a reader was
 * asked a question is not a fact about their account — and adopting a canonical
 * payload neither clears nor sets it.
 *
 * Keyed by the change rather than by the book on purpose: declining settles
 * *that* position, and a newer one from another client is a new question. A
 * boolean per book would silence every later change too.
 */
val AccountBook.resumeOfferSettledFor: String?
    get() = (host[AccountResumeOffers.KEY] as? JsonPrimitive)?.contentOrNull

/**
 * Records that the resume offer for one remote change has been answered
 * (REQ-511), whichever way it was answered.
 *
 * It **queues nothing and sends nothing**: it writes the book row's
 * [resumeOfferSettledFor] host record through the engine's single writer and
 * that is all. A book the account has no row for is skipped rather than
 * invented — there is nothing to have been offered for.
 */
class AccountResumeOffers(private val records: AccountHostRecords) {

    suspend fun settle(bookId: String, changeKey: String) {
        records.updateBookHostRecord(bookId, KEY) { JsonPrimitive(changeKey) }
    }

    companion object {
        /** The book row's host record: schema 5's own `resumeOfferSettledFor` key. */
        const val KEY: String = "resumeOfferSettledFor"
    }
}
