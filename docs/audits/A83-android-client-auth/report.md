# Android client authentication through reader-api

- Audit ID: `A83`
- Audit key: `android-client-auth`
- Status: Review
- Dossier PR: https://github.com/cedagova/fastReader/pull/83
- Started: 2026-09-11
- Decision owner: Cesar Gonzalez (cedagova)
- Lead investigator: Claude (audit-lead, cedagova)
- Independent reviewer: cedagova-codex-reviewer[bot] (audit-reviewer; elected by claim on the dossier PR)

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

None.

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
| `A83-F001` | The pre-auth bootstrap is a single-client projection: one static redirect allow-list that reader-web requires to match its own set exactly | Candidate | High | Pending | Pending | Not required | Not required |
| `A83-F002` | No native redirect destination exists in the identity provider's allow-list, the reader-db contract validator rejects custom schemes, and password recovery is link-only | Candidate | High | Pending | Pending | Not required | Not required |
| `A83-F003` | The core bearer contract already works for any client, but the only sign-in narrative is web-only and the native integrator surface is undocumented | Candidate | High | Pending | Pending | Not required | Not required |
| `A83-F004` | reader-api cannot name a native client: the client identifier allow-list is `reader-web` only | Candidate | Medium | Pending | Pending | Not required | Not required |
| `A83-F005` | Native Google sign-in depends on identity-provider settings that no repository manages, and local development has no Google provider at all | Candidate | Medium | Pending | Pending | Not required | Not required |
| `A83-F006` | Resource-server session hardening gaps that a mobile fleet exposes: zero clock leeway, a per-IP limiter that may key on the proxy, no anonymous-token policy, no revocation | Candidate | Medium | Pending | Pending | Not required | Not required |
| `A83-F007` | This repository's no-network product guarantee (REQ-050) is enforced by a release gate and a published privacy statement, so it cannot host an auth experiment without a product-definition change | Candidate | High | Pending | Pending | Not required | Not required |
| `A83-F008` | The Android client contract: OTP-code sign-in, Keystore-backed session storage excluded from backup, single-flight refresh with margin, and a fixed 401 policy | Candidate | High | Pending | Pending | Not required | Not required |

## A83-F001 — The pre-auth bootstrap is a single-client projection: one static redirect allow-list that reader-web requires to match its own set exactly

- Decision: Candidate
- Confidence: High
- Review: Pending
- Planning readiness: Pending
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

The pre-auth projection distinguishes client kinds, so each kind receives the
redirect destinations that belong to it, and reader-web's binding check
continues to fail closed on its own set without being disturbed by another
client's entries.

### Outcome boundary

In: how reader-api selects and serves the redirect allow-list (and, if the
owner prefers, `publicClientId`) per client kind; reader-web's binding check
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

### Planning inputs

- Affected surfaces: `app/features/reader_pre_auth/service.py`,
  `app/core/settings.py` (`READER_PRE_AUTH_REDIRECT_URIS` and related),
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
- Review: Pending
- Planning readiness: Pending
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

The identity-provider contract admits at least one native redirect
destination per environment through the same reviewed tool and parity
workflow the web destinations use, and every provider email offers a path a
native client can complete (a code, or a redirect the client owns).

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
- Review: Pending
- Planning readiness: Pending
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
6. `docs/error-codes.md:47` documents `auth.forbidden` 403 which no code path
   raises; `:16` shows a non-UUID `request_id` example;
   `docs/functional-tests/auth.md:69` names a "Legacy User Header" test that
   no longer corresponds to code.

### Cause

Confirmed. The backend was built client-agnostic, but its integrator-facing
documentation was written while reader-web was the only client.

### Effect

A native integrator must reverse-engineer reader-web to learn the refresh,
error, and bootstrap expectations, and will type the 401 contract from an
OpenAPI document that omits the header the server always sends. The
misstatements (UUID subject, `auth.forbidden`) lead to wrong client-side
handling.

### Recommended outcome

reader-api's own documentation and contract artifacts describe the native
client contract end to end (bootstrap, sign-in authority, bearer, refresh
ownership, error codes including the 502 path, first calls after sign-in,
`publicClientId` semantics), and the documentation discrepancies above are
corrected so a generated Kotlin client is sufficient.

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
- The six discrepancies above are corrected or the code is changed to match.

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
- Review: Pending
- Planning readiness: Pending
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
- Direct — reader-web sends four names (`reader-web`,
  `reader-web-notifications`, `reader-web-sync`, `reader-web-capabilities`),
  of which only the first is recognised (`packages/clients/src/api/readerSyncClient.ts:38-47`,
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

reader-api recognises a stable native client identifier (and reader-web's
existing sub-names, if the owner wants them distinguished), records it in
telemetry, and documents the accepted values.

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
- Review: Pending
- Planning readiness: Pending
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

The provider contract and parity tool cover the fields native Google sign-in
needs, the local configuration offers a Google provider (or an explicit
documented substitute) so the native flow can be developed offline, and the
owner records which Google mechanism native clients use.

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

## A83-F006 — Resource-server session hardening gaps that a mobile fleet exposes: zero clock leeway, a per-IP limiter that may key on the proxy, no anonymous-token policy, no revocation

- Decision: Candidate
- Confidence: Medium
- Review: Pending
- Planning readiness: Pending
- Cause status: Hypothesis
- Expected implementation repositories: `Chunipers/reader-api`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

A resource server that will receive traffic from many devices with untrusted
clocks, through carrier NAT and a CDN, should tolerate small clock skew, key
per-client limits on the real client address, state its policy for anonymous
identities, and know that an issued token cannot be revoked before expiry.

### Condition and evidence

- Direct — no leeway: `jwt.decode` is called without `leeway` or `options`
  (`app/core/auth.py:288-299`); PyJWT 2.13.0 defaults to `leeway=0` and
  enforces `exp`, `nbf`, and `iat` when present (Indirect, from the pinned
  dependency). A device whose clock runs ahead of the server would present a
  token whose `iat` is in the future and receive 401 `auth.invalid_token`,
  which the client contract (F008) treats as "re-authenticate", not
  "refresh".
- Direct — the pre-auth limiter keys on `request.client.host`
  (`app/api/auth_dependencies.py:62-65`; 300 requests per 60 s default,
  `app/core/settings.py:535-548`), and the container starts uvicorn with
  `--no-proxy-headers` (`Dockerfile:97`). README lines 592-594 state the app
  accepts only `CF-Connecting-IP` for the guest-issuance bucket and that
  `X-Forwarded-For` is ignored; that verified-ingress address is not used by
  the pre-auth limiter. Inference: behind Render and Cloudflare,
  `request.client.host` is the ingress hop, so the limiter may be one shared
  bucket for all callers; a fleet of phones would exhaust it long before any
  single user misbehaves. Not observed on stage.
- Direct — no code reads `is_anonymous` (grep in `app/`: none). Anonymous
  sign-in is disabled in the reader-db hosted contract
  (`provider-contract.json:9`; `tools/auth-provider-config.mjs` enforces
  `external_anonymous_users_enabled: false`), so the exposure is latent: if
  it were ever enabled, an anonymous session (`aud=authenticated`, UUID `sub`)
  would be a full Reader actor.
- Direct — no revocation, denylist, or `jti` handling exists (grep for
  `revoke`, `jti`, `denylist` in `app/`: none); a leaked access token is valid
  until `exp`. reader-db sets `jwt_expiry = 3600` locally
  (`supabase/config.toml:173`); the hosted lifetime is dashboard state.
- Direct — subject-is-UUID enforcement is inconsistent across features
  (F003 item 5), so a malformed subject fails differently per route.

### Cause

Hypothesis. These are defaults left in place when one browser client behind
one origin was the only caller; the proxy-keyed limiter in particular needs a
stage observation to move to Confirmed.

### Effect

Mobile devices with skewed clocks are signed out instead of refreshed; a
shared limiter bucket would produce correlated 429s across unrelated users;
the anonymous and revocation policies are undocumented rather than wrong.

### Recommended outcome

reader-api tolerates a small, bounded clock skew, keys its per-client limiter
on the verified ingress address it already trusts elsewhere, states its policy
on anonymous identities (reject by default), and documents that access-token
lifetime is the only revocation window so the client contract (F008) can set
its refresh margin accordingly.

### Outcome boundary

In: the verifier's decode options, the pre-auth limiter's key source, an
`is_anonymous` check or documented policy, the README security section.
Out: refresh-token semantics (identity provider), the guest cookie path, and
per-user AI limits.

### Cohesion rationale

All four are properties of "how the resource server treats a token from an
unknown device", and each is a small, independent hardening that shares the
same test files.

### Outcome acceptance

- A token with `iat` up to an agreed number of seconds in the future is
  accepted; the bound is documented.
- Two requests from two distinct public addresses on stage land in two
  limiter buckets (observed via the limiter's key in logs or a targeted
  test), or the owner records that the shared bucket is acceptable with its
  current size.
- A token carrying `is_anonymous: true` is rejected with a documented code,
  or the policy to accept it is written down.
- README states that revocation is by expiry only and names the hosted access
  token lifetime.

### Planning inputs

- Affected surfaces: `app/core/auth.py`, `app/api/auth_dependencies.py`,
  `app/security.py` (verified-ingress address helper), `tests/test_auth.py`,
  `tests/test_security.py`, README.
- Constraint: keep audience fixed at `authenticated` and algorithms at
  `ES256`/`RS256`; do not weaken those.
- Owner question: hosted `jwt_exp` and session timebox values (names only).

### Limitations

- The shared-bucket consequence is inferred from `--no-proxy-headers` and the
  limiter's key; a single stage observation would settle it.
- Clock-skew impact is inferred from PyJWT defaults; no device with a skewed
  clock was tested.

### Decision rationale

Pending.

## A83-F007 — This repository's no-network product guarantee (REQ-050) is enforced by a release gate and a published privacy statement, so it cannot host an auth experiment without a product-definition change

- Decision: Candidate
- Confidence: High
- Review: Pending
- Planning readiness: Pending
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

The owner decides, through this repository's normal product-definition route,
how the proving ground coexists with REQ-050: for example an experiment that
never reaches the released build, or a separately identified build whose own
privacy statement and gate are honest. The shipped speed reader keeps its
no-network guarantee unless the owner explicitly redefines it.

### Outcome boundary

In: this repository's product definition, release gate, privacy statement,
and build configuration as they relate to an auth experiment. Out: the auth
implementation itself (F008), and anything in Chunipers.

### Cohesion rationale

One product decision (does this app ever talk to a network, and in which
build) governs the gate, the statement, and the manifest together.

### Outcome acceptance

- A definition in this repository states where the auth experiment lives and
  what the released build promises; the release gate and privacy statement
  agree with it.
- `scripts/release.sh` on the released build still passes with the promise
  the definition makes.

### Planning inputs

- Affected surfaces: `docs/product-definitions/`, `scripts/release.sh`,
  `docs/privacy-statement.md`, `README.md`, `app/build.gradle.kts` (flavours
  or a separate module/application id), `AppVersionTest` if it asserts on
  the manifest.
- Constraint: the backup exclusion already in place must be kept for any
  build that stores tokens.
- This is client-owned: it is handled through this repository's own
  definition and planning route, not carried to Chunipers.

### Limitations

- None material; every claim is a direct read of tracked files.

### Decision rationale

Pending.

## A83-F008 — The Android client contract: OTP-code sign-in, Keystore-backed session storage excluded from backup, single-flight refresh with margin, and a fixed 401 policy

- Decision: Candidate
- Confidence: High
- Review: Pending
- Planning readiness: Pending
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
- Dependencies: F007 (where the code lives), F003 (documented server
  contract), F001/F002/F005 only for redirect and Google flows.

### Limitations

- Android practice claims rest on documentation retrieved on 2026-09-11 and
  the SDK versions current on that date; they should be re-checked when
  implementation starts.
- No Android code was written or run during the audit.

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

### Dependencies

- Redirect-based flows (OAuth, email links, password recovery in-app) require
  F001 and F002 together; the email-code path requires neither.
- Native Google requires F005 and, if the redirect fallback is chosen instead,
  F001 and F002.
- The client refresh margin in F008 depends on the leeway decision in F006.

### Residual unknowns

- Hosted Supabase settings not derivable from any repository: JWT signing key
  type (legacy HS256 versus asymmetric JWKS; reader-api and reader-web both
  require asymmetric, so the hosted project is presumably migrated, but this
  is inferred), hosted `jwt_exp`, session timebox and inactivity limits,
  `external_google_additional_client_ids`.
- Whether the pre-auth limiter distinguishes client addresses on stage
  (F006).
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
