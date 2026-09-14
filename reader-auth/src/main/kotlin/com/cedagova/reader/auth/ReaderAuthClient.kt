package com.cedagova.reader.auth

import android.content.Context
import android.util.Base64
import com.cedagova.reader.auth.api.PreAuthDocument
import com.cedagova.reader.auth.api.ReaderApiClient
import com.cedagova.reader.auth.api.ReaderApiResponse
import com.cedagova.reader.auth.api.ReaderProfileUpdate
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
class ReaderAuthClient internal constructor(
    val config: ReaderAuthConfig,
    internal val supabase: SupabaseClient,
    private val store: SessionStore,
    private val refresher: SessionRefresher,
    /** The reader-api client, for any further route a host needs. */
    val api: ReaderApiClient,
) {
    private val auth: Auth get() = supabase.auth
    private val bootstrapLock = Mutex()

    @Volatile
    private var verifiedPreAuth: PreAuthDocument? = null

    /** The stored session as a host renders it; emits on every change. */
    val sessionState: Flow<ReaderSessionState> = auth.sessionStatus.map { it.toState() }

    /** The current [sessionState] value without collecting. */
    fun currentState(): ReaderSessionState = auth.sessionStatus.value.toState()

    /** Suspends until the stored session has been read (or found absent). */
    suspend fun awaitReady() = auth.awaitInitialization()

    // ---- Bootstrap ---------------------------------------------------------------------------

    /**
     * `GET /v1/reader/pre-auth` and the fail-closed wiring check: sign-in is
     * refused with [ReaderAuthException.ConfigurationMismatch] unless the
     * document names this client's application id, publishable key, and
     * authority (CONTRACT.md, "Bootstrap and first calls"). The verified
     * document is kept, so later sign-in calls do not fetch it again.
     */
    suspend fun bootstrap(): PreAuthDocument = bootstrapLock.withLock {
        verifiedPreAuth?.let { return it }
        val document = api.preAuth()
        document.mismatch(config)?.let { throw ReaderAuthException.ConfigurationMismatch(it) }
        document.also { verifiedPreAuth = it }
    }

    private suspend fun ensureBootstrapped() {
        bootstrap()
    }

    // ---- Sign-in methods, in the contract's preference order ---------------------------------

    /**
     * Email code, step one: ask the provider to email a six-digit code.
     * [createUser] true is sign-up (a new address gets an account); false
     * refuses an unknown address.
     */
    suspend fun requestEmailCode(email: String, createUser: Boolean) {
        ensureBootstrapped()
        provider {
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
    suspend fun verifyEmailCode(email: String, code: String, purpose: EmailCodePurpose = EmailCodePurpose.SIGN_IN): ReaderSessionState.SignedIn {
        ensureBootstrapped()
        return verify(purpose.otpType, email, code)
    }

    /** Password sign-in. */
    suspend fun signInWithPassword(email: String, password: String): ReaderSessionState.SignedIn {
        ensureBootstrapped()
        provider {
            auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
        }
        return signedInOrThrow()
    }

    /** Code-based recovery, step one: ask the provider to email a recovery code. */
    suspend fun requestRecoveryCode(email: String) {
        ensureBootstrapped()
        provider { auth.resetPasswordForEmail(email) }
    }

    /** Code-based recovery, step two: the recovery code yields a session; then call [setPassword]. */
    suspend fun verifyRecoveryCode(email: String, code: String): ReaderSessionState.SignedIn {
        ensureBootstrapped()
        return verify(OtpType.Email.RECOVERY, email, code)
    }

    /** Sets the signed-in user's password (recovery step three, or a plain change). */
    suspend fun setPassword(newPassword: String) {
        refresher.sessionForRequest() ?: throw ReaderAuthException.SignedOut(code = null)
        provider { auth.updateUser { password = newPassword } }
    }

    // ---- Protected calls ---------------------------------------------------------------------

    /** The first authenticated call after sign-in. */
    suspend fun capabilities(): JsonObject = api.capabilities()

    /** [capabilities], with the request id the successful call carried beside the document (#100). */
    suspend fun capabilitiesResponse(): ReaderApiResponse = api.capabilitiesResponse()

    /** The profile upsert that precedes any profile read. */
    suspend fun upsertProfile(update: ReaderProfileUpdate): JsonObject = api.upsertProfile(update)

    /**
     * What a host calls when it returns to the foreground: refreshes the
     * session if it is inside the margin, and reports the resulting state.
     * The module runs no timer of its own.
     */
    suspend fun onForeground(): ReaderSessionState {
        try {
            refresher.sessionForRequest()
        } catch (e: ReaderAuthException.SignedOut) {
            // The state already says so.
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
    suspend fun signOut() = refresher.withoutRefresh {
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
    suspend fun signOutOtherDevices() {
        refresher.sessionForRequest() ?: throw ReaderAuthException.SignedOut(code = null)
        provider { auth.signOut(SignOutScope.OTHERS) }
    }

    /** Releases the provider SDK's resources; the stored session is untouched. */
    suspend fun close() = supabase.close()

    // ---- Internals ---------------------------------------------------------------------------

    private suspend fun verify(type: OtpType.Email, email: String, code: String): ReaderSessionState.SignedIn {
        val result = provider { auth.verifyEmailOtp(type, email, code) }
        if (result !is OtpVerifyResult.Authenticated) {
            throw ReaderAuthException.ProviderRejected(200, "no_session", "the provider verified the code without issuing a session")
        }
        return signedInOrThrow()
    }

    private fun signedInOrThrow(): ReaderSessionState.SignedIn =
        currentState() as? ReaderSessionState.SignedIn ?: throw ReaderAuthException.SignedOut(code = null)

    /** Runs one provider operation and maps its failure into the contract's closed set. */
    private suspend inline fun <T> provider(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ReaderAuthException) {
        throw e
    } catch (e: AuthWeakPasswordException) {
        throw ReaderAuthException.ProviderRejected(e.statusCode, e.errorCode?.value ?: e.error, e.reasons.joinToString().ifBlank { e.errorDescription })
    } catch (e: AuthRestException) {
        if (e.isTransient()) {
            throw ReaderAuthException.TryLater(e.statusCode, e.errorCode?.value ?: e.error, ReaderAuthPolicy.retryAfter(e.response.headers[HttpHeaders.RetryAfter]))
        }
        throw ReaderAuthException.ProviderRejected(e.statusCode, e.errorCode?.value ?: e.error, e.errorDescription)
    } catch (e: RestException) {
        if (e.isTransient()) {
            throw ReaderAuthException.TryLater(e.statusCode, e.error, ReaderAuthPolicy.retryAfter(e.response.headers[HttpHeaders.RetryAfter]))
        }
        throw ReaderAuthException.ProviderRejected(e.statusCode, e.error, e.description ?: e.error)
    } catch (e: IOException) {
        throw ReaderAuthException.NetworkUnavailable(e)
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
    enum class EmailCodePurpose(internal val otpType: OtpType.Email) {
        /** `email`: a sign-in code, and also a sign-up code — the provider accepts either under this type. */
        SIGN_IN(OtpType.Email.EMAIL),

        /** `signup`: strictly a sign-up confirmation code. */
        SIGN_UP(OtpType.Email.SIGNUP),
    }

    companion object {

        /**
         * The production client: Keystore-encrypted store under the no-backup
         * files directory, OkHttp engine, device clock. Throws
         * [ReaderAuthException.NotConfigured] instead of calling anything when a
         * service value is blank.
         */
        fun create(context: Context, config: ReaderAuthConfig): ReaderAuthClient {
            if (!config.isConfigured) throw ReaderAuthException.NotConfigured()
            val store = FileSessionStore(FileSessionStore.directoryIn(context.applicationContext), KeystoreSessionCipher())
            return build(config, store, OkHttp.create())
        }

        /**
         * The wiring shared by production and tests. The three SDK defaults the
         * contract overrides are set here and pinned by `SdkDefaultsTest`:
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
            val supabase = createSupabaseClient(config.supabaseUrl, config.publishableKey) {
                httpEngine = engine
                requestTimeout = ReaderAuthPolicy.REQUEST_TIMEOUT
                install(Auth) {
                    sessionManager = StoreSessionManager(store)
                    codeVerifierCache = MemoryCodeVerifierCache()
                    alwaysAutoRefresh = false
                    enableLifecycleCallbacks = false
                    autoLoadFromStorage = true
                    autoSaveToStorage = true
                    flowType = FlowType.PKCE
                }
            }
            val refresher = SessionRefresher(supabase.auth, clock, waiter)
            val api = ReaderApiClient(config, ReaderApiClient.httpClient(engine), supabase.auth, refresher, waiter, requestIds)
            return ReaderAuthClient(config, supabase, store, refresher, api)
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
