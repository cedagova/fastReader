# Android client authentication through reader-api: audit summary

- Audit ID: `A83`
- Audit key: `android-client-auth`
- Status: Decision ready
- Prepared: 2026-09-11
- Completed: Not complete
- Dossier PR: https://github.com/cedagova/fastReader/pull/83

## Answer

A native Android client can use the Chunipers backend today with the same
identity as the web app, as long as it signs in with the emailed 6-digit code
or a password. reader-api already verifies Supabase tokens in a way that does
not care which client sent them, and it needs no change for sign-in, session
keeping, refresh, or protected calls. The gaps are around the edges: the
backend's sign-in bootstrap document can only describe one client's redirect
destinations at a time (and the web app refuses to work if that list changes),
the identity provider trusts only web addresses for redirects, native Google
sign-in needs provider settings no repository manages, and the backend's
integrator documentation only tells the web story, and the backend cannot
even tell Android traffic apart from unknown traffic. The bootstrap,
redirect, Google, and client-identification gaps share one root cause: the
platform has no notion of a "client kind" at all, so the web client's
identity is hard-wired in five places across three repositories. The right
move for a multi-client Reader is to declare each client kind once and derive
everything from it, rather than patch each place. The documentation gap is
separate and is fixed by publishing a contract per client kind.
On the client side, the Android auth work should be a standalone reusable
module with its own small host app, not a change to the FastReader app, and
it must avoid the now-deprecated encrypted-preferences library and the
Kotlin SDK's plaintext default storage.

## Why it matters

The owner wants the real Android reader's backend sign-in to be solved before
that app exists. This audit says what is already solved (most of it), which
backend and provider changes must land for redirect-based flows and
Google, and what the client must do so the proving-ground code can be lifted
into the real reader unchanged.

## What we decided

- **Recommended dispositions:** accept F001 to F008 and F010; defer F009
  until one stage observation confirms or clears its premise. F010 is the
  architectural outcome and F001, F002, F004, and F005 are its increments.
  F001 to F006, F009, and F010 are owned by Chunipers repositories and are
  carried across by the owner; F007 and F008 are owned by this repository
  and follow its normal definition and planning route.
- **Owner decisions:** Pending

## What happens next

| Outcome | Owner | Tracking issue |
| --- | --- | --- |
| Pre-auth bootstrap serves per-client redirect destinations without breaking the web app (F001) | Chunipers/reader-api, Chunipers/reader-web | Owner-carried; pending |
| Identity provider admits a native redirect destination and every email offers a code or an app-owned link (F002) | Chunipers/reader-db (web host for App Links) | Owner-carried; pending |
| reader-api documents the native client contract and fixes seven documentation discrepancies (F003) | Chunipers/reader-api | Owner-carried; pending |
| reader-api recognises a native client identifier in telemetry (F004) | Chunipers/reader-api | Owner-carried; pending |
| Provider contract manages native Google sign-in fields and local parity (F005) | Chunipers/reader-db | Owner-carried; pending |
| Token verifier tolerates clock skew and states its anonymous-identity policy (F006) | Chunipers/reader-api | Owner-carried; pending |
| Android auth work lives in a standalone reusable module with its own host app; FastReader untouched (F007) | cedagova/fastReader | Pending |
| General Android auth module contract and its proving-ground implementation (F008) | cedagova/fastReader | Pending |
| Pre-auth rate limiter proven to key on real client addresses, or switched to the trusted ingress address (F009) | Chunipers/reader-api | Owner-carried; pending |
| One client-aware identity contract: client kinds declared once, everything derived from it, web client migrated onto it (F010; F001, F002, F004, F005 become its increments) | Chunipers/reader-db, Chunipers/reader-api, Chunipers/reader-web | Owner-carried; pending |

## Limits and unknowns

- Hosted Supabase dashboard settings were not read: the JWT signing key type,
  the hosted access-token lifetime, session limits, and any Android Google
  client registration are owner questions, not audit findings.
- Whether the backend's per-address rate limiter distinguishes clients behind
  the CDN was inferred from configuration, not observed on stage.
- No native request was made against stage and no Android code was written;
  the client contract rests on the reference web client, the pinned backend
  tests, and platform documentation retrieved on 2026-09-11.
- Web Push notifications and the guest reading cookie are browser-shaped
  post-sign-in surfaces outside this question; a native client cannot satisfy
  the notifications capability as currently defined.

## Details

- [Technical report](report.md)
- [Dossier pull request and native independent review](https://github.com/cedagova/fastReader/pull/83)
