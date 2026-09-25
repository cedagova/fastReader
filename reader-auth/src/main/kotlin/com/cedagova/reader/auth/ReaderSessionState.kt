package com.cedagova.reader.auth

import kotlin.time.Instant

/** What a host renders: the module's view of the stored session, never the tokens themselves. */
public sealed interface ReaderSessionState {

    /** The stored session has not been read yet. */
    public data object Initializing : ReaderSessionState

    /** No session is stored on this device. */
    public data object SignedOut : ReaderSessionState

    /**
     * A session is stored. [userId] is the provider's subject for it — the
     * identity reader-api sees as the bearer token's `sub`.
     */
    public data class SignedIn(val userId: String, val email: String?, val expiresAt: Instant) : ReaderSessionState
}
