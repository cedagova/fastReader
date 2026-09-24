package com.cedagova.fastreader.account

import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.auth.ReaderSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * What the account surface can ask for. The controller implements it; the
 * goldens hand the screen an object that does nothing.
 */
interface ReaderAccountActions {
    fun requestEmailCode(email: String, newAccount: Boolean)
    fun verifyEmailCode(email: String, code: String)
    fun signInWithPassword(email: String, password: String)
    fun requestRecoveryCode(email: String)
    fun verifyRecoveryCode(email: String, code: String)
    fun setPassword(newPassword: String)
    fun loadCapabilities()
    fun signOut()
    fun signOutOtherDevices()
    fun dismissOutcome()
}

/**
 * The account surface's state model (#100): the library's session state plus
 * the one operation in flight and the last outcome, folded into one
 * [ReaderAccountState] the screen renders from.
 *
 * ## What it does not do
 *
 * It classifies no error — each `ReaderAuthException` branch becomes its
 * [AccountOutcome] twin and nothing else — retries nothing, and never re-sends
 * or re-verifies a code: every tap is exactly one gateway call, and a second
 * tap while one is in flight is ignored rather than queued. `NotConfigured`
 * is a state, not an outcome: a controller built with no gateway reports
 * [ReaderAccountState.NotConfigured] and has nothing to call.
 *
 * ## Readiness
 *
 * The session is [ReaderAccountState.Loading] until the gateway has read the
 * stored session, so a device with a session never shows the sign-in form for
 * a frame. A session that goes away for any reason — sign-out here, or the
 * server rejecting it — drops the capabilities document with it.
 *
 * Process-scoped, like the library graph: it survives rotation and leaving
 * the screen, and a code request made before checking the inbox is still the
 * state on screen when the reader comes back.
 */
class ReaderAccountController(
    private val gateway: ReaderAccountGateway?,
    missingValues: List<String>,
    private val scope: CoroutineScope,
) : ReaderAccountActions {

    private val session = MutableStateFlow<ReaderSessionState>(ReaderSessionState.Initializing)
    private val activity = MutableStateFlow<AccountActivity?>(null)
    private val outcome = MutableStateFlow<AccountOutcome?>(null)
    private val capabilities = MutableStateFlow<LoadedCapabilities?>(null)

    val state: StateFlow<ReaderAccountState> = if (gateway == null) {
        MutableStateFlow(ReaderAccountState.NotConfigured(missingValues))
    } else {
        combine(session, activity, outcome, capabilities) { session, activity, outcome, capabilities ->
            when (session) {
                ReaderSessionState.Initializing -> ReaderAccountState.Loading
                ReaderSessionState.SignedOut -> ReaderAccountState.SignedOut(activity, outcome)
                is ReaderSessionState.SignedIn -> ReaderAccountState.SignedIn(
                    userId = session.userId,
                    email = session.email,
                    activity = activity,
                    outcome = outcome,
                    capabilities = capabilities,
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, ReaderAccountState.Loading)
    }

    init {
        if (gateway != null) {
            scope.launch {
                gateway.awaitReady()
                gateway.sessionState.collect { next ->
                    if (next !is ReaderSessionState.SignedIn) capabilities.value = null
                    session.value = next
                }
            }
        }
    }

    override fun requestEmailCode(email: String, newAccount: Boolean) = run(AccountActivity.REQUESTING_CODE) {
        it.requestEmailCode(email, createUser = newAccount)
        AccountOutcome.CodeSent
    }

    override fun verifyEmailCode(email: String, code: String) = run(AccountActivity.VERIFYING_CODE) {
        it.verifyEmailCode(email, code)
        null
    }

    override fun signInWithPassword(email: String, password: String) = run(AccountActivity.SIGNING_IN_WITH_PASSWORD) {
        it.signInWithPassword(email, password)
        null
    }

    override fun requestRecoveryCode(email: String) = run(AccountActivity.REQUESTING_RECOVERY_CODE) {
        it.requestRecoveryCode(email)
        AccountOutcome.RecoveryCodeSent
    }

    override fun verifyRecoveryCode(email: String, code: String) = run(AccountActivity.VERIFYING_RECOVERY_CODE) {
        it.verifyRecoveryCode(email, code)
        null
    }

    override fun setPassword(newPassword: String) = run(AccountActivity.SETTING_PASSWORD) {
        it.setPassword(newPassword)
        AccountOutcome.PasswordSet
    }

    override fun loadCapabilities() = run(AccountActivity.LOADING_CAPABILITIES, onFailure = { capabilities.value = null }) {
        val response = it.capabilities()
        capabilities.value = LoadedCapabilities(
            document = pretty.encodeToString(JsonObject.serializer(), response.document),
            requestId = response.requestId,
        )
        null
    }

    override fun signOut() = run(AccountActivity.SIGNING_OUT) {
        it.signOut()
        AccountOutcome.SignedOutLocally
    }

    override fun signOutOtherDevices() = run(AccountActivity.SIGNING_OUT_OTHER_DEVICES) {
        it.signOutOtherDevices()
        AccountOutcome.OtherDevicesSignedOut
    }

    override fun dismissOutcome() {
        outcome.value = null
    }

    /**
     * One gateway call: marks [what] in flight, runs [block] once, and records
     * its outcome — the value it returns, or the twin of the exception it
     * threw. Refused, not queued, while another call is in flight.
     */
    private fun run(
        what: AccountActivity,
        onFailure: () -> Unit = {},
        block: suspend (ReaderAccountGateway) -> AccountOutcome?,
    ) {
        val gateway = gateway ?: return
        if (!activity.compareAndSet(null, what)) return
        outcome.value = null
        scope.launch {
            try {
                outcome.value = block(gateway)
            } catch (e: ReaderAuthException) {
                onFailure()
                outcome.value = e.toOutcome()
            } finally {
                activity.value = null
            }
        }
    }

    private companion object {
        val pretty = Json { prettyPrint = true }
    }
}

/**
 * The branch-to-outcome map, total over the sealed class so a branch the
 * library adds later fails to compile here rather than falling into a
 * catch-all. `NotConfigured` cannot reach a controller that has a gateway —
 * the application builds one only for a configured client — but the map
 * stays total; it is reported as a mismatch naming the library's own message.
 */
internal fun ReaderAuthException.toOutcome(): AccountOutcome = when (this) {
    is ReaderAuthException.NotConfigured -> AccountOutcome.ConfigurationMismatch(message.orEmpty())
    is ReaderAuthException.ConfigurationMismatch -> AccountOutcome.ConfigurationMismatch(reason)
    is ReaderAuthException.SignInUnavailable -> AccountOutcome.SignInUnavailable(reason)
    is ReaderAuthException.NetworkUnavailable -> AccountOutcome.NetworkUnavailable
    is ReaderAuthException.TryLater -> AccountOutcome.TryLater(status, code, retryAfter?.inWholeSeconds, requestId)
    is ReaderAuthException.SignedOut -> AccountOutcome.SessionGone(code, requestId)
    is ReaderAuthException.Forbidden -> AccountOutcome.Forbidden(code, requestId)
    is ReaderAuthException.ProviderRejected -> AccountOutcome.ProviderRejected(status, code, description)
    is ReaderAuthException.ApiError -> AccountOutcome.ApiError(status, code, requestId, description)
    is ReaderAuthException.StorageUnavailable -> AccountOutcome.StorageUnavailable
    is ReaderAuthException.UnexpectedResponse -> AccountOutcome.UnexpectedResponse
}
