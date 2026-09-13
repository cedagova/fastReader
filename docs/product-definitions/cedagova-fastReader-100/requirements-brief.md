# Requirements Brief: FastReader signs in to a Reader account through :reader-auth (stage)

## Problem and intended outcome

FastReader is the owner's testing app for the Reader backend, but it cannot
talk to that backend: it has no internet permission, and the README, the
in-app privacy statement and the release gate all promise it never will. The
Android auth library `:reader-auth` exists in this repository, but its only
host is a bare proving-ground form, and the end-to-end stage run (emailed
code, capabilities, reboot survival, sign-out, backup exclusion) is still
unproven on a device.

Outcome: from FastReader on a device, the owner signs in to a Reader account
against stage and sees the account's capabilities; the session behaves as the
library contract says; the app stays fully usable signed out and offline; and
every published promise is rewritten to be true.

## Proposed behavior and main flows

- A **Reader account** surface under Settings shows not configured, signed
  out, or signed in as `<email>`, and holds every account action. Nothing
  else in the app mentions an account.
- Signed out: sign in or sign up with an emailed six-digit code (first),
  sign in with a password, or recover a password with an emailed code. The
  backend's pre-auth wiring guard runs before any provider call; rejections,
  rate limits and network failures are shown as distinct outcomes with their
  codes; nothing is retried automatically.
- Signed in: show and refresh the capabilities document as returned (with
  the request id); sign out (device first, provider best-effort); sign out
  other devices.
- Session survives process death and reboot, is stored only by the
  library's Keystore-encrypted no-backup store, and is gone after sign out.
- A build without the three public backend values builds and tests green and
  shows not configured at runtime.
- The privacy statement (English, Spanish, README, release-notes block) says
  what now leaves the device and to whom; the README drops "no internet
  permission at all"; `scripts/release.sh` proves "INTERNET and no other
  permission, no cleartext" instead of "no INTERNET".

## Scope and non-goals

In scope: the account surface, all contract sign-in methods, capabilities
display, sign-out, configuration handling, and the truthful rewrite of
promise, statement, README and release gate.

Out of scope: sync of positions or library; profile calls; any use of
capabilities beyond showing them; native Google sign-in; link-based flows;
account-gated reading features; the production backend; cutting a release;
consumer polish beyond the existing accessibility bar.

## Product outcomes

One root, no children: https://github.com/cedagova/fastReader/issues/100
owns REQ-401 to REQ-413.

## Important constraints and success measures

- Stage only; client kind `reader-android` 1.0.0.
- `reader-auth/CONTRACT.md` is authoritative for auth behaviour and its host
  requirements bind FastReader; nothing here restates or contradicts it.
- The stored session is never in a backup or transfer; FastReader's existing
  all-domain exclusion stays.
- Success: the deferred stage run from `docs/evidence/93/` is completed from
  FastReader on `Phone_Mid_API36` and recorded. Guardrails: signed-out
  offline FastReader is unchanged from v1.5.0 outside Settings; the release
  gate never publishes an APK with a second permission or cleartext.

## Evidence, assumptions, and uncertainty

Evidence is pinned at `820ef28ae97ec13b90c87bfb09fa312f037b9fc4`: the
library contract and API, the host's configuration and manifests, the
2026-09-13 stage evidence from the host, the current statement, README and
release gate, and the REQ-050/REQ-303/REQ-107 promises being replaced.

Assumptions: the owner has the stage values for `local.properties`; the
stage email template delivers the code; the three-place statement test
stays. Uncertainty: the fate of `:reader-auth-host` (owner question);
placement and visual treatment inside Settings (design).

## Owner decisions

2026-09-13: integrate Reader sign-in into FastReader and replace the
no-network promise; FastReader is a testing app, not a consumer product;
network access is whatever the backend contract needs; sign-in optional and
the app usable signed out; scope is account, session and capabilities; client
kind `reader-android`; stage only.

## Links and next action

- Root issue: https://github.com/cedagova/fastReader/issues/100
- Definition PR: https://github.com/cedagova/fastReader/pull/101
- Next action: `plan https://github.com/cedagova/fastReader/issues/100`
