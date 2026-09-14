# Product Definition: Account library synced through the Reader API from FastReader (stage)

- Product definition issue: https://github.com/cedagova/fastReader/issues/104
- Product definition PR: https://github.com/cedagova/fastReader/pull/105
- Requirements brief: https://github.com/cedagova/fastReader/issues/104#issuecomment-5667864121
- Status: Ready for planning
- Classification: DECOMPOSE
- Definition lead: cedagova
- Started: 2026-09-14

## Pinned evidence baselines

| Repository | Baseline |
| --- | --- |
| `cedagova/fastReader` | `e180fbc0b13d36bb337a058196498af830e8a401` |
| `Chunipers/reader-api` | `a517fc6db64560df309bce656ec4c34e0bc7e1bd` (`origin/stage`) |
| `Chunipers/reader-web` | `3652f47745cada5be21184da89a31bc89ad5a17b` (`origin/stage`) |
| `Chunipers/reader-db` | `fe6c87d9220e1de269300eb2725ab8daad89c9f1` (`origin/stage`) |

## Objective

Owner objective (2026-09-14, verbatim): "signin process works correctly. let's
define a collection of issues to create a remotely persisted and auth based
synced library system. just as done for auth, the purpose is not to create
persistence for fastReader for the sake of it, but for aligning the reader-api
to work with different clients. Most of the library logic already exists in
reader-web/reader-api, so whatever we create in fastReader should reuse as much
as posible (or propose improvmenet to chunipers' repos) because the purpose is
to imprve chunipers. when this repo uses data that chunipers does not, like WPM
or whatever, just ignore it, we are not interested in creting a full sync
system for fastReader. So probably syn only books? not sure."

Read as: a signed-in FastReader keeps an **account library** — the same one
reader-web shows — through the Reader API's *existing* library, publication-
import and synchronization contracts, as the backend's second real client.
FastReader-only reading data (RSVP speed, token index, presentation settings)
stays on the device. Every place where the existing contracts do not let a
non-web client do what reader-web does becomes a recorded improvement proposal
for the owning Chunipers repository, not a FastReader workaround.

## User or operator need

The owner develops both FastReader and the Chunipers Reader backend. The
backend already carries a complete, server-authoritative library and sync
design (`reader.sync.v1`, publication imports, publication membership and
activity convergence contracts) that today has exactly one consumer,
reader-web, which co-evolved with it. Nothing has yet proven that a client
written independently of reader-web — different platform, different storage
model, different reading model — can bootstrap, keep in step, add a book,
remove a book and resume a position using only the published contract.

FastReader (v1.6.0, #100) already signs in to a Reader account against stage
from a real device. It is the natural second client: it has a real local
library keyed by the same content identity the backend uses (whole-file
SHA-256), a real reading position, and none of reader-web's code. What the
owner needs from this work is (1) the account library working from Android,
and above all (2) the list of things Chunipers must change or document for
that to be possible without reading reader-web's source.

## Actors and context

- **Owner (the only user):** developer of both sides, using FastReader on a
  phone and the emulator matrix against the Chunipers **stage** backend, and
  reader-web on stage in a browser as the other device.
- **Reader backend (stage):** reader-api at its stage origin, reader-db, and
  Supabase Storage bucket `reader-files`. Client kind `reader-android` 1.0.0,
  signed in through `:reader-auth` (#100). Production is not a target.
- **reader-web (stage):** the reference client; the owner uses it to observe
  what FastReader synced and to make changes FastReader must pick up.
- **Context:** the owner reads local EPUBs picked through the Files app as
  today, signed out or signed in, online or offline. When signed in, the
  account library appears beside the local one.
- **Triggers:** sign-in; app foreground; the owner adding a local book to the
  account, removing an account book, opening an account book that is not yet
  on this device, reading; a change made on another device.
- **Permissions:** none new. INTERNET (from `:reader-auth`) already exists;
  document access stays user-granted picks; app-private storage needs no
  permission.

## Desired outcomes

1. Signed in, the owner sees the account library on FastReader — the same
   books reader-web shows for that account — and it stays in step with
   changes made on other devices without a manual refresh.
2. The owner can add a book that is already on the device to the account
   library, with explicit consent that its bytes leave the device; a book
   that is too large or unsupported is refused with the backend's reason and
   the local book is untouched.
3. The owner can remove an account book (with the one immediate Undo the
   membership contract defines) and the removal reaches every device.
4. The owner can read an account book on this device that was added from
   another device, and a position reached in FastReader is visible on
   reader-web at the granularity the portable contract allows, and vice
   versa. (Owner decisions D1, D2, 2026-09-14.)
5. Books the owner reads only locally are never sent anywhere unless the
   owner adds them; signing in never adopts them; signing out never deletes
   them. Signed out and offline, FastReader is unchanged.
6. Every FastReader request is one the published contract declares; nothing
   is learned from reader-web's source. Every gap found on the way is a
   dated, evidenced proposal against the owning Chunipers repository.
7. The privacy statement, README, release-notes block and release gate say
   truthfully what now leaves the device, following the #100 pattern.

## Product behavior and flows

### Two kinds of book on the shelf

- **Device book** — what FastReader has today: an EPUB read in place through
  a user-granted document grant, identified by its whole-file SHA-256. Never
  contacts the backend. This is the backend's own "device-only" notion
  (`reader.publication-ownership.v1`: device-only reading requires zero API
  calls, zero uploaded bytes, zero account mutations).
- **Account book** — a member of the signed-in account's library, known to
  the backend by its canonical book identity and content SHA-256. It may or
  may not have its bytes on this device.

The same file can be both: a device book whose SHA-256 equals an account
book's content identity is shown once, as an account book that is also
present locally. Identity is content, never file path.

### Signed out

Exactly v1.6.0. No account-library surface, no network use outside the
account surface of #100.

### Signing in — bootstrap

On the first signed-in session, and whenever the backend says the client's
cursor is expired, FastReader loads the whole account library and its
per-book state from the backend, then follows the account's change stream
from the cursor it was given. Signing in never uploads or registers a device
book (membership contract case `sign-in-does-not-adopt-device-only`).

### Keeping in step

While signed in and online, FastReader picks up changes from other devices —
added books, removals, Undo, status and last-opened changes, positions (D1)
— on foreground and after its own writes, by consuming the account's change
stream from its last cursor. It never guesses the winner of a concurrent
change: the backend's admission order is the truth (activity-convergence and
publication-membership contracts: causal successor wins, true concurrency
resolved by later durable admission, never by device clock or percentage).

A change made in FastReader while offline is kept and sent when the network
returns, with the idempotency the contract requires so that a retry is never
a second admission.

### Adding a device book to the account

From a device book, **Add to account library**. FastReader states that the
file's bytes will be uploaded to the Reader account and asks for consent
(the backend requires `upload_consent: true`). It then follows the backend's
publication-import lifecycle: announce (format, size, SHA-256), transfer,
complete, wait until the import is `ready`. When ready, the backend itself
places the book in the library and in the change stream, and FastReader
shows the device book as an account book. Duplicate bytes already in the
backend become the same canonical book (dedup is the backend's).

Refusals are the backend's, shown with the backend's category: too large
(hosted cap 52,428,800 bytes today; FastReader reads the cap from the
import policy, never hard-codes it), unsupported, protected, malformed.
A failed or cancelled import leaves the device book exactly as it was and
creates no broken account entry (import contract: `local_state_retained`,
`broken_account_entry_created: false`).

### Removing an account book

**Remove from account** on an account book removes the membership for the
account — on every device — and offers the contract's one immediate Undo
(`undo.availability: immediateConfirmationLifetime`, non-blocking). Undo
restores the same identity and its reading activity. Removing from the
account never deletes the device's own file or grant; a device book that was
also an account book goes back to being a device book. A book that another
device removed while it is open here stays readable until closed and cannot
be reopened unless a later add or Undo wins (`remote-remove-keeps-open-session`).

Distinct from that, **Remove downloaded copy** (D2) frees this device's copy
of an account book without touching the account.

### Reading an account book on this device (decision D2)

An account book whose bytes are not on this device shows as such. Opening it
downloads the bytes through the backend's download grant into FastReader's
own private storage, verifies them against the content SHA-256, and then
reads them exactly like a device book. The copy is FastReader's to keep or
free; it is never part of a backup (the existing all-domain exclusion). No
network is needed to read a downloaded copy.

### Reading position across clients (decision D1)

For an account book, FastReader publishes the position it can state
portably — the section (href) and the fraction read — as the account's
reading progress for that book, and reads back the position another client
left, resuming at the nearest word to that section and fraction. FastReader's
own precise position (token index, pipeline version, structural fingerprint)
and its speed setting stay local, as today. Which position wins between two
devices is the backend's decision, never FastReader's or the web's.

### Library status and last opened

Opening an account book records last-opened for the account; finishing it
records finished; both are the backend's `library_item` fields and are what
reader-web's Continue reading card reads.

## States and failure behavior

| State | What the owner sees |
| --- | --- |
| Signed out | v1.6.0 library, no account surface in the library. |
| Signed in, bootstrapping | Account books appear as the load completes; device books are untouched. |
| Signed in, offline | Account library as last known; account actions queue and say so; device books fully usable; downloaded copies readable. |
| Cursor expired / ahead (`rebootstrap_required`) | Full reload without duplicated writes; nothing lost; a brief note. |
| Sync capability unavailable (`reader.sync.v1` unavailable in capabilities, or 5xx retryable) | Account library shown as last known, actions deferred with the reason; never a sign-out. |
| Session gone (per #100 / `:reader-auth`) | Signed out; account books remain listed as last known but read-only until sign-in; downloaded copies still open. |
| Add to account: consent | Plain statement that the file's bytes will be uploaded to the Reader account (stage) and kept there; Add / Cancel. |
| Add to account: in progress | Progress on the book; cancellable; app death resumes or restarts the transfer without a duplicate book. |
| Add to account: refused (too large / unsupported / protected / malformed) | The backend's category, plainly worded; the device book unchanged. |
| Add to account: same bytes already in account | Becomes the existing account book; no second copy. |
| Remove from account | Book leaves the shelf; one immediate Undo; a concurrent add/remove elsewhere settles by the backend's order. |
| Remote removal while open | Readable until close; cannot reopen unless re-added. |
| Open account book not on device | Download with progress; hash mismatch or failure keeps the book listed and not opened, with the reason. |
| Download grant unavailable / storage full | Reason shown; the book stays listed. |
| Position from another client | Resume at the nearest word to the portable position; the owner can decline in the same way the existing "front matter" offer works. |
| Conflict result on a non-activity resource | Never surfaced as a decision; FastReader adopts the canonical payload (`readerDecisionRequired: false`). |

## Requirements and acceptance

Requirement numbers start at REQ-501 (REQ-4xx is #100). Requirements marked
(D1)/(D2)/(D3) follow the owner decisions of 2026-09-14 recorded below.

### Account library on the shelf

- **REQ-501** Signed in, the library shows the account's books beside the
  device books; a device book whose content identity equals an account
  book's appears once. Signed out, the library is v1.6.0.
  *Accept:* with a book added on reader-web, FastReader signed in lists it
  (title, author) after foreground; the same EPUB picked locally does not
  produce a second row; signed out, no account row exists and the existing
  library goldens are unchanged.
- **REQ-502** Sign-in bootstraps the account library from the backend and
  then follows the account's change stream; changes made on another device
  (add, remove, Undo, status, last-opened, and position under D1) are
  reflected on the next foreground without a manual refresh.
  *Accept:* remove on reader-web → gone on FastReader after foreground;
  Undo on reader-web → back; an expired cursor forced on stage leads to a
  full reload with no duplicated rows and no re-sent writes.
- **REQ-503** Offline, the account library is shown as last known; account
  actions taken offline are kept and sent once online, and a retry never
  produces a second admission.
  *Accept:* remove in airplane mode, then online: one delete admitted
  (`applied`), the retry `replayed`; reader-web shows it gone.
- **REQ-504** FastReader never decides a conflict: the backend's canonical
  outcome is adopted for every resource, and no "choose a version" prompt
  exists.
  *Accept:* concurrent status changes on both clients settle to the same
  value on both after sync, with no prompt on either.

### Adding a device book to the account

- **REQ-505** A device book offers **Add to account library**, which states
  that the file's bytes will leave the device and requires an explicit
  consent before anything is sent; signing in or opening never adds a book.
  *Accept:* stage import records show zero imports and zero uploaded bytes
  after signing in and reading device books; one import after the consent.
- **REQ-506** The add follows the backend's publication-import lifecycle and
  its policy (formats, size cap, active limits are read from the policy,
  never hard-coded); a ready import appears as an account book on this device
  and on reader-web with the same identity; identical bytes already in the
  account become that book, not a second one.
  *Accept:* a ≤50 MiB EPUB added from FastReader appears on reader-web with
  the same title and is readable there; adding the same file again produces
  no new row; the import id in FastReader's evidence matches the row on stage.
- **REQ-507** A refused, failed or cancelled add shows the backend's
  category and leaves the device book exactly as it was, with no account
  entry.
  *Accept:* a >50 MiB EPUB shows "too large" with the cap from the policy and
  stays a readable device book; killing the app mid-transfer and reopening
  either resumes or restarts without a duplicate.

### Removing

- **REQ-508** **Remove from account** removes the membership for every
  device and offers the contract's one immediate Undo; the device's own file
  and grant are never touched; a device book that was also an account book
  remains a device book.
  *Accept:* remove → reader-web loses it; Undo within the confirmation →
  reader-web has it again with the same progress; the local file still opens.
- **REQ-509** A book removed by another device while open here stays
  readable until closed and cannot be reopened until re-added.
  *Accept:* remove on reader-web while reading on FastReader: reading
  continues; after close, the row reads removed and does not open.

### Reading an account book here (D2)

- **REQ-510** Opening an account book whose bytes are not on the device
  downloads them through the backend's grant into FastReader's private
  storage, verifies the content SHA-256, and then reads offline like a device
  book; **Remove downloaded copy** frees the copy without touching the
  account; downloaded copies are excluded from backup.
  *Accept:* a book uploaded from reader-web opens on FastReader after one
  download and again in airplane mode; a tampered download is refused with
  the reason; the backup procedure of `docs/evidence/46/` still transfers zero
  bytes.

### Position across clients (D1)

- **REQ-511** For an account book, FastReader publishes the portable
  position (section and fraction) as the account's reading progress and
  resumes from the position another client left at the nearest word;
  FastReader's precise token position and speed stay local. Which position
  wins is the backend's admission order.
  *Accept:* read to 40% on FastReader → reader-web's Continue reading shows
  the book at ~40% in the right chapter; move to chapter 8 on reader-web →
  FastReader offers to resume at the start of chapter 8's fraction; reading
  backwards on FastReader is accepted by the backend (`causal-progress-can-
  move-backward`).

### Unchanged reading product, truthful promises

- **REQ-512** With no account or no network, every existing behaviour is
  unchanged; device books never leave the device; FastReader-only data (WPM,
  token index, presentation settings, crash report) is never sent.
  *Accept:* goldens outside the new account-library surfaces unchanged;
  stage records show no `settings`, `note` or `bookmark` mutations from
  `reader-android`.
- **REQ-513** The privacy statement (string, Spanish copy, README block,
  release-notes block, per-sentence table), the README and `docs/release.md`
  say truthfully what now leaves the device and when (only books the owner
  adds, their membership, status, last-opened and portable position, to the
  Reader API and its storage on stage); the release gate's permission and
  cleartext proofs are unchanged (INTERNET plus the platform self-permission,
  no cleartext).
  *Accept:* `PrivacyStatementTest` passes with the new text; `scripts/release.sh`
  verify passes on a release build.
- **REQ-514** Every backend request FastReader makes is declared by the
  published Reader API contract at a pinned identity, and the client kind is
  `reader-android`; FastReader honours the capabilities document's
  `reader.sync.v1` availability before syncing.
  *Accept:* the pinned OpenAPI identity is recorded in the repository; a
  capabilities document with `reader.sync.v1` unavailable leads to the
  deferred state, not to requests.

### Chunipers proposals

- **REQ-515** Each gap found while meeting REQ-501–514 that requires a
  Chunipers change (contract, documentation, artifact, or reader-web
  behaviour) is recorded in this definition with evidence and, under
  decision D3, filed as an issue in the owning Chunipers repository and
  linked from the root. FastReader never works around such a gap by reading
  reader-web's source or by inventing a private protocol.
  *Accept:* the root issue links every filed proposal; none of FastReader's
  requests is outside the published contract.

## Accessibility and content

- New controls meet the existing bar (REQ-060/REQ-301): TalkBack labels,
  48 dp targets, font scale, announced state changes (download progress,
  import progress, refusal reasons).
- Every new string has a hand-written Spanish twin; the privacy statement's
  Spanish copy is edited in the same change.
- Refusal and error copy names the backend's category in plain words, then
  the backend's code and request id (testing surface; the code is useful).

## Privacy, security, and policy

- **What leaves the device, and only after an explicit owner action:** the
  bytes and metadata of a book the owner *adds* (title, author, language,
  size, SHA-256, file name); for account books, membership, status,
  last-opened and — under D1 — the portable position. Recipients: the Reader
  API (stage) and its Supabase Storage (stage). Nothing about device books,
  RSVP speed, token positions, presentation settings or crash reports is sent.
- **Consent:** adding a book is the consent for its bytes; the backend
  refuses without it. Signing in is not consent to upload anything.
- **Storage:** downloaded copies live in FastReader's private storage,
  excluded from backup like everything else; the session store stays the
  library's. Removing a downloaded copy deletes the bytes.
- **Authorization:** the account's bearer per `reader-auth/CONTRACT.md`; the
  backend's actor scoping guarantees another account's data is never
  visible (`account-actors-remain-isolated`).
- **Retention:** account data lives in the account until removed; a removed
  book's bytes are the backend's retention policy; local copies until the
  owner frees them or uninstalls.
- **Transport:** HTTPS only in release; uploads go to the backend's storage
  through the grant the backend issues, never to a FastReader-chosen URL.
- **Secrets:** unchanged from #100; no new value.

## Success measures and guardrails

- **Success:** on `Phone_Mid_API36` against stage with reader-web as the
  other device, the full round trip is recorded under `docs/evidence/104/`:
  add from FastReader → read on web; add on web → download and read on
  FastReader; remove and Undo in both directions; offline remove replayed
  once; (D1) position both ways; and the Chunipers proposal list filed.
- **Guardrail:** signed out or offline, FastReader is indistinguishable from
  v1.6.0.
- **Guardrail:** zero API calls, zero uploaded bytes and zero account
  mutations for a device book until the owner adds it (the backend's own
  device-only cases).
- **Guardrail:** no request outside the published contract; no reader-web
  code or private protocol.
- **Guardrail:** the release gate's permission and cleartext proofs unchanged.

## Constraints and non-goals

- **Backend:** stage only; client kind `reader-android` 1.0.0.
- **Contracts are authoritative and reused, not restated:**
  `reader.sync.v1` (mutations and deltas), `reader.publication-ownership.v1`
  and `reader.publication-membership.v1` (device-only vs account, consent,
  removal, Undo, open-session rule), `reader.activity-convergence.v1`
  (who wins), `reader.portable-semantics.v1` (portable locator), the
  publication-import policy (formats, caps), `reader-auth/CONTRACT.md` and
  the `reader-android` client contract (headers, refresh, 401 policy).
- **Non-goals:** syncing FastReader settings or portable preferences; notes,
  bookmarks, covers as synced assets; the catalog ("Explore books") surface;
  reader-web's automatic offline-copy policies; production; non-EPUB
  formats beyond what the import policy already accepts for a file the owner
  picks; a FastReader-private sync protocol; consumer polish; cutting a
  release.

## Evidence

Direct evidence, pinned at the baselines above.

**reader-api (`origin/stage`)**
- `app/features/reader_sync/api.py` — `POST /v1/reader/sync/mutations`
  (batch 1–50, per-item atomic, idempotent), `GET /v1/reader/sync/deltas`
  (`after_cursor`, `limit` ≤500, `rebootstrap_required`).
  `app/contracts/reader_sync.py` — resource types `profile | book |
  library_item | reading_progress | note | bookmark | settings`; results
  `applied | replayed | superseded | conflict | rejected`; membership outcome.
- `app/features/reader_sync/activity_convergence.py`,
  `publication_membership.py`; `contracts/reader-activity-convergence.v1.json`,
  `contracts/reader-publication-membership.v1.json` — the convergence and
  membership cases quoted above.
- `app/contracts/publication_imports.py` — `POST /reader/v1/imports`
  (`client_import_id`, `ownership_intent: account_library`,
  `upload_consent`, `source_format`, `size_bytes` ≤ 52,428,800, `sha256`),
  TUS transfer grant, statuses `pending_upload … ready | failed |
  cancelled`, `PublicationOwnershipPolicy` (device-only: zero calls, zero
  bytes, zero mutations; activity types `reading_progress|note|bookmark`).
  Cap follow-up: Chunipers/reader-api#384 (restore 200 MiB).
- `app/contracts/reader_products.py` — `PutReaderLibraryItemRequest
  {status: queued|reading|finished|archived, last_opened_at}`,
  `PutReaderProgressRequest {progress_percent 0–100, chapter_title, locator}`;
  `ReaderLibraryItem {book, assets, cover_status, status, last_opened_at}`.
  `app/contracts/reader_semantics.py:169` normalizes any `locator` into the
  portable v1 form (`href`, `epub_cfi`, `progression`) server-side.
- `POST /v1/reader/assets/{asset_id}/download-grant` (OpenAPI) — bytes for a
  second device.
- `app/contracts/reader_capabilities.py` — capability keys `reader.sync.v1`,
  `reader.ai-quota.v1`, `reader.notifications.registration.v1`.
- `docs/client-contracts/{README,core,reader-android}.md` (revision
  2026-09-13.1) — auth-only client contract; names a published Kotlin archive
  `reader-api-kotlin-<sha>.tar.gz`. `docs/client-artifacts.md` describes the
  publish workflow. **Observed 2026-09-14 via the GitHub API:** the latest
  reader-api release is `v0.0.11` (2026-05-28) with a single asset
  `v0.0.11.json`; no Kotlin, TypeScript or Swift archive has been published
  on any release.

**reader-db (`origin/stage`)**
- `supabase/migrations/20260822010000_add_reader_sync_state.sql` — sync
  ledger (resources with `revision`, changes with identity cursor, 7-day
  idempotency, 30-day tombstones, actor cursors).
- `20260822040000_bind_ingestion_library_sync.sql` — a ready import inserts
  the library item and the sync change atomically.
- `20260822060000_bind_deduplicated_ingestion_assets.sql`,
  `20260822030000_add_publication_ingestion_lifecycle.sql` —
  `reader_publications.content_sha256` unique; identical bytes reuse one
  canonical asset.
- `20260514030000_reader_baseline.sql` — `user_library_items`,
  `reading_progress (locator, progress_percent, chapter_title)`; no
  shelves, tags or collections exist.

**reader-web (`origin/stage`)**
- `packages/clients/src/api/readerSyncClient.ts`, `readerProductClient.ts`,
  `publicationImportClient.ts` — the web client uses exactly the routes above
  (codegen from `contracts/reader-api.openapi.json`; `check:reader-api-client`
  fails CI on drift).
- `packages/library/src/infrastructure/libraryScope.ts`,
  `outboxLibraryRepository.ts:440-560` — storage scoped `anonymous` /
  `user-<id>`; sign-in does not adopt the anonymous library; server↔local
  merge keyed by alias → remote id → import canonical id → SHA-256.
- `packages/library/src/domain/bookProgressMerge.ts` — **client-side
  "furthest progress wins"** when merging local and server progress, whereas
  the server contract says the causal successor wins and percentage never
  selects a winner.
- `packages/contracts/src/reader.ts:28-32` — web `locator` =
  `{fileFingerprint, locationSnapshot{cfi, href, section, progressPercent,
  runtimeLocator}}`; `serverLibraryRecords.ts:92` reads `progress_percent`
  from the server row. Whether the web resumes from a portable locator
  written by another client is not shown by any test found (inference).
- `docs/sync-portability-plan.md`, `docs/adr/0001-storage-sync-architecture.md`.

**fastReader (`e180fbc`)**
- `app/src/main/java/com/cedagova/fastreader/library/Catalog.kt` —
  `Book.id = sha256:<hex>` (whole file), `BookSource {uri, origin, folderId,
  availability}` device-only, `ReadingState {bookDigest, tokenIndex,
  pipelineVersion, structuralFingerprint, progressFraction, wpm}`;
  `CatalogSchema.CURRENT_VERSION = 10`; catalog is one JSON document.
- `docs/plans/cedagova-fastReader-1/plan.md` AD-1 "in-place SAF access, no
  import copies" — D2 changes this for account books only.
- `reader-auth/.../api/ReaderApiClient.kt` — generic authenticated
  `get`/`put`, `X-Reader-Client`, `clientVersion`; no token exposure.
- `docs/privacy-statement.md`, `PrivacyStatementTest` — current promise
  "your books, your reading positions, your settings … are never sent".
- `version.properties` 1.6.0 / 8.

## Assumptions

- A1 The stage backend's `reader.sync.v1` capability reports available for
  `reader-android` 1.0.0 (to be observed at planning; if not, the first
  Chunipers proposal is that gate).
- A2 A portable position of section + fraction is honest for an RSVP
  reader and useful to a paginated one; exact word equivalence is not
  promised by either side.
- A3 The publication-import policy on stage accepts `epub` for account
  admission (`account_admission: upload_only`, device-renderable).
- A4 The Kotlin archive gap does not block FastReader: the exact OpenAPI
  source is in the repository and any generator can be pointed at it.

## Owner decisions

| Date | Decision | Rationale | Affects |
| --- | --- | --- | --- |
| 2026-09-14 | **D1** Sync scope = account library membership, book bytes (import/download), library status and last-opened, and the portable reading position (section + fraction) in both directions. FastReader-only data (WPM, token index, pipeline state, presentation settings, crash report) stays local. Portable preferences are out. | Owner chose the recommended option ("Books + membership + position"). Exercises sync, imports, membership and activity convergence from a second client and surfaces the reader-web progress-merge and portable-locator gaps (CP-3, CP-4). Preferences rejected: the API refuses device-local controls and FastReader's settings are RSVP presentation. | REQ-502, REQ-511, REQ-512, OUT504 |
| 2026-09-14 | **D2** An account book whose bytes are not on this device is downloaded on open through the backend's grant into FastReader's private storage, SHA-256-verified, and read offline like a device book; a downloaded copy can be freed without leaving the account. This amends AD-1 ("no import copies") for account books only. | Owner chose the recommended option. Makes the multi-device test two-directional and exercises the download-grant path reader-web uses. | REQ-510, OUT503, privacy statement (REQ-513) |
| 2026-09-14 | **D3** Every Chunipers proposal recorded here (CP-1…CP-5 and any found later) is filed, after the owner approves this definition, as an issue in the owning Chunipers repository with the Chunipers Claude worker identity, evidence-linked, and linked from the root issue. | Owner chose the recommended option; keeps the list from going stale and puts the proposals into Chunipers' own definition/planning flow. | REQ-515, OUT505 |

## Remaining uncertainty

- Whether reader-web resumes from a portable locator written by a non-web
  client (candidate Chunipers proposal; observed at planning against stage).
- Exact stage values of the import policy and capability document for
  `reader-android` (observed at planning).

## Chunipers proposals (to be filed under D3)

| Key | Repository | Proposal | Evidence |
| --- | --- | --- | --- |
| CP-1 | Chunipers/reader-api | Publish the client contract's **library and sync chapter**: bootstrap (full list vs. deltas from cursor 0), cursor lifecycle and rebootstrap, import → membership atomicity, download grant, idempotency-key rules — so a client can implement the account library from `docs/client-contracts/` alone, as it can auth today. | `docs/client-contracts/core.md` covers auth only. |
| CP-2 | Chunipers/reader-api | Actually publish the generated client archives (Kotlin at least) the `reader-android` contract references, or drop the reference until they exist. | Latest release `v0.0.11` (2026-05-28), one JSON asset; `docs/client-artifacts.md`. |
| CP-3 | Chunipers/reader-web | Align local progress merge with `reader.activity-convergence.v1` (server admission order), or document the deliberate divergence; a client honouring the server contract sees the web override a backward move. | `bookProgressMerge.ts` "furthest progress wins". |
| CP-4 | Chunipers/reader-web | Resume from a `reader.portable-semantics.v1` locator (href + progression) written by another client; write one alongside the web-specific snapshot. | `contracts/src/reader.ts`, `reader_semantics.py:169`. |
| CP-5 | Chunipers/reader-api | Capabilities document: declare publication-import availability (today only `reader.sync.v1`, ai-quota, notifications), so a client can defer "Add to account" for the right reason. | `reader_capabilities.py:6-10`. |

## Product issue graph

| Key | Kind | Parent | Title | Issue |
| --- | --- | --- | --- | --- |
| ROOT | ROOT | None | Account library synced through the Reader API from FastReader (stage) | https://github.com/cedagova/fastReader/issues/104 |
| OUT501 | OUTCOME | ROOT | Account library on the shelf, kept in step, removable with Undo (REQ-501–504, 508–509, 512–514) | https://github.com/cedagova/fastReader/issues/106 |
| OUT502 | OUTCOME | ROOT | Add a device book to the account through publication import (REQ-505–507) | https://github.com/cedagova/fastReader/issues/107 |
| OUT503 | OUTCOME | ROOT | Read an account book on this device from a verified downloaded copy (REQ-510) | https://github.com/cedagova/fastReader/issues/108 |
| OUT504 | OUTCOME | ROOT | Reading position portable between FastReader and reader-web (REQ-511) | https://github.com/cedagova/fastReader/issues/109 |
| OUT505 | OUTCOME | ROOT | Chunipers alignment proposals filed and linked (REQ-515, CP-1…CP-5) | https://github.com/cedagova/fastReader/issues/110 |

## Publication verification

Owner decisions D1–D3 recorded 2026-09-14. Pending: outcome publication,
graph verification, brief, owner approval, semantic-anchor review.
