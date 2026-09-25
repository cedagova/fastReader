package com.cedagova.reader.account.library

/**
 * Where the host notes that a resume offer has been answered (#200,
 * A197-F003): [AccountShelf]'s one collaborator besides the engine's actions.
 *
 * Whether and when a reader is offered to resume at another device's position
 * is host UX policy (REQ-511 in FastReader), so the note stays the host's: the
 * shelf only passes the answer through. One operation, and it must put nothing
 * on the wire — `AccountShelfTest` holds that this interface has no other.
 *
 * FastReader's implementation is `AccountResumeOffers`, a host record on the
 * book's row of the account document. A host's tests substitute
 * `com.cedagova.reader.account.testing.RecordingResumeOfferRecords` from this
 * module's test fixtures.
 */
public interface ResumeOfferRecords {

    /** Records that the resume offer for the remote change [changeKey] of [bookId] was answered, either way. */
    public suspend fun settle(bookId: String, changeKey: String)
}
