# Implementation Plan: Reader API token verifier tolerates bounded clock skew and states its anonymous-identity policy

- Planning issue: https://github.com/cedagova/fastReader/issues/91
- Planning PR: https://github.com/cedagova/fastReader/pull/95
- Status: Ready for implementation
- Root classification: LEAF
- Delivery topology: DIRECT
- Planner: Planning lead (Claude)
- Started: 2026-09-12

## Pinned baselines

| Repository | Baseline |
| --- | --- |
| `Chunipers/reader-api` | `f6a3df33a9ffaca423e656b82dfdedd977d32a0c` |
| `cedagova/fastReader` | `ed90c4a6479e849c7f0001951870c139e5072df5` |

`Chunipers/reader-api` is `origin/stage` on 2026-09-12, the integration
branch the leaf PR targets. `cedagova/fastReader` is `origin/main`; it hosts
the root issue and the audit record (dossier PR #83, reviewed head
`1b99102603eb05975e655b26be02a5a79abd646e`, finding A83-F006) and receives no
code change from this plan. The audit inspected reader-api at
`fbaa90db5495aa7b6cf995bceb3a54e2f6039ff9`; the verifier module, its test
module, and the auth error catalog are byte-identical between that commit and
the pinned baseline (`git diff fbaa90d..f6a3df3 -- app/core/auth.py
tests/test_auth.py` is empty; `docs/error-codes.md` gained one unrelated
`reader.*` code), so every audit evidence line still holds.

## Preserved objective and boundaries

Root #91 is audit outcome A83-F006, accepted by the owner on 2026-09-11 and
carrying `Audit handoff: PLANNING_REQUIRED` under the native audit umbrella
#94. Its desired outcome is preserved verbatim: reader-api tolerates a small,
bounded clock skew on time claims and states its policy on anonymous
identities (reject by default), both in code and in the README security
section.

Boundaries preserved from the root: in scope are the verifier's decode
options and claim checks, the tests that pin them, and the README lines that
state the policy. Out of scope are the pre-auth limiter key (F009), the
revocation statement (F003 item 7), refresh-token semantics (identity
provider), the guest cookie path, and per-user AI limits. Constraints
preserved: audience stays fixed at `authenticated` and the algorithm list at
`ES256`/`RS256`; the leeway is at most a couple of minutes (RFC 7519 §4.1.4).
Known dependency preserved: the Android client's refresh margin (A83-F008,
#93) is set after this bound is chosen; nothing here blocks or is blocked by
#93 natively, because #93 only reads the documented bound.

## Classification

- **ROOT #91 — `LEAF`.** One coherent, independently verifiable outcome in
  one repository (`Chunipers/reader-api`), delivered as one PR to `stage`.
  The two acceptance conditions are two claim-acceptance rules in the same
  verifier function, proven by the same test module and documented in the
  same README section (the root's own cohesion rationale). Splitting them
  would create two PRs that each touch the same function, tests, and README
  paragraph with no independently useful intermediate state. No children are
  published; the fast-leaf path applies.

Not `ALREADY_SATISFIED`: at the pinned baseline `verify_access_token` calls
`jwt.decode` with no `leeway` (so a one-second-future `iat` or `nbf` is
rejected as `invalid_token`) and nothing in `app/` reads `is_anonymous`.
Neither acceptance condition holds. Not `NEEDS_DECISION`: the one material
bound was chosen by the owner on 2026-09-12 (120 seconds, recorded under
Assumptions and open questions), and the anonymous policy is the accepted
audit outcome's stated default (reject). Every remaining choice is an
ordinary, reversible naming or wiring choice.

## Current-state evidence

At `Chunipers/reader-api@f6a3df33a9ffaca423e656b82dfdedd977d32a0c`:

- `app/core/auth.py`: `SupabaseTokenVerifier.verify_access_token` decodes
  with `algorithms`, `audience`, and `issuer` only (lines 292–298); PyJWT
  2.13.0 (pinned in `uv.lock`) then applies `leeway=0` to `exp`, `nbf`, and
  `iat`, raising `ImmatureSignatureError` for a future `iat`/`nbf` and
  `ExpiredSignatureError` for a past `exp`. The verifier maps the former to
  `TokenVerificationFailureReason.INVALID_TOKEN` and the latter to
  `EXPIRED_TOKEN`. The failure-reason enum has five members; there is no
  anonymous-related reason and no read of `is_anonymous` anywhere in `app/`.
- `app/api/auth_dependencies.py`: every failure reason becomes HTTP 401 with
  error code `auth.<reason>`, a sanitized detail from `AUTH_ERROR_DETAILS`,
  `WWW-Authenticate: Bearer`, an `auth_failure_reason` log field, and an
  auth-failure metric increment via `record_auth_failure`. A new reason
  therefore flows through the existing error contract and telemetry without
  a new signal.
- `app/core/settings.py` and `app/main.py`: verifier knobs are
  pydantic-settings fields with `SUPABASE_JWKS_*` aliases, validated at load
  and passed to the verifier at construction next to `expected_issuer` and
  `expected_audience`. This is the established pattern for a bounded,
  operator-visible verifier setting.
- `tests/test_auth.py`: mints ES256 tokens against a fake JWKS and already
  pins the expired case (`exp = now − 1 s → EXPIRED_TOKEN`) and the future
  `nbf` case (`nbf = now + 5 min → INVALID_TOKEN`), so the new bound can be
  pinned on both sides of the same boundary. `tests/test_error_contracts.py`
  and `docs/error-codes.md` enumerate every public `auth.*` code; a new code
  must be added to both or the contract test fails.
- `README.md`, "Protected Route Contract": states signature, `iss`, `aud`,
  unexpired, and non-empty `sub` requirements, and the controlled 401
  responses. It says nothing about clock tolerance or anonymous identities.
- Client side (root evidence, not re-derived here): the Android contract
  (A83-F008) treats `auth.expired_token` as "refresh once and retry" and every
  other `auth.*` code as "clear the session", so a skewed clock today ends in
  sign-out rather than refresh.

## Selected implementation direction

One PR in `Chunipers/reader-api` against `stage`, landing both policies and
their proof together:

1. **Bounded clock leeway on time claims.** The verifier passes one leeway
   value to the JWT library so `iat`, `nbf`, and `exp` all tolerate the same
   bound, without special-casing individual claims and without loosening
   signature, issuer, audience, or algorithm checks. The bound is an
   operator setting following the existing `SUPABASE_JWKS_*` pattern:
   `SUPABASE_JWT_LEEWAY_SECONDS`, default `120`, validated to `0`–`300`
   (`0` restores today's strict behavior; the ceiling keeps the bound within
   "a few minutes"), and wired into the verifier at construction. The
   README's settings table and Protected Route Contract state the default,
   the range, and that the tolerance applies to issued-at, not-before, and
   expiry checks.
2. **Anonymous identities are rejected.** After signature and claim
   verification succeeds, a payload whose `is_anonymous` claim is boolean
   `true` is rejected with a new failure reason, surfaced as a new documented
   401 code in the `auth.<reason>` family (recommended
   `auth.anonymous_identity_rejected`; the implementer keeps the exact
   spelling consistent across the enum, `AUTH_ERROR_DETAILS`,
   `docs/error-codes.md`, and the error-contract test). A missing or `false`
   claim is not anonymous. The check runs only on verified claims, never on
   the unverified header or payload. The README Protected Route Contract
   gains one bullet stating that anonymous sessions are not accepted as
   Reader actors. No provider setting changes; the hosted contract keeps
   anonymous sign-in disabled, so this is a fail-closed statement of policy.
3. **Proof in the existing auth test module.** Tests pin both sides of the
   bound for each time claim (`iat` and `nbf` at `now + 60 s` accepted, at
   `now + 200 s` rejected as `invalid_token`; `exp` at `now − 60 s`
   accepted, at `now − 200 s` rejected as `expired_token`), that leeway `0`
   rejects a future `iat`, that an `is_anonymous: true` token is rejected
   with the new reason and reaches the client as the documented 401 code,
   and that the setting defaults to `120` and rejects out-of-range values.

Observability: the new failure reason is carried by the existing
auth-failure log field and metric; no new event, dashboard, or alert is
required, and the PR states that non-applicability explicitly per the
repository's observability rule. Migration: none; the setting has a default,
so no environment change is required to deploy.

## Issue publication manifest

| Key | Kind | Parent | Repository | Title | Delivery | Blocked by | Issue |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ROOT | LEAF | None | cedagova/fastReader | A83-F006 — The token verifier has no clock leeway and no anonymous-identity policy, so a device with a skewed clock is signed out instead of refreshed and an anonymous session would be a full actor | None | None | https://github.com/cedagova/fastReader/issues/91 |

The root issue lives in `cedagova/fastReader` by owner decision (its hosting
note); the leaf's code, tests, README, and PR live in `Chunipers/reader-api`
on `stage`, as the root's `Expected implementation repositories` line
states. The implementation PR body links the root; because the Chunipers
worker identity cannot write to `cedagova/fastReader`, GitHub will not
auto-close the root, and the implementation lead closes it as completed
after post-merge verification.

## Acceptance coverage

| Root acceptance | Covered by |
| --- | --- |
| A token with `iat` or `nbf` up to an agreed number of seconds in the future is accepted; the bound is documented and a test pins it | ROOT, direction 1 and 3: bound 120 s (owner decision), documented in README, pinned on both sides by the auth tests; `exp` receives the same bound so the documented tolerance is one number |
| A token carrying `is_anonymous: true` is rejected with a documented code, or the policy to accept it is written down and tested | ROOT, direction 2 and 3: rejected with a new documented `auth.*` 401 code, README states the policy, auth and error-contract tests pin it |
| Constraint: audience `authenticated` and algorithms `ES256`/`RS256` unchanged | ROOT, direction 1: neither becomes configurable; existing `iss`/`aud`/algorithm tests keep passing |

No orphan or overlapping outcome: the root is the only node.

## Validation and feedback

- Focused tests: `uv run pytest tests/test_auth.py tests/test_settings.py
  tests/test_error_contracts.py`, then the repository's full check set
  (`ruff`, `mypy`, full `pytest`) because a public error code and a settings
  field are shared contracts.
- Boundary check: one local request against a running API with a minted
  token whose `iat` is 60 s ahead returns the protected resource, and one
  with `is_anonymous: true` returns 401 with the new code and
  `WWW-Authenticate: Bearer`. This is the capable layer for an HTTP API; no
  browser or screenshot evidence applies.
- Hosted CI on the reader-api PR (`dev-checks` quality gate and required
  checks); one independent implementation reviewer on the exact head.
- Observability statement in the PR: the existing auth-failure metric and log
  carry the new reason value; no Grafana change. A stage-level check after
  merge is optional and limited to confirming that auth failure counts do not
  rise (a rise would indicate a mis-set bound), because no skewed-clock device
  exists to exercise the tolerance directly.
- Feedback loop: the Android client (A83-F008, #93) reads the documented
  default when it sets its refresh margin; if the client work needs a
  different bound, the setting changes without a code change and the README
  is updated in the same PR.

## Assumptions and open questions

### Owner decision: the clock-skew bound

Decided by the owner on 2026-09-12 in the planning thread: **120 seconds,
applied uniformly to `iat`, `nbf`, and `exp`, as a setting with default 120
and range 0–300.** Facts: RFC 7519 §4.1.4 describes leeway as "usually no
more than a few minutes"; Supabase's guidance (F008 sources) notes device
clocks can be off by minutes; the audit's Android-practice research bounded a
client-side margin at 120 s. Assumption, not verified: the hosted access-token
lifetime (`jwt_exp`) was excluded from the audit and is assumed to be the
Supabase default of 3600 s, so a 120 s tolerance on `exp` extends acceptance
by about 3 %; the bound does not depend on this being exact. Alternatives
considered: 60 s (tighter, but below the client margin the audit
recommended) and 300 s (RFC upper edge, more than the evidence needs). The
value is reversible by configuration. No open decision remains.

### Recorded assumptions

- Anonymous policy: reject is the accepted audit outcome's stated default;
  the acceptance's alternative ("accept, written down and tested") was not
  chosen because anonymous sign-in is disabled in the hosted provider
  contract and no Reader flow expects an anonymous actor. Reversing it later
  is a code change plus a README line, not a migration.
- The exact new error code spelling and the settings field name are
  implementation choices within the stated `auth.<reason>` and
  `SUPABASE_JWKS_*`-style conventions; the plan names recommended values so
  the client contract (#93) can reference them.
- Root closure is manual (see the manifest note) because the implementation
  identity cannot close a `cedagova/fastReader` issue.

## Satisfaction proof

Implementation work remains; at the pinned baseline the verifier has no
leeway and no anonymous-identity check, so neither root acceptance condition
holds.

## Publication verification

- `cedagova-infra plan validate --path docs/plans/cedagova-fastReader-91 --phase publication-ready`: valid; classification LEAF, delivery DIRECT, 1 manifest row, 0 external prerequisites, 0 inherited predecessors, 0 retained outcomes.
- `cedagova-infra plan verify-graph --path docs/plans/cedagova-fastReader-91`: root #91 open with `Planning root`, `Planning plan`, and `Planning kind: LEAF` lines beneath its preserved `Audit handoff: PLANNING_REQUIRED` line, native parent #94 (audit umbrella) unchanged, zero native sub-issues, zero blocked-by edges — the zero-child graph matches the one-row manifest.
- Exact-head semantic-anchor approval is recorded in the native review on PR #95, not here.
