package com.cedagova.reader.auth

import android.content.Context
import android.util.Base64
import com.cedagova.reader.auth.api.PreAuthDocument
import com.cedagova.reader.auth.api.ReaderApiClient
import com.cedagova.reader.auth.api.ReaderApiResponse
import com.cedagova.reader.auth.api.ReaderProfileUpdate
import com.cedagova.reader.auth.api.SignInMethod
import com.cedagova.reader.auth.session.FileSessionStore
import com.cedagova.reader.auth.session.KeystoreSessionCipher
import com.cedagova.reader.auth.session.SessionStore
import com.cedagova.reader.auth.session.StoreSessionManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.OtpVerifyResult
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.AuthWeakPasswordException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Reader authentication module: one object a host creates with its
 * [ReaderAuthConfig] and keeps for the life of the process. It implements
 * `CONTRACT.md` — bootstrap, the sign-in methods, the encrypted session
 * store, single-flight refresh, the reader-api call policy, and sign-out —
 * on top of the identity provider's Kotlin SDK with three of that SDK's
 * defaults overridden (see [build]).
 *
 * Every operation throws a [ReaderAuthException] on failure, so a host renders
 * one closed set of outcomes and never parses a message.
 */
public class ReaderAuthClient internal constructor(
    public val config: ReaderAuthConfig,
    internal val supabase: SupabaseClient,
    private val store: SessionStore,
    private val sessions: StoreSessionManager,
    private val refresher: SessionRefresher,
    /** The reader-api client, for any further route a host needs. */
    public val api: ReaderApiClient,
    private val clock: ReaderClock = ReaderClock.System,
) : ReaderAuthOperations {
    private val auth: Auth get() = supabase.auth
    private val bootstrapLock = Mutex()

    @Volatile
    private var verifiedPreAuth: VerifiedPreAuth? = null

    private class VerifiedPreAuth(val document: PreAuthDocument, val lifetime: PreAuthDocument.Lifetime)

    /** The stored session as a host renders it; emits on every change. */
    override val sessionState: Flow<ReaderSessionState> = auth.sessionStatus.map { it.toState() }

    /** The current [sessionState] value without collecting. */
    override fun currentState(): ReaderSessionState = auth.sessionStatus.value.toState()

    /** Suspends until the stored session has been read (or found absent). */
    override suspend fun awaitReady(): Unit = auth.awaitInitialization()

    // ---- Bootstrap ---------------------------------------------------------------------------

    /**
     * `GET /v1/reader/pre-auth` and the fail-closed checks (CONTRACT.md,
     * "Bootstrap and first calls"): sign-in is refused with
     * [ReaderAuthException.ConfigurationMismatch] unless the document names
     * this client's application id, publishable key, and authority, and with
     * [ReaderAuthException.SignInUnavailable] unless its account entry is
     * available. A host reads [PreAuthDocument.enabledMethods] from the
     * result to offer only the methods the server has turned on.
     *
     * The verified document is reused until its `freshUntil`, then fetched
     * again. If that fetch fails with [ReaderAuthException.NetworkUnavailable]
     * or [ReaderAuthException.TryLater], the old document stands in until its
     * `staleUntil`; after that the failure is thrown. A document that fails a
     * check is never kept.
     */
    override suspend fun bootstrap(): PreAuthDocument = bootstrapLock.withLock {
        val kept = verifiedPreAuth
        val now = clock.now()
        if (kept != null && now < kept.lifetime.freshUntil) return kept.document
        val document = try {
            api.preAuth()
        } catch (e: ReaderAuthException) {
            val transient = e is ReaderAuthException.NetworkUnavailable || e is ReaderAuthException.TryLater
            if (transient && kept != null && now < kept.lifetime.staleUntil) return kept.document
            verifiedPreAuth = null
            throw e
        }
        val receivedAt = clock.now()
        verifiedPreAuth = null
        document.mismatch(config)?.let { throw ReaderAuthException.ConfigurationMismatch(it) }
        if (!document.accountEntryAvailable) {
            val entry = document.accountEntry
            throw ReaderAuthException.SignInUnavailable(
                entry?.reason ?: "account_entry_${entry?.availability ?: "missing"}",
                entry?.retryable ?: false,
            )
        }
        verifiedPreAuth = VerifiedPreAuth(document, document.lifetime(receivedAt))
        document
    }

    /** [bootstrap], then refuses [method] with [ReaderAuthException.SignInUnavailable] when the server has turned it off. */
    private suspend fun ensureBootstrapped(method: SignInMethod) {
        if (method !in bootstrap().enabledMethods) {
            throw ReaderAuthException.SignInUnavailable(METHOD_DISABLED, retryable = false, method = method)
        }
    }

    // ---- Sign-in methods, in the contract's preference order ---------------------------------

    /**
     * Email code, step one: ask the provider to email a six-digit code.
     * [createUser] true is sign-up (a new address gets an account); false
     * refuses an unknown address.
     */
    override suspend fun requestEmailCode(email: String, createUser: Boolean) {
        ensureBootstrapped(SignInMethod.EMAIL_CODE)
        providerCall {
            auth.signInWith(OTP) {
                this.email = email
                this.createUser = createUser
            }
        }
    }

    /**
     * Email code, step two: exchange the emailed [code] for a session. `email`
     * verifies both a sign-in and a sign-up code; a host that knows it is
     * completing a sign-up may pass [EmailCodePurpose.SIGN_UP]. Never retried
     * automatically — a wrong or expired code is [ReaderAuthException.ProviderRejected],
     * and a provider 429 is [ReaderAuthException.TryLater].
     */
    override suspend fun verifyEmailCode(
        email: String,
        code: String,
        purpose: EmailCodePurpose,
    ): ReaderSessionState.SignedIn {
        ensureBootstrapped(SignInMethod.EMAIL_CODE)
        return verify(purpose.otpType, email, code)
    }

    /** Password sign-in. */
    override suspend fun signInWithPassword(email: String, password: String): ReaderSessionState.SignedIn {
        ensureBootstrapped(SignInMethod.PASSWORD)
        refresher.withoutRefresh {
            provider {
                auth.signInWith(Email) {
                    this.email = email
                    this.password = password
                }
            }
        }
        return signedInOrThrow()
    }

    /** Code-based recovery, step one: ask the provider to email a recovery code. */
    override suspend fun requestRecoveryCode(email: String) {
        ensureBootstrapped(SignInMethod.PASSWORD)
        providerCall { auth.resetPasswordForEmail(email) }
    }

    /** Code-based recovery, step two: the recovery code yields a session; then call [setPassword]. */
    override suspend fun verifyRecoveryCode(email: String, code: String): ReaderSessionState.SignedIn {
        ensureBootstrapped(SignInMethod.PASSWORD)
        return verify(OtpType.Email.RECOVERY, email, code)
    }

    /**
     * Sets the signed-in user's password (recovery step three, or a plain
     * change). The provider call saves the session again with the updated
     * user, so it runs under the refresh mutex (#179): a refresh in flight
     * finishes first, and none can land between the SDK reading the session
     * and saving it back, which would restore the spent refresh token.
     */
    override suspend fun setPassword(newPassword: String) {
        refresher.sessionForRequest() ?: throw ReaderAuthException.SignedOut(code = null)
        refresher.withoutRefresh {
            // A sign-out may have run while this call waited for the mutex.
            auth.currentSessionOrNull() ?: throw ReaderAuthException.SignedOut(code = null)
            provider { auth.updateUser { password = newPassword } }
        }
    }

    // ---- Protected calls ---------------------------------------------------------------------

    /** The first authenticated call after sign-in. */
    override suspend fun capabilities(): JsonObject = api.capabilities()

    /** [capabilities], with the request id the successful call carried beside the document (#100). */
    override suspend fun capabilitiesResponse(): ReaderApiResponse = api.capabilitiesResponse()

    /** The profile upsert that precedes any profile read. */
    override suspend fun upsertProfile(update: ReaderProfileUpdate): JsonObject = api.upsertProfile(update)

    /**
     * What a host calls when it returns to the foreground: refreshes the
     * session if it is inside the margin, and reports the resulting state.
     * The module runs no timer of its own.
     *
     * Never throws a [ReaderAuthException] (#159). A refresh that fails keeps
     * or clears the session exactly as CONTRACT.md's refresh outcomes say, and
     * the returned state is the whole answer: [ReaderAuthException.SignedOut]
     * leaves `SignedOut`; a transient failure ([ReaderAuthException.TryLater],
     * [ReaderAuthException.NetworkUnavailable], [ReaderAuthException.ProviderRejected],
     * [ReaderAuthException.UnexpectedResponse]) leaves the still-valid session
     * `SignedIn`, and the next protected call refreshes again;
     * [ReaderAuthException.StorageUnavailable] leaves the refreshed session
     * `SignedIn` for this process. A host that needs the
     * failure itself gets it from its next protected call.
     */
    override suspend fun onForeground(): ReaderSessionState {
        try {
            refresher.sessionForRequest()
        } catch (e: ReaderAuthException) {
            // The session state already says what happened.
        }
        return currentState()
    }

    // ---- Sign-out ----------------------------------------------------------------------------

    /**
     * Local sign-out: the store is cleared first, then the provider is told
     * with `local` scope on a best-effort basis; a provider failure never
     * leaves the device signed in (CONTRACT.md, "Sign-out semantics"). It
     * waits for any refresh in flight and blocks the next one, so a refresh
     * that started a moment earlier cannot re-save a session afterwards.
     */
    override suspend fun signOut(): Unit = refresher.withoutRefresh {
        store.clear()
        try {
            auth.signOut(SignOutScope.LOCAL)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort: the device is signed out regardless.
        } finally {
            auth.clearSession()
        }
    }

    /** Revokes every other device's session (`others` scope); this device stays signed in. */
    override suspend fun signOutOtherDevices() {
        refresher.sessionForRequest() ?: throw ReaderAuthException.SignedOut(code = null)
        providerCall { auth.signOut(SignOutScope.OTHERS) }
    }

    /** Releases the provider SDK's resources; the stored session is untouched. */
    public suspend fun close(): Unit = supabase.close()

    // ---- Internals ---------------------------------------------------------------------------

    // Sign-in saves its session under the refresh mutex (#153): a refresh of
    // the previous session that is still in flight finishes first and cannot
    // overwrite the new one afterwards.

    private suspend fun verify(type: OtpType.Email, email: String, code: String): ReaderSessionState.SignedIn {
        val result = refresher.withoutRefresh { provider { auth.verifyEmailOtp(type, email, code) } }
        if (result !is OtpVerifyResult.Authenticated) {
            throw ReaderAuthException.ProviderRejected(
                200,
                "no_session",
                "the provider verified the code without issuing a session",
            )
        }
        return signedInOrThrow()
    }

    private fun signedInOrThrow(): ReaderSessionState.SignedIn =
        currentState() as? ReaderSessionState.SignedIn ?: throw ReaderAuthException.SignedOut(code = null)

    /**
     * Runs one provider operation and maps its failure into the contract's
     * closed set. An operation that issued a session the device could not
     * save (#154) keeps it in memory and throws
     * [ReaderAuthException.StorageUnavailable].
     *
     * Only an operation that saves a session goes through here, and only
     * under the refresh mutex: the save-failure slot is shared, so a caller
     * outside the mutex could reset or read it in the moment between a
     * refresh's failed save and the refresh reading it back, and take that
     * failure from it (#183). Operations that save nothing use [providerCall].
     */
    private suspend inline fun <T> provider(block: () -> T): T {
        sessions.takeSaveFailure()
        val result = providerCall(block)
        sessions.throwIfSaveFailed()
        return result
    }

    /** [provider] without the save-failure slot, for operations that save no session. */
    private suspend inline fun <T> providerCall(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ReaderAuthException) {
        throw e
    } catch (e: AuthWeakPasswordException) {
        throw ReaderAuthException.ProviderRejected(
            e.statusCode,
            e.errorCode?.value ?: e.error,
            e.reasons.joinToString().ifBlank {
                e.errorDescription
            },
        )
    } catch (e: AuthRestException) {
        if (e.isTransient()) {
            throw ReaderAuthException.TryLater(
                e.statusCode,
                e.errorCode?.value ?: e.error,
                ReaderAuthPolicy.retryAfter(e.response.headers[HttpHeaders.RetryAfter]),
            )
        }
        throw ReaderAuthException.ProviderRejected(e.statusCode, e.errorCode?.value ?: e.error, e.errorDescription)
    } catch (e: RestException) {
        if (e.isTransient()) {
            throw ReaderAuthException.TryLater(
                e.statusCode,
                e.error,
                ReaderAuthPolicy.retryAfter(e.response.headers[HttpHeaders.RetryAfter]),
            )
        }
        throw ReaderAuthException.ProviderRejected(e.statusCode, e.error, e.description ?: e.error)
    } catch (e: IOException) {
        throw ReaderAuthException.NetworkUnavailable(e)
    } catch (e: Exception) {
        // A provider answer the SDK could not read, or an SDK failure the contract does not name (#191).
        throw ReaderAuthException.UnexpectedResponse(e)
    }

    private fun SessionStatus.toState(): ReaderSessionState = when (this) {
        is SessionStatus.Initializing -> ReaderSessionState.Initializing

        is SessionStatus.NotAuthenticated, is SessionStatus.RefreshFailure -> ReaderSessionState.SignedOut

        is SessionStatus.Authenticated -> ReaderSessionState.SignedIn(
            userId = session.user?.id ?: session.subject() ?: "",
            email = session.user?.email,
            expiresAt = session.expiresAt,
        )
    }

    /** Which OTP type verifies an emailed code. */
    public enum class EmailCodePurpose(internal val otpType: OtpType.Email) {
        /** `email`: a sign-in code, and also a sign-up code — the provider accepts either under this type. */
        SIGN_IN(OtpType.Email.EMAIL),

        /** `signup`: strictly a sign-up confirmation code. */
        SIGN_UP(OtpType.Email.SIGNUP),
    }

    public companion object {

        /** [ReaderAuthException.SignInUnavailable.reason] when the server has turned the method off. */
        public const val METHOD_DISABLED: String = "method_disabled"

        /**
         * The production client: Keystore-encrypted store under the no-backup
         * files directory, OkHttp engine, device clock. Throws
         * [ReaderAuthException.NotConfigured] instead of calling anything when a
         * service value is blank.
         */
        public fun create(context: Context, config: ReaderAuthConfig): ReaderAuthClient {
            if (!config.isConfigured) throw ReaderAuthException.NotConfigured()
            val store =
                FileSessionStore(FileSessionStore.directoryIn(context.applicationContext), KeystoreSessionCipher())
            return build(config, store, OkHttp.create())
        }

        /**
         * The wiring shared by production and tests: [create] passes the
         * Keystore store and OkHttp, and the test fixtures
         * (`src/testFixtures`, `ReaderAuthHarness`) pass an in-memory store and
         * a mock engine to this same function, so a test drives exactly the
         * production client (#199). The three SDK defaults the contract
         * overrides are set here and pinned by `SdkDefaultsTest`:
         *
         * - the session manager is [StoreSessionManager] over the module's
         *   [SessionStore], never the SDK's plaintext `SharedPreferences`
         *   default (the PKCE code-verifier cache is in memory for the same
         *   reason; the code flow never needs it across a restart);
         * - the SDK's background auto-refresh loop and its lifecycle callbacks
         *   are off, so [SessionRefresher] is the only refresh path;
         * - the flow type is PKCE, matching reader-web for the redirect flows a
         *   later host may add.
         */
        internal fun build(
            config: ReaderAuthConfig,
            store: SessionStore,
            engine: HttpClientEngine,
            clock: ReaderClock = ReaderClock.System,
            waiter: RetryWaiter = RetryWaiter.Delay,
            requestIds: () -> String = { UUID.randomUUID().toString().lowercase() },
        ): ReaderAuthClient {
            if (!config.isConfigured) throw ReaderAuthException.NotConfigured()
            val sessions = StoreSessionManager(store)
            val supabase = createSupabaseClient(config.supabaseUrl, config.publishableKey) {
                httpEngine = engine
                requestTimeout = ReaderAuthPolicy.REQUEST_TIMEOUT
                install(Auth) {
                    sessionManager = sessions
                    codeVerifierCache = MemoryCodeVerifierCache()
                    alwaysAutoRefresh = false
                    enableLifecycleCallbacks = false
                    autoLoadFromStorage = true
                    autoSaveToStorage = true
                    flowType = FlowType.PKCE
                }
            }
            val refresher = SessionRefresher(supabase.auth, clock, waiter, sessions)
            val api = ReaderApiClient(config, ReaderApiClient.httpClient(engine), refresher, waiter, requestIds)
            return ReaderAuthClient(config, supabase, store, sessions, refresher, api, clock)
        }

        private val json = Json { ignoreUnknownKeys = true }

        /** The `sub` claim of the access token, read (not verified) as a fallback when the session carries no user. */
        private fun UserSession.subject(): String? = try {
            val payload = accessToken.split('.').getOrNull(1) ?: return null
            val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            json.parseToJsonElement(decoded.decodeToString()).jsonObject["sub"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }
}
