# Implementation Plan: Map unexpected auth errors

- Planning issue: https://github.com/cedagova/fastReader/issues/191
- Planning PR: https://github.com/cedagova/fastReader/pull/192
- Status: Ready for implementation
- Root classification: LEAF
- Delivery topology: DIRECT
- Planner: Planning lead (Claude)
- Started: 2026-09-24

## Pinned baselines

| Repository | Baseline |
| --- | --- |
| `cedagova/fastReader` | `0abfe5312a60feaf4e932b1eb15f7dc76b847156` |
| `supabase-community/supabase-kt` | `233683736f9b8cd5971da4a18c0ff056489323a5` |

`cedagova/fastReader` is `origin/main` on 2026-09-24 (after #187, #189, #190).
It hosts the root, the `:reader-auth` library, its `CONTRACT.md`, and the
FastReader host app, and receives the change. `supabase-kt` is the SDK the
library pins (`supabase = "3.8.0"` in `gradle/libs.versions.toml`); the SHA
is its `3.8.0` tag, read only to establish which exceptions the provider
calls can throw.

## Preserved objective and boundaries

Root #191 (bug, low severity, found by the effort reviewer on PR #184 for
#159). Objective preserved: an unexpected, non-cancellation exception from a
provider call — the named case is a `SerializationException` from a malformed
or unexpected 2xx body, such as a captive portal's HTML page — must not
escape `onForeground()` (which `CONTRACT.md` host requirement 5 says never
throws a `ReaderAuthException`) or a protected call. It becomes a typed
`ReaderAuthException`, `CancellationException` still propagates, and
`ReaderApiClient` is checked for the same gap. `CONTRACT.md` is updated when
the mapping adds a documented case.

Proof preserved: a provider refresh that throws `SerializationException`
makes `onForeground()` return the current state without throwing, and makes a
protected call throw the chosen typed `ReaderAuthException`.

Boundaries. In: `:reader-auth` provider-call error mapping, `ReaderApiClient`
body reading, `CONTRACT.md`, the exception KDoc, and the FastReader host's
mapping of the closed set (a compile-time consequence of any new subtype).
Out: any change to retry counts, margins, the `Retry-After` ceiling (#157),
the retryable-5xx rule (#158), storage-failure handling (#180), and every
reader-api or Supabase server behaviour.

## Classification

- **ROOT #191 — `LEAF`.** One module's failure-mapping defect, one
  repository, one PR to `main`. The provider-side mapping, the
  `ReaderApiClient` hardening, the contract text, and the host's one new
  branch together produce one observable result — "no unexpected provider or
  body failure escapes the closed set" — and none is independently useful:
  a new subtype without the host branch does not compile, and the
  `ReaderApiClient` hardening is two small edits on the same principle. No
  children; the fast-leaf path applies.

Not `ALREADY_SATISFIED`: at the baseline `SessionRefresher.grant()` catches
only `AuthRestException`, `RestException` and `IOException`, so the named
exception escapes (evidence below). Not `NEEDS_DECISION`: the one material
choice (which type) is settled below from the code and the SDK, and recorded
with the alternatives so the owner or reviewer can overturn it with one reply.

## Current-state evidence

At `0abfe5312a60feaf4e932b1eb15f7dc76b847156` unless marked SDK:

- **Refresh grant gap.** `SessionRefresher.grant()` wraps
  `auth.refreshSession(...)` and maps `AuthRestException`, `RestException`
  (429/5xx → `TryLater` after one retry, else `ProviderRejected`) and
  `IOException` (one retry, then `NetworkUnavailable`). Anything else
  escapes. Both `onForeground()` (via `sessionForRequest()`) and every
  protected `ReaderApiClient` call (via `sessionForRequest()` /
  `refreshAfterRejection()`) run this path. `onForeground()` catches only
  `ReaderAuthException`.
- **Sign-in gap, same cause.** `ReaderAuthClient.providerCall` — used by
  every sign-in, recovery, `setPassword` and `signOutOtherDevices` provider
  call — has the same three catches, so a malformed body there escapes to
  the host too. The `ReaderAuthException` KDoc promises "every failure the
  module surfaces to a host, as one closed set".
- **SDK: what escapes (3.8.0).** `SupabaseApi` throws the plugin's
  `parseErrorResponse` result (a `RestException`) for every non-2xx before
  any body is decoded; `parseErrorResponse` itself reads error bodies with
  `bodyOrNull`, so malformed error bodies are already typed. Success bodies
  go through `safeBody`, which calls `supabaseJson.decodeFromString` and
  rethrows only `MissingFieldException` as `SupabaseEncodingException`
  (a plain `Exception`); every other decode failure is a
  `kotlinx.serialization.SerializationException` (an
  `IllegalArgumentException`). `refreshSession` is
  `postJson("token?grant_type=refresh_token").safeBody(...)`. Network
  failures are `HttpRequestException`, which extends `IOException`, and
  timeouts are Ktor's `HttpRequestTimeoutException` (also an `IOException`).
  So the unmapped provider failures are exactly: a 2xx whose body does not
  decode, plus any SDK-internal failure the contract does not name.
- **A malformed 2xx on refresh is ambiguous.** A captive portal never reached
  the provider (refresh token unspent); a real provider 200 that failed to
  decode means the provider already rotated the token. `CONTRACT.md` and
  #154 forbid resending a possibly consumed refresh token; the provider's
  reuse window is 10 s and the inline retry waits 10 s by default.
- **`ReaderApiClient` — mostly closed, two gaps.**
  - Success bodies: `jsonBody()` already maps a non-JSON-object 2xx to
    `ApiError(status, null, X-Request-ID, "response is not a JSON object")`.
  - Gap 1, every route: `ErrorBody.of` parses the error body inside a
    `try`, but then reads `code`, `message` and `request_id` with
    `jsonPrimitive` outside it. A JSON object whose field is an object or
    array (a proxy's own JSON error, say) throws `IllegalArgumentException`
    out of any call, protected ones included.
  - Gap 2, bootstrap: `preAuth()` feeds the parsed object to
    `PreAuthDocument.parse` (`decodeFromJsonElement`), so a JSON object with
    a wrongly typed field throws `SerializationException` out of
    `bootstrap()` and every sign-in that bootstraps.
- **Host.** `ReaderAccountController.toOutcome()` is an exhaustive `when` over
  the closed set (no `else`); `AccountDownloads` and `AccountImports` map a
  subset with an `else`/grouped fallback. `TryLater` is rendered as "Try
  again later (HTTP …). Nothing was changed."; `ProviderRejected` is
  documented to the user as a wrong code or password.
- **Precedent for growing the set.** #180 added `StorageUnavailable(cause)`
  as a new subtype, with a host outcome, when an existing branch would have
  told the user something untrue.

## Selected implementation direction

One PR on `main` touching `reader-auth/` (main, tests, `CONTRACT.md`) and the
FastReader account layer's outcome mapping and its one user string.

1. **New closed-set member `ReaderAuthException.UnexpectedResponse(cause)`.**
   Meaning: the identity provider's answer could not be read (a 2xx whose
   body is not what the SDK expects — e.g. a captive portal or proxy page) or
   the provider SDK failed in a way the contract does not name. The session
   is intact, nothing was cleared, and nothing was retried. It carries the
   original throwable as its cause, like `NetworkUnavailable` and
   `StorageUnavailable`, and no invented status or code.
2. **Provider calls map it at the source.** In `SessionRefresher.grant()` and
   in `ReaderAuthClient.providerCall`, after the existing catches, any other
   non-cancellation `Exception` thrown by the provider call becomes
   `UnexpectedResponse`. `CancellationException` and `ReaderAuthException`
   keep propagating unchanged. The catch wraps only the provider call itself,
   not module code around it, so the module's own bugs are not masked.
3. **No retry, no clear, on the refresh path.** Because a malformed 2xx may
   mean the refresh token was already rotated, the grant is never re-sent for
   this failure. The stored session is kept: if the token was unspent (captive
   portal) the next refresh succeeds; if it was spent, the next refresh gets
   `refresh_token_already_used` and the existing rule signs the device out.
   `onForeground()` therefore returns the still-valid `SignedIn`; a protected
   call throws `UnexpectedResponse`.
4. **`ReaderApiClient` hardening, keeping its existing mapping.** reader-api
   body failures stay `ApiError` (the established branch for a reader-api
   answer the policy does not act on, with its status and request id):
   - `ErrorBody.of` reads `code`, `message` and `request_id` tolerantly — a
     non-primitive field counts as absent — so the status-based policy
     (401/403/429/502/5xx `retryable`) still decides the branch;
   - a pre-auth 2xx whose object does not decode as the pre-auth document
     becomes `ApiError(status, null, requestId, …)` like `jsonBody()`, so
     `bootstrap()` refuses sign-in with a typed error and keeps no document.
5. **Contract and KDoc.** `ReaderAuthException` KDoc gains the new member.
   `CONTRACT.md` "Refresh policy → Outcomes" gains the unreadable-answer rule
   (kept, surfaced once, never retried, why), host requirement 5 lists it
   among the outcomes that return `SignedIn`, and the reader-api table notes
   that an unreadable body is surfaced with its status and request id,
   session intact.
6. **Host.** FastReader's `toOutcome()` gains one outcome for the new member,
   rendered as a generic "the sign-in service answered with something
   unexpected; nothing was changed; try again" message. The download and
   import mappers keep their fallbacks unless the compiler requires a branch.

Reversibility: the new member is additive to the library's own closed set in
the same repository; the one host is updated in the same PR. Switching to
`TryLater` later is a mapping change plus removing the member.

## Issue publication manifest

| Key | Kind | Parent | Repository | Title | Delivery | Blocked by | Issue |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ROOT | LEAF | None | cedagova/fastReader | reader-auth: unexpected provider errors (e.g. malformed body) escape onForeground() and protected calls | None | None | https://github.com/cedagova/fastReader/issues/191 |

## Acceptance coverage

| Root acceptance | Covered by |
| --- | --- |
| Refresh throwing `SerializationException` → `onForeground()` returns the current state, no throw | Directions 1–3; `RefreshPolicyTest` (or its neighbour): mock provider answers the refresh with a 200 HTML body; `onForeground()` returns `SignedIn` with the unchanged session, and exactly one refresh request was sent |
| Same refresh → a protected call throws the chosen typed exception | Directions 1–3; `ReaderApiPolicyTest`: a protected call inside the margin with the same provider answer throws `UnexpectedResponse`, sends no reader-api request, sends one refresh request, and leaves the stored session in place |
| `CancellationException` keeps propagating | Direction 2; existing cancellation tests stay green; one test that a cancelled refresh is not wrapped |
| `ReaderApiClient` checked for the same gap | Direction 4; `ReaderApiPolicyTest`: an error body with an object-valued `code` on a 503 still follows the status policy (typed, no crash); a pre-auth 200 with a wrongly typed field makes `bootstrap()` throw `ApiError` and cache nothing |
| `CONTRACT.md` updated for a new documented case | Direction 5; review reads the three edited passages against the code |
| (Same defect, sign-in path) | Direction 2; `ProviderOperationsTest`: a verify or password sign-in answered with a 200 HTML body throws `UnexpectedResponse` and saves no session |
| Host compiles and shows the new outcome | Direction 6; `:app` unit test for `toOutcome()` mapping the new member |

No orphan or overlapping outcome: the root is the only node.

## Validation and feedback

- `./gradlew :reader-auth:testDebugUnitTest :app:testDebugUnitTest lint
  assembleDebug` under JDK 21; hosted `checks.yml` on the PR.
- Provider answers are simulated with the existing Ktor mock-engine harness
  (`TestHarness.kt`); no network, no device, no emulator. No UI screen
  changes, so no new Roborazzi golden is expected; `verifyRoborazziDebug`
  must stay green.
- Note the known flake `concurrent expired_token rejections share one
  refresh` on CI: rerun before blaming the change.

## Assumptions and open questions

### Which typed error — decided: new `UnexpectedResponse`; owner or reviewer may overturn

**Problem.** A provider answer the SDK cannot read has no honest home in the
current closed set, and hosts render each member differently.

**Facts.** `ProviderRejected` is shown to the user as a wrong code or
password; a captive portal page during sign-in would tell the user their code
was wrong. `TryLater` requires an HTTP status and says "the server asked for
a later retry"; the SDK's decode exception carries no status, and nothing
asked for a retry. `TryLater` on the refresh path also implies the one inline
retry, which could resend a consumed refresh token. `NetworkUnavailable`
fits the captive-portal case but not SDK or provider drift. #180 set the
precedent of adding a member when an existing one would mislead.

**Options.**
- **A — new `UnexpectedResponse(cause)` (selected).** Honest, carries the
  cause, no retry; cost: one KDoc/contract entry and one host outcome and
  string.
- **B — `TryLater` with a synthetic status/code and no retry.** No new API;
  cost: a fudged status, a false "server asked" meaning, and a documented
  exception to `TryLater`'s "after the one permitted retry".
- **C — `ProviderRejected`.** Rejected: wrong user message (credential error).

**Reason.** A keeps every member truthful for a library meant to lift into
the real Reader client, at the cost of one additive branch in the one host.
**Blocked if overturned:** directions 1, 5 and 6 change; the no-retry,
no-clear behaviour and `ReaderApiClient` hardening stay. **Exact reply to
overturn:** `Choose B` on the planning PR.

### Non-material (implementer may adjust within the invariants)

- The host's exact wording of the new message.
- Whether tolerant `ErrorBody` field reading is a small helper or inline.

## Satisfaction proof

Implementation work remains; this is not an `ALREADY_SATISFIED` plan.

## Publication verification

- `plan validate --phase publication-ready` on this directory: valid at the
  candidate head.
- Native graph: root #191 has no sub-issues, no blocked-by edges, and no
  parent, matching the one-row manifest.
- Root #191 carries `Planning root`, `Planning plan`, and `Planning kind:
  LEAF`, and the implementation leaf contract below its original report.
- Exact-head approval lives in the native PR review, not here.
