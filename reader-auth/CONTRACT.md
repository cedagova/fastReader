# The Android client contract for Reader authentication

This document is the client-side half of one identity model: a Supabase user,
presented to reader-api as a bearer JWT, on an Android device. It is written
for **any Android client of reader-api** — the proving-ground host in this
repository today, the real Reader client later — and the `:reader-auth`
module beside it implements exactly these rules. Where a rule names a
constant, `ReaderAuthPolicy` carries the same value and
`ReaderAuthPolicyTest` reads this file, so the two cannot drift apart
silently.

Audit finding A83-F008 (#93) is the origin; the sources are listed at the end.

## Sign-in methods, in preference order

1. **Email code** (`signInWithOtp` then `verifyOtp`). The client asks the
   provider to email a six-digit code (`POST /auth/v1/otp` with `email` and
   `create_user`), then exchanges the typed code for a session
   (`POST /auth/v1/verify` with `type`, `email`, `token`). `create_user: true`
   is sign-up — a new address gets an account — and `false` refuses an
   unknown address. The verify type `email` accepts both a sign-in and a
   sign-up code; a client that knows it is completing a sign-up may send
   `signup`. No redirect, no App Link, no custom scheme, no PKCE exchange:
   the session comes back in the verify response. This is the first choice
   because email *links* are fragile on Android (Android 12+ opens an
   unverified `https` link in the browser, mail scanners consume single-use
   links, and a PKCE link must be finished on the device that started it),
   and a code has none of those failure modes.
2. **Password** (`POST /auth/v1/token?grant_type=password`). **Recovery is
   code-based**: `POST /auth/v1/recover` emails a code, `POST /auth/v1/verify`
   with `type: recovery` yields a session, and `PUT /auth/v1/user` sets the new
   password under it. Link-based recovery is not offered.
3. **Native Google** — reserved for audit finding A83-F005 (Credential
   Manager, `signInWithIdToken` with a raw nonce). Not implemented here.

A wrong or expired code, wrong credentials, and a weak password surface as a
**provider rejection** with the provider's error code. **Code verification is
never retried automatically.** A provider `429` on sign-in, verify, or
recovery — many phones behind one carrier address share the provider's per-IP
buckets of 30 sign-ins, 30 verifications and 150 refreshes per window — is
surfaced as **try later**, never as a credential error.

## Bootstrap and first calls

Before any sign-in the client fetches `GET /v1/reader/pre-auth` with
`clientVersion=1.0.0` and the header `X-Reader-Client: reader-android`.
reader-api projects its contract per client and **fails closed** on a missing
or unknown selector (the document then has `compatibility.status:
client_unknown` and no `configuration`). The client mirrors that: sign-in is
**refused with a configuration error before any provider call** unless the
document reports `compatibility.status: compatible` and its
`configuration.client.applicationId` is `reader-android`, its
`configuration.authentication.publicClientId` equals the publishable key the
client was configured with, and its `authentication.authorityOrigin` (when
present) is the configured Supabase URL. The check is a client-side wiring
guard, not a server requirement; a host may relax it, but this module does
not. The verified document is kept for the life of the process, so later
sign-ins do not fetch it again.

The **first authenticated call is `GET /v1/reader/capabilities`**
(`clientVersion=1.0.0`), which answers a `reader.capabilities.v1` document
scoped to the actor. **`PUT /v1/reader/profile` precedes any profile `GET`**:
the upsert creates the actor's row.

## Session storage and backup exclusion

- The session — access token, refresh token, expiry, and the provider's user
  record — is stored as **one file, encrypted with an AES-256-GCM key that is
  generated in the Android Keystore and never leaves it**. The key needs no
  user authentication (a device without a lock screen still works) and no
  StrongBox (a device without a secure element still works). The blob is
  `[version][12-byte IV][ciphertext+tag]` around a JSON envelope
  `{"format_version": 1, "session": …}`; the format version is bumped when
  the envelope changes.
- The file is `<noBackupFilesDir>/reader-auth/session.bin`: the platform's
  own backup skips the no-backup directory, and the host's extraction rules
  (below) exclude every domain a second time.
- **No `SharedPreferences`, no DataStore, no Jetpack Security crypto**
  (deprecated in favour of the platform Keystore) anywhere in the module. The
  provider SDK's default session manager writes plaintext preferences; it is
  replaced (see the SDK section).
- **An absent, unreadable, corrupt, or undecryptable file means "signed
  out"**, never a crash: a restore onto another device brings the blob
  without its key, and the client simply starts signed out and removes the
  unusable file.
- **A failed save keeps the session for this process.** When the provider
  issued a session (sign-in or refresh) but the Keystore or the file refused
  it, the new session stays current in memory and the operation fails as
  **storage unavailable**. After a refresh the stored copy, which holds the
  consumed refresh token, is also removed, so the next start is signed out
  rather than replaying it. Repeating it
  works without another provider call. A refresh grant that succeeded is
  never sent again because its save failed.
- Rollback: the Keystore key is app-scoped and disappears with the app;
  `pm uninstall` of the host or its sign-out clears everything.

## Refresh policy

| Constant | Value |
| --- | --- |
| Refresh margin | 300 s |
| Request timeout | 10 s |
| Default `Retry-After` | 10 s |
| Retry limit | 1 |
| Max inline `Retry-After` | 30 s |

- **Proactive with a margin.** Before any protected call, and when the app
  returns to the foreground, the session is refreshed when
  `exp - now <= 300 s`. reader-api's verifier tolerates 120 s of clock skew
  (`SUPABASE_JWT_LEEWAY_SECONDS`, default 120), and the margin exceeds that
  plus any plausible device skew; with a 3600 s access token the refresh lands
  at 55 minutes at the latest.
- **Single-flight everywhere.** Every refresh goes through one mutex; a
  caller that arrives while a refresh is in flight waits for it and then finds
  the session already fresh. N concurrent callers produce exactly one
  `POST /auth/v1/token?grant_type=refresh_token`. This is not a nicety: the
  provider rotates refresh tokens with a 10 s reuse window, and a second
  refresh with the old token outside that window revokes the whole family.
- **Reactive, once.** When reader-api answers 401 `auth.expired_token`, the
  client refreshes (single-flight, and only if no other caller already
  replaced that token) and retries the call once.
- **Outcomes.** Success replaces the stored session. The provider codes
  `refresh_token_already_used`, `refresh_token_not_found`, `session_not_found`,
  `session_expired`, and `bad_jwt` mean the session is gone: it is cleared and
  the device is signed out. A `429`, any `5xx`, or a network failure **keeps
  the session**, is retried **once** after `Retry-After` (integer seconds; 10 s
  when absent), and is then surfaced as try later (or network unavailable).
  A `Retry-After` above 30 s is never waited out inside a call: the failure is
  surfaced at once as try later carrying the server's value, with no second
  request.
  Any other provider error keeps the session and is surfaced. Nothing retries
  in a loop. Only the grant is retried: a grant that succeeded and then could
  not be saved is surfaced as storage unavailable with the new session kept in
  memory (see "Session storage"), never retried with the consumed token.
- **No background timer.** Refresh happens only on the code paths above;
  nothing runs while the app is not in the foreground.

## reader-api call policy (401 / 403 / 429 / 5xx)

Every request carries `Authorization: Bearer <access_token>` (protected
routes), `X-Reader-Client: reader-android`, `Accept: application/json`, a
fresh lowercase-UUID `X-Request-ID` (reader-api echoes it), and the 10 s
timeout; bootstrap and capabilities add `clientVersion=1.0.0`. reader-api's
errors are `{code, message, category, retryable, request_id}` with
`WWW-Authenticate: Bearer` on 401.

| Answer | Client behaviour |
| --- | --- |
| 401 `auth.expired_token` | One single-flight refresh and one retry; a second expiry is surfaced with its code and `request_id`, session intact. |
| 401 any other `auth.*` (`auth.invalid_token`, `auth.anonymous_identity_rejected`, `auth.malformed_token`, `auth.missing_sub_claim`, `auth.missing_token`, `auth.unauthorized`) on a protected call | The session is cleared; the device is signed out. The clear takes the refresh mutex and applies only while the rejected token is still the stored one: a rejection of a token that a refresh or sign-in already replaced is stale, so the call is retried once with the replacement and the newer session is kept. (A 401 on a public route such as pre-auth carried no bearer and says nothing about the session: surfaced with its code, session intact.) |
| 403 | Surfaced as forbidden; session intact. |
| 429 | One retry after `Retry-After` (default 10 s), then try later; session intact. |
| 502 `auth.jwks_dependency_failed` | One retry after `Retry-After` (default 10 s), then try later; session intact. |
| Any other 5xx whose body says `retryable: true` (e.g. 503 `reader_sync.unavailable`, `auth.ingress_identity_unavailable`, 502 `db.unavailable`) | One retry after `Retry-After` (default 10 s), then try later; session intact. |
| Any other non-2xx, including a 5xx that says `retryable: false` or omits it (e.g. 503 `publication_import.admissions_disabled`) | Surfaced with the server's `code` and `request_id`; session intact. No retry. |
| Network failure or timeout | Surfaced as network unavailable; nothing is cleared. |

Every retry above waits inside the call, so the wait is capped: a
`Retry-After` above 30 s (reader-api's quota answers can run until the quota
resets) is not waited out. The call ends at once as try later carrying the
server's `Retry-After`, with no second request, and the host decides when to
come back. Only `retryable` is read from the error body to decide a retry;
`category` is parsed but drives nothing.

A device whose clock is skewed beyond the server's leeway is answered 401
`auth.invalid_token` and is signed out by the rule above; the 300 s margin
makes that rare, not impossible.

This differs from reader-web, which treats 401 and 403 as local-only state
and never forces a sign-out. A mobile client with a stored refresh token has
already done its one permitted refresh by the time a non-expiry 401 arrives;
keeping a session the server rejects would only repeat the rejection.

## Sign-out semantics

- **Local sign-out clears the store first**, then tells the provider with
  `POST /auth/v1/logout?scope=local` on a best-effort basis. **A provider
  failure never leaves the device signed in.** Sign-out takes the same
  mutex as refresh: it waits for a refresh in flight and holds off the next
  one, so a refresh that began a moment earlier cannot re-save a session
  after the store was cleared. This is a deliberate divergence
  from reader-web, which restores the session when the provider call fails:
  on a device, local revocation is the user's intent, and the stored refresh
  token is the thing to destroy.
- **Sign-in saves its session under the same mutex**, so a refresh of the
  previous session that is still in flight cannot overwrite the new one.
  Setting a password does too: the provider call saves the current session
  again, and a refresh landing in between would have that save restore the
  refresh token the refresh just spent.
- **Sign out other devices** uses `scope=others`; the local session is kept.
- A provider `global` sign-out is not offered.

## Provider SDK defaults this module overrides

The identity provider's Kotlin SDK (`supabase-kt` 3.8.0, `auth-kt`) performs
the provider calls. Its sources at that release were re-read on 2026-09-13
(`AuthConfig.kt`, `AuthImpl.kt`, `SessionManager.kt`, `CodeVerifierCache.kt`,
`setupPlatform.kt`, `OtpType.kt`, `SignOutScope.kt`, `AuthErrorCode.kt`); the
audit's 2026-09-11 reading holds, with one addition noted below. Three of its
defaults conflict with this contract and are overridden, pinned by
`SdkDefaultsTest`:

| SDK default (3.8.0) | This module |
| --- | --- |
| `sessionManager = null` → `SettingsSessionManager`, the whole session as JSON in plaintext `SharedPreferences` | `StoreSessionManager` over the encrypted file store above |
| `alwaysAutoRefresh = true` (refresh at 80 % of lifetime, retry after 10 s on 5xx or network error, **clear the session on any other HTTP error** — a 429 would sign the user out) plus `enableLifecycleCallbacks = true` (restart that loop on every foreground) | Both `false`; `SessionRefresher` is the only refresh path |
| `flowType = IMPLICIT` | `PKCE`, matching reader-web for the redirect flows a later host may add |

Addition found in the re-read: with PKCE on, the SDK writes a code verifier
to `codeVerifierCache`, whose default (`SettingsCodeVerifierCache`) is also
`SharedPreferences`. The module installs `MemoryCodeVerifierCache`; the code
flow never exchanges a verifier, so it need not survive a restart.
`autoLoadFromStorage` and `autoSaveToStorage` stay `true` so the SDK reads
and writes through the module's store. The SDK's `checkSessionOnRequest`
option is unused in its own sources at 3.8.0 and needs no override.

Other SDK behaviours relied on, as read at 3.8.0: `refreshSession(token)` is a
bare `POST token?grant_type=refresh_token` that does not import the result;
`importSession(session, autoRefresh = false)` saves through the session
manager; `signOut(scope)` posts `logout?scope=…` and clears local state for
every scope but `others`; `verifyEmailOtp(type, email, token)` posts
`verify`; provider errors arrive as `AuthRestException` with a typed
`errorCode`, network failures as `HttpRequestException` (an `IOException`);
the SDK sends `apikey: <publishable key>` on every provider request.

## Host requirements

The module owns the store, refresh, provider access, and the reader-api
policy. Each host owns:

1. **Configuration.** The Supabase URL, the publishable key, and the
   reader-api base URL are public browser-runtime values, but they are never
   committed: the host reads them from its untracked `local.properties` (or
   the environment) into `BuildConfig` and hands the module one
   `ReaderAuthConfig` with `clientId = reader-android` and
   `clientVersion = 1.0.0`. No service key exists anywhere. With any value
   absent the host must still build, lint, and pass its unit tests, and must
   refuse at runtime with a clear "not configured" message instead of calling
   anything (`ReaderAuthClient.create` throws `NotConfigured`).
2. **Backup exclusion**, as `README.md` states: `allowBackup="false"`, and
   `dataExtractionRules` plus `fullBackupContent` excluding all nine domains
   in both `cloud-backup` and `device-transfer`.
3. **Cleartext policy**: no `usesCleartextTraffic`; a debug-only network
   security configuration may allow the emulator loopback `10.0.2.2` and
   nothing else.
4. **Its own application id.**
5. **The foreground hook**: call `ReaderAuthClient.onForeground()` when the
   process returns to the foreground; the module runs no timer of its own.

## Sources

- Audit dossier A83 (finding A83-F008), reviewed head
  `1b99102603eb05975e655b26be02a5a79abd646e`:
  https://github.com/cedagova/fastReader/pull/83 — report at
  https://github.com/cedagova/fastReader/blob/1b99102603eb05975e655b26be02a5a79abd646e/docs/audits/A83-android-client-auth/report.md
- Root issue and preserved audit record: https://github.com/cedagova/fastReader/issues/93;
  plan: https://github.com/cedagova/fastReader/pull/97
- reader-api contract (Chunipers/reader-api, private): the Android projection
  delivered by reader-api#489 (pre-auth and capabilities per `X-Reader-Client`,
  `reader-android` 1.0.0), the error catalogue `docs/error-codes.md`
  (`auth.expired_token`, `auth.invalid_token`, `auth.jwks_dependency_failed`,
  …), the leeway quick fix `SUPABASE_JWT_LEEWAY_SECONDS` and
  `auth.anonymous_identity_rejected` (#91, recorded on #93 on 2026-09-12).
  Stage answers were checked live on 2026-09-13.
- reader-web reference behaviour (Chunipers/reader-web `packages/auth`,
  `packages/clients`), as cited in the dossier.
- Supabase sessions and refresh-token rotation:
  https://supabase.com/docs/guides/auth/sessions; error codes:
  https://supabase.com/docs/guides/auth/debugging/error-codes; email
  templates and `{{ .Token }}`:
  https://supabase.com/docs/guides/auth/auth-email-templates; PKCE:
  https://supabase.com/docs/guides/auth/sessions/pkce-flow
- Supabase Kotlin SDK 3.8.0 sources (`io.github.jan-tennert.supabase:auth-kt`
  and `supabase-kt`, Maven Central, 2026-08-26).
- Android: Jetpack Security deprecation notes
  https://developer.android.com/jetpack/androidx/releases/security; Android
  Keystore https://developer.android.com/privacy-and-security/keystore; Auto
  Backup and data extraction rules
  https://developer.android.com/identity/data/autobackup; Android 12 link
  behaviour https://developer.android.com/about/versions/12/behavior-changes-all;
  legacy Google Sign-In https://developer.android.com/identity/legacy/gsi
- OWASP MASTG: MASTG-KNOW-0036 (encrypted storage and backup)
  https://github.com/OWASP/mastg/blob/9444102bee7c65d7037904c95ebd06110a59e336/knowledge/android/MASVS-STORAGE/MASTG-KNOW-0036.md
- RFC 9700 §2.2.2 (rotation for public clients), RFC 6750 §3.1
  (`invalid_token` → obtain a new token and retry), RFC 8252 §7.
