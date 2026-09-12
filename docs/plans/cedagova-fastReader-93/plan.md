# Implementation Plan: Android client auth contract and its reader-auth implementation

- Planning issue: https://github.com/cedagova/fastReader/issues/93
- Planning PR: https://github.com/cedagova/fastReader/pull/97
- Status: Ready for implementation
- Root classification: LEAF
- Delivery topology: DIRECT
- Planner: Planning lead (Claude)
- Started: 2026-09-12

## Pinned baselines

| Repository | Baseline |
| --- | --- |
| `cedagova/fastReader` | `ed90c4a6479e849c7f0001951870c139e5072df5` |

`origin/main` on 2026-09-12 (release 1.5.0, versionCode 7). It hosts the root
issue, the audit record (dossier PR #83, reviewed head
`1b99102603eb05975e655b26be02a5a79abd646e`, finding A83-F008, native umbrella
#94), the prerequisite root #92 with its reviewed plan (PR #96, head
`ca2e4531e50cdf085faff6ee577f2f5c8d1b6740`), and receives the change. The
audit's Android-practice sources are cited from the report at this baseline;
the plan re-read them for the decisions below and re-checking them at
implementation start is part of the leaf (see Validation).

Backend facts used below are not pinned to a repository here: the audit's
targets are Chunipers repositories, and the reader-api effort that delivered
the Android projection to stage (Chunipers/reader-api#489, closed 2026-09-12)
is private to this repository's identity. Those facts are recorded as owner-
supplied inputs in Current-state evidence and stay verifiable against stage.

## Preserved objective and boundaries

Root #93 is audit outcome A83-F008, accepted by the owner on 2026-09-11 and
carrying `Audit handoff: PLANNING_REQUIRED` under the native audit umbrella
#94. Its desired outcome is preserved verbatim: a general Android auth module
contract exists in this repository, written for any Android client of
reader-api, covering sign-in methods in preference order (email code first,
password, native Google when F005 lands), session storage and backup
exclusion, refresh policy, the 401/429/502 policy, bootstrap and first calls,
and sign-out semantics; and the proving ground implements exactly that
contract. Criterion preserved: the native client implements the same identity
model as reader-web (a Supabase user presented to reader-api as a bearer JWT)
with the platform's current storage and sign-in guidance, general enough to
lift into the real Android reader unchanged. Owner direction (2026-09-11 and
the planning launch): general-purpose for any Android client of Reader, known
best practice, liftable into the real Reader client unchanged.

Boundaries preserved from the root. In: the client-side contract document and
its implementation in the build F007 designates (#92: library `:reader-auth`,
host `:reader-auth-host`); unit tests for the token store and refresh policy
that run without a device; an emulator flow against a stage Supabase project.
Out: UI beyond a minimal sign-in screen, any reader feature, and all backend
changes (F001–F006). Known dependencies preserved: F007 (#92, where the code
lives) gates this outcome; F003 (server contract) and F006 (leeway) are
consumed as facts; redirect and Google flows additionally need F001, F002,
F005 and are out of this leaf.

## Classification

- **ROOT #93 — `LEAF`.** One coherent, independently verifiable outcome in
  one repository: the contract document plus its implementation inside the
  two modules #92 creates, delivered as one PR to `main` after #92 merges.
  The audit's cohesion rationale is preserved: storage, refresh, and error
  policy are the client-side halves of one identity model and drift apart
  when split. Every candidate split fails the contract's test of independent
  verification: the document alone is prose with no proof; the session store
  alone cannot prove "survives process death and reboot" without a real
  session, which needs sign-in; sign-in without the store cannot prove
  backup exclusion of a token. No children are published; the fast-leaf
  path applies with one external prerequisite.

Not `ALREADY_SATISFIED`: at the baseline no module, contract document,
Supabase or HTTP artifact, or token store exists in the tree
(`settings.gradle.kts` includes `:app` only; the catalog has no Ktor,
Supabase, or OkHttp entry). Not `NEEDS_DECISION`: the one material stack
choice the root left to planning (Supabase Kotlin SDK versus direct GoTrue
REST) is recorded below as an Owner decision brief with a recommendation the
lead proceeds on, per the owner's launch instruction, and the owner or
reviewer may overturn it with one reply. Not `DECOMPOSE`: see the split
analysis above. Not `RESEARCH_REQUIRED`: the two evidence gaps (how the
emailed code is obtained during the emulator run; whether the stage email
template carries the code) have a defined fallback each and do not change
the execution path.

## Current-state evidence

At `ed90c4a6479e849c7f0001951870c139e5072df5`:

- No network-capable module exists yet. `gradle/libs.versions.toml` has
  `kotlinx-serialization-json` 1.11.0 and coroutines 1.11.0 but no Ktor,
  OkHttp, or Supabase artifact; AGP 9.4.0, Kotlin 2.4.20, minSdk 26,
  compileSdk/targetSdk 37. `.github/workflows/checks.yml` runs
  `testDebugUnitTest`, `verifyRoborazziDebug`, and `lint` from the root, so
  the new modules' tests join the hosted gate without a workflow change.
- Backup exclusion pattern and its runtime proof: `app/src/main/res/xml/
  data_extraction_rules.xml` and `backup_rules.xml` exclude all nine domains
  in both `cloud-backup` and `device-transfer`; `docs/evidence/46/
  backup-run-phone-mid-api36.txt` is the reusable procedure (`bmgr
  backupnow` on the local and D2D transports, then inspect what the
  transport stored). #92's host carries the same exclusion and a static
  unit test for it.
- #92 (prerequisite, open, implementation in progress on branch
  `leaf/92-reader-auth-module`, reviewed plan PR #96): defines library
  `:reader-auth` (namespace `com.cedagova.reader.auth`, declares `INTERNET`,
  module README listing host requirements), host `:reader-auth-host`
  (applicationId `com.cedagova.reader.auth.host`, full backup exclusion,
  debug-only cleartext to `10.0.2.2`, one HTTPS probe), no HTTP stack or
  Supabase artifact, no `:app` dependency. This leaf replaces the probe with
  the real flow and adds the stack.
- Audit sources preserved in the root (retrieved 2026-09-11): Jetpack
  Security crypto is deprecated in favour of direct Android Keystore use;
  OWASP MASTG requires the encrypted store to be excluded from backup; the
  official Kotlin SDK `supabase-kt` 3.8.0 persists the whole session through
  `SettingsSessionManager` (plaintext `SharedPreferences` on Android) unless
  a custom `SessionManager` is installed, defaults to the implicit flow,
  auto-refreshes at 80% of token lifetime, retries 5xx or network errors
  after 10 s, clears the session on any other HTTP error, and pauses refresh
  in the background; its own tests use `ktor-client-mock` and
  `MemorySessionManager`; Supabase refresh tokens rotate with a 10 s reuse
  interval and reuse outside it revokes the family; the OTP-code path needs
  no redirect; email links are fragile on Android; the identity provider
  limits 30 sign-in, 30 verify, 150 refresh per window per IP.
- Server contract (audit F003 at the dossier head, plus the delivered
  reader-api#489 facts supplied by the owner at launch, stage 2026-09-12):
  `GET /v1/reader/pre-auth?clientVersion=X.Y.Z` and
  `GET /v1/reader/capabilities?clientVersion=X.Y.Z` are projected per client
  by `X-Reader-Client`; the Android identifier is `reader-android`, version
  line `1.0.0`, supported major `1`; a missing or unknown selector fails
  closed; the pre-auth document carries `client.applicationId`
  (`reader-android`) and `authentication.publicClientId` (the Supabase
  publishable key); Android sign-in methods are email code
  (`signInWithOtp` then `verifyOtp` type `email`/`signup`) and password,
  with code-based password recovery; no native redirect, no Google yet.
  Stage Supabase signs ES256, access-token lifetime 3600 s, refresh-token
  rotation with a 10 s reuse interval. Protected routes take
  `Authorization: Bearer <access_token>`, `X-Reader-Client`,
  `Accept: application/json`, and an optional lowercase-UUID `X-Request-ID`
  that is echoed; 401 bodies are `{code: auth.<reason>, message, category,
  retryable, request_id}` with `WWW-Authenticate: Bearer`; 502
  `auth.jwks_dependency_failed` is `retryable: true`; first call after
  sign-in is capabilities; `PUT /v1/reader/profile` precedes `GET`.
- Leeway (F006): a reader-api quick fix adding `SUPABASE_JWT_LEEWAY_SECONDS`
  with default 120 is in progress in Chunipers; until it lands the server
  has zero leeway. The refresh margin below exceeds both states.

## Selected implementation direction

One PR on `main`, after #92 has merged, touching only `reader-auth/`,
`reader-auth-host/`, the version catalog, and documentation under `docs/`.
Nothing under `app/`, `scripts/release.sh`, or `docs/privacy-statement.md`
changes.

1. **Contract document** — one Markdown file inside the library module
   (beside the module README #92 creates, so it lifts with the code). It is
   written for any Android client of reader-api and names its sources (the
   dossier, the reader-api contract, the platform documents). Sections, in
   this order: sign-in methods in preference order (email code, password
   with code-based recovery, native Google reserved for F005); bootstrap and
   first calls; session storage and backup exclusion; refresh policy; the
   401/403/429/502 policy; sign-out semantics; host requirements. The
   normative rules are the ones in items 2–6; the document states them, the
   module implements them, and the unit tests pin them.
2. **Provider access** — the Supabase Kotlin SDK (`supabase-kt` 3.8.0 auth
   module over a Ktor engine; see the decision brief) with three defaults
   overridden: a custom `SessionManager` backed by the module's session
   store (item 3); the SDK's background auto-refresh disabled so the module
   owns refresh (item 4); PKCE selected as the flow type so later redirect
   flows (F001/F002/F005) match reader-web. Sign-in operations exposed by
   the module: request email code (`shouldCreateUser` true for sign-up),
   verify code (`email` or `signup`), password sign-in, request recovery
   code, verify recovery code then set a new password. Every SDK behaviour
   the module relies on is re-checked against the pinned SDK release at
   implementation start and recorded in the contract document.
3. **Session store** — an interface (`save`, `load`, `clear`) with one
   production implementation: the session JSON encrypted with an AES-GCM key
   generated in the Android Keystore (no user-authentication requirement,
   no StrongBox requirement, key never leaves the Keystore) and written as a
   single file under the app's no-backup files directory. The host's
   extraction rules (from #92) exclude it a second time. An unreadable or
   undecryptable blob (key lost after a restore, corrupt file) means
   "signed out", never a crash. No `SharedPreferences`, DataStore, or
   Jetpack Security crypto anywhere in the module. The cipher sits behind
   its own seam so the store's persistence rules are unit-tested with a
   fake cipher and the real Keystore path is proven on the emulator.
4. **Refresh policy** — proactive with margin plus one single-flight
   reactive refresh. Before any protected call and on return to the
   foreground, the module refreshes when `exp - now <= 300 s` (margin
   exceeds the 120 s leeway and plausible device skew; the token lives
   3600 s, so refresh lands at 55 minutes at the latest). All refresh paths
   funnel through one mutex so concurrent callers await the same in-flight
   refresh (the 10 s reuse interval makes a second refresh a family-revoking
   error). Refresh outcomes: success replaces the stored session; provider
   `refresh_token_already_used`, `refresh_token_not_found`,
   `session_not_found`, `session_expired`, or `bad_jwt` clears the session
   (signed out); 429 or 5xx or a network failure keeps the session and
   surfaces "try later", retried once after `Retry-After` (or a short fixed
   delay), never in a loop. No background timer runs while the app is not in
   the foreground.
5. **reader-api call policy** — one thin client in the module for the three
   calls the contract needs (pre-auth, capabilities, profile upsert) that any
   host reuses for further routes. Every request carries `Authorization:
   Bearer`, `X-Reader-Client: reader-android`, `Accept: application/json`,
   a fresh lowercase-UUID `X-Request-ID`, and a 10 s timeout, with
   `clientVersion` on bootstrap and capabilities. Responses: 401
   `auth.expired_token` → one single-flight refresh and one retry, then
   surface; any other 401 `auth.*` → clear the session (signed out); 403 →
   surface, session intact; 502 `auth.jwks_dependency_failed` and 429 →
   retry once after `Retry-After` (or a short fixed delay), then surface;
   other errors surface with the server's `code` and `request_id`. Bootstrap:
   `GET /v1/reader/pre-auth` before any sign-in; the module refuses to
   proceed unless `client.applicationId` is `reader-android` and
   `authentication.publicClientId` equals the configured publishable key
   (fail closed, mirroring the server's selector). First authenticated call
   is capabilities; profile is upserted with `PUT` before it is read.
   Provider 429 on sign-in or verify is surfaced as "try later", never as a
   credential error, and code verification is never retried automatically.
6. **Sign-out semantics** — local sign-out clears the store first, then
   makes a best-effort provider logout with local scope; a provider failure
   never leaves the device signed in. A separate "sign out other devices"
   operation uses the provider's `others` scope and keeps the local
   session. (reader-web restores the session on provider failure; a mobile
   device's local revocation is the user's intent, so the client contract
   diverges here on purpose and the document says so.)
7. **Configuration** — the reader-api base URL, the Supabase URL, and the
   publishable key are public configuration but never committed: the host
   reads them from the untracked `local.properties` (or environment) into
   `BuildConfig`; the module receives them as a plain configuration value
   together with `clientId = reader-android` and `clientVersion = 1.0.0`.
   No service key anywhere. The host's debug cleartext allowance for
   `10.0.2.2` (from #92) stays debug-only.
8. **Host proving ground** — the #92 probe screen becomes a minimal sign-in
   screen: email and code entry, password entry, recovery, a signed-in view
   showing the verified user id, the capabilities call result, and the two
   sign-out actions. No further UI.
9. **Documentation** — the agent-first guide's project bindings gain the
   host's sign-in run (configuration keys, how the code is obtained,
   emulator lock) and the backup proof procedure for the host; `README.md`
   gains one sentence pointing at the contract document.

Reversibility: the provider transport is the only hard-to-swap piece and it
sits behind the module's interfaces (store, refresh, API client), so the
overturnable SDK-versus-REST choice below changes item 2 and its
dependencies, not the contract. The margin, timeout, and retry delay are
named constants the contract document records.

## External prerequisite manifest

| Key | Blocked row | Title | Required state | Issue | Planning plan |
| --- | --- | --- | --- | --- | --- |
| EXT092 | ROOT | A83-F007 — This repository's no-network product guarantee (REQ-050) is enforced by a release gate and a published privacy statement, so the Android auth work cannot live inside the FastReader app | CLOSED | https://github.com/cedagova/fastReader/issues/92 | https://github.com/cedagova/fastReader/pull/96 |

## Issue publication manifest

| Key | Kind | Parent | Repository | Title | Delivery | Blocked by | Issue |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ROOT | LEAF | None | cedagova/fastReader | A83-F008 — The Android client contract: OTP-code sign-in, Keystore-backed session storage excluded from backup, single-flight refresh with margin, and a fixed 401 policy | None | None | https://github.com/cedagova/fastReader/issues/93 |

## Acceptance coverage

Every acceptance condition of root #93 maps to ROOT; the direction gives each
an observable check:

| Root acceptance | Covered by |
| --- | --- |
| The contract document exists and names its sources (dossier, reader-api contract, platform documents) | Direction 1; the file is in the PR and its sources section lists each URL; review reads it against directions 2–6 |
| Unit tests prove: tokens never written to plain shared preferences or files; concurrent refresh calls collapse to one; refresh happens before expiry with the documented margin; 401 `auth.expired_token` triggers one refresh and one retry; other `auth.*` codes clear the session | Directions 3–5; `./gradlew :reader-auth:testDebugUnitTest` with a fake cipher and clock and a Ktor mock engine: the persisted file bytes never contain the token and no `shared_prefs` file appears; N concurrent callers produce exactly one refresh request; a token inside the margin refreshes and one outside does not; the four 401/502/429/403 branches produce the exact request sequence above |
| On an emulator against stage: sign-up and sign-in with an emailed code succeed, `GET /v1/reader/capabilities` returns a capability document, the session survives process death and a reboot, and sign-out revokes locally | Directions 2, 4, 5, 6, 8; one `Phone_Mid_API36` run: code sign-up, code sign-in, capabilities 200 shown on screen, `am force-stop` then relaunch still signed in, `adb reboot` then relaunch still signed in, sign-out leaves the store empty; screencaps and logcat under `docs/evidence/93/` |
| A backup/transfer test proves the session store is excluded | Direction 3 and the #92 host rules; the `docs/evidence/46/` procedure on the signed-in host (`bmgr backupnow` on the local and D2D transports moves nothing; a reinstall or `bmgr restore` yields a signed-out host); transcript under `docs/evidence/93/` |

No orphan or overlapping outcome: the root is the only node. The external
prerequisite #92 is not part of this graph; it is consumed as delivered code.

## Validation and feedback

- Bounded builds and tests: `./gradlew :reader-auth:testDebugUnitTest
  :reader-auth-host:testDebugUnitTest lint` and both `assembleDebug` tasks
  (JDK 21); hosted `checks.yml` on the PR. Unit tests use a fake cipher,
  fake clock, in-memory store, and a Ktor mock engine; no network, no device.
- Emulator: one run on `Phone_Mid_API36` against the stage project under
  the shared emulator lock (`mkdir ~/worktrees/fastReader/.emulator.lock`,
  pid inside, `rmdir` when done), driven with `adb shell input`. The emailed
  code is read by the owner (or from the test mailbox the owner designates)
  and typed in; obtaining it is not automated at this leaf. Screencaps are
  read, not inferred from exit codes; `adb logcat -d -s AndroidRuntime:E`
  must be empty.
- Backup proof: the `docs/evidence/46/` procedure repeated on the host
  after sign-in, both transports, transcript kept.
- Secrets: stage URL and publishable key come from untracked
  `local.properties`/environment; the PR diff is checked for them; no
  service key is used or needed.
- Source re-check at implementation start: the pinned SDK release's session
  manager seam, auto-refresh switch, flow type, error types, and
  `verifyOtp` types are re-read from the SDK at its release tag and any
  divergence from the audit's 2026-09-11 reading is recorded in the
  contract document before code relies on it.
- Reviewer verifies in its own detached worktree and never writes into the
  lead's worktree or `docs/evidence/`.

## Assumptions and open questions

### Provider access: Supabase Kotlin SDK versus direct GoTrue REST — proceeding on the recommendation; owner or reviewer may overturn

**Problem.** The module needs to talk to the identity provider (request and
verify codes, password sign-in, refresh, logout, recovery). Two viable ways
exist and the root left the choice to planning. It matters now because the
choice fixes the module's dependency set and which behaviours the module
must own versus inherit.

**Facts.** The official SDK (`supabase-kt` 3.8.0, minSdk 26) covers every
operation the contract needs and the future Google and PKCE flows, but its
defaults conflict with the contract in three places: plaintext session
storage, an auto-refresh loop that clears the session on any non-5xx HTTP
error (a 429 from a shared NAT bucket would sign the user out), and the
implicit flow. Each has a documented override (custom `SessionManager`,
auto-refresh switch, flow type). It brings Ktor, kotlinx-serialization (already
in the catalog), and its own release cadence. The GoTrue REST surface the
contract needs is six endpoints with stable JSON shapes; reader-api's own
dev helper already calls one of them directly. The store, refresh policy,
and reader-api policy are module-owned under either option.

**Assumptions.** The SDK's documented overrides behave as the audit read
them on 2026-09-11 (re-checked at implementation start). The real Reader
client will want native Google and possibly OAuth later (F005 is accepted).

**Option A — Supabase Kotlin SDK with a custom session manager and
module-owned refresh (recommended).** Behaviour: SDK performs provider
calls and typing; the module supplies storage, refresh scheduling, and error
policy. Benefit: the provider's maintained, documented Android path;
typed error codes; Google/PKCE support already present for F005; matches
"known best practice" and is what the audit's Android-practice specialist
recommended. Risk: three defaults must be overridden and stay overridden
across SDK upgrades (pinned by unit tests); a larger dependency set.
Reversibility: medium — the transport is behind module interfaces.
Execution: one leaf, as planned.

**Option B — direct GoTrue REST over OkHttp/Ktor.** Behaviour: the module
owns request and response shapes for six endpoints and parses provider
error codes itself. Benefit: no SDK defaults to fight, smallest dependency
set, every behaviour explicit. Risk: hand-built Google/OAuth/PKCE later;
provider shape changes land on this module; diverges from Supabase's
documented Android guidance. Reversibility: medium, same seams. Execution:
one leaf, same shape, slightly more code.

**Option C — do nothing (no provider client; contract document only).**
Not viable: the root's acceptance requires the implemented flow.

**Recommendation.** Option A, because it is the provider's supported path
for the exact flows the real Reader client will need, and the contract's
three overrides are small, testable, and already identified. **Blocked if
overturned:** direction item 2 and the dependency additions; the rest of
the leaf is unchanged. **Exact reply to overturn:** `Choose B` on the
planning PR; the lead then rewrites item 2 and re-requests review.

### Non-material assumptions (implementer may adjust within the invariants)

- Constants: refresh margin 300 s, request timeout 10 s, one retry after
  `Retry-After` (default 10 s when absent). Any change is recorded in the
  contract document and its pinning test.
- The stage email templates deliver the six-digit code (`{{ .Token }}`) for
  sign-in and recovery. If a template sends only a link, the code path
  cannot be exercised; the owner then updates the reader-db template
  (Chunipers, out of this leaf) and the emulator acceptance waits for it.
  The implementer checks this with one code request before building the UI.
- How the code is obtained during the emulator run (owner's inbox or a
  designated test mailbox) is an operational input; it does not change the
  contract or the tests.
- The pre-auth document does not carry the Supabase URL, so the URL is
  configuration; if it does, the module may prefer the document's value.
- The bootstrap check against pre-auth is a client-side wiring guard, not a
  server requirement; a host may relax it, and the document says so.

## Satisfaction proof

Implementation work remains; this is not an `ALREADY_SATISFIED` plan.

## Publication verification

- `plan validate --phase publication-ready` on this directory: valid
  (recorded on the planning PR with the semantic digest of the reviewed
  head).
- Native graph: the root #93 has no sub-issues and exactly one blocked-by
  edge, to the external prerequisite #92, matching the one-row manifest
  and the one-row external prerequisite manifest; its native parent remains
  the audit umbrella #94, which this plan neither claims nor changes.
- `plan verify-graph` authenticates the external prerequisite's
  closed-as-completed state by design. While #92 is open (its implementation
  is in progress), the command reports exactly that #92 must be closed as
  completed and no other finding; the implementation router likewise holds
  `work on #93` until the native blocker closes. Re-run `plan verify-graph`
  once #92 is delivered.
- The root issue carries `Planning root`, `Planning plan`, and
  `Planning kind: LEAF` beside its preserved `Audit handoff:
  PLANNING_REQUIRED` marker, and the implementation leaf contract below the
  preserved audit record.
- Exact-head approval lives in the native PR review, not here.
