package com.cedagova.fastreader.account

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
sealed interface ReaderAccountState {

    /** The operation in flight, if any; every control is disabled while one is. Only the two live states can have one. */
    val activity: AccountActivity?

    /** The last operation's outcome, if it has not been dismissed. Only the two live states can have one. */
    val outcome: AccountOutcome?

    /** The stored session has not been read yet; the surface draws nothing. */
    data object Loading : ReaderAccountState {
        override val activity: AccountActivity? get() = null
        override val outcome: AccountOutcome? get() = null
    }

    /**
     * A build without the stage values: [missingValues] names the absent
     * `local.properties` keys, every action is absent, and nothing is called.
     * `ReaderAuthException.NotConfigured` is this state, not an error.
     */
    data class NotConfigured(val missingValues: List<String>) : ReaderAccountState {
        override val activity: AccountActivity? get() = null
        override val outcome: AccountOutcome? get() = null
    }

    /** No session on this device: the sign-in forms, in the contract's preference order. */
    data class SignedOut(
        override val activity: AccountActivity? = null,
        override val outcome: AccountOutcome? = null,
    ) : ReaderAccountState

    /** A session on this device, for [email] (the provider's [userId] when it carries no address). */
    data class SignedIn(
        val userId: String,
        val email: String?,
        override val activity: AccountActivity? = null,
        override val outcome: AccountOutcome? = null,
        /** The last capabilities document fetched, until the next fetch or a sign-out. */
        val capabilities: LoadedCapabilities? = null,
    ) : ReaderAccountState
}

/** One library operation, from the tap until it returns; the surface runs at most one at a time. */
enum class AccountActivity {
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
sealed interface AccountOutcome {

    /** The provider was asked to email a sign-in or sign-up code. */
    data object CodeSent : AccountOutcome

    /** The provider was asked to email a recovery code. */
    data object RecoveryCodeSent : AccountOutcome

    data object PasswordSet : AccountOutcome

    /** This device signed out; the provider was told on a best-effort basis. */
    data object SignedOutLocally : AccountOutcome

    /** Every other device's session was revoked; this one stays. */
    data object OtherDevicesSignedOut : AccountOutcome

    /** `ReaderAuthException.ProviderRejected`: a wrong code or password, a weak password, an unknown address. */
    data class ProviderRejected(val status: Int, val code: String?, val description: String) : AccountOutcome

    /** `ReaderAuthException.TryLater`: a rate limit or a server failure after the library's one retry; nothing was changed. */
    data class TryLater(val status: Int, val code: String?, val retryAfterSeconds: Long?, val requestId: String?) : AccountOutcome

    /** `ReaderAuthException.NetworkUnavailable`: nothing was sent and nothing was cleared. */
    data object NetworkUnavailable : AccountOutcome

    /** `ReaderAuthException.ConfigurationMismatch`: refused before any provider call, with the library's reason. */
    data class ConfigurationMismatch(val reason: String) : AccountOutcome

    /** `ReaderAuthException.SignedOut`: the server rejected the session and the library cleared it. Shown once. */
    data class SessionGone(val code: String?, val requestId: String?) : AccountOutcome

    /** `ReaderAuthException.Forbidden`: authenticated but not allowed; the session is intact. */
    data class Forbidden(val code: String?, val requestId: String?) : AccountOutcome

    /** `ReaderAuthException.ApiError`: any other reader-api answer, with its code and request id; the session is intact. */
    data class ApiError(val status: Int, val code: String?, val requestId: String?, val description: String) : AccountOutcome
}

/** A `reader.capabilities.v1` document as returned, pretty-printed, and the request id of the call that fetched it (REQ-407). */
data class LoadedCapabilities(val document: String, val requestId: String)

/** What the Settings row says about the account, derived from [ReaderAccountState]. */
sealed interface ReaderAccountSummary {
    data object Loading : ReaderAccountSummary
    data object NotConfigured : ReaderAccountSummary
    data object SignedOut : ReaderAccountSummary
    data class SignedIn(val email: String) : ReaderAccountSummary
}

fun ReaderAccountState.summary(): ReaderAccountSummary = when (this) {
    ReaderAccountState.Loading -> ReaderAccountSummary.Loading
    is ReaderAccountState.NotConfigured -> ReaderAccountSummary.NotConfigured
    is ReaderAccountState.SignedOut -> ReaderAccountSummary.SignedOut
    is ReaderAccountState.SignedIn -> ReaderAccountSummary.SignedIn(email ?: userId)
}
