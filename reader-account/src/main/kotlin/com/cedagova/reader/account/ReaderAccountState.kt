package com.cedagova.reader.account

import com.cedagova.reader.library.sync.AccountSession

/**
 * What the Reader account surface renders (#100, REQ-401): exactly the
 * definition's three states — not configured, signed out, signed in as
 * `<email>` — plus the blank moment before the stored session has been read,
 * which is never shown as "signed out".
 *
 * The transient outcomes of the definition's states table ride on the two
 * live states as an [AccountActivity] (an operation in flight) and an
 * [AccountOutcome] (what the last one came back with). Everything here is a
 * plain value, so the surface, its unit tests and its goldens are reachable
 * from a literal with no SDK behind them.
 */
public sealed interface ReaderAccountState {

    /** The operation in flight, if any; every control is disabled while one is. Only the two live states can have one. */
    public val activity: AccountActivity?

    /** The last operation's outcome, if it has not been dismissed. Only the two live states can have one. */
    public val outcome: AccountOutcome?

    /** The stored session has not been read yet; the surface draws nothing. */
    public data object Loading : ReaderAccountState {
        override val activity: AccountActivity? get() = null
        override val outcome: AccountOutcome? get() = null
    }

    /**
     * A build without the stage values: [missingValues] names the absent
     * `local.properties` keys, every action is absent, and nothing is called.
     * `ReaderAuthException.NotConfigured` is this state, not an error.
     */
    public data class NotConfigured(val missingValues: List<String>) : ReaderAccountState {
        override val activity: AccountActivity? get() = null
        override val outcome: AccountOutcome? get() = null
    }

    /** No session on this device: the sign-in forms, in the contract's preference order. */
    public data class SignedOut(
        override val activity: AccountActivity? = null,
        override val outcome: AccountOutcome? = null,
    ) : ReaderAccountState

    /** A session on this device, for [email] (the provider's [userId] when it carries no address). */
    public data class SignedIn(
        val userId: String,
        val email: String?,
        override val activity: AccountActivity? = null,
        override val outcome: AccountOutcome? = null,
        /** The last capabilities document fetched, until the next fetch or a sign-out. */
        val capabilities: LoadedCapabilities? = null,
    ) : ReaderAccountState
}

/**
 * The session as `:reader-library`'s account sync engine reads it (#147): who is
 * signed in, and nothing else of this surface's state.
 */
public fun ReaderAccountState.toAccountSession(): AccountSession = when (this) {
    ReaderAccountState.Loading -> AccountSession.Loading
    is ReaderAccountState.NotConfigured -> AccountSession.NotConfigured
    is ReaderAccountState.SignedOut -> AccountSession.SignedOut
    is ReaderAccountState.SignedIn -> AccountSession.SignedIn(userId)
}

/** One library operation, from the tap until it returns; the surface runs at most one at a time. */
public enum class AccountActivity {
    REQUESTING_CODE,
    VERIFYING_CODE,
    SIGNING_IN_WITH_PASSWORD,
    REQUESTING_RECOVERY_CODE,
    VERIFYING_RECOVERY_CODE,
    SETTING_PASSWORD,
    LOADING_CAPABILITIES,
    SIGNING_OUT,
    SIGNING_OUT_OTHER_DEVICES,
}

/**
 * What the last operation came back with. The first five are successes the
 * session state alone cannot express; the rest map one-to-one onto the
 * branches of `ReaderAuthException`, carrying the provider's or the backend's
 * code and the backend's request id so the screen can quote them. The app
 * classifies nothing itself: [ReaderAccountController] converts each branch
 * to its twin here and does nothing else with it.
 */
public sealed interface AccountOutcome {

    /** The provider was asked to email a sign-in or sign-up code. */
    public data object CodeSent : AccountOutcome

    /** The provider was asked to email a recovery code. */
    public data object RecoveryCodeSent : AccountOutcome

    public data object PasswordSet : AccountOutcome

    /** This device signed out; the provider was told on a best-effort basis. */
    public data object SignedOutLocally : AccountOutcome

    /** Every other device's session was revoked; this one stays. */
    public data object OtherDevicesSignedOut : AccountOutcome

    /** `ReaderAuthException.ProviderRejected`: a wrong code or password, a weak password, an unknown address. */
    public data class ProviderRejected(val status: Int, val code: String?, val description: String) : AccountOutcome

    /** `ReaderAuthException.TryLater`: a rate limit or a server failure after the library's one retry; nothing was changed. */
    public data class TryLater(val status: Int, val code: String?, val retryAfterSeconds: Long?, val requestId: String?) :
        AccountOutcome

    /** `ReaderAuthException.NetworkUnavailable`: nothing was sent and nothing was cleared. */
    public data object NetworkUnavailable : AccountOutcome

    /** `ReaderAuthException.ConfigurationMismatch`: refused before any provider call, with the library's reason. */
    public data class ConfigurationMismatch(val reason: String) : AccountOutcome

    /** `ReaderAuthException.SignInUnavailable`: the server does not allow this sign-in now (#160); nothing was sent. */
    public data class SignInUnavailable(val reason: String) : AccountOutcome

    /** `ReaderAuthException.SignedOut`: the server rejected the session and the library cleared it. Shown once. */
    public data class SessionGone(val code: String?, val requestId: String?) : AccountOutcome

    /** `ReaderAuthException.Forbidden`: authenticated but not allowed; the session is intact. */
    public data class Forbidden(val code: String?, val requestId: String?) : AccountOutcome

    /** `ReaderAuthException.ApiError`: any other reader-api answer, with its code and request id; the session is intact. */
    public data class ApiError(val status: Int, val code: String?, val requestId: String?, val description: String) :
        AccountOutcome

    /** `ReaderAuthException.StorageUnavailable`: signed in for this run, but the device could not save the session. */
    public data object StorageUnavailable : AccountOutcome

    /** `ReaderAuthException.UnexpectedResponse`: the sign-in service's answer could not be read (#191); nothing was changed. */
    public data object UnexpectedResponse : AccountOutcome
}

/** A `reader.capabilities.v1` document as returned, pretty-printed, and the request id of the call that fetched it (REQ-407). */
public data class LoadedCapabilities(val document: String, val requestId: String)

/** What the Settings row says about the account, derived from [ReaderAccountState]. */
public sealed interface ReaderAccountSummary {
    public data object Loading : ReaderAccountSummary
    public data object NotConfigured : ReaderAccountSummary
    public data object SignedOut : ReaderAccountSummary
    public data class SignedIn(val email: String) : ReaderAccountSummary
}

public fun ReaderAccountState.summary(): ReaderAccountSummary = when (this) {
    ReaderAccountState.Loading -> ReaderAccountSummary.Loading
    is ReaderAccountState.NotConfigured -> ReaderAccountSummary.NotConfigured
    is ReaderAccountState.SignedOut -> ReaderAccountSummary.SignedOut
    is ReaderAccountState.SignedIn -> ReaderAccountSummary.SignedIn(email ?: userId)
}
