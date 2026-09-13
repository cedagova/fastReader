# Product Definition: FastReader signs in to a Reader account through :reader-auth (stage)

- Product definition issue: https://github.com/cedagova/fastReader/issues/100
- Product definition PR: https://github.com/cedagova/fastReader/pull/101
- Requirements brief: https://github.com/cedagova/fastReader/issues/100#issuecomment-5657177459
- Status: Ready for planning
- Classification: REFINE
- Definition lead: cedagova
- Started: 2026-09-13

## Pinned evidence baselines

| Repository | Baseline |
| --- | --- |
| `cedagova/fastReader` | `820ef28ae97ec13b90c87bfb09fa312f037b9fc4` |

## Objective

FastReader gains Reader account sign-in, using the `:reader-auth` library
already in this repository (contract in `reader-auth/CONTRACT.md`), so that
FastReader becomes the first real Android client of the Chunipers Reader
backend. This intentionally replaces FastReader's current "no internet
permission, ever" product promise (REQ-050 in
`docs/product-definitions/cedagova-fastReader-1/`, REQ-303 and REQ-107 from
the v1.1.0 definition, the README's "no internet permission at all"
paragraph, `docs/privacy-statement.md`, and the `scripts/release.sh` gate
that refuses INTERNET in the release APK).

Owner intent (2026-09-13, verbatim): "that's the whole point" — integrate
Reader sign-in into FastReader; and "i need fastreader as a testing app" —
FastReader is the owner's own Android app for exercising the Reader backend
(stage) end to end. It is not shipped to consumers. The no-network promise,
privacy statement and release gate are updated truthfully with minimum
ceremony, not defended.

## User or operator need

The owner needs one real Android app, on a real device, that signs in to a
Reader account and talks to the Reader backend through the shipped Android
client contract — so that the backend's Android projection (pre-auth,
capabilities, the auth error policy, refresh, sign-out) is exercised the way
a real client exercises it, not through a throwaway form.

Today that role is played by `:reader-auth-host`, a bare proving-ground app
with no product around it (`docs/evidence/93/`). The stage acceptance run
that would prove the whole flow on a device — emailed-code sign-in,
capabilities on screen, session surviving process death and reboot, sign-out
clearing the store, backup exclusion of the live store — was deferred by owner
decision on 2026-09-13 and is still unproven. FastReader, which the owner
already installs and uses, is the app where that proof belongs.

## Actors and context

- **Owner (the only actor):** the developer of both FastReader and the Reader
  backend, using FastReader on their own phone and on the emulator matrix to
  exercise the stage backend. Friends who sideloaded earlier releases are not
  a target of this definition; nothing here is promised to them.
- **Reader backend (stage):** the Chunipers stage environment, already
  serving the `reader-android` client kind with email-code and password
  sign-in and code-based recovery. Production is not promoted and is not a
  target.
- **Context:** the app is used as before — offline, with local EPUBs — and
  additionally, when the owner chooses, signed in to a Reader account. A
  phone with or without a lock screen, with or without network at any given
  moment.
- **Triggers:** the owner opens the account surface to sign in, to look at
  the capabilities the backend grants, to refresh them, or to sign out; the
  app returns to the foreground with a stored session.
- **Permissions:** `android.permission.INTERNET`, delivered by the library's
  manifest, is the one new permission. Nothing else changes: document access
  stays user-granted picks; no contacts, no accounts framework, no location.

## Desired outcomes

1. From FastReader on a device, the owner can create or sign in to a Reader
   account against stage with an emailed six-digit code, and can also sign in
   with a password and recover a password with an emailed code — every
   sign-in method the library contract implements is reachable from the app.
2. Once signed in, the owner can see the capabilities document the backend
   returns for that account, and refresh it on demand.
3. The session survives the app being killed and the device rebooting; sign
   out removes it; the stored session is never part of a backup or transfer.
4. FastReader remains fully usable with no account and with no network:
   library, reading, settings and every existing behaviour are unchanged for
   a signed-out user.
5. The README, the in-app privacy statement (and its Spanish copy), the
   release notes block and `scripts/release.sh` say truthfully what the app
   can now do on the network, and the release gate proves the new promise
   instead of the old one.
6. The `:reader-auth` contract is neither duplicated nor contradicted: what
   FastReader adds is a product surface over the library; the auth behaviour
   stays the library's.
7. FastReader is the library's only host in this repository: the
   proving-ground `:reader-auth-host` is retired, and what it proved is
   proved from FastReader instead.

## Product behavior and flows

### Where the account lives

A **Reader account** surface is reachable from Settings. It is one place: it
shows whether the app is signed out, signed in (as which email), or not
configured, and it holds every account action. The rest of the app never
mentions the account and never requires it.

### Not configured

A build without the three public backend values (Supabase URL, publishable
key, Reader API base URL — the same untracked `local.properties` or
environment values `:reader-auth-host` reads today) shows the account surface
in a **not configured** state that names what is missing and calls nothing.
The app still builds, lints, and passes its tests without the values. This is
the state of any build made on the hosted runner.

### Signed out — signing in

- **Email code (preferred, first on the surface):** the owner types an email
  address and asks for a code; the app asks the provider to email a six-digit
  code, then the owner types the code and the app exchanges it for a session.
  A **new account** choice on the same form controls whether an unknown
  address is signed up (`create_user: true`) or refused.
- **Password:** email plus password signs in directly.
- **Forgot password:** the owner asks for a recovery code by email, types it,
  and then sets a new password; that leaves them signed in.
- Before the first provider call of the process, the app fetches the
  backend's pre-auth document and refuses to continue when the backend does
  not report this client compatible or its configuration does not match what
  the app was built with (the library's wiring guard). The owner sees a
  configuration error naming the mismatch, not a credential error.
- A wrong or expired code, wrong credentials, or a weak new password is shown
  as the provider's rejection with its error code. A code is never re-sent or
  re-verified automatically. The provider's rate limit is shown as **try
  later**, never as a credential error.

### Signed in

- The surface shows **signed in as <email>** and offers: **Show
  capabilities**, **Refresh capabilities**, **Sign out**, and **Sign out other
  devices**.
- **Capabilities:** the document the backend returns for this account is
  shown as returned (the full `reader.capabilities.v1` document, readable on
  screen, with the request identifier of the call). Refresh re-fetches it.
  This is a testing surface; the document is not interpreted or summarised.
- Returning to the foreground with a session close to expiry refreshes it,
  per the library contract; the owner sees nothing unless the session is
  gone, in which case the surface shows signed out with the reason.
- **Sign out** clears the device session first and tells the provider on a
  best-effort basis; the surface shows signed out even when the provider call
  fails. **Sign out other devices** keeps this device signed in.

### Signed-out or offline use

Everything FastReader did before this definition works identically with no
account and with no network. No account prompt appears anywhere outside the
account surface; no reading data is sent anywhere.

## States and failure behavior

| State | What the owner sees |
| --- | --- |
| Not configured | Account surface names the missing values; every action disabled; nothing is called. |
| Signed out | Sign-in forms (email code first, then password, then recovery). |
| Requesting a code | Progress on the form; the code entry appears once the request succeeds. |
| Provider rejection (wrong code, bad credentials, weak password, unknown address with sign-up off) | The provider's message and error code, on the form; the owner can correct and retry by hand. |
| Try later (provider or backend rate limit; backend dependency failure after its one retry) | A "try later" message; the form and any session are kept. |
| Network unavailable or timeout | A "network unavailable" message; the form and any session are kept. |
| Configuration mismatch (pre-auth says incompatible or a value differs) | A configuration error naming the mismatch, before any provider call. |
| Signed in | Email, capabilities actions, sign-out actions. |
| Capabilities loading / loaded / failed | Progress; the document with its request id; or the backend's error code and request id with the session kept (or signed out, when the backend rejected the token per the contract's 401 rule). |
| Session gone (server rejected it; refresh token revoked or expired) | Signed out, with the reason shown once. |
| Forbidden (403) | The backend's message; the session is kept. |
| Corrupt or unreadable stored session (e.g. restored onto another device without its key) | Starts signed out; the unusable file is removed; no crash. |

## Requirements and acceptance

Requirement numbers start at REQ-401 so they do not collide with REQ-0xx
(definition #1), REQ-1xx/2xx/3xx (definitions #36 and #39).

### Account surface

- **REQ-401** A Reader account surface reachable from Settings shows one of
  three states — not configured, signed out, signed in as `<email>` — and
  holds every account action. Nothing outside it mentions or requires an
  account.
  *Accept:* on a fresh install with the backend values present, Settings
  reaches the account surface in one tap and it reads signed out; on a build
  without the values it reads not configured and no network call is made
  (nothing in logcat from the library, no socket opened).
- **REQ-402** Every sign-in method the library contract implements is
  reachable from the surface: email code with a sign-up choice, password,
  and code-based password recovery. Each maps one-to-one to the contract; the
  app adds no method and drops none.
  *Accept:* against stage, a new address is signed up with an emailed code;
  the same address later signs in with a code and with a password; recovery
  with an emailed code followed by a new password leaves the owner signed in.
- **REQ-403** The wiring guard runs before the first provider call: a
  backend that does not report `reader-android` compatible, or whose
  configuration does not match the build's values, produces a configuration
  error and no provider call.
  *Accept:* a build configured with a wrong publishable key shows the
  mismatch and the provider's sign-in log stays empty.
- **REQ-404** Provider rejections, rate limits, network failure and backend
  errors are shown as distinct outcomes with the provider's or backend's
  code (and the backend's request id), exactly as the library classifies
  them; the app never retries a code or a credential on its own.
  *Accept:* a wrong password shows the provider's `invalid_credentials`
  rejection; airplane mode shows network unavailable and leaves the form
  intact; a stored session is not cleared by either.

### Session

- **REQ-405** A signed-in session survives process death and device reboot
  and is gone after sign out. The session is stored only through the
  library's Keystore-encrypted no-backup store; FastReader's existing
  all-domain backup exclusion stays in force.
  *Accept:* after `am force-stop` and after `adb reboot` the surface reads
  signed in as the same email; after sign out `no_backup/reader-auth/` holds
  no `session.bin`; a backup run per `docs/evidence/46/` while signed in
  transfers zero bytes.
- **REQ-406** Sign out shows the device signed out even when the provider
  cannot be reached; sign out other devices keeps this device signed in.
  *Accept:* sign out in airplane mode reads signed out and the store is
  empty; sign out other devices in normal conditions leaves the surface
  signed in.

### Capabilities

- **REQ-407** A signed-in owner can fetch and refresh the backend's
  capabilities document for the account and read it on screen in full, with
  the request id of the call.
  *Accept:* against stage, Show capabilities renders a document whose kind
  is `reader.capabilities.v1`; Refresh re-fetches and shows a new request
  id.

### Unchanged reading product

- **REQ-408** With no account, or with no network, every existing FastReader
  behaviour is unchanged: launch, library, reading, settings, external open,
  crash offer. No reading data — books, positions, settings, covers, crash
  report — is sent anywhere by this definition.
  *Accept:* the existing Roborazzi goldens outside the account surface are
  unchanged; the existing unit tests pass; a signed-out emulator run of the
  v1.5.0 flows (open book, play, pause, settings) shows no difference.

### Truthful promises

- **REQ-409** The privacy statement — in-app string, its Spanish copy, the
  README block and the release-notes block, all kept word-for-word equal by
  the existing test — says truthfully: FastReader has the internet
  permission and uses it only for the Reader account; what leaves the device
  and to whom (the email address, the typed code or password, and the
  session tokens, to the Reader identity provider and the Reader API); that
  the session is kept encrypted on this device, outside backup, and removed
  by sign out; that books, reading positions, settings and crash reports
  stay on the device and are not sent unless a later change says otherwise.
  The rest of the statement (books in place, backup exclusion, crash report,
  external open) stays true and stays.
  *Accept:* `PrivacyStatementTest` passes; every sentence of the new
  statement maps to a manifest declaration or an observed behaviour in
  `docs/privacy-statement.md`'s table; the Spanish string is updated in the
  same change.
- **REQ-410** The README no longer claims "no internet permission at all";
  it states the permission, what it is for, and that the reading product
  needs no account. The `:reader-auth` section no longer says the host app
  is where network work lives.
  *Accept:* `grep -i "no internet permission" README.md` matches nothing
  outside the historical release-notes files.
- **REQ-411** `scripts/release.sh` stops refusing `android.permission.INTERNET`
  and instead proves the new promise on the artifact: the APK requests
  INTERNET and no other permission, and its release manifest permits no
  cleartext traffic (no network security configuration that allows it, no
  `usesCleartextTraffic`). Signing, minSdk 26 and version proofs are kept.
  `docs/release.md` describes the new proof.
  *Accept:* a release build with the library merged passes the script's
  verify step; a build that adds any second permission, or a debug-style
  cleartext allowance, fails it.

### Configuration and build

- **REQ-412** The three public backend values are read from the untracked
  `local.properties` or the environment, never committed; a build without
  them compiles, lints and passes unit tests and shows not configured at
  runtime.
  *Accept:* the hosted CI job (which has no values) stays green; the
  emulator install of that build shows not configured.
- **REQ-413** FastReader presents itself to the backend as client kind
  `reader-android`, version `1.0.0`, as the library configures; FastReader's
  own application id is unchanged.
  *Accept:* stage pre-auth reports `compatibility.status: compatible` for
  the app and the installed package is still `com.cedagova.fastreader`.

### Retiring the proving-ground host

- **REQ-414** `:reader-auth-host` is retired: the module is removed from the
  build, the two host-side proofs it carried (backup-exclusion and
  cleartext-policy manifest checks; configuration read with the
  not-configured fallback) hold for FastReader as the host, and the
  repository's documentation (`README.md`, `reader-auth/README.md`,
  `docs/agent-first-development.md`, `docs/evidence/93/README.md`) names
  FastReader as the host and the place where the deferred stage run is
  completed. The library itself is unchanged and still depends on nothing
  under `:app`.
  *Accept:* `settings.gradle.kts` includes `:app` and `:reader-auth` only;
  no `reader-auth-host/` directory remains; the unit tests that pinned the
  host's manifest and configuration behaviour now pass against FastReader;
  `grep -ri "reader-auth-host" docs README.md reader-auth` matches only
  historical records (audit dossier, release notes, plan and evidence
  directories of #92/#93).

## Accessibility and content

- The account surface meets the existing bar (REQ-060/REQ-301): every
  control TalkBack-labelled, 48 dp targets, system font scale respected;
  error messages are announced when they appear.
- Codes and passwords use the appropriate keyboard types; passwords are
  masked with a reveal.
- Copy is English with a Spanish translation for every new string (the
  existing `values-es` rule); the privacy statement's Spanish copy is edited
  by hand in the same change as the English.
- Error copy shows the provider's or backend's code verbatim after a plain
  sentence; this is a testing surface and the code is the useful part.

## Privacy, security, and policy

- **What leaves the device, and only when the owner acts on the account
  surface:** the email address; the typed code or password; session tokens.
  Recipients: the Reader identity provider (Supabase, stage project) and the
  Reader API (stage). The library adds `X-Reader-Client`, a client version
  and a per-request id; nothing about books, positions or settings is ever
  sent.
- **Storage:** the session is stored only by the library (AES-256-GCM,
  Keystore key, no-backup directory). FastReader stores nothing else about
  the account. FastReader's `allowBackup="false"`, `dataExtractionRules` and
  `fullBackupContent` all-domain exclusions stay.
- **Transport:** HTTPS only in release; the debug build may allow cleartext
  to the emulator loopback `10.0.2.2` and nothing else, as the contract
  permits a host.
- **Secrets:** the publishable key, Supabase URL and API base URL are public
  browser-runtime values but are never committed and never pasted into
  evidence; no service key exists anywhere.
- **Consent:** signing in is the consent; nothing is sent before the owner
  submits an address. There is no analytics, no telemetry, no crash
  upload — the crash report path is unchanged.
- **Retention:** the session lives until sign out, server rejection, or app
  uninstall. The backend's own retention is the backend's policy, outside
  this definition.
- **Audience:** this is the owner's testing app; the privacy statement stays
  published in the same three places because the test enforces it and the
  statement must be true for anyone who does install it.

## Success measures and guardrails

- **Success:** the stage acceptance run deferred on 2026-09-13
  (`docs/evidence/93/README.md`, "What was deferred") is completed from
  FastReader on `Phone_Mid_API36` and recorded as evidence: sign-up and
  sign-in by emailed code, capabilities on screen, session surviving
  force-stop and reboot, sign-out leaving no `session.bin`, and the backup
  transcript.
- **Guardrail:** a signed-out, offline FastReader is indistinguishable from
  v1.5.0 outside Settings.
- **Guardrail:** the release gate never publishes an APK that requests any
  permission beyond INTERNET or permits cleartext.
- **Guardrail:** no line of `reader-auth/CONTRACT.md` is restated with
  different values anywhere in FastReader; FastReader documentation links to
  the contract instead.

## Constraints and non-goals

- **Backend:** stage only. Production promotion of `reader-android` is a
  Chunipers decision outside this definition.
- **Client kind:** `reader-android`, version `1.0.0` — the kind the backend
  already declares; FastReader does not get its own kind.
- **Library contract is authoritative:** sign-in methods, storage, refresh,
  401/403/429/502 policy and sign-out semantics are `reader-auth/CONTRACT.md`'s
  and are not redefined, duplicated or contradicted here. The host
  requirements in that contract (configuration, backup exclusion, cleartext
  policy, application id, the foreground hook) are constraints on FastReader.
- **Non-goals:** sync of positions or library; profile (`PUT /v1/reader/profile`)
  and any use of capabilities beyond showing them; native Google sign-in
  (A83-F005); link-based sign-in or recovery; any account-gated reading
  feature; production backend; cutting a release (the gate is redefined; a
  version bump and release are separate work); consumer-facing polish of the
  account surface beyond the accessibility bar.
- **Release channel** unchanged: signed APK on GitHub Releases, when a
  release is cut.

## Evidence

Direct evidence, pinned at `820ef28ae97ec13b90c87bfb09fa312f037b9fc4`
unless stated:

- `reader-auth/CONTRACT.md` — the client contract: methods in preference
  order, pre-auth wiring guard, Keystore store in the no-backup directory,
  refresh policy, error policy, sign-out semantics, and the five host
  requirements. `reader-auth/src/main/AndroidManifest.xml` declares
  `android.permission.INTERNET` for every host.
- `reader-auth/src/main/kotlin/com/cedagova/reader/auth/ReaderAuthClient.kt`
  — the operations a host can offer: `requestEmailCode(email, createUser)`,
  `verifyEmailCode`, `signInWithPassword`, `requestRecoveryCode`,
  `verifyRecoveryCode`, `setPassword`, `capabilities()`, `onForeground()`,
  `signOut()`, `signOutOtherDevices()`; `ReaderSessionState` is
  `Initializing | SignedOut | SignedIn(userId, email, expiresAt)`.
- `reader-auth-host/` — the proving-ground host: reads
  `reader.supabaseUrl`, `reader.supabasePublishableKey`, `reader.apiBaseUrl`
  from `local.properties`/environment into `BuildConfig`; manifest with
  all-domain backup exclusion; debug-only network security configuration
  allowing cleartext to `10.0.2.2` only; `HostManifestTest`, `HostConfigTest`.
- `docs/evidence/93/README.md` (2026-09-13, head `d9fdb7e7`) — stage
  pre-auth and provider rejection proven from the host on `Phone_Mid_API36`;
  not-configured proven; the emailed-code run deferred by owner decision
  ("merge without evidence — owner will test on stage").
- `app/src/main/AndroidManifest.xml` — no `<uses-permission>`; backup
  exclusion attributes; comments citing REQ-107/REQ-303.
  `app/src/main/res/values/strings.xml` `settings_privacy` and
  `docs/privacy-statement.md` — the current statement beginning "FastReader
  has no internet permission"; `PrivacyStatementTest` keeps the string, the
  README block and the release-notes block equal; the Spanish copy is
  hand-edited.
- `README.md` lines 7–10 ("no internet permission at all") and 120–127 (the
  host is "a place to prove network-backed work without touching
  FastReader").
- `scripts/release.sh` — dies on `uses-permission: name='android.permission.INTERNET'`
  citing REQ-050; `docs/release.md` table row "No `android.permission.INTERNET`".
- `docs/product-definitions/cedagova-fastReader-1/definition.md` REQ-050
  ("Everything on device; no telemetry, no network transmission of reading
  data"). REQ-303 ("All data stays on device; no network permission") and
  REQ-107 (nothing stored is backed up; the privacy statement says so) are
  from `docs/product-definitions/cedagova-fastReader-36/definition.md` at
  PR #37 head `da4394c35e305a4e2a1d6516cababf126fa277c0`, delivered by #46
  under root #38.
- Audit A83 (`docs/audits/A83-android-client-auth/`, PR #83; outcomes #92
  and #93 closed; umbrella #94 open) — finding A83-F007 recorded that the
  no-network guarantee kept auth out of FastReader; this definition is the
  owner's reversal of that constraint.

Inference: the emailed-code stage run needs a person to read the code from
an inbox, so the success measure is an owner-driven device run, not an
automated gate. Assumption: stage continues to declare `reader-android`
1.0.0 compatible during planning and delivery.

## Assumptions

- The three stage values are available to the owner (published by the stage
  web app) and will be placed in the untracked `local.properties` on the
  development machine; the hosted runner never has them.
- The stage identity provider's email template delivers a six-digit code
  (`{{ .Token }}`) to the owner's inbox within the code's validity window.
- The existing three-place privacy statement mechanism and its test are
  kept rather than replaced (issue #78 about the test's declared inputs is
  separate).
- Earlier release notes (`docs/release-notes/v*.md`) are historical records
  and keep their old statement.

## Owner decisions

| Date | Decision | Rationale | Affects |
| --- | --- | --- | --- |
| 2026-09-13 | FastReader integrates Reader sign-in; the no-network promise is replaced, not preserved ("that's the whole point"). | FastReader is the point of the repository; A83-F007's constraint is reversed by the owner. | Objective, REQ-409–REQ-411 |
| 2026-09-13 | "i need fastreader as a testing app": FastReader is the owner's own app for exercising the Reader backend (stage) end to end; not shipped to consumers. Promise, statement and gate are updated truthfully with minimum ceremony. | Sets the audience and the depth of every truthfulness requirement. | Actors, REQ-407, REQ-409–REQ-411, non-goals |
| 2026-09-13 | Network access: whatever the backend contract needs now and later — auth now; sync and other capabilities as later definitions add them. | Avoids re-litigating the permission per feature; the statement names what is sent today. | Privacy section, REQ-409 |
| 2026-09-13 | Sign-in is optional; the app is fully usable signed out and offline. | Offline-first reading stays the default. | REQ-401, REQ-408 |
| 2026-09-13 | Scope: account, session and capabilities discovery; later definitions add the rest. | Smallest end-to-end proof of the client contract. | Desired outcomes, REQ-407, non-goals |
| 2026-09-13 | Client kind `reader-android` 1.0.0; no FastReader-specific kind. | Stage already declares it; FastReader is the Android reader for now. | REQ-413 |
| 2026-09-13 | Backend target: stage only; production promotion is a Chunipers decision outside this definition. | Production is not promoted. | Constraints |
| 2026-09-13 | "retire, unnecesary": `:reader-auth-host` is retired in this delivery — module dropped, its two host proofs move to FastReader, `docs/evidence/93/` points at FastReader for the deferred stage run. All lead drafting choices below stand. | One host, one set of proofs; the README sentence justifying the host stops being true once FastReader is the client. | REQ-410, REQ-414, desired outcome 7 |

Lead drafting choices, recorded as overturnable (not owner decisions):

- Every contract sign-in method is exposed, not only email code, because a
  testing app should exercise the whole contract (REQ-402).
- Capabilities are shown raw, in full, with the request id, rather than
  summarised (REQ-407).
- Sign out other devices is offered, since it is part of the contract's
  session semantics and costs nothing (REQ-406).
- The account surface lives under Settings, beside the privacy statement,
  so the promise and the thing it describes sit together (REQ-401).
- The release gate's new proof is "INTERNET and nothing else, no cleartext"
  rather than a pinned list of contacted hosts: a network security
  configuration governs cleartext and trust, not which hosts may be reached,
  so a host allowlist would not be a truthful proof (REQ-411).

## Remaining uncertainty

- Exact placement and wording within Settings, and the visual treatment of
  the capabilities document — design choices inside REQ-401/REQ-407.

## Product issue graph

| Key | Kind | Parent | Title | Issue |
| --- | --- | --- | --- | --- |
| ROOT | ROOT | None | FastReader signs in to a Reader account through :reader-auth (stage) | https://github.com/cedagova/fastReader/issues/100 |

The root owns REQ-401 to REQ-414; there are no outcome children.

## Publication verification

- Requirements Brief published: https://github.com/cedagova/fastReader/issues/100#issuecomment-5657177459
- Graph: single root, no children; root issue metadata written and
  `definition verify-graph` valid (1 row, 2026-09-13).
- Owner approval and semantic-anchor review: recorded on the PR against the
  presented head.
- Next action: `plan https://github.com/cedagova/fastReader/issues/100`.
