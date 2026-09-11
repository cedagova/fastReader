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
integrator documentation only tells the web story. On the client side, this
repository promises to never touch the network, so it cannot host the
experiment without a product decision, and the Android implementation must
avoid the now-deprecated encrypted-preferences library and the Kotlin SDK's
plaintext default storage.

## Why it matters

The owner wants the real Android reader's backend sign-in to be solved before
that app exists. This audit says what is already solved (most of it), which
backend and provider changes must land for redirect-based flows and
Google, and what the client must do so the proving-ground code can be lifted
into the real reader unchanged.

## What we decided

- **Recommended dispositions:** accept F001 to F008; defer F009 until one
  stage observation confirms or clears its premise. F001 to F006 and F009 are
  owned by Chunipers repositories and are carried across by the owner; F007
  and F008 are owned by this repository and follow its normal definition and
  planning route.
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
| Product decision on where a network-capable auth experiment lives in this repository (F007) | cedagova/fastReader | Pending |
| General Android auth module contract and its proving-ground implementation (F008) | cedagova/fastReader | Pending |
| Pre-auth rate limiter proven to key on real client addresses, or switched to the trusted ingress address (F009) | Chunipers/reader-api | Owner-carried; pending |

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
