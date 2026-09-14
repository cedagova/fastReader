# Implementation Plan: FastReader signs in to a Reader account through :reader-auth (stage)

- Planning issue: https://github.com/cedagova/fastReader/issues/100
- Planning PR: https://github.com/cedagova/fastReader/pull/102
- Status: Review
- Root classification: LEAF
- Delivery topology: DIRECT
- Planner: Planning lead (Claude)
- Started: 2026-09-13
- Product definition: https://github.com/cedagova/fastReader/pull/101
- Product definition head: 1c7a1728d7e9a9af05624fb12de69141e3f18a04

## Pinned baselines

| Repository | Baseline |
| --- | --- |
| `cedagova/fastReader` | `820ef28ae97ec13b90c87bfb09fa312f037b9fc4` |

`origin/main` on 2026-09-13: the commit that delivered #93 (the client
contract and its `:reader-auth` implementation, PR #99) on top of #92 (the
library and host modules, PR #98) and release 1.5.0 (versionCode 7). It is
also the baseline the product definition pinned, so definition evidence and
planning evidence read the same tree. The definition is consumed from the
open, owner- and reviewer-approved PR #101 at head
`1c7a1728d7e9a9af05624fb12de69141e3f18a04` (semantic digest
`sha256:c4a90a352ad67d2cb5a17d6fdfe217e8726f4b427a5885e2c77a47bd1e124374`);
`docs/product-definitions/cedagova-fastReader-100/definition.md` and
`requirements-brief.md` on that head carry REQ-401 to REQ-414 and the owner
decisions of 2026-09-13. A change to that head blocks this plan until it is
re-pinned.

The Chunipers stage backend is not pinned to a repository: its behaviour is
consumed as the facts `reader-auth/CONTRACT.md` records (checked live on
2026-09-13 by #93) and the definition's assumption that stage keeps declaring
`reader-android` 1.0.0 compatible.

## Preserved objective and boundaries

Root #100 is a product-definition root (`Definition handoff:
PLANNING_REQUIRED`, `Definition kind: ROOT`, brief at
https://github.com/cedagova/fastReader/issues/100#issuecomment-5657177459).
Its desired outcome is preserved verbatim: from FastReader on a device, the
owner signs in to a Reader account against the Chunipers stage backend through
the `:reader-auth` library — with an emailed six-digit code (sign-up or
sign-in), a password, or code-based password recovery — sees the account's
capabilities document, and signs out. The session survives process death and
reboot, is never backed up, and is gone after sign out. FastReader stays fully
usable with no account and no network. The README, the in-app privacy
statement (English and Spanish), the release-notes block and
`scripts/release.sh` say truthfully what now leaves the device, and the
release gate proves "INTERNET plus the platform self-permission and no other,
no cleartext" instead of "no INTERNET". `:reader-auth-host` is retired;
FastReader is the library's only host.

Owner decisions preserved from the definition (2026-09-13): FastReader
integrates Reader sign-in and the no-network promise (REQ-050, REQ-303,
REQ-107) is replaced, not preserved; FastReader is the owner's own testing
app for the stage backend, not a consumer product, so promise, statement and
gate are updated truthfully with minimum ceremony; network access is whatever
the backend contract needs; sign-in is optional and the app is fully usable
signed out and offline; scope is account, session and capabilities discovery;
client kind `reader-android` 1.0.0; stage only; the host is retired in this
delivery with its two proofs moved to FastReader. The definition's lead
drafting choices stand as overturnable: every contract sign-in method is
exposed; capabilities are shown raw and in full with the request id; sign out
other devices is offered; the account surface lives under Settings beside the
privacy statement; the release gate proves the permission set and the absence
of cleartext rather than a host allowlist.

Boundaries preserved. In: the Reader account surface under Settings (not
configured / signed out / signed in as `<email>`), every sign-in method the
library contract implements, capabilities display and refresh, sign out and
sign out other devices, configuration handling, the retirement of
`:reader-auth-host` with its proofs moved, and the truthful rewrite of the
privacy statement (EN/ES), README, release-notes block and release gate.
Out (non-goals): sync; profile calls; using capabilities beyond showing them;
native Google sign-in (A83-F005); link-based flows; account-gated reading;
production backend; cutting a release; consumer polish beyond the
accessibility bar. Constraints: `reader-auth/CONTRACT.md` is authoritative
for sign-in methods, storage, refresh, the 401/403/429/502 policy and
sign-out semantics and is neither restated nor contradicted; its five host
requirements bind FastReader; the three backend values stay untracked and
never appear in evidence. Success measure: the stage run deferred on
2026-09-13 (`docs/evidence/93/README.md`, "What was deferred") is completed
from FastReader on `Phone_Mid_API36` and recorded. Guardrails: a signed-out,
offline FastReader is indistinguishable from v1.5.0 outside Settings; the
release gate never publishes an APK with a permission beyond INTERNET and the
platform self-permission, or with cleartext.

## Classification

- **ROOT #100 — `LEAF`.** One coherent outcome in one repository, delivered
  as one PR to `main`: the integration, the moved host proofs, and the
  truthful promises are one integration boundary. Every candidate split
  leaves `main` in a broken or untruthful state between PRs and fails the
  contract's test of independent verification:
  - *Integration first, promises later.* The moment `:app` depends on
    `:reader-auth`, the merged manifest requests INTERNET, so
    `scripts/release.sh` (which dies on that permission) and the privacy
    statement ("FastReader has no internet permission") are false on `main`
    until the second PR lands. The release command would be red in between.
  - *Promises first, integration later.* A statement that says the app has
    the internet permission and uses it for the Reader account, and a gate
    that requires INTERNET, are false until the integration exists; the
    gate would refuse the 1.5.0-shaped APK.
  - *Retire the host separately.* The host's two proofs (manifest and
    configuration tests) exist to guard a host; FastReader becomes that host
    in the integration, and the definition ties the retirement to the same
    delivery (REQ-414, desired outcome 7). A separate PR would only carry
    two duplicated test files and a directory removal.
  No children are published; the fast-leaf path applies with no external
  prerequisite (#92 and #93 are closed and delivered at the baseline).

Not `ALREADY_SATISFIED`: at the baseline `settings.gradle.kts` includes
`:reader-auth-host`, `app/build.gradle.kts` declares no dependency on
`:reader-auth`, `app/src/main/AndroidManifest.xml` declares no permission,
Settings has no account row, `scripts/release.sh` dies on
`android.permission.INTERNET`, and the statement in three places begins
"FastReader has no internet permission". Not `NEEDS_DECISION`: the
definition's owner decisions settle every product, privacy-boundary and
audience choice; the two technical resolutions below (the release-notes
block needs a version bump; the success-path request id needs one additive
library accessor) are ordinary, precedent-backed or behaviour-neutral, and
are recorded as overturnable in Assumptions. Not `DECOMPOSE`: see the split
analysis above. Not `RESEARCH_REQUIRED`: the one runtime unknown (the stage
email template delivering the code) is the definition's own assumption with
the same fallback #93 recorded, and does not change the execution path.

## Current-state evidence

At `820ef28ae97ec13b90c87bfb09fa312f037b9fc4`:

- **Library ready to host.** `:reader-auth` implements `CONTRACT.md`:
  `ReaderAuthClient.create(context, ReaderAuthConfig)` (throws
  `NotConfigured` when any of the three values is blank), operations
  `requestEmailCode(email, createUser)`, `verifyEmailCode`,
  `signInWithPassword`, `requestRecoveryCode`, `verifyRecoveryCode`,
  `setPassword`, `capabilities()`, `onForeground()`, `signOut()`,
  `signOutOtherDevices()`; `sessionState` as a flow of `Initializing |
  SignedOut | SignedIn(userId, email, expiresAt)`; every failure one branch
  of the sealed `ReaderAuthException` (`NotConfigured`,
  `ConfigurationMismatch(reason)`, `NetworkUnavailable`, `TryLater(status,
  code, retryAfter, requestId)`, `SignedOut(code, requestId)`,
  `Forbidden(code, requestId)`, `ProviderRejected(status, code,
  description)`, `ApiError(status, code, requestId, description)`). Its
  manifest declares `android.permission.INTERNET` and nothing else;
  `ReaderAuth.REQUIRED_PERMISSION` names it. The client's constructor is
  internal, so a host cannot build a fake of it: a host that wants to render
  or unit-test its surface without the SDK needs a seam of its own.
- **Request id on success is not exposed.** `ReaderApiClient.request`
  generates the lowercase-UUID `X-Request-ID` per call and discards it; a
  successful `capabilities()` returns only the `JsonObject` body, whose
  `reader.capabilities.v1` shape (as fixed by the module's own tests) has no
  `request_id`. Only failures carry `requestId`. REQ-407 asks for the id of
  each successful call on screen.
- **Host as the pattern to move.** `:reader-auth-host` reads
  `reader.supabaseUrl`, `reader.supabasePublishableKey`, `reader.apiBaseUrl`
  from the untracked `local.properties` or the environment
  (`READER_SUPABASE_URL`, …) into `BuildConfig` with blank defaults;
  `HostApplication` owns one client for the process (null when not
  configured) and calls `onForeground()` from a `ProcessLifecycleOwner`
  observer; `HostManifestTest` pins the nine-domain backup exclusion in both
  rule files, INTERNET arriving from the library and no `<uses-permission>`
  in any host manifest, and a debug-only network security configuration
  naming `10.0.2.2` alone with `usesCleartextTraffic` set nowhere;
  `HostConfigTest` pins the `BuildConfig` → `ReaderAuthConfig` mapping with
  the `reader-android` identity, the `NotConfigured` refusal, and that no
  service value fragment is committed. `docs/evidence/93/README.md` proved
  stage pre-auth, a provider rejection and the not-configured state from
  the host and records the deferred emailed-code run.
- **FastReader today.** `app/build.gradle.kts`: `compileSdk`/`targetSdk`
  37, `minSdk` 26, release `isMinifyEnabled = true` with signing from the
  machine-local keystore, Roborazzi, lint `MissingTranslation`/
  `ExtraTranslation` as errors, no `buildConfig` feature. Manifest: no
  `<uses-permission>`, `allowBackup="false"`, `dataExtractionRules` and
  `fullBackupContent` excluding all nine domains (proven in
  `docs/evidence/46/`), comments citing REQ-107/REQ-303, `singleTask`
  activity; `app/src/debug/AndroidManifest.xml` already exists for
  debug-only activities. `FastReaderApplication` owns the library graph and
  a `ProcessLifecycleOwner` observer that rescans on every foreground.
  `MainActivity` routes three destinations with saved booleans; Settings sit
  over whichever screen opened them. `SettingsScreen` ends with an About
  section: version row, Check for updates, the privacy statement
  (`settings_privacy`, test tag `settings_privacy`) and the visual-only
  statement. Goldens: 88 PNGs in `app/screenshots/`, eleven of them
  `settings_*`.
- **Promise machinery.** `PrivacyStatementTest` holds `settings_privacy`,
  the marked block in `docs/privacy-statement.md`, the README's marked
  block, and `docs/release-notes/v<AppVersion.name>.md`'s marked block equal
  (whitespace-collapsed), asserts six literal claims including "no internet
  permission", and requires the release-notes file for the version the app
  reports. `version.properties` is 1.5.0 / 7 and `docs/release-notes/v1.5.0.md`
  is the published v1.5.0 notes. Precedent: the v1.2.0 statement change
  (crash report, #54) landed as one commit "Bump to 1.2.0, write its notes,
  and close the REQ-303 crash-report gap" (`d86b58c`), with the release cut
  separately. The Spanish `settings_privacy` is hand-edited; lint fails a
  missing translation, not a stale one. README lines 7–10 say "no internet
  permission at all"; lines 113–127 describe the two modules as "not part
  of FastReader" and the host as "a place to prove network-backed work
  without touching FastReader".
- **Release gate.** `scripts/release.sh` builds `:app:assembleRelease`,
  verifies the pinned signing certificate, then reads `aapt2 dump badging`
  and dies if `uses-permission: name='android.permission.INTERNET'` appears,
  checks `minSdkVersion:'26'` and the version line, and prints "no INTERNET
  permission, minSdk 26, version matches". `docs/release.md`'s check table
  has the row "No `android.permission.INTERNET` | REQ-050".
  `scripts/test-release-publish.sh` runs the real script under stubbed
  `gradlew`, `apksigner`, `aapt2`, `java`, `gh`, `curl` in a throwaway
  sandbox — the place a negative gate case can be proven without building
  a rogue APK. Every FastReader release APK already carries
  `com.cedagova.fastreader.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` as its
  one `uses-permission` line (README line 8, `docs/evidence/56/`); the
  host's release badging (`docs/evidence/92/aapt2-host-manifests.txt`) shows
  INTERNET plus that self-permission, the exact pair FastReader will show.
- **Hosted gate.** `.github/workflows/checks.yml` runs `testDebugUnitTest`,
  `verifyRoborazziDebug`, `lint` from the root with no secrets and no
  `local.properties`, and uploads `reader-auth-host/build/reports/` among
  its artifacts (`if-no-files-found: ignore`).

## Selected implementation direction

One PR on `main` touching `app/`, `settings.gradle.kts`, the removal of
`reader-auth-host/`, `scripts/`, `version.properties`, `docs/` and
`README.md`, plus one additive, behaviour-neutral accessor in
`reader-auth/` (item 4). `reader-auth/CONTRACT.md` and the library's
behaviour do not change.

1. **Module wiring and configuration.** `:app` depends on `:reader-auth`;
   INTERNET reaches FastReader by manifest merge and FastReader's own
   manifests declare no permission. `:reader-auth-host` leaves
   `settings.gradle.kts` and the tree. The host's mechanism for the three
   public backend values moves to the app build: `reader.supabaseUrl`,
   `reader.supabasePublishableKey`, `reader.apiBaseUrl` from the untracked
   `local.properties` or the environment into `BuildConfig` with blank
   defaults, so the hosted runner and any clone without the values build,
   lint and pass tests unchanged. The application id stays
   `com.cedagova.fastreader`; the client identity is the library's
   `reader-android` 1.0.0. The CI artifact list drops the host path.
2. **Account ownership in the app.** The application object owns one
   `ReaderAuthClient` for the process (absent when the build is not
   configured) and calls `onForeground()` from its existing foreground
   observer, which is the contract's fifth host requirement; the app runs no
   timer and adds no other network use. The client's readiness (`awaitReady`,
   which reads the stored session) is awaited before the surface shows a
   state, so a stored session is never reported as signed out for a frame.
3. **The account surface.** One row in Settings, placed beside the privacy
   statement in the About section per the definition's drafting choice
   (exact wording and section placement are the implementer's within
   REQ-401), opens a full-screen Reader account surface that sits over
   Settings the way Settings sits over the reader and returns to it on back.
   The surface renders from an app-owned state model with exactly the
   definition's states — not configured (naming the three missing values;
   every action disabled; nothing called), signed out (forms in contract
   order: email code with a "new account" choice, then password, then
   recovery), and signed in as `<email>` (Show capabilities, Refresh
   capabilities, Sign out, Sign out other devices) — plus the transient
   outcomes of the definition's states table (requesting a code, provider
   rejection with the provider's code, try later, network unavailable,
   configuration mismatch naming the reason, capabilities loading / loaded
   with its request id / failed with code and request id, session gone with
   the reason shown once, forbidden). Each outcome maps one-to-one to a
   `ReaderAuthException` branch; the app classifies nothing itself, retries
   nothing, never re-sends or re-verifies a code, and treats
   `NotConfigured` as the not-configured state rather than an error. Every
   contract operation is reachable and no method is added. The capabilities
   document is shown as returned (pretty-printed JSON, scrollable) with the
   request id of the call; Refresh re-fetches. The surface depends on a
   small app-owned gateway interface over the library client so that the
   state model, its unit tests and its goldens run with a fake gateway — no
   SDK, network or Keystore — while the production implementation is a thin
   pass-through; this seam is the one interface the leaf owns, and it is
   the reason the library's internal constructor is no obstacle.
   Accessibility per REQ-060/REQ-301: TalkBack labels on every control,
   48 dp targets, system font scale, errors announced when they appear,
   code and password keyboard types, masked password with reveal; every
   new string has a Spanish twin (the lint gate enforces the presence, the
   implementer writes the words).
4. **Request id of a successful call.** The library gains one additive,
   behaviour-neutral way for a host to learn the `X-Request-ID` a successful
   reader-api call carried (the id the module already generates and
   reader-api already echoes) — for example a result value beside the
   document, or the id supplied by the caller. Headers, policy, retries and
   `CONTRACT.md` do not change; the library's own mock-engine tests pin the
   addition. This is the only library edit, recorded as an overturnable
   resolution in Assumptions because REQ-407 (request id on screen) and
   REQ-414's sentence "the library itself is unchanged" cannot both hold
   with the baseline API.
5. **Signed-out and offline product unchanged.** Nothing outside Settings
   and the new surface changes; no account prompt appears anywhere else; no
   reading data is sent. The goldens outside `settings_*` and the new
   `account_*` set are byte-identical; the eleven `settings_*` goldens are
   re-recorded for the added row and inspected.
6. **Truthful promises.** The privacy statement is rewritten in the four
   places the test holds equal — `settings_privacy`, its Spanish twin by
   hand, `docs/privacy-statement.md`'s block and table (each new sentence
   with what makes it true and how it was checked), the README's block —
   to say what REQ-409 lists: FastReader has the internet permission and
   uses it only for the Reader account; what leaves the device and to whom
   (the email address, the typed code or password, the session tokens; to
   the Reader identity provider and the Reader API); that the session is
   kept encrypted on this device, outside backup, and removed by sign out;
   that books, positions, settings and crash reports stay on the device and
   are not sent. The rest of the statement stays. `PrivacyStatementTest`'s
   literal-claims list drops "no internet permission" and gains the new
   claims. The README's opening paragraph and its module section are
   rewritten: the permission, what it is for, that the reading product
   needs no account, and that `reader-auth/` is the library FastReader
   hosts (no host app). The release-notes block follows the repository's
   precedent: `version.properties` moves to the next version (1.6.0,
   versionCode 8) and `docs/release-notes/v1.6.0.md` is written with the
   new block and this change described, while `v1.5.0.md` stays the
   historical record of what 1.5.0 promised; no release is cut.
7. **Release gate.** `scripts/release.sh` replaces the INTERNET refusal
   with the new proof on the artifact: the badging's `uses-permission`
   lines are exactly `android.permission.INTERNET` and
   `com.cedagova.fastreader.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — any
   other line, or either missing, dies — and the release manifest (read
   with `aapt2 dump xmltree`) carries no `networkSecurityConfig` attribute
   and no `usesCleartextTraffic`. Signing, `minSdkVersion 26` and the
   version proofs stay. `docs/release.md`'s table and the script's header
   comment describe the new proof; `scripts/test-release-publish.sh`'s
   stubbed `aapt2` emits the two-permission badging so the publish
   rehearsal still reaches `gh release create`, and gains the negative
   cases (an extra permission; a cleartext attribute) so REQ-411's "fails
   it" half is proven in the sandbox rather than by building rogue APKs.
   The shrunk release build now includes the library's dependencies for
   the first time (the host never minified), so the leaf keeps the sign-in
   path working under R8 — consumer or app keep rules as needed — proven by
   installing the gate's own signed APK (item Validation).
8. **Host proofs moved and the host retired.** The host's two tests become
   FastReader tests in `app/src/test/` against FastReader's package: the
   merged manifest does not allow backup and requests INTERNET from the
   library alone (no `<uses-permission>` in any `app/` manifest); the rule
   files keep the nine-domain exclusion in both sections; the debug-only
   network security configuration lives in `app/src/debug/` naming
   `10.0.2.2` alone, the main manifest references none, and
   `usesCleartextTraffic` appears in no manifest of `app/` or
   `reader-auth/`; the `BuildConfig` values map to `ReaderAuthConfig` with
   the `reader-android` identity, a blank configuration yields
   `NotConfigured` and no client, and no service value fragment is
   committed in `app/build.gradle.kts`, the manifests or `strings.xml`.
   `reader-auth-host/` is deleted. Documentation names FastReader as the
   host: the README section, `reader-auth/README.md` ("what every host must
   declare", now pointing at FastReader's tests as the pattern),
   `docs/agent-first-development.md`'s two host rows (rewritten for
   FastReader's install/launch/drive loop against stage), and
   `docs/evidence/93/README.md` gains a pointer to `docs/evidence/100/` as
   the place the deferred run is completed. `docs/evidence/92/` and `93/`
   stay as historical records and are not edited beyond that pointer;
   manifest comments citing REQ-303 as the reason for "no network" are
   updated to the new promise.

Reversibility: every piece sits behind the app's gateway seam, the build
script's value mechanism, or a shell gate; reverting the PR restores the
1.5.0 promise exactly. The version bump and the library accessor are the
two choices the owner or reviewer may overturn with one reply each (see
Assumptions).

## Issue publication manifest

| Key | Kind | Parent | Repository | Title | Delivery | Blocked by | Issue |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ROOT | LEAF | None | cedagova/fastReader | FastReader signs in to a Reader account through :reader-auth (stage) | None | None | https://github.com/cedagova/fastReader/issues/100 |

## Acceptance coverage

Every acceptance condition of root #100 (REQ-401 to REQ-414) maps to ROOT;
the direction gives each an observable check:

| Root acceptance | Covered by |
| --- | --- |
| REQ-401 Settings reaches the account surface in one tap; signed out with the values, not configured without them and no network call | Directions 2, 3; goldens for the signed-out and not-configured states; emulator: a values-present build reads signed out, the CI-shaped build reads not configured with nothing from the library in logcat and no socket opened |
| REQ-402 every contract method reachable: email code with sign-up choice, password, code-based recovery | Direction 3; unit tests with the fake gateway assert each form calls exactly its operation; the owner-relayed stage run signs up a new address with a code, signs in with a code and with a password, recovers with a code and a new password |
| REQ-403 wiring guard before the first provider call | Direction 3 maps `ConfigurationMismatch` to the configuration error; emulator: a build with a wrong publishable key shows the mismatch and the provider's sign-in log stays empty |
| REQ-404 distinct outcomes with codes and request id, no automatic retry | Direction 3; unit tests assert the exception-to-state map and that no operation is invoked twice; emulator: wrong password shows `invalid_credentials`, airplane mode shows network unavailable with the form intact and any session kept |
| REQ-405 session survives force-stop and reboot, gone after sign out, store excluded from backup | Directions 2, 8; the moved manifest test; owner-relayed run: `am force-stop` and `adb reboot` both relaunch signed in as the same email; after sign out `no_backup/reader-auth/` has no `session.bin`; the `docs/evidence/46/` backup procedure while signed in moves zero bytes |
| REQ-406 sign out works offline; sign out other devices keeps this device | Direction 3; unit tests for both actions' state effect; run: sign out in airplane mode reads signed out with an empty store; sign out other devices leaves the surface signed in |
| REQ-407 capabilities shown in full with the request id; Refresh re-fetches | Directions 3, 4; unit test that the loaded state carries the document and the id; run: Show capabilities renders a `reader.capabilities.v1` document with its id, Refresh shows a new one |
| REQ-408 signed-out and offline behaviour unchanged, no reading data sent | Direction 5; `verifyRoborazziDebug` with every golden outside `settings_*`/`account_*` byte-identical; existing unit tests pass; a signed-out emulator pass of the v1.5.0 flows (open book, play, pause, settings) shows no difference |
| REQ-409 truthful statement in four places, Spanish updated, table maps every sentence | Direction 6; `PrivacyStatementTest` passes with its updated claims; review reads `docs/privacy-statement.md`'s table against the manifest and the evidence |
| REQ-410 README no longer claims "no internet permission at all"; module section corrected | Direction 6; `grep -i "no internet permission" README.md` is empty; the historical release-notes files are not edited |
| REQ-411 release gate proves INTERNET + self-permission only and no cleartext; signing, minSdk, version kept; `docs/release.md` updated | Direction 7; `./scripts/release.sh` (verify only) passes on the merged build; `./scripts/test-release-publish.sh` proves the extra-permission and cleartext negatives fail the gate |
| REQ-412 values untracked; a build without them compiles, lints, tests and shows not configured | Directions 1, 8; hosted `checks.yml` (no values) green; the moved configuration test; emulator install of the CI-shaped build reads not configured |
| REQ-413 client kind `reader-android` 1.0.0; application id unchanged | Directions 1, 8; the moved configuration test pins the identity; run: stage pre-auth reports `compatibility.status: compatible`; `pm list packages` still shows `com.cedagova.fastreader` and no host package |
| REQ-414 host retired, proofs hold for FastReader, docs name FastReader as host, library depends on nothing under `:app` | Directions 1, 8; `settings.gradle.kts` includes `:app` and `:reader-auth` only; no `reader-auth-host/`; the moved tests pass; `grep -ri "reader-auth-host" docs README.md reader-auth` matches only the historical records the definition lists; `reader-auth/build.gradle.kts` declares no project dependency |

No orphan or overlapping outcome: the root is the only node. The one library
edit (direction 4) serves REQ-407 and is recorded against REQ-414's wording
in Assumptions.

## Validation and feedback

- **Bounded builds and tests (JDK 21):** `./gradlew testDebugUnitTest
  verifyRoborazziDebug lint` from the root (the hosted gate's exact three
  steps, run locally without and then with `local.properties` values —
  the values must not change any test result); `./gradlew assembleDebug`.
  The account surface's state model and forms are unit-tested with the
  fake gateway; the moved host proofs run as FastReader tests; the
  library's tests gain the request-id accessor case.
- **Goldens:** `recordRoborazziDebug` for the new `account_*` set (at least
  not configured, signed out, signed in, one Spanish, one compact large
  font, per the existing Settings pattern) and the re-recorded
  `settings_*`; every PNG is read, not inferred; every other golden is
  unchanged in the diff.
- **Release gate:** `./scripts/release.sh` (no `--publish`; the machine
  holds the signing material) passes its verify step on the merged build,
  printing the two permissions and the cleartext proof;
  `./scripts/test-release-publish.sh` passes including its new negative
  cases. The gate's own signed release APK is installed on the emulator and
  the wrong-password check is repeated from it, which proves the shrunk
  build's network and serialization path.
- **Emulator, implementer alone** (`Phone_Mid_API36` under the shared lock:
  `mkdir ~/worktrees/fastReader/.emulator.lock`, pid inside, removed after
  `adb emu kill`): the CI-shaped build (no values) reads not configured; a
  wrong-key build shows the configuration mismatch before any provider
  call; a correct build shows the provider's `invalid_credentials`
  rejection for a wrong password (stage reached, no session); airplane mode
  (`adb shell cmd connectivity airplane-mode enable`) shows network
  unavailable with the form intact; the signed-out v1.5.0 flows show no
  difference; screencaps read and `adb logcat -d -s AndroidRuntime:E`
  empty. `pm list packages` shows no host package after
  `pm uninstall com.cedagova.reader.auth.host` of any earlier install.
- **Emulator, owner-relayed code — the final acceptance step:** the
  deferred stage run from FastReader: sign-up with an emailed code, sign-in
  with a code and with a password, recovery with a code and a new password,
  capabilities on screen with its request id and a Refresh, `am
  force-stop` then relaunch signed in, `adb reboot` then relaunch signed
  in, sign out in airplane mode leaving `no_backup/reader-auth/` without
  `session.bin`, sign out other devices keeping the device signed in, and
  the `docs/evidence/46/` backup procedure (local and D2D transports) on
  the signed-in app moving zero bytes. The six-digit code is read by the
  owner from the inbox and relayed; nothing automates it and it is valid
  for 60 minutes. Screencaps, the logcat excerpt and the backup transcript
  go under `docs/evidence/100/`, never a value or a code. The implementer
  asks the owner for this step when everything else is green; it is the
  definition's success condition, not a planning blocker.
- **Secrets:** the PR diff is checked for the three values and for any
  fragment of them; the moved configuration test guards the tracked
  files; no service key exists.
- **Reviewer** verifies in its own detached worktree and never writes into
  the lead's worktree or `docs/evidence/`.

## Assumptions and open questions

Both items below are recorded, overturnable resolutions the lead proceeds
on; neither is a product decision, and one reply on the planning PR
overturns either.

### Release-notes block: bump to 1.6.0 rather than edit the published 1.5.0 notes

**Problem.** `PrivacyStatementTest` holds the release-notes block in
`docs/release-notes/v<version the app reports>.md` equal to the statement.
With the version at 1.5.0, rewriting the statement means either editing
`v1.5.0.md` — the notes of a release already published with the old
promise, which the definition's assumption says stay historical — or moving
the version forward. **Facts:** the v1.2.0 statement change landed as
"Bump to 1.2.0, write its notes" in one commit with the release cut
separately (`d86b58c`); `scripts/release.sh --publish` refuses a reused tag,
so a bump without a release costs nothing. **Assumption:** the next version
is 1.6.0 / versionCode 8. **Recommended (A):** bump `version.properties`
and write `v1.6.0.md` with the new block; cutting the release stays a
separate owner action. **Alternative (B):** keep 1.5.0 and rewrite
`v1.5.0.md`'s block — simplest diff, but the tracked notes then differ from
the GitHub Release they describe. **Reply to overturn:** `Edit the 1.5.0
notes` on the PR.

### Success-path request id: one additive library accessor

**Problem.** REQ-407 shows the request id of each capabilities call; the
baseline library discards it on success and only failures carry
`requestId`. REQ-414 says the library itself is unchanged. **Facts:** the id
is generated inside the module and echoed by reader-api; the
`reader.capabilities.v1` body carries none; the client's constructor is
internal, so the app cannot supply or intercept it. **Recommended (A):**
one additive, behaviour-neutral library accessor for the id of a successful
call (direction 4), pinned by the library's tests, `CONTRACT.md` untouched;
REQ-414's sentence is read as "the retirement does not alter the library",
which its acceptance criteria test. **Alternative (B):** leave the library
untouched and show request ids on failures only — REQ-407's success-path
id and its "Refresh shows a new request id" acceptance are then dropped,
which is a definition deviation the owner would be accepting. **Reply to
overturn:** `Request ids on failures only` on the PR.

### Non-material assumptions (implementer may adjust within the invariants)

- The debug-only cleartext allowance for `10.0.2.2` moves to
  `app/src/debug/` with the host's test as-is; the implementer may instead
  drop it and tighten the moved test to "no configuration anywhere". Either
  satisfies REQ-411, since the release manifest carries none.
- The stage email templates deliver the six-digit code (`{{ .Token }}`);
  if a template sends only a link, the owner updates the reader-db
  template (Chunipers, outside this leaf) and the owner-relayed run waits.
- The account row's exact wording and the capabilities document's visual
  treatment are design choices inside REQ-401/REQ-407 (the definition's
  own remaining uncertainty).
- The stage backend keeps declaring `reader-android` 1.0.0 compatible.

## Satisfaction proof

Implementation work remains; this is not an `ALREADY_SATISFIED` plan.

## Publication verification

- `plan validate --phase review-ready` on this directory: valid at the
  presented head (recorded on the planning PR with the semantic digest).
- `definition status` for #100 on 2026-09-13: `ready_for_planning`,
  definition PR #101 at head `1c7a1728d7e9a9af05624fb12de69141e3f18a04`,
  owner approval by `cedagova`, official reviewer
  `cedagova-codex-reviewer[bot]`. Re-checked before implementation
  readiness.
- Native graph: the root #100 has no sub-issues, no blocked-by edges and no
  parent, matching the one-row manifest; `plan verify-graph` recorded on
  the PR. The root carries `Planning root`, `Planning plan` and
  `Planning kind: LEAF` beside its preserved definition metadata
  (`Product definition`, `Definition root`, `Definition handoff:
  PLANNING_REQUIRED`, `Definition kind: ROOT`, `Requirements brief`) and
  the implementation leaf contract below the preserved definition record.
- Exact-head approval lives in the native PR review, not here.
