package com.cedagova.reader.account.library

import com.cedagova.reader.library.model.PublicationFailureCategory
import com.cedagova.reader.library.model.ReaderCapabilityReason

/**
 * How far one device book's **Add to account library** has got, as the shelf
 * shows it (REQ-505, REQ-506, REQ-507).
 *
 * Every member is a state the definition's table names, and the order below is
 * the order they happen in. Two of them exist specifically because REQ-505 is
 * about what has *not* happened yet:
 *
 * - [Checking] is the tap, before the consent question. The only thing that has
 *   left the device at this point is `GET /reader/v1/imports/policy`, which
 *   carries no book, no name, no digest and no byte of anyone's library — it
 *   asks what this deployment accepts.
 * - [Consent] is the question itself. Nothing about *this book* has been sent
 *   while it is on screen, and cancelling it sends nothing ever.
 *
 * The first thing that names the book to the Reader API is the admission, and
 * the only call that makes one is behind
 * [com.cedagova.reader.library.imports.UploadConsent], which has one member and
 * no default anywhere on the way here.
 */
public sealed interface BookImportState {

    /** True while the owner could still change their mind and nothing is lost. */
    public val cancellable: Boolean get() = false

    /**
     * The action was tapped; the deployment's policy is being read.
     *
     * Nothing about this book has been sent.
     */
    public data object Checking : BookImportState

    /**
     * The consent question: this file's bytes will be uploaded to the Reader
     * account and kept there.
     *
     * [maxSourceBytes] is the policy's own cap for this file's format, quoted
     * so the question states the bound it was just checked against rather than
     * a number this app believes.
     */
    public data class Consent(val sizeBytes: Long, val maxSourceBytes: Long) : BookImportState {
        override val cancellable: Boolean get() = true
    }

    /**
     * The bytes are going out. [fraction] is the share the storage provider has
     * *acknowledged*, never a local count of what was written.
     */
    public data class Sending(val fraction: Float) : BookImportState {
        override val cancellable: Boolean get() = true
    }

    /**
     * The bytes are all at the backend and it is preparing the book —
     * `verifying_upload`, `queued` or `processing`. Still the owner's to call
     * off.
     */
    public data object Finishing : BookImportState {
        override val cancellable: Boolean get() = true
    }

    /**
     * The add did not happen, and the device book is exactly as it was
     * (REQ-507): no file touched, no position moved, no account entry created.
     *
     * [problem] is the backend's own classification wherever there is one; this
     * app invents no category and no message. [code] and [requestId] are the
     * server's words, carried through so a screen and a server log can be read
     * against each other — the same bargain the account notice already makes.
     */
    public data class Refused(
        val problem: ImportProblem,
        val code: String? = null,
        val requestId: String? = null,
        /** The file's size, when the refusal is about how big it is. */
        val sizeBytes: Long? = null,
        /** The policy's cap for this format, when the refusal is about it. */
        val maxSourceBytes: Long? = null,
        /** The backend's own say on whether trying again could work. */
        val retryable: Boolean = true,
    ) : BookImportState
}

/** Why an add did not happen. */
public sealed interface ImportProblem {

    /**
     * The backend's own classification of a refused, failed or cancelled
     * import (REQ-507). Never re-interpreted, never replaced by a local guess.
     */
    public data class Category(val category: PublicationFailureCategory) : ImportProblem

    /**
     * There is no network. The admission is deliberately **not** queued: the
     * transfer needs a connection, and an admission without one would put a
     * pending import on the account that no byte could follow.
     */
    public data object NeedsConnection : ImportProblem

    /** The file the row names cannot be read right now, so there is nothing to send. */
    public data class SourceUnavailable(val problem: PublicationSourceProblem) : ImportProblem

    /** Anything else the Reader API answered; [code] is its own. */
    public data class Api(val status: Int?) : ImportProblem
}

/**
 * The action is not on offer, and why — shown as a reason in place of it.
 *
 * Two sources, never mixed up:
 *
 * - the deployment's policy said `enabled: false` — [requestId] is reader-api's
 *   echo of that policy read and [reason] is null;
 * - the account's `reader.publication-import.v1` capability is unavailable,
 *   missing or duplicated (#139, core.md §6) — [reason] is the entry's own typed
 *   reason, quoted as the code under the sentence, and [requestId] is null
 *   because the capabilities document carries none.
 *
 * Account-wide rather than per book: offering a control that cannot work would
 * be worse than saying so.
 */
public data class ImportsOff(val requestId: String?, val reason: ReaderCapabilityReason? = null)

/**
 * What the account's `reader.publication-import.v1` entry says, as the shelf
 * reads it (#139).
 *
 * The contract's rule is that import is offered only on exactly one entry with
 * `availability: available`; [Unknown] — not read yet this session, or the read
 * failed — is therefore *not* an offer. Discovery reserves nothing: admission
 * stays authoritative and the policy still supplies every cap.
 */
public sealed interface ImportOffer {
    /** Not read yet in this session, or the read did not get an answer. Not an offer. */
    public data object Unknown : ImportOffer

    /** Exactly one entry, `available`. */
    public data object Available : ImportOffer

    /** The entry said no, or there was not exactly one; [reason] is the entry's, or `UNKNOWN`. */
    public data class Unavailable(val reason: ReaderCapabilityReason) : ImportOffer
}

/**
 * Every add this device is in the middle of, plus the account-wide verdicts.
 *
 * [byDeviceBookId] is keyed by the catalog's own book id (`sha256:<hex>`),
 * which is the content identity AD-23 merges on — so a row's progress belongs
 * to the *bytes*, not to a path, a name or a position in a list.
 */
public data class AccountImportsState(
    val disabled: ImportsOff? = null,
    val offer: ImportOffer = ImportOffer.Unknown,
    val byDeviceBookId: Map<String, BookImportState> = emptyMap(),
) {
    public companion object {
        /** Nothing in flight and nothing known: the state before any tap. */
        public val NONE: AccountImportsState = AccountImportsState()
    }
}
