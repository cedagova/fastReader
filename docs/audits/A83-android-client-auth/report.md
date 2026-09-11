# Android client authentication through reader-api

- Audit ID: `A83`
- Audit key: `android-client-auth`
- Status: Decision ready
- Dossier PR: https://github.com/cedagova/fastReader/pull/83
- Started: 2026-09-11
- Decision owner: Cesar Gonzalez (cedagova)
- Lead investigator: Claude (audit-lead, cedagova)
- Independent reviewer: cedagova-codex-reviewer[bot] (audit-reviewer; approved review https://github.com/cedagova/fastReader/pull/83#pullrequestreview-5180254937 on the revision-2 head; the Decision-ready fields below are the candidate presented for the exact-head verdict)

## Question

What must a native Android client do, and what must Chunipers/reader-api (with reader-db / Supabase Auth where needed) provide, so that the client can sign in, keep a session, refresh it, and call protected routes with the same identity model reader-web uses today, and which gaps stand in the way?

## Why this matters

The owner intends to build a native Android reader on the Chunipers backend. This
repository (a personal, offline speed reader) is a candidate proving ground for
the client half of that work, so the backend auth contract can be exercised and
fixed before the real Android reader exists. The decision this audit supports is
threefold: which sign-in and session model a native Android client should adopt,
which backend and identity-provider changes must land first, and whether this
repository can host that experiment at all given its own product guarantees.

Findings that require `Chunipers/*` changes are recorded here, with their
expected implementation repositories, and carried across by the owner. This
audit writes nothing to any Chunipers repository.

## Targets and baselines

| Repository | Full commit SHA |
| --- | --- |
| `cedagova/fastReader` | `752cfcc9a93d767d56117a4ce11b31a509d7b084` |
| `Chunipers/reader-api` | `fbaa90db5495aa7b6cf995bceb3a54e2f6039ff9` |
| `Chunipers/reader-db` | `9391b283f2a31fff1b664304104338748d881739` |
| `Chunipers/reader-web` | `15a35625e715cd047eed6c2f887c42e647722b95` |

The three Chunipers baselines are the `origin/stage` refs held by the local
clones on 2026-09-11 (last fetched 2026-09-10 for reader-api, 2026-09-08 for
reader-db, 2026-09-11 for reader-web) and were inspected as `git archive`
exports of exactly those commits. `cedagova/fastReader` is `origin/main`.
`Chunipers/reader-web` is evidence only (the reference client); it is listed as
an expected implementation repository only where a finding's evidence points
at a reader-web change.

## Scope

### Included

- `reader-api` bearer-token verification: JWKS source, algorithms, claims,
  error contract, rate limits, and every route's authentication requirement.
- `reader-api` pre-sign-in bootstrap (`GET /v1/reader/pre-auth`) and
  post-sign-in capabilities (`GET /v1/reader/capabilities`) as they bind a
  client to the identity provider.
- `reader-db` Supabase Auth configuration: local `config.toml`, the hosted
  provider contract and its parity tool, email templates, and the database
  identity model (`auth.users` → `profiles`, RLS, the `reader_api` actor path).
- `reader-web` sign-in, session, refresh, API-call, and sign-out behaviour as
  the reference contract a native client must match.
- Android-side token acquisition, storage, refresh, and transport patterns,
  evaluated in general for any Android client from primary sources.
- This repository's product guarantees that constrain its use as a proving
  ground (network permission, backup exclusion, privacy statement).

### Excluded

- Any UI or product behaviour of a specific Android app, including this one.
- Non-auth `reader-api` features (AI help, sync semantics, imports) beyond
  their authentication requirement.
- Deployment topology, Doppler/Render configuration values, and hosted
  Supabase dashboard state (not derivable from any repository; recorded as
  owner questions).
- Implementation planning and task design.

### Scope changes

- 2026-09-11, owner direction at Decision ready (revision 5): evaluate the
  Chunipers-side findings as a move to a multi-thin-client architecture,
  not as patches to a web-first design; and for the client-side findings,
  optimise for the long-term Reader product, treating this repository's own
  product constraints as irrelevant to the outcome. The evidence and
  finding boundaries are unchanged; recommended outcomes, planning inputs,
  and the new root-cause finding A83-F010 reflect the direction.

## Methods

- **Pinned source reading** of the four targets at the SHAs above: proves what
  the code does on the baseline (graded Direct).
- **Offline test execution** of reader-api's auth suites from the pinned
  export (`uv run --offline --locked -m pytest tests/test_auth.py
  tests/test_security.py tests/test_dependencies.py tests/test_rate_limit.py
  tests/test_reader_pre_auth.py`; 81 passed, Python 3.14.3): proves the
  documented error contract is enforced, not just written.
- **Contract cross-reading** between reader-web's binding check and
  reader-api's pre-auth projection: proves the multi-client behaviour that
  neither repository tests on its own.
- **Pinned-dependency reading** (PyJWT 2.13.0, `@supabase/auth-js` 2.112.4
  version pins): supports claims about default behaviour (graded Indirect).
- **Primary-source research** for Android practice (Android developer
  documentation, Supabase documentation and SDK repositories, OWASP MASTG,
  RFCs): supports the client-side recommendations (graded Direct when the
  official document states it).
- Four lead-sponsored specialists worked disjoint surfaces (reader-api,
  reader-db, reader-web, Android practice). The lead re-read every cited line
  that a finding rests on before recording it; specialist packets are private
  working material and are not evidence by themselves.

## Coverage inventory

| Surface | Scope | Evidence examined | Result |
| --- | --- | --- | --- |
| reader-api token verification (`app/core/auth.py`, `app/api/auth_dependencies.py`, `app/main.py`) | In | Full read; 81 auth tests run offline | Client-agnostic; see F003, F006 |
| reader-api route authentication (every `app/features/*/api.py`, `contracts/reader-api.openapi.json`) | In | Route table built from decorators and OpenAPI `security` | Bearer everywhere except health, pre-auth, VAPID key, guest `/books`; F003 |
| reader-api pre-auth and capabilities (`app/features/reader_pre_auth`, `reader_capabilities`, `app/core/settings.py`) | In | Full read | Single static redirect allow-list; F001 |
| reader-api edge behaviour (`app/security.py`, `Dockerfile`, `render.*.yaml`, README) | In | Full read of security middleware; env names only | Web-only client id; proxy-keyed limiter; F004, F006 |
| reader-api documentation (`README.md`, `docs/error-codes.md`, `docs/reader-web-contracts.md`, `docs/client-artifacts.md`, `docs/functional-tests/auth.md`) | In | Full read | Accurate core contract; no native sign-in narrative; F003 |
| reader-db local auth config (`supabase/config.toml`) | In | Full `[auth]` block | Web redirect list; no Google locally; F002, F005 |
| reader-db hosted contract and parity tool (`supabase/auth/provider-contract.json`, `tools/auth-provider-config.mjs`, `.github/workflows/auth-provider-hosted.yml`, `docs/auth-provider-operations.md`) | In | Full read; safe local check of the URL validator | Exact HTTPS web allow-list; validator rejects custom schemes; JWT/session/Google-client fields unmanaged; F002, F005 |
| reader-db email templates (`supabase/auth/templates/*.html`) | In | Full read | Sign-in/sign-up carry link and 6-digit code; recovery is link-only; F002 |
| reader-db identity schema (16 migrations, `src/supabase/client.ts`) | In | Full read of identity-relevant SQL; grep for claims/hooks | Identity is `sub` only; no client-specific schema; no DB change indicated |
| reader-web auth package (`packages/auth/src/*`), runtime wiring (`src/app/auth*`), API transport (`packages/clients/src/*`), config (`packages/config/src/*`) | In | Full read | Reference contract extracted; origin-bound binding; F001, F003 |
| reader-web hosting (`nginx/default.conf.template`, `public/`) | In | Full read of auth routes; listing of `public/` | No server-side auth; no `/.well-known/assetlinks.json` served; F002 |
| reader-web tests pinning auth (`packages/auth/src/*.test.ts`, `src/app/*.test.ts`, `e2e/auth-*.spec.ts`) | In | Titles and mocked endpoints read; not run | Refresh grant, refresh failure, PKCE exchange, and 401-after-sign-in untested; F008 planning input |
| Android practice: Supabase Kotlin SDK, Android Keystore/Jetpack Security, Auto Backup, Credential Manager, App Links, OWASP MASTG | In | Primary sources retrieved 2026-09-11 | See F005, F008 |
| fastReader manifest, backup rules, release gate, privacy statement, product definition (`app/src/main/AndroidManifest.xml`, `res/xml/*.xml`, `scripts/release.sh`, `docs/privacy-statement.md`, `docs/product-definitions/cedagova-fastReader-1/definition.md`) | In | Full read | No-network guarantee is a product requirement with a release gate; F007 |
| Hosted Supabase dashboard state (JWT signing keys, `jwt_exp`, session limits, Google client IDs) | Excluded | Not accessible from any repository | Recorded as owner questions |
| reader-api LLM, sync, import, notification internals | Excluded | Authentication requirement only | Not examined beyond route table |

## Findings

### Finding index

| ID | Title | Decision | Confidence | Review | Planning readiness | Outcome issue | Outcome umbrella |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `A83-F001` | The pre-auth bootstrap is a single-client projection: one static redirect allow-list that reader-web requires to match its own set exactly | Candidate | High | Corroborated | Ready | Not required | Not required |
| `A83-F002` | No native redirect destination exists in the identity provider's allow-list, the reader-db contract validator rejects custom schemes, and password recovery is link-only | Candidate | High | Corroborated | Ready | Not required | Not required |
| `A83-F003` | The core bearer contract already works for any client, but the only sign-in narrative is web-only and the native integrator surface is undocumented | Candidate | High | Corroborated | Ready | Not required | Not required |
| `A83-F004` | reader-api cannot name a native client: the client identifier allow-list is `reader-web` only | Candidate | Medium | Corroborated | Ready | Not required | Not required |
| `A83-F005` | Native Google sign-in depends on identity-provider settings that no repository manages, and local development has no Google provider at all | Candidate | Medium | Corroborated | Ready | Not required | Not required |
| `A83-F006` | The token verifier has no clock leeway and no anonymous-identity policy, so a device with a skewed clock is signed out instead of refreshed and an anonymous session would be a full actor | Candidate | Medium | Corroborated | Ready | Not required | Not required |
| `A83-F007` | This repository's no-network product guarantee (REQ-050) is enforced by a release gate and a published privacy statement, so the Android auth work cannot live inside the FastReader app | Candidate | High | Corroborated | Ready | Not required | Not required |
| `A83-F008` | The Android client contract: OTP-code sign-in, Keystore-backed session storage excluded from backup, single-flight refresh with margin, and a fixed 401 policy | Candidate | High | Corroborated | Ready | Not required | Not required |
| `A83-F009` | The pre-auth rate limiter keys on the TCP peer address behind a proxy that strips forwarding headers, so all callers may share one bucket | Candidate | Low | Corroborated | Ready | Not required | Not required |
| `A83-F010` | The platform has no first-class notion of a client kind: the web client's identity is hard-wired in five places across three repositories, which is the root cause behind F001, F002, F004, and F005 | Candidate | High | Corroborated | Ready | Not required | Not required |

## A83-F001 — The pre-auth bootstrap is a single-client projection: one static redirect allow-list that reader-web requires to match its own set exactly

- Decision: Candidate
- Confidence: High
- Review: Corroborated
- Planning readiness: Ready
- Cause status: Confirmed
- Expected implementation repositories: `Chunipers/reader-api`, `Chunipers/reader-web`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

A backend that serves more than one kind of client should be able to advertise
each client's redirect destinations without the advertisement for one client
breaking another. reader-api's own README already describes the pre-auth
surface as the "exact native redirect allowlist" and a "public native-client
identity", so the intended standard is multi-client.

### Condition and evidence

- Direct — reader-api projects `allowedRedirectUris` straight from one
  deployment-wide environment list: `app/features/reader_pre_auth/service.py:174-176`
  sets `allowedRedirectUris=self.settings.reader_pre_auth_redirect_uris`, and
  `app/core/settings.py:277-279` defines that list from
  `READER_PRE_AUTH_REDIRECT_URIS`. Nothing in `app/features/reader_pre_auth/`
  reads `X-Reader-Client`, a client parameter, or the request origin (grep for
  `reader-client`, `client_id`, `x_reader_client` in that package: no hits).
- Direct — the validator already accepts native forms: HTTPS, loopback HTTP,
  and reverse-domain private-use schemes with a path
  (`app/core/settings.py:33-52`), and README lines 99-114 document
  `READER_PRE_AUTH_REDIRECT_URIS` as "Comma-separated exact native redirect
  allowlist".
- Direct — reader-web fails closed unless that list equals exactly the eight
  web URLs it derives from `window.location.origin`:
  `src/app/authEntryPolicyRuntime.ts:16-33` builds the expected set
  (`/auth/callback?purpose={signup,signin}&locale={en,es}` and
  `/reset-password?purpose={reset,setup}&locale={en,es}`), `:82-97` returns
  `unavailable('binding_mismatch')` unless `hasExactMembers(allowedRedirectUris,
  expectedCallbacks)`, and `:35-39` defines `hasExactMembers` as same length,
  no duplicates, every expected member present. Unit test
  `src/app/authEntryPolicyRuntime.test.ts:136` pins the fail-closed behaviour
  on a changed allow-list.
- Direct — reader-web also requires `environment.apiOrigin`, `authorityOrigin`,
  `publicClientId`, and revision `2026-09-05.2` to match its own configuration
  (`authEntryPolicyRuntime.ts:82-91`). Those values would be the same for a
  native client; only the redirect set differs.
- Inference — therefore adding even one native redirect URI to the deployed
  `READER_PRE_AUTH_REDIRECT_URIS` makes every reader-web account-entry screen
  report `binding_mismatch` and refuse sign-in, while omitting it leaves a
  native client with an allow-list it cannot use. Neither repository has a
  test for two clients sharing one projection.
- Direct (reviewer R4, corroborated by the lead) — the same single-client
  shape applies to the client-version gate on the same documents:
  `READER_PRE_AUTH_MIN_CLIENT_VERSION` / `READER_PRE_AUTH_SUPPORTED_CLIENT_MAJOR`
  (`app/core/settings.py:283-288`) and
  `READER_CAPABILITY_MIN_CLIENT_VERSION` / `READER_CAPABILITY_SUPPORTED_CLIENT_MAJOR`
  (`:198-203`) are one deployment-wide floor and major, README lines 94 and
  111 describe the pre-auth pair as the "native Reader client" floor, and
  reader-web pins its own version to the constant `'1.0.0'`
  (`packages/clients/src/api/readerPreAuthClient.ts:10`,
  `readerCapabilitiesClient.ts:15`). Raising the floor or major for native
  clients would lock reader-web out, and a native client with its own
  version line cannot be gated independently.

### Cause

Confirmed. The pre-auth contract was shaped for a native client but the only
consumer shipped so far is reader-web, whose binding check treats the
deployment-wide list as its own exact set. The projection has no notion of
client kind.

### Effect

A native client cannot be enabled on stage or production without either
breaking reader-web sign-in or shipping the native client with a redirect
allow-list it does not satisfy. Any flow that needs a redirect (OAuth, email
links) is blocked at bootstrap; the OTP-code path is unaffected because it
needs no redirect, but the client would still receive a binding it cannot
honour.

### Recommended outcome

The bootstrap and capabilities documents are projections of a registered
client kind (F010): each kind receives its own redirect destinations,
version floor, and public client identity, derived from one declaration
rather than from flat deployment-wide settings, and every client validates
only its own entry. reader-web's fail-closed binding stays, but it binds to
the web entry, not to the whole list. This replaces the web-first shape; it
is not a second list bolted beside the first.

### Outcome boundary

In: how reader-api selects and serves the redirect allow-list and the
client-version floor/major (and, if the owner prefers, `publicClientId`) per
client kind, on both the pre-auth and capabilities documents; reader-web's binding check
only insofar as its exact-set semantics must be reconciled with whatever
reader-api serves; tests that prove two clients can coexist. Out: which native
redirect destinations exist in the identity provider (F002), the client
identifier allow-list used for telemetry (F004), and any Android code.

### Cohesion rationale

This is one contract decision (how one bootstrap document serves several
clients) with two coupled implementations. Splitting it by repository would
leave each half untestable on its own.

### Outcome acceptance

- A native client requesting `GET /v1/reader/pre-auth` receives a redirect
  allow-list containing only native destinations, and reader-web requesting
  the same route continues to receive exactly its eight web destinations.
- reader-web's account entry remains available (no `binding_mismatch`) on a
  deployment where native destinations are also configured.
- A test in reader-api proves both projections from one configuration, and a
  test in reader-web proves its binding still fails closed on a foreign set.
- A native client with a version below its own floor is refused while
  reader-web at `1.0.0` is still served, on the same deployment.

### Planning inputs

- Affected surfaces: `app/features/reader_pre_auth/service.py`,
  `app/features/reader_capabilities/service.py:69-82, 111-132` (version gate),
  `app/core/settings.py` (`READER_PRE_AUTH_REDIRECT_URIS`,
  `READER_PRE_AUTH_MIN_CLIENT_VERSION`, `READER_PRE_AUTH_SUPPORTED_CLIENT_MAJOR`,
  `READER_CAPABILITY_MIN_CLIENT_VERSION`, `READER_CAPABILITY_SUPPORTED_CLIENT_MAJOR`),
  `app/contracts/reader_pre_auth.py` (if the document shape changes),
  `contracts/reader-pre-auth.v1.examples.json`, the OpenAPI contract and the
  generated Kotlin/TypeScript clients; `src/app/authEntryPolicyRuntime.ts` and
  its tests in reader-web.
- Constraint: reader-web's accepted revision is a constant
  (`acceptedReaderPreAuthRevision = '2026-09-05.2'`); a shape change forces a
  revision bump and a coordinated reader-web release.
- Options the planner must weigh: key the projection by `X-Reader-Client`
  (see F004), by an explicit query parameter, or by serving a per-client map
  in one document; keep reader-web's exact-set check as is versus relax it to
  a subset check. The lead does not select.
- Dependency: F002 decides which native destinations exist; F004 decides how a
  native client names itself.

### Limitations

- The deployed `READER_PRE_AUTH_REDIRECT_URIS` value was not read (Doppler
  state); the finding rests on the code path, not on an observed production
  failure.
- No live request was issued against stage.

### Decision rationale

Pending.

## A83-F002 — No native redirect destination exists in the identity provider's allow-list, the reader-db contract validator rejects custom schemes, and password recovery is link-only

- Decision: Candidate
- Confidence: High
- Review: Corroborated
- Planning readiness: Ready
- Cause status: Confirmed
- Expected implementation repositories: `Chunipers/reader-db`, `Chunipers/reader-web`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

Every sign-in flow the identity provider offers should be completable from a
native client. Supabase Auth only follows redirects that are on the project's
allow-list, so a native client needs at least one destination it can claim
(an HTTPS App Link or a private-use scheme), and every email the provider
sends must carry something the client can consume without a redirect, or a
redirect the client owns.

### Condition and evidence

- Direct — the hosted allow-list is exactly eight HTTPS web URLs per
  environment: `tools/auth-provider-config.mjs:134-146` expands
  `callbacks.<env>.origins` × purposes × locales, and `:480` writes it as
  `uri_allow_list`; origins are `https://stage.chunipers.com` and
  `https://chunipers.com` (`supabase/auth/provider-contract.json:36-50`).
  Locally `supabase/config.toml:154-171` lists the same shapes on
  `127.0.0.1:5173` / `localhost:5173`.
- Direct — the contract validator cannot admit a custom scheme:
  `assertUrlOrigin` (`tools/auth-provider-config.mjs:124-131`) requires
  `new URL(value).origin === value` and HTTPS when hosted. Safe local check:
  `node -e 'console.log(new URL("com.example.app://auth/callback").origin)'`
  prints `null`, so a private-use scheme fails the origin equality. Purposes
  and locales are fixed constants (`:13-14`); callbacks must contain exactly
  `origins` and `siteUrl` (`:379`); the schema is pinned
  `reader.supabase-auth.v1` (`:336`).
- Direct — hosted changes flow only through this tool: the parity workflow
  applies to stage on push and to production by manual dispatch
  (`.github/workflows/auth-provider-hosted.yml:3-32, 70-92`); no
  `supabase config push` exists in `scripts/`.
- Direct — sign-up and sign-in emails carry both a link and a code:
  `supabase/auth/templates/confirmation.html` and `magic-link.html` contain
  `{{ .ConfirmationURL }}` and `{{ .Token }}` (lines 4-6 and 12-14, EN and ES).
  Password recovery does not: `recovery.html` contains only
  `{{ .ConfirmationURL }}` (lines 4, 8, 12, 16, 20). The validator requires the
  token only in confirmation and magic-link templates
  (`tools/auth-provider-config.mjs:397-398`).
- Direct — reader-api's redirect validator already accepts private-use
  schemes and HTTPS App-Link style URLs (`app/core/settings.py:33-52`), so the
  API side is ready for either choice once F001 is resolved.
- Direct — the web host serves no `/.well-known/assetlinks.json`:
  `nginx/default.conf.template` has no `well-known` location and
  `reader-web/public/` contains no such file (listing: `dictionaries`,
  `env.js`, `favicon.svg`, `fonts`, `icons`, `library-seeds`,
  `pwa-screenshots`, `reader-host-sw.js`). Android App Links on
  `chunipers.com` would require it.
- Inference — consequences: (a) a native client can complete sign-up and
  sign-in today with the 6-digit code and no redirect; (b) it cannot complete
  password recovery or web-redirect OAuth in-app without a new allow-listed
  destination; (c) the two ways to add one are an HTTPS App Link on the
  existing origins (reader-web must host `assetlinks.json`; the URL shapes
  already exist) or a private-use scheme (reader-db contract schema and
  validator must change).

### Cause

Confirmed. The provider contract was written for the web client and encodes
that assumption in its validator and templates.

### Effect

Native clients are limited to email code sign-in and password sign-in until a
native redirect destination exists. Password reset from a native client ends
in the browser on the web reset page. Web-redirect OAuth cannot return to the
app.

### Recommended outcome

The identity-provider contract becomes client-aware: it declares, per client
kind and per environment, the redirect destinations and the sign-in methods
that kind uses, and both the hosted Supabase allow-list and reader-api's
per-client projection (F001) are generated from that one declaration through
the existing reviewed parity workflow. Every provider email offers a path
each declared client kind can complete (a code, or a redirect that kind
owns). Adding a client kind later (iOS, a second web surface) is then a
declaration, not a validator change.

### Outcome boundary

In: the reader-db provider contract, its validator, the hosted parity apply,
`config.toml` for local parity, the recovery template; the web host only for
serving `assetlinks.json` if the App Link route is chosen. Out: how reader-api
projects those destinations (F001), Google-specific provider settings (F005),
and the Android manifest that claims the link (client repository).

### Cohesion rationale

One decision (which native destination the provider trusts) determines the
validator change, the template change, and whether the web host participates.
The three move together or the native client still cannot finish a redirect
flow.

### Outcome acceptance

- `cedagova-infra`-style validation of the provider contract (the repo's own
  `tools/auth-provider-config.mjs` test suite) passes with a native
  destination present for stage and production.
- The hosted `uri_allow_list` on stage contains that destination after the
  parity workflow runs, observed through the tool's own probe output (field
  names only).
- A password-recovery email offers a path a native client can complete, or
  the owner records that recovery stays web-only as an explicit decision.
- If App Links are chosen: `https://chunipers.com/.well-known/assetlinks.json`
  is served with the correct content type and the Android statement for the
  client package.

### Planning inputs

- Affected surfaces: `supabase/auth/provider-contract.json` (schema and
  revision), `tools/auth-provider-config.mjs` (`assertUrlOrigin`,
  `expandCallbackDestinations`, `MANAGED_SAFE_FIELDS` unchanged),
  `tools/auth-provider-config.test.mjs`, `supabase/config.toml`
  `additional_redirect_urls`, `supabase/auth/templates/recovery.html`,
  `docs/auth-provider-operations.md`; reader-web `nginx/default.conf.template`
  and `public/.well-known/` for App Links.
- Decision the owner must make: App Link on the existing origins versus a
  private-use scheme. App Links reuse the eight existing URL shapes (zero
  provider change for sign-in/sign-up links, only recovery needs thought)
  but add a hosting dependency and Android verification (`autoVerify`,
  SHA-256 signing certificate in `assetlinks.json`). A private-use scheme is
  self-contained but is the weaker option per Android and Supabase guidance
  because any app can register the same scheme (see F008 sources).
- Migration concern: the provider contract revision and reader-web's
  `authProviderSecurityContract` test (`packages/auth/src/authProviderSecurityContract.test.ts:27`)
  both assert the redirect path allow-list; a new destination shape may need
  both updated.
- Dependency: F001 must let reader-api serve the new destination to native
  clients only.

### Limitations

- Hosted dashboard state was not read; the audit assumes the parity tool's
  enforced values are what stage and production carry.
- The reader-db export was not tied to issue #82 by content; the
  "password-free" change is evidenced by the contract revision `2026-09-08.1`
  and test names, not by an issue reference in the tree.

### Decision rationale

Pending.

## A83-F003 — The core bearer contract already works for any client, but the only sign-in narrative is web-only and the native integrator surface is undocumented

- Decision: Candidate
- Confidence: High
- Review: Corroborated
- Planning readiness: Ready
- Cause status: Confirmed
- Expected implementation repositories: `Chunipers/reader-api`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

A client integrator should be able to implement sign-in, session, refresh,
protected calls, and sign-out from the backend's own documentation and
contract artifacts, without reading reader-web's source.

### Condition and evidence

The contract as it exists (all Direct unless marked):

- Verification: `SupabaseTokenVerifier` accepts `ES256` and `RS256` only,
  caches JWKS 600 s, and refreshes once per 60 s on an unknown `kid`
  (`app/core/auth.py:92-115`); JWKS URL defaults to
  `<SUPABASE_URL>/auth/v1/.well-known/jwks.json` (`app/core/settings.py:846-849`);
  `jwt.decode(..., audience="authenticated", issuer=f"{supabase_url}/auth/v1")`
  with no leeway and no required-claims option (`app/core/auth.py:288-299`,
  `app/main.py:101-108`); only `sub` is read (`app/core/auth.py:311-315`); the
  claims dict is carried but no feature consumes it.
- Errors: 401 with `WWW-Authenticate: Bearer` and body
  `{code: auth.<missing_token|malformed_token|expired_token|invalid_token|missing_sub_claim>, message, category: "auth", retryable: false, request_id}`
  (`app/api/auth_dependencies.py:31-59, 153-166`; `app/api/errors.py:202-221`);
  JWKS failure is 502 `auth.jwks_dependency_failed`, `retryable: true`, also
  with `WWW-Authenticate` (`tests/test_security.py:587-641`). Offline run of
  the five auth suites: 81 passed.
- Issuance and refresh: none in the API. `grep` for `jwt.encode`,
  `refresh_token`, `grant_type`, `logout`, `sign_out` in `app/`: zero hits. The
  pre-auth document states `submissionAuthority: "identity_provider"`
  (`app/contracts/reader_pre_auth.py:57-60`). The dev helper obtains a token
  with `POST <SUPABASE_URL>/auth/v1/token?grant_type=password` and an `apikey`
  header (`scripts/dev_auth_token.py:102-121`).
- Routes: public `GET /health`, `GET /v1/reader/pre-auth`,
  `GET /notifications/vapid-public-key`, and `GET /books*` (optional bearer,
  else a guest cookie); static probe token on `GET /health/ready/machine`;
  every other route is bearer, with AI routes also under a per-user 60/min
  limiter (`require_api_access`). OpenAPI declares exactly `HTTPBearer` and
  `BookGuestCookie` (`tests/test_openapi_contract.py:26-27`).
- Post-sign-in expectations: first call `GET /v1/reader/capabilities?clientVersion=X.Y.Z`
  (`app/contracts/reader_pre_auth.py:99-105`); `clientVersion` omitted makes
  every capability unavailable (`app/features/reader_capabilities/service.py:111-132`);
  `GET /v1/reader/profile` is 404 `reader_product.not_found` until
  `PUT /v1/reader/profile` upserts a row
  (`app/features/reader_products/repository_db.py:573-601`).
- Headers: `X-Request-ID` optional lowercase UUID, echoed
  (`app/security.py:184-195`); `X-Reader-Client` telemetry only (F004);
  `traceparent` honoured; body cap 1 MiB; no API key, user agent, or
  attestation is read anywhere; `READER_PRE_AUTH_CLIENT_SECRET` is actively
  rejected (`app/core/settings.py:723-731`).
- reader-web implements exactly this: `Authorization: Bearer` plus
  `x-reader-client` (`packages/clients/src/api/readerApiClient.ts:310-314`),
  10 s timeout, no retry, 401/403 mapped to a local-only state without
  sign-out (`packages/core/src/httpErrors.ts:33-60`,
  `src/app/capabilities/UserScopedReaderCapabilitiesProvider.tsx:276-280`).
- A Kotlin transport client is already generated from the checked-in OpenAPI
  contract (`scripts/publish_reader_api_clients.py:25-40`, openapi-generator
  7.17.0; `/openapi.json` is disabled in production, `app/main.py:384-386`).

What is missing or wrong for a native integrator (all Direct):

1. The only token-acquisition narrative is "sign in through reader-web" /
   stage (`README.md:254, 326`); no document describes a native flow
   (OTP code, PKCE, or ID-token) or the refresh loop the client owns.
2. `publicClientId` in the pre-auth document has no stated meaning for a
   native client (Supabase publishable key versus an OAuth client id);
   README line 102 calls it "Public native-client identity" without saying
   what value it carries (`app/features/reader_pre_auth/service.py:167-170`).
3. README line 250 lists only 401 outcomes; the 502 JWKS path appears only in
   `docs/error-codes.md:49`.
4. OpenAPI 401 responses declare no `WWW-Authenticate` header, so the
   generated Kotlin client does not type it.
5. `docs/reader-web-contracts.md:143` says the API validates the subject as a
   UUID; only the products, sync, and imports repositories do
   (`reader_products/repository_db.py:805-809`, `reader_sync/repository_db.py:1464-1468`,
   `publication_imports/repository_db.py:161-165`); vocabulary, notifications,
   and usage pass the raw string.
6. `docs/error-codes.md:47` documents `auth.forbidden` 403; the code is
   produced only by the generic status mapper (`app/api/errors.py:211-212`)
   and is listed in `tests/test_error_contracts.py:23`, but no route or
   dependency raises a 403 on the pinned baseline, so an integrator cannot
   learn from the docs when, if ever, to expect it. `:16` shows a non-UUID
   `request_id` example; `docs/functional-tests/auth.md:69` names a "Legacy
   User Header" test that no longer corresponds to code.
7. Nothing states that revocation is by expiry only: `grep` for `revoke`,
   `jti`, `denylist` in `app/` finds nothing, and README's security section
   does not say that a leaked access token stays valid until `exp` or name
   the hosted access-token lifetime an integrator must plan its refresh
   margin around.

### Cause

Confirmed. The backend was built client-agnostic, but its integrator-facing
documentation was written while reader-web was the only client.

### Effect

A native integrator must reverse-engineer reader-web to learn the refresh,
error, and bootstrap expectations, and will type the 401 contract from an
OpenAPI document that omits the header the server always sends. The
misstatements (UUID subject, an `auth.forbidden` code with no documented
trigger) lead to wrong client-side handling, and the unstated revocation
model leaves the client's refresh margin unanchored.

### Recommended outcome

The client contract is a published, versioned artifact per client kind,
alongside the OpenAPI document and the generated clients reader-api already
ships: bootstrap, sign-in authority, bearer, refresh ownership, error codes
including the 502 path, first calls after sign-in, `publicClientId`
semantics, and the statement that revocation is by expiry only together with
the hosted access-token lifetime. reader-web's contract is the first
instance of that artifact, not a special case, and the documentation
discrepancies above are corrected so a generated Kotlin client plus the
artifact is sufficient for any thin client.

### Outcome boundary

In: `README.md`, `docs/error-codes.md`, `docs/reader-web-contracts.md` (or a
new native-client contract document), `docs/functional-tests/auth.md`, the
OpenAPI 401 header declaration and the regenerated client artifacts. Out:
behaviour changes to verification (F006), client identification (F004), and
the multi-client projection (F001).

### Cohesion rationale

Every item is "what the backend tells an integrator"; none changes runtime
behaviour, and they are best corrected in one documentation and contract
pass.

### Outcome acceptance

- A reader-api document exists that a native integrator can follow from
  `GET /v1/reader/pre-auth` to sign-out without reading reader-web, and it
  names who owns refresh.
- The OpenAPI contract declares `WWW-Authenticate` on 401 responses and the
  regenerated Kotlin client exposes it.
- The seven discrepancies above are corrected or the code is changed to match.

### Planning inputs

- Affected surfaces: README auth sections (lines 54, 69-70, 99-114, 236-254,
  326), `docs/error-codes.md`, `docs/reader-web-contracts.md`,
  `docs/functional-tests/auth.md`, `app/api/openapi.py` or the route response
  declarations, `contracts/reader-api.openapi.json`, `docs/client-artifacts.md`.
- The dossier's F008 records the client-side half of the contract; the
  reader-api document should agree with it.
- Open question for the owner: what value `READER_PRE_AUTH_PUBLIC_CLIENT_ID`
  carries today (name only, never the value).

### Limitations

- No live request was made to stage; the contract is proven by the pinned
  tests, not by an observed native call.

### Decision rationale

Pending.

## A83-F004 — reader-api cannot name a native client: the client identifier allow-list is `reader-web` only

- Decision: Candidate
- Confidence: Medium
- Review: Corroborated
- Planning readiness: Ready
- Cause status: Confirmed
- Expected implementation repositories: `Chunipers/reader-api`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

Telemetry, caching, and any future per-client policy should be able to
distinguish a native client from the web client and from unknown traffic.

### Condition and evidence

- Direct — `X-Reader-Client` is normalised to `"reader-web"` or `"unknown"` in
  three places: `app/security.py:221-223`, `app/api/request_logging.py:25-34`,
  `app/infra/observability/schema.py:455`; `tests/test_security.py:282-287`
  pins it. The header is not an OpenAPI parameter.
- Direct — the header never affects authorisation or the rate-limit key
  (`app/api/auth_dependencies.py:141-150` keys on `sub`;
  `tests/test_security.py` "IP bucket ignores X-Reader-Client").
- Direct — `GET /v1/reader/capabilities` sets
  `Vary: Authorization, X-Reader-Client` (`app/features/reader_capabilities/api.py:51`),
  so cache partitioning already assumes distinct client names.
- Direct (list corrected per reviewer R2 and R8) — reader-web already sends
  several distinct names. Its non-test source uses `reader-web`,
  `reader-web-browser`, `-capabilities`, `-library`, `-notes-support`,
  `-notifications`, `-products`, `-publication-imports`, `-reader-cache`,
  and `-sync` (plus a dev-login credentials key); four more values appear
  only in tests (`-exact-boundary`, `-local-book`, `-notes`,
  `-one-byte-over`). Only `reader-web` is recognised
  (`packages/clients/src/api/readerSyncClient.ts:38-47`,
  `readerCapabilitiesClient.ts:173-188`).
- Inference — a native client's traffic is recorded as `client_id="unknown"`,
  indistinguishable from probes and misconfigured callers; F001's per-client
  projection has no header to key on unless this list grows.

### Cause

Confirmed. The allow-list was introduced for observability with one client in
mind.

### Effect

No functional break for sign-in. Operationally, native adoption, errors, and
rate-limit pressure cannot be attributed, and per-client behaviour (F001)
cannot use the existing header.

### Recommended outcome

Client identification comes from the registered client model (F010): the
accepted identifiers are the declared client kinds (and their sub-surfaces,
if the owner wants them distinguished), the same identifier selects the
per-client projection in F001, and telemetry records it. The hard-coded
`reader-web` literal disappears from the three normalisers.

### Outcome boundary

In: the three normalisers, their tests, the OpenAPI header declaration if the
header becomes part of the contract, and the documentation of accepted
values. Out: any authorisation semantics for the header (it must remain
telemetry and projection selection, never a credential).

### Cohesion rationale

One allow-list, three copies, one documentation line; changing them together
is the only coherent unit.

### Outcome acceptance

- A request carrying the agreed native identifier is logged and metered with
  that identifier, not `unknown`, on stage.
- The accepted identifiers are listed in reader-api documentation and the
  OpenAPI contract.

### Planning inputs

- Affected surfaces: `app/security.py`, `app/api/request_logging.py`,
  `app/infra/observability/schema.py`, `tests/test_security.py`,
  `tests/test_dependencies.py`, README.
- Decide with F001 whether this header is the per-client projection key.
- Risk: the header is caller-controlled; it must never gate access.

### Limitations

- Medium confidence because the operational cost is inferred from logging
  code, not from observed dashboards.

### Decision rationale

Pending.

## A83-F005 — Native Google sign-in depends on identity-provider settings that no repository manages, and local development has no Google provider at all

- Decision: Candidate
- Confidence: Medium
- Review: Corroborated
- Planning readiness: Ready
- Cause status: Confirmed
- Expected implementation repositories: `Chunipers/reader-db`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

If Google is an offered sign-in method, a native Android client should be able
to use it through the platform's native path (Credential Manager producing a
Google ID token, exchanged with the identity provider) rather than a browser
redirect, and the settings that make that work should be reviewable in a
repository like the rest of the provider contract.

### Condition and evidence

- Direct — Google is enabled hosted: `supabase/auth/provider-contract.json:7`
  (`"google": true`) and `tools/auth-provider-config.mjs:451`
  (`external_google_enabled: true`); the web client id and secret are patched
  as secrets (`SECRET_PATCH_FIELDS`, `:64-68`).
- Direct — the fields a native ID-token exchange depends on are not in
  `MANAGED_SAFE_FIELDS` (`:28-62`): `external_google_additional_client_ids`
  and `external_google_skip_nonce_check` are absent, so they live only in the
  dashboard.
- Direct — locally there is no Google at all: `supabase/config.toml` has no
  `[auth.external.google]` block (grep: none; only `[auth.external.apple]`,
  disabled, at lines 320-333).
- Direct — reader-web uses the web redirect flow only: `signInWithOAuth`
  with `redirectTo` (`packages/auth/src/authClient.ts:134-154`); there is no
  `signInWithIdToken` anywhere in `packages/auth/src` (grep: none).
- Direct — reader-api's pre-auth provider set is `apple | google | password`
  (`app/core/settings.py:713-721`); it says nothing about which Google
  mechanism a client should use.
- Direct (primary source) — Supabase documents the native Android path as
  Credential Manager producing an ID token, then `signInWithIdToken`, and
  requires the Android-side client id to be registered with the provider and a
  nonce to be handled (see the Android practice sources in F008; Supabase
  "Login with Google" documentation retrieved 2026-09-11).
- Inference — a native client could fall back to reader-web's browser
  redirect flow, but that requires F001 and F002 to land first and gives the
  weaker mobile experience the platform guidance warns against.

### Cause

Confirmed. The provider contract manages exactly what the web client needs;
the native-only provider fields were never in scope.

### Effect

Native Google sign-in cannot be enabled reviewably: the required client id
registration would be a dashboard-only change with no parity check, and it
cannot be exercised on `supabase start`.

### Recommended outcome

Sign-in methods are declared per client kind in the client-aware provider
contract (F002): the web kind uses redirect OAuth, the Android kind uses the
platform ID-token exchange, and the provider fields each mechanism needs
(including the native client-ID registrations) are managed by the parity
tool like every other hosted setting. The local configuration offers the
same provider set and an asymmetric signing key so any client kind can be
developed offline against a stack reader-api accepts.

### Outcome boundary

In: `provider-contract.json` schema, `MANAGED_SAFE_FIELDS`, the validator and
its tests, `config.toml`, `docs/auth-provider-operations.md`. Out: the
Android-side Credential Manager code (client repository), the redirect
destination question (F002), and Apple sign-in (not enabled anywhere).

### Cohesion rationale

All items are "what the identity provider must accept from a native Google
sign-in", and the local-parity gap is the same change applied to the local
profile.

### Outcome acceptance

- The parity tool's enforced hosted values include the native Google client
  registration field(s), validated and applied through the existing stage →
  production workflow.
- A native ID-token sign-in against stage yields a session whose `sub` matches
  the same `auth.users` row a web Google sign-in produces for that account.
- Local `supabase start` exposes a Google provider configuration, or the
  operations document states that Google is stage-only for native
  development.

### Planning inputs

- Affected surfaces: `supabase/auth/provider-contract.json`,
  `tools/auth-provider-config.mjs`, `tools/auth-provider-config.test.mjs`,
  `supabase/config.toml`, `docs/auth-provider-operations.md`.
- Secret handling: the Android client id is public (it appears in the APK),
  but it is still a registration the provider must trust; treat it as a
  managed non-secret field.
- The `handle_new_user` trigger reads `full_name`/`name`/`avatar_url` from
  `raw_user_meta_data` (`supabase/migrations/20260514030000_reader_baseline.sql:197-198`);
  a native Google sign-in that yields different metadata keys would produce a
  profile with no display name. Verify on stage.
- Open owner question: does the hosted project already carry an Android
  client id under `external_google_additional_client_ids` (field name only)?
- Related local-parity gap (same file, same outcome if the owner wants an
  offline native loop): `supabase/config.toml:177` leaves `signing_keys_path`
  commented, so the local stack signs HS256 and reader-api (ES256/RS256 only)
  rejects every local token; see F008 for the source.

### Limitations

- Medium confidence: the dashboard state was not read, and Google was not
  exercised on any environment during the audit.

### Decision rationale

Pending.

## A83-F006 — The token verifier has no clock leeway and no anonymous-identity policy, so a device with a skewed clock is signed out instead of refreshed and an anonymous session would be a full actor

- Decision: Candidate
- Confidence: Medium
- Review: Corroborated
- Planning readiness: Ready
- Cause status: Confirmed
- Expected implementation repositories: `Chunipers/reader-api`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

A token verifier that will accept tokens presented by many devices with
untrusted clocks should tolerate a small, bounded clock skew, and it should
state which identities it accepts as full actors, including anonymous ones,
rather than leave that to whatever the identity provider happens to issue.

### Condition and evidence

- Direct — no leeway: `jwt.decode` is called without `leeway` or `options`
  (`app/core/auth.py:288-299`); PyJWT 2.13.0 defaults to `leeway=0` and
  enforces `exp`, `nbf`, and `iat` when present (Indirect, from the pinned
  dependency; the reviewer reproduced that a future `iat` yields
  `auth.invalid_token`). A device whose clock runs ahead of the server
  presents a freshly issued token whose `iat` is in the future and receives
  401 `auth.invalid_token`, which the client contract (F008) treats as
  "re-authenticate", not "refresh". Supabase's own guidance notes that user
  devices "can sometimes be off by minutes or even hours" (F008 sources).
- Direct — no code reads `is_anonymous` (grep in `app/`: none). Anonymous
  sign-in is disabled in the reader-db hosted contract
  (`provider-contract.json:9`; `tools/auth-provider-config.mjs` enforces
  `external_anonymous_users_enabled: false`), so the exposure is latent: if
  it were ever enabled, an anonymous session (`aud=authenticated`, UUID `sub`)
  would be a full Reader actor with no server-side distinction.
- Direct — both properties live in the same verifier and are covered by the
  same test module (`tests/test_auth.py:164-206` covers the claim checks that
  a leeway or anonymous policy would extend).

### Cause

Confirmed. The verifier was written for one browser client whose clock is
the user's desktop and for a provider profile that never issues anonymous
sessions; neither assumption holds for a mobile fleet.

### Effect

Mobile devices with skewed clocks are signed out instead of refreshed, which
the client cannot distinguish from a revoked session. Anonymous identity
remains an unstated policy that a provider setting change would silently
turn into full access.

### Recommended outcome

reader-api tolerates a small, bounded clock skew on time claims and states
its policy on anonymous identities (reject by default), both in code and in
the README security section.

### Outcome boundary

In: the verifier's decode options and claim checks (`app/core/auth.py`), the
tests that pin them, and the README lines that state the policy. Out: the
limiter key (F009), the revocation statement (F003 item 7), refresh-token
semantics (identity provider), the guest cookie path, and per-user AI
limits.

### Cohesion rationale

Both are decisions about which presented tokens the verifier accepts, made
in the same function and proven by the same test file; they are two lines of
policy, not two projects. The limiter and the revocation statement were
split out because they live on other surfaces (reviewer R1).

### Outcome acceptance

- A token with `iat` or `nbf` up to an agreed number of seconds in the future
  is accepted; the bound is documented and a test pins it.
- A token carrying `is_anonymous: true` is rejected with a documented code,
  or the policy to accept it is written down and tested.

### Planning inputs

- Affected surfaces: `app/core/auth.py`, `tests/test_auth.py`, README
  security section.
- Constraint: keep audience fixed at `authenticated` and algorithms at
  `ES256`/`RS256`; do not weaken those. A leeway of at most a couple of
  minutes matches RFC 7519 §4.1.4 ("usually no more than a few minutes").
- The client-side refresh margin (F008) should be set after the leeway is
  chosen.

### Limitations

- Clock-skew impact is inferred from PyJWT defaults; no device with a skewed
  clock was tested against stage.
- Anonymous exposure is latent while the hosted provider keeps anonymous
  sign-in disabled.

### Decision rationale

Pending.

## A83-F007 — This repository's no-network product guarantee (REQ-050) is enforced by a release gate and a published privacy statement, so the Android auth work cannot live inside the FastReader app

- Decision: Candidate
- Confidence: High
- Review: Corroborated
- Planning readiness: Ready
- Cause status: Confirmed
- Expected implementation repositories: `cedagova/fastReader`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

A repository used as a proving ground for a network-backed feature must
either permit network access in the build that carries the experiment or
isolate the experiment so the shipped product's guarantees stay true.

### Condition and evidence

- Direct — the product definition requires it: `docs/product-definitions/cedagova-fastReader-1/definition.md:292`
  "REQ-050 Everything on device; no telemetry, no network transmission".
- Direct — the release gate enforces it: `scripts/release.sh:138-140` fails
  the release if the APK badging shows `android.permission.INTERNET`
  ("reading data must never leave the device (REQ-050)").
- Direct — the public documents promise it: `README.md:7-10` and
  `docs/privacy-statement.md:24, 47` state the app has no internet permission
  and cannot send or receive anything; the privacy statement is pasted into
  every release note.
- Direct — the manifest declares no permission at all
  (`app/src/main/AndroidManifest.xml`), and both backup rule files exclude
  every domain from cloud backup and device-to-device transfer
  (`res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`), which is
  the right posture for token storage (F008) but is currently justified by
  REQ-107, not by auth.
- Direct — the app has no HTTP client dependency (`gradle/libs.versions.toml`
  lists coroutines and kotlinx-serialization; no OkHttp, Ktor, Retrofit, or
  Supabase artifact).
- Inference — any auth experiment in this repository therefore requires
  `INTERNET`, an HTTP stack, and a place to keep it that the release gate and
  the privacy statement do not cover.

### Cause

Confirmed. The product was defined offline-only on purpose, and the guarantee
is mechanically enforced.

### Effect

The owner cannot simply add sign-in to this app. Doing so in the shipped
build would break the release gate, falsify the privacy statement, and change
the product's public contract.

### Recommended outcome

The proving ground is not the FastReader app. The Android auth work lives as
a standalone, reusable module with its own minimal host app and its own
application id, built to be lifted into the real Reader client unchanged.
FastReader's product, manifest, release gate, and privacy statement are left
exactly as they are. Owner direction (2026-09-11): optimise for the long-term
Reader product; this repository's own product constraints are irrelevant to
the outcome.

### Outcome boundary

In: where the reusable module and its host app live (this repository as a
separate Gradle module with its own application id, or a repository of its
own), and the build rules that keep it out of the FastReader artifact. Out:
any change to the FastReader app, its definition, or its guarantees; the
auth implementation itself (F008); anything in Chunipers.

### Cohesion rationale

One placement decision (a separate module and host app, untouched
FastReader) removes the whole conflict; splitting it into "gate", "statement",
and "manifest" work would only make sense if FastReader itself were to gain
network access, which the owner has ruled out.

### Outcome acceptance

- The reusable module and its host app build and run without any change to
  the FastReader application module, manifest, release script, or privacy
  statement.
- `scripts/release.sh` on the FastReader release APK still passes with no
  network permission.
- The module has no dependency on FastReader code, so the real Reader client
  can depend on it directly.

### Planning inputs

- Affected surfaces: `settings.gradle.kts` and a new module (or a new
  repository), a minimal host `AndroidManifest.xml` with `INTERNET` and its
  own backup exclusion rules; nothing under `app/`.
- Constraint: the module's host app carries the same full-domain backup
  exclusion FastReader already proves (`res/xml/data_extraction_rules.xml`,
  `backup_rules.xml`), because it stores tokens.
- Decide during planning, not here: same repository (dossier and issues
  already live here) versus a dedicated repository the Reader client will
  import.
- This is client-owned: it is handled through this repository's own
  definition and planning route, not carried to Chunipers.

### Limitations

- None material; every claim is a direct read of tracked files.

### Decision rationale

Pending.

## A83-F008 — The Android client contract: OTP-code sign-in, Keystore-backed session storage excluded from backup, single-flight refresh with margin, and a fixed 401 policy

- Decision: Candidate
- Confidence: High
- Review: Corroborated
- Planning readiness: Ready
- Cause status: Confirmed
- Expected implementation repositories: `cedagova/fastReader`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

A native Android client should implement the same identity model as
reader-web (a Supabase user, presented to reader-api as a bearer JWT) with the
platform's current storage and sign-in guidance, and it should be general
enough to lift into the real Android reader unchanged.

### Condition and evidence

What reader-web does that the client must match (Direct, reader-web):

- PKCE flow, `apikey` header with the publishable key, session persisted under
  `sb-<project-ref>-auth-token`, auto-refresh on, no URL detection
  (`packages/auth/src/supabaseClient.ts:23-29, 88-99`).
- Bootstrap verifies the stored access token against the project JWKS and
  refuses symmetric algorithms; identity comes from verified `sub`
  (`packages/auth/src/authSessionDriver.ts:81-120`).
- Code sign-in needs no redirect: `signInWithOtp({ email, shouldCreateUser })`
  then `verifyOtp({ email, token, type: 'email' | 'signup' })`
  (`packages/auth/src/authClient.ts:102-132, 165-180`).
- API calls: `Authorization: Bearer`, `x-reader-client`, `Accept`,
  `X-Request-ID`, 10 s timeout, no retry; 401/403 → local-only state, never
  a forced sign-out (`packages/clients/src/api/readerApiClient.ts:310-314`;
  `packages/core/src/httpErrors.ts:33-60`).
- Sign-out `scope: 'local'` with session restore on provider failure;
  `'others'` to revoke other devices (`packages/auth/src/authClient.ts:197-241`).
- Not pinned by any reader-web test: refresh grant, refresh failure, PKCE
  exchange, 401 after a valid sign-in. The native client must define these.

What the identity provider and resource server impose (Direct, reader-db and
reader-api): refresh-token rotation with a 10 s reuse window
(`supabase/config.toml:179-182`; hosted `security_refresh_token_reuse_interval: 10`),
6-digit code with 3600 s expiry, per-IP `token_refresh` limit 150 per window,
access tokens `ES256`/`RS256` with `aud=authenticated`, zero server-side
leeway (F006), `clientVersion` required on bootstrap and capabilities (F003).

What Android practice requires (primary sources retrieved 2026-09-11; the
lead re-fetched the items marked "lead-verified"):

- Direct, lead-verified — the Jetpack Security crypto library is deprecated:
  release notes for 1.1.0-beta01 (2025-06-04) state "Deprecated all APIs in
  favour of existing platform APIs and direct use of Android Keystore", and
  1.1.0 (2025-07-30) shipped in that state
  (https://developer.android.com/jetpack/androidx/releases/security). OWASP
  MASTG KNOW-0036 adds that the encrypted-preferences file must be excluded
  from backup because its key may not survive a restore
  (https://github.com/OWASP/mastg/blob/9444102bee7c65d7037904c95ebd06110a59e336/knowledge/android/MASVS-STORAGE/MASTG-KNOW-0036.md).
  Therefore: an Android Keystore key wrapping an encrypted session blob,
  written under the no-backup files directory or excluded by extraction
  rules, is the supported pattern; plain `SharedPreferences` and DataStore
  are unencrypted.
- Direct, lead-verified — the official Kotlin SDK (`supabase-kt` 3.8.0,
  release 2026-08-26, master `947a100243bc2151e1e1c4d941b2f0ed94987dc0`)
  stores the whole session, refresh token included, through
  `SettingsSessionManager`, which writes `json.encodeToString(session)` to a
  multiplatform-settings `Settings()` instance
  (`Auth/src/settingsMain/kotlin/io/github/jan/supabase/auth/SettingsSessionManager.kt`,
  `SettingsUtil.kt`). Indirect — that library's Android implementation is
  `SharedPreferencesSettings`, i.e. plaintext preferences
  (https://github.com/russhwolf/multiplatform-settings). The SDK accepts a
  custom `SessionManager` in `install(Auth) { sessionManager = ... }`. Its
  default flow type is implicit, PKCE is opt-in; it refreshes at 80% of token
  lifetime, retries on 5xx or network error after 10 s, clears the session on
  any other HTTP error, and stops refreshing while the app is in the
  background (`AuthImpl.kt`, `setupPlatform.kt`). minSdk 26.
- Direct — Supabase's session documentation: refresh tokens are single-use
  with a 10 s reuse interval ("we do not recommend changing this value");
  reuse outside the interval revokes the whole token family; a retried parent
  token returns the active child (https://supabase.com/docs/guides/auth/sessions).
  Error codes `refresh_token_already_used`, `refresh_token_not_found`,
  `session_not_found`, `session_expired`, `bad_jwt`
  (https://supabase.com/docs/guides/auth/debugging/error-codes). RFC 9700
  §2.2.2 requires rotation or sender-constraining for public clients; RFC
  6750 §3.1 says an `invalid_token` response means "request a new access
  token and retry". The web reference client refreshes proactively 90 s
  before expiry under a storage lock (`@supabase/auth-js` `constants.ts`
  `EXPIRY_MARGIN_MS`).
- Direct — email links are fragile on Android for three independent reasons:
  on Android 12+ an `https` link opens the app only if the domain is a
  verified App Link, otherwise the default browser
  (https://developer.android.com/about/versions/12/behavior-changes-all);
  mail security scanners prefetch links and consume single-use tokens, for
  which Supabase's documented mitigation is sending `{{ .Token }}` and
  calling `verifyOtp` (https://supabase.com/docs/guides/auth/auth-email-templates);
  and a PKCE link exchange "must be initiated on the same browser and device
  where the flow was started" (https://supabase.com/docs/guides/auth/sessions/pkce-flow).
  The OTP-code path returns a session from `POST /auth/v1/verify` with no
  redirect, no allow-list entry, and no verifier. RFC 8252 §7 prefers claimed
  HTTPS redirects over custom schemes when a redirect is needed.
- Direct, lead-verified — native Google: the legacy Google Sign-In API "is
  outdated and no longer supported"; Credential Manager replaces it
  (https://developer.android.com/identity/legacy/gsi). Supabase's Android
  guide uses Credential Manager, states "The Web client ID is the one used in
  your Android app" as the server client id, requires "add all of the Client
  IDs to the Supabase dashboard", and hashes the nonce for Google while
  passing the raw nonce to `signInWithIdToken`
  (https://supabase.com/docs/guides/auth/social-login/auth-google?platform=android).
- Direct — backup exclusion must cover both `<cloud-backup>` and
  `<device-transfer>` in `dataExtractionRules`, plus `fullBackupContent` for
  API 30 and below (https://developer.android.com/identity/data/autobackup;
  OWASP MASTG BEST-0004). This repository already does exactly that
  (`app/src/main/res/xml/data_extraction_rules.xml`, `backup_rules.xml`) with
  an emulator-proven backup test in `docs/evidence/46/`.
- Direct, lead-verified — the local Supabase CLI stack signs HS256 unless
  `signing_keys_path` is set (`# signing_keys_path = "./signing_keys.json"`
  in the CLI's `config.toml` template at
  `supabase/cli@21db855916f2c2b12f61cde923a27094b8528b23`), and reader-db's
  `config.toml:177` leaves it commented. reader-api accepts only `ES256` and
  `RS256`, so a local stack cannot mint a token reader-api accepts until that
  key exists (`supabase gen signing-key --algorithm ES256`).
- Direct — Play Integrity has a 10,000 request/day quota and needs
  server-side verdict checks (https://developer.android.com/google/play/integrity/overview);
  Supabase's own lever against sign-up abuse is CAPTCHA/Turnstile. Inference:
  attestation is not warranted for a solo consumer app at this stage.
- Direct — testing without an IDE: `supabase start` exposes the API on
  `127.0.0.1:54321` and the Mailpit mail catcher on `54324`; the emulator
  reaches the host at `10.0.2.2`; `adb shell bmgr backupnow <pkg>` exercises
  backup; the SDK's own tests use `ktor-client-mock` and
  `MemorySessionManager`, which are the seams for unit-testing refresh and
  rotation. Mailpit's HTTP API for scripted OTP extraction was not verified.

### Cause

Confirmed. No native client exists yet; the contract has to be assembled
from the reference client, the provider, the server, and platform guidance.

### Effect

Without a written client contract, the proving-ground implementation and the
later real reader would each guess at refresh timing, storage, and error
handling, and the guesses would diverge from reader-web.

### Recommended outcome

A general Android auth module contract exists in this repository, written
for any Android client of reader-api, covering: sign-in methods in
preference order (email code first, password, native Google when F005 lands),
session storage and backup exclusion, refresh policy, the 401/429/502
policy, bootstrap and first calls, and sign-out semantics; and the proving
ground implements exactly that contract.

### Outcome boundary

In: the client-side contract document and its implementation in whatever
build F007 designates; unit tests for the token store and refresh policy that
run without a device; an emulator flow against a stage Supabase project. Out:
UI beyond a minimal sign-in screen, any reader feature, and all backend
changes (F001-F006).

### Cohesion rationale

These are the client-side halves of one identity model; separating storage,
refresh, and error policy into different outcomes would let them drift apart
exactly as they have between reader-web's tests and its runtime.

### Outcome acceptance

- The contract document exists and names its sources (this dossier, the
  reader-api contract, the platform documents).
- A unit test proves: tokens are never written to plain shared preferences or
  files; concurrent refresh calls collapse to one; refresh happens before
  expiry with the documented margin; 401 `auth.expired_token` triggers one
  refresh and one retry, other `auth.*` codes clear the session.
- On an emulator against stage: sign-up and sign-in with an emailed code
  succeed, `GET /v1/reader/capabilities` returns a capability document, the
  session survives process death and a reboot, and sign-out revokes locally.
- A backup/transfer test proves the session store is excluded (this
  repository already has that proof for REQ-107 in `docs/evidence/46/`).

### Planning inputs

- Stack decision the planner must make (not selected here): the Supabase
  Kotlin SDK versus direct GoTrue REST calls; the lead records the trade-offs
  from the Android practice sources below and the constraint that the client
  must stay liftable into the real reader.
- Storage: Android Keystore-backed encryption of the session blob; do not use
  the deprecated Jetpack Security crypto library for new code; keep
  `allowBackup="false"` and the full-domain exclusion rules.
- Refresh policy inputs: rotation with a 10 s reuse window means concurrent
  refreshes must be serialised; refresh margin must exceed plausible clock
  skew because the server has none (F006).
- Redirect inputs: none needed for the code path; App Link or scheme per
  F002 only for recovery and OAuth.
- Testing without an IDE: emulator matrix in this repository's guide, a
  stage project for OTP email, and the local Supabase stack with its mail
  catcher for offline runs.
- Identity-provider rate limits per client address (reviewer R5): the
  reader-db contract enforces 30 sign-in/sign-up, 30 code verifications, and
  150 refreshes per window per IP (`supabase/config.toml:195-209`; hosted
  values `30/30/150` in `tools/auth-provider-config.mjs`). Many phones
  behind one carrier NAT share those buckets, so the client must surface
  `429` from the provider as "try later", never as a credential error, and
  must not retry code verification in a loop.
- Local emulator loop (reviewer R6): the emulator reaches the local stack at
  `http://10.0.2.2:54321`, which is cleartext; Android blocks cleartext by
  default from API 28, so a debug-only network security configuration is
  needed, and it must be confined to a build that the F007 decision keeps
  out of the released artifact.
- Dependencies: F007 (where the code lives), F003 (documented server
  contract), F001/F002/F005 only for redirect and Google flows.

### Limitations

- Android practice claims rest on documentation retrieved on 2026-09-11 and
  the SDK versions current on that date; they should be re-checked when
  implementation starts.
- No Android code was written or run during the audit.

### Decision rationale

Pending.

## A83-F009 — The pre-auth rate limiter keys on the TCP peer address behind a proxy that strips forwarding headers, so all callers may share one bucket

- Decision: Candidate
- Confidence: Low
- Review: Corroborated
- Planning readiness: Ready
- Cause status: Hypothesis
- Expected implementation repositories: `Chunipers/reader-api`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

A per-client rate limit in front of token verification should key on the
address the deployment already trusts as the real client address, so one
misbehaving caller cannot exhaust the budget for everyone and a growing
device fleet does not hit a fixed global ceiling.

### Condition and evidence

- Direct — the pre-auth limiter keys on `request.client.host`
  (`app/api/auth_dependencies.py:62-65`; 300 requests per 60 s default,
  `app/core/settings.py:535-548`).
- Direct — the container starts uvicorn with `--no-proxy-headers`
  (`Dockerfile:97`), so `request.client.host` is the TCP peer, not a
  forwarded address.
- Direct — the app already has a verified-ingress address helper for the
  guest-issuance bucket: README lines 592-594 state that outside development
  it accepts only Render's `CF-Connecting-IP`, "which Cloudflare overwrites
  before Render's load balancer forwards the request", and that
  caller-controlled `X-Forwarded-For` entries are ignored
  (`app/security.py:60-84`). The pre-auth limiter does not use it.
- Inference — behind Render and Cloudflare the TCP peer is the ingress hop,
  so the pre-auth limiter may be one shared 300-per-minute bucket for all
  callers. This has not been observed on stage; if Render presents distinct
  peer addresses per connection the effect is smaller.

### Cause

Hypothesis. The limiter predates the verified-ingress helper, or the two
were never reconciled; a stage observation is needed before treating this as
confirmed.

### Effect

If the premise holds, unrelated users receive correlated `429
rate_limit.exceeded` responses under load, and a mobile fleet reaches the
ceiling long before any single user misbehaves. If it does not hold, there is
no defect.

### Recommended outcome

The pre-auth limiter's key is either proven to distinguish clients on stage,
or it is switched to the verified-ingress address the deployment already
trusts for guest issuance.

### Outcome boundary

In: the limiter key source in `app/api/auth_dependencies.py` and its tests,
plus one stage observation. Out: the limiter's size, per-user AI limits,
the verifier's claim policy (F006).

### Cohesion rationale

One question ("what address does this bucket key on") with one observation
that settles it and at most one code change.

### Outcome acceptance

- Two requests from two distinct public addresses on stage land in two
  limiter buckets, observed via the limiter's key in logs or a targeted
  probe; or the owner records that a shared bucket of the current size is
  acceptable.

### Planning inputs

- Affected surfaces: `app/api/auth_dependencies.py`, `app/security.py`
  (verified-ingress helper), `tests/test_security.py`, `Dockerfile` only if
  proxy headers are to be trusted instead.
- Do the observation first; the code change may be unnecessary.

### Limitations

- Low confidence: the shared-bucket consequence is inferred from
  `--no-proxy-headers` and the limiter's key; a single stage observation
  would settle it either way.

### Decision rationale

Pending.

## A83-F010 — The platform has no first-class notion of a client kind: the web client's identity is hard-wired in five places across three repositories, which is the root cause behind F001, F002, F004, and F005

- Decision: Candidate
- Confidence: High
- Review: Corroborated
- Planning readiness: Ready
- Cause status: Confirmed
- Expected implementation repositories: `Chunipers/reader-api`, `Chunipers/reader-db`, `Chunipers/reader-web`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

A backend and identity provider that serve several thin clients (web now,
Android next, others later) should declare each client kind once, with its
redirect destinations, sign-in methods, version line, and public identity,
and derive every client-facing surface from that declaration. Adding a client
should be a declaration, not a change to validators, allow-lists, normalisers,
and binding checks in three repositories.

### Condition and evidence

All evidence is already recorded in the facet findings; this finding names
the pattern they share. The web client's identity is implicit in:

1. Direct — reader-api's bootstrap settings: one flat redirect list
   (`app/core/settings.py:277-279`), one flat provider list
   (`:713-721`), one version floor and major per document (`:198-203`,
   `:283-288`), projected verbatim
   (`app/features/reader_pre_auth/service.py:160-176`). See F001.
2. Direct — reader-api's client identification: the literal `"reader-web"`
   in three normalisers (`app/security.py:221-223`,
   `app/api/request_logging.py:25-34`,
   `app/infra/observability/schema.py:455`). See F004.
3. Direct — reader-db's provider contract: callbacks are keyed by
   environment only, with fixed purposes and locales and an origin-equality
   validator that cannot express a non-web destination
   (`supabase/auth/provider-contract.json:36-50`,
   `tools/auth-provider-config.mjs:13-14, 124-146, 379`), and sign-in methods
   are one flat set (`provider-contract.json:4-10`). See F002 and F005.
4. Direct — reader-db's managed hosted fields cover exactly what the web
   flow needs and omit the native Google registrations
   (`tools/auth-provider-config.mjs:28-68`). See F005.
5. Direct — reader-web's binding treats the deployment-wide list as its own
   exact set (`src/app/authEntryPolicyRuntime.ts:35-39, 82-97`). See F001.
6. Direct — the design intent was multi-client: reader-api's README
   describes the bootstrap surface as the "native" client identity and
   redirect allow-list (lines 99-114), and its redirect validator already
   accepts private-use schemes (`app/core/settings.py:33-52`). The
   implementation shipped with one client and the abstraction was never
   introduced.
7. Inference — because each place encodes the same fact independently,
   every new client kind would need coordinated edits in all of them, and
   F001 shows that editing one of them (the redirect list) breaks the
   existing client.

### Cause

Confirmed. The bootstrap, provider contract, and binding were each built
while reader-web was the only client, and each captured "the client" as a
set of flat values rather than as an entry in a registry.

### Effect

Patching the facets one by one (a second redirect list, a second literal in
the normalisers, a special case in the validator) would leave the same
five-way coupling in place and reproduce this audit for the next client
kind. The owner's stated direction is a multi-thin-client architecture, so
the facets should be planned as one architectural change with F001, F002,
F004, and F005 as its delivery increments.

### Recommended outcome

One client-aware identity contract: client kinds are declared once (the
reader-db provider contract is the natural home, since it already owns the
hosted provider settings and is consumer-synced to reader-api and
reader-web), each declaration carries that kind's redirect destinations,
sign-in methods, version line, and public client identity, and the Supabase
allow-list, reader-api's per-client bootstrap and capabilities projections,
its client identification, and each client's binding check are all derived
from it. An architecture decision record captures the choice and the
migration for the web client.

### Outcome boundary

In: the declaration's schema and home, how it reaches reader-api and
reader-web (consumer sync or generated artifacts), and the migration of the
existing web client onto it. Out: the token verifier (F006), the limiter
(F009), the per-client contract artifact's prose (F003), the Android module
itself (F008). F001, F002, F004, and F005 remain separately accepted
findings and become increments of this outcome; they are not duplicated
here.

### Cohesion rationale

The four facets share one cause and one fix shape; planning them as isolated
patches would produce four inconsistent partial registries. The verifier,
limiter, documentation, and client-side findings do not share that cause and
stay separate.

### Outcome acceptance

- A new client kind can be added by editing the declaration and running the
  parity workflow, with no change to validators, normalisers, or the web
  client's code.
- The web client's existing behaviour (its exact eight destinations, its
  version floor, its fail-closed binding) is unchanged after migration,
  proven by its existing tests.
- reader-api serves different bootstrap documents to two declared kinds from
  one configuration, and identifies each in telemetry, from the same
  declaration.
- An architecture decision record exists and is linked from the outcome.

### Planning inputs

- Sequence suggestion, not a plan: declaration schema and ADR first
  (reader-db), then reader-api projection and identification (F001, F004),
  then reader-web binding migration (F001), then native destinations and
  methods (F002, F005) as the first non-web entry.
- Constraint: the reader-web pre-auth revision constant
  (`acceptedReaderPreAuthRevision = '2026-09-05.2'`) and the provider
  contract `schemaVersion` (`reader.supabase-auth.v1`) both pin the current
  shape; a coordinated revision bump and release is part of the migration.
- Constraint: consumer sync today copies only four TypeScript files from
  reader-db to reader-api and reader-web; the declaration needs a delivery
  path to a Python consumer as well (generated artifact, or a checked-in
  copy validated in CI).
- Dependency: F003's per-client contract artifact should be generated from
  or validated against the same declaration.

### Limitations

- This finding synthesises evidence already graded in F001, F002, F004, and
  F005; it adds no new baseline observation beyond the README design-intent
  reading (item 6).
- Whether the declaration lives in reader-db or reader-api is a planning
  choice; the evidence only shows that today it lives nowhere.

### Decision rationale

Pending.

## Cross-finding analysis

### Duplicates and interactions

- F001 and F004 interact: the simplest per-client projection key is the
  client identifier header, which today only recognises `reader-web`.
- F002 and F005 both touch the reader-db provider contract and validator;
  they can be planned as one reader-db change if the owner accepts both.
- F003 and F008 are the two halves of one written contract (server side and
  client side) and should reference each other.
- F007 gates F008: nothing in F008 can be exercised in this repository until
  the owner decides where network-capable code lives.
- F006 and F009 were one finding in the first candidate; the reviewer (R1)
  asked for independently decidable outcomes, so the verifier policy stays in
  F006, the limiter key is F009, and the revocation statement moved into
  F003 as a documentation item.
- F010 (revision 5, owner direction) is the root cause behind F001, F002,
  F004, and F005. Those four keep their own evidence, boundaries, and
  acceptance and become increments of F010's outcome; F010 adds the
  declaration, its home, and the web-client migration, which none of the
  facets owns.

### Dependencies

- Redirect-based flows (OAuth, email links, password recovery in-app) require
  F001 and F002 together; the email-code path requires neither.
- Native Google requires F005 and, if the redirect fallback is chosen instead,
  F001 and F002.
- The client refresh margin in F008 depends on the leeway decision in F006.
- F001, F002, F004, and F005 depend on F010's declaration existing first if
  the owner accepts F010; if F010 is rejected, each can still be delivered
  as the narrower per-repository change its own sections describe.
- F007 no longer depends on a FastReader product decision: the module lives
  beside the app, not inside it.

### Residual unknowns

- Hosted Supabase settings not derivable from any repository: JWT signing key
  type (legacy HS256 versus asymmetric JWKS; reader-api and reader-web both
  require asymmetric, so the hosted project is presumably migrated, but this
  is inferred), hosted `jwt_exp`, session timebox and inactivity limits,
  `external_google_additional_client_ids`.
- Whether the pre-auth limiter distinguishes client addresses on stage
  (F009).
- What value `READER_PRE_AUTH_PUBLIC_CLIENT_ID` carries on stage (name only).
- Web Push (VAPID) notifications and the `/books` guest cookie are
  browser-shaped post-sign-in surfaces; they are outside the auth question
  but a native client cannot satisfy the notifications capability as
  defined. Recorded for the owner, not as a finding.

## Completion gate

- [x] The decision owner approved the charter.
- [x] Every target has a full baseline commit SHA.
- [x] The coverage inventory accounts for every in-scope surface.
- [x] Every claim has proportionate, reproducible evidence.
- [ ] The independent review is complete.
- [ ] Every review challenge and gap is reconciled or named as unresolved.
- [ ] Every finding is accepted, rejected, or deferred.
- [x] Every finding records the evidence-backed repositories expected to change if its recommendation is accepted.
- [ ] Every accepted finding has `Planning readiness: Ready` from the independent reviewer.
- [ ] Every accepted finding links a planning-ready outcome issue.
- [ ] Every outcome issue is a native child of the audit's same-repository outcome umbrella.
- [ ] `summary.md` answers the original question.
- [ ] The Decision-ready semantic anchor has an approved independent review verdict.
- [ ] Any post-review completion delta is limited to mechanical owner decisions and handoff fields.
- [ ] Structural validation passes.
- [ ] The dossier pull request is complete and ready to coordinate downstream delivery.
