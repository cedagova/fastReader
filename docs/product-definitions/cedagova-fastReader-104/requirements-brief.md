# Requirements Brief: Account library synced through the Reader API from FastReader (stage)

## Problem and intended outcome

The Chunipers Reader backend has a complete server-authoritative library and
sync design (`reader.sync.v1`, publication imports, publication membership,
activity convergence, portable semantics) with exactly one consumer,
reader-web, which co-evolved with it. Nothing proves that an independently
written client can use it from the published contract alone. FastReader
(Android, signed in against stage since v1.6.0) becomes that second client:
signed in, it keeps the same account library reader-web shows, and every gap
found on the way becomes an evidenced improvement proposal for the owning
Chunipers repository. FastReader-only data (WPM, token index, presentation
settings) stays on the device.

## Proposed behavior and main flows

- Two kinds of book on the shelf: **device books** (today's in-place local
  EPUBs, never sent anywhere) and **account books** (members of the signed-in
  account's library). The same file is shown once, matched by content SHA-256.
- Sign-in bootstraps the account library and then follows the account's
  change stream; changes from other devices appear on foreground without a
  manual refresh; offline actions are kept and sent once, never twice.
- **Add to account library** on a device book asks for explicit upload
  consent, then follows the backend's publication-import lifecycle and policy
  (formats, 50 MiB cap read from the policy); refusals are the backend's and
  leave the device book untouched; identical bytes become the same book.
- **Remove from account** removes membership on every device with the
  contract's one immediate Undo; the device file is never touched; a remote
  removal keeps an open book readable until closed.
- Opening an account book not on this device downloads a SHA-256-verified
  private copy through the backend's grant and reads it offline; the copy can
  be freed without leaving the account (D2).
- The portable position (section + fraction) is published for account books
  and resumed from other clients at the nearest word; who wins is the
  backend's admission order, never a client's (D1).
- Explicit sign-out and a session the backend no longer accepts are one
  signed-out state: account-only rows leave the shelf, downloaded copies stay
  and open as device books, offline-queued account actions are held for the
  next sign-in to the same account; sign-out deletes nothing (D4).
- The privacy statement, README, release-notes block and `docs/release.md` are
  rewritten truthfully; the release gate's permission and cleartext proofs are
  unchanged.

## Scope and non-goals

In: account library membership, book bytes (import and download), library
status and last-opened, portable reading position, truthful promises, and
the Chunipers proposals. Out: FastReader settings or portable preferences,
notes, bookmarks, cover assets, the catalog surface, reader-web's automatic
offline-copy policies, production, a FastReader-private protocol, consumer
polish, cutting a release.

## Product outcomes

- ROOT #104 — the account library from FastReader as the second client.
- OUT501 — account library on the shelf, kept in step, removable with Undo; sign-out per D4 (REQ-516).
- OUT502 — add a device book to the account through publication import.
- OUT503 — read an account book here from a verified downloaded copy.
- OUT504 — reading position portable between FastReader and reader-web.
- OUT505 — Chunipers alignment proposals filed and linked.

## Important constraints and success measures

Stage only; client kind `reader-android` 1.0.0. The backend's contracts are
authoritative and reused, never restated: `reader.sync.v1`,
`reader.publication-ownership.v1`, `reader.publication-membership.v1`,
`reader.activity-convergence.v1`, `reader.portable-semantics.v1`, the import
policy, `reader-auth/CONTRACT.md`, the `reader-android` client contract.
Success: the full round trip recorded against stage with reader-web as the
other device (add both ways, remove and Undo both ways, offline remove
replayed once, position both ways) and the proposals filed. Guardrails:
signed out or offline FastReader is indistinguishable from v1.6.0 except for
downloaded copies, which behave as device books; zero API
calls, bytes or mutations for a device book until the owner adds it; no
request outside the published contract; release gate proofs unchanged.

## Evidence, assumptions, and uncertainty

Direct evidence pinned at `reader-api@a517fc6d…`, `reader-web@3652f477…`,
`reader-db@fe6c87d9…` (`origin/stage`) and `fastReader@e180fbc0…`: the sync
routes and resource types, the import contract and 50 MiB cap, the
membership and convergence cases, `library_item` and progress shapes,
server-side portable-locator normalization, reader-web's client-side
"furthest progress wins" merge, and the unpublished Kotlin client archive
(latest reader-api release `v0.0.11`, 2026-05-28, one JSON asset).
Assumptions: `reader.sync.v1` is available to `reader-android` on stage; a
section + fraction position is honest for RSVP and useful to a paginated
reader; the import policy accepts EPUB for account admission. Uncertainty:
whether reader-web resumes from a portable locator written by another client;
exact stage policy and capability values.

## Owner decisions

2026-09-14: **D1** sync scope = membership + bytes + status/last-opened +
portable position, FastReader-only data local, no preferences; **D2** account
books are downloaded on open into a verified private copy (amends AD-1 for
account books only); **D3** Chunipers proposals are filed as issues in the
owning repositories after approval and linked from the root (filed:
reader-api #511, #512, #513; reader-web #1907, #1908); **D4** explicit
sign-out and session-gone are one signed-out state — account-only rows leave,
downloaded copies stay as device books, queued actions held for the same
account, nothing deleted.

## Links and next action

- Root issue: https://github.com/cedagova/fastReader/issues/104
- Definition PR: https://github.com/cedagova/fastReader/pull/105
- Next action: `plan https://github.com/cedagova/fastReader/issues/104`
