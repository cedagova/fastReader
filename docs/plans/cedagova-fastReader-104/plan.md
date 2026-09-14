# Implementation Plan: Account library synced through the Reader API from FastReader (stage)

- Planning issue: https://github.com/cedagova/fastReader/issues/104
- Planning PR: https://github.com/cedagova/fastReader/pull/111
- Status: Review
- Root classification: INCREMENTAL
- Delivery topology: INCREMENTAL
- Planner: Planning lead (Claude)
- Started: 2026-09-14
- Product definition: https://github.com/cedagova/fastReader/pull/105
- Product definition head: 418e14b4951c37e8afd945f9c4480fb0cdd36015

## Pinned baselines

| Repository | Baseline |
| --- | --- |
| `cedagova/fastReader` | `e180fbc0b13d36bb337a058196498af830e8a401` |
| `Chunipers/reader-api` | `a517fc6db64560df309bce656ec4c34e0bc7e1bd` |
| `Chunipers/reader-web` | `3652f47745cada5be21184da89a31bc89ad5a17b` |
| `Chunipers/reader-db` | `fe6c87d9220e1de269300eb2725ab8daad89c9f1` |

The three Chunipers baselines are the `origin/stage` heads the definition
pinned; they are evidence of the contract FastReader consumes, not repositories
this plan changes. The Reader API contract identity FastReader pins is the
OpenAPI document at that reader-api head,
`contracts/reader-api.openapi.json`, sha256
`a550abfd7046368681d02aec50e80e80e372b152416c744669dd72ee534c6f9e`
(387,828 bytes, `info.version` 0.1.0), the same identity the published client
contract family (`docs/client-contracts/`, revision 2026-09-13.1) was written
against.

## Preserved objective and boundaries

The approved objective is the product definition at the pinned head
(`docs/product-definitions/cedagova-fastReader-104/definition.md`, PR #105 at
`418e14b4`; Requirements Brief:
https://github.com/cedagova/fastReader/issues/104#issuecomment-5667864121):
signed in against the Chunipers stage backend, FastReader keeps the **account
library** — the same one reader-web shows — through the Reader API's existing
contracts, as the backend's second real client. Device books stay device-only
until the owner adds one with explicit upload consent; account books can be
removed with the membership contract's one Undo, downloaded to a SHA-256-
verified private copy and read offline; the portable reading position
(section + fraction) travels both ways; FastReader-only data (WPM, token
index, presentation settings, crash report) never leaves the device; the
privacy statement, README, release-notes block and `docs/release.md` are
rewritten truthfully with the release gate's proofs unchanged; every request is
one the pinned published contract declares; every gap becomes a filed Chunipers
proposal. Signed out or offline, FastReader is indistinguishable from v1.6.0
except that downloaded copies remain as device books.

Everything in that definition is preserved unchanged: REQ-501 to REQ-516 and
their acceptance, the flows and states table, the owner decisions D1 (scope),
D2 (verified private download copies, amending AD-1 for account books only),
D3 (Chunipers proposals filed in the owning repositories — done: reader-api
#511, #512, #513; reader-web #1907, #1908) and D4 (sign-out and session-gone
are one signed-out state: account-only rows leave, downloaded copies stay as
device books, offline-queued actions are held for the same account and
discarded for a different one, nothing deleted), the evidence, assumptions
A1–A4, the remaining uncertainty, the non-goals (no settings or portable
preferences, no notes, bookmarks or cover assets, no catalog surface, no
automatic offline-copy policies, no production, no FastReader-private
protocol, no consumer polish, no release cut) and the success measure (the
full round trip recorded against stage with reader-web as the other device
under `docs/evidence/104/`). The five outcome issues #106–#110 remain the
native children of root #104 and keep their `Definition handoff:
PLANNING_REQUIRED` provenance. The authoritative contracts named in the
definition — `reader.sync.v1`, `reader.publication-ownership.v1`,
`reader.publication-membership.v1`, `reader.activity-convergence.v1`,
`reader.portable-semantics.v1`, the import policy, `reader-auth/CONTRACT.md`
and the `reader-android` client contract — are consumed, never restated. This
plan adds system-level HOW and executable leaves; it does not reinterpret the
product contract.

Two notes the definition reviewer left for planning are carried here rather
than back into the definition: the moment FastReader *publishes* its position
is fixed below (AD-25); the privacy text must match what has actually shipped
at every point where a release could be cut, which AD-27 makes a completion
rule of every increment.

## Classification

- **ROOT #104 — `INCREMENTAL`.** The five native outcome children are each a
  delivery-sized effort with an independent delivery boundary, and the later
  ones build on the first: nothing about importing (#107), downloading (#108)
  or exchanging positions (#109) can be verified until the account library is
  on the shelf and kept in step (#106), and #108 and #109 each need a book that
  reached the account from the *other* client, which only #107 or #108 can
  produce. Flattening them into one project run would put the whole feature
  behind one merge gate; the owner asked for the app to be testable step by
  step and has approved each increment's merge separately on #36. Root #104
  becomes a tracking-only root.
- **#106 OUT501 — `GROUP` (ready), sequence 001.** Four leaves: the Reader
  library contract module, the account store and sync engine, the shelf
  surface with remove/Undo and sign-out, and the truthful-promises leaf.
  Topology `COLLECTOR`.
- **#107 OUT502 — `GROUP` (ready), sequence 002.** Two leaves: the
  publication-import client with resumable transfer, and the Add-to-account
  flow. Topology `COLLECTOR`.
- **#108 OUT503 — `GROUP` (ready), sequence 003.** Two leaves: the verified
  private-copy store on the download grant and the account-copy open path, and
  the shelf surface for download, offline reading and freeing a copy. Topology
  `COLLECTOR`.
- **#109 OUT504 — `GROUP` (ready), sequence 004.** Two leaves: the portable
  position mapping and its publish/consume path through the sync engine, and
  the resume offer. Topology `COLLECTOR`.
- **#110 OUT505 — `GROUP` (ready), sequence 005.** One leaf: the integrated
  stage round trip recorded as the root's success evidence, plus closing the
  Chunipers proposal log (filing anything 001–004 surfaced, confirming the
  five filed issues are linked). Topology `DIRECT`.

No increment is `DEFERRED`. Every requirement is fixed by the pinned
definition and its execution path is knowable from the pinned code and
contracts today; nothing in 002–005 depends on evidence only an earlier
delivery can produce. The sequence edges carry the product ordering; they are
not deferrals. Nothing is `ALREADY_SATISFIED`: REQ-515's five proposals are
filed, but the outcome also covers gaps surfaced during 001–004 and the root's
round-trip evidence, both absent at the baseline.

## Current-state evidence

All at fastReader `e180fbc0` unless stated; Chunipers facts at the pinned
`origin/stage` heads.

- **Composition.** `LibraryGraph` is plain constructor injection with no DI
  framework: a `DocumentGateway` (SAF), a `CoverStore`, a `FileCatalogStore`
  and one `LibraryRepository`; the UI receives the graph by parameter, the
  reader alone has a ViewModel. `FastReaderApplication` builds the graph, the
  optional `ReaderAuthClient` and a `ReaderAccountController`, and its
  `ProcessLifecycleOwner` `onStart` hook already fires a rescan and the
  library's foreground refresh — the one existing trigger point for "on
  foreground".
- **Repository writes are serialized** by one mutex through
  `mutateCatalog`; the catalog is one JSON document written atomically
  (tmp, fsync, rename) by a kotlinx.serialization codec with
  `ignoreUnknownKeys = true`, so **unknown keys are dropped on every save**.
  Schema is version 10 with one raw-`JsonObject` migration per step; a newer
  document is refused, never rewritten.
- **Identity.** `Book.id` is the whole-file SHA-256 as `sha256:<hex>`; it keys
  `readingStates`, `removedBookIds`, `frontMatterOfferedBookIds`,
  `lastReadBookId` and cover files. The backend's content identity is the
  same digest (`reader_publications.content_sha256`, the import request's
  `sha256`, the asset checksum `sha256:<hex>`).
- **Open path.** The reader opens a book through `fun interface
  EpubByteSource { open(): InputStream; openChannel(): SeekableByteChannel? }`
  inside a `BookOpenRequest` (bytes, identity, origin). `EpubArchives.open`
  prefers the seekable directory strategy (which also yields the structural
  fingerprint) and falls back to streaming. A `java.io.File` satisfies the
  interface with no SAF involvement and keeps the directory strategy.
- **Position.** `ReadingState` holds `bookDigest`, `tokenIndex`,
  `pipelineVersion`, `structuralFingerprint`, `progressFraction`, `wpm`;
  `ReadingPositionWriter` coalesces writes to at most one per 500 ms and every
  non-word event (pause, jump, speed change, background, close) flushes.
  `BookContent.chapters` carries, per section, `spinePath` (the href),
  `startTokenIndex`, `endTokenIndex`, `tokenCount` and `title`; every token
  knows its chapter index. A section-plus-fraction position is therefore
  computable both ways from data the reader already has.
- **Front-matter offer** is the existing "offer once, settle whichever way it
  is answered" pattern: a nullable offer value from the ViewModel, joined with a
  persisted settled-set in the route, drawn by the screen as a value.
- **`:reader-auth`.** `ReaderApiClient` exposes `get(path)` and `put(path,
  body)` over `JsonObject`, attaches the bearer, `X-Reader-Client`,
  `X-Request-ID` and `clientVersion`, and maps every outcome to a
  `ReaderAuthException` branch (`SignedOut` on a protected 401 per the
  contract's rule, `TryLater` on 429/502, `Forbidden`, `ApiError`,
  `NetworkUnavailable`); it has **no `post`**, exposes no raw response, and
  never exposes tokens. Ktor 3.5.1 on the OkHttp engine with
  kotlinx.serialization; `ktor-client-mock` drives its tests. The app-side
  `ReaderAccountGateway` deliberately exposes no generic call; a
  `FakeReaderAccountGateway` scripts the account surface's tests.
- **No background-work dependency exists** (no WorkManager, JobScheduler,
  Room, DataStore, receivers); the manifest declares one activity and no
  permission element; `scripts/release.sh` compares the release APK's
  permission set **whole** against exactly INTERNET plus the platform
  self-permission, so any new permission fails the gate.
- **Promises.** `PrivacyStatementTest` holds four copies equal (string,
  `docs/privacy-statement.md` block, README block, `docs/release-notes/
  v<versionName>.md` block) and asserts nine literal claims, including "your
  books, your reading positions, your settings and any crash report stay on
  this device and are never sent"; Spanish is a hand-maintained twin gated by
  lint's `MissingTranslation`. `version.properties` is 1.6.0 / 8 and **v1.6.0
  is not yet published**; `docs/release-notes/v1.6.0.md` exists.
- **UI.** `LibraryScreen` is a pure function of `LibraryUiState` built by
  `buildLibraryUiState`; `BookRow` shows one status line and a per-row action
  slot; removal is confirm-dialog then an undo bar fed by
  `repository.undoableRemoval`. 96 goldens (25 library, 34 reader, 11 settings,
  9 account, 7 folders, 3 crash offer), parameterised per test method by
  qualifiers (reference/compact phone, `+es`, font scale 1.3, dark, tablet,
  landscape). 232 strings with 232 Spanish twins.
- **Backend (reader-api `a517fc6d`).** `POST /v1/reader/sync/mutations`
  (1–50 envelopes, each independently atomic and idempotent; results
  `applied | replayed | superseded | conflict | rejected` with canonical
  payload, revision, cursor) and `GET /v1/reader/sync/deltas` (actor-scoped
  cursor stream, `cursor_expired` → `rebootstrap_required`); `GET
  /v1/reader/library` and `GET /v1/reader/progress` for bootstrap;
  `library_item` upsert `{status, last_opened_at}`, delete and restore (Undo:
  empty payload) converge by admission order and never touch bytes or
  activity; `reading_progress` upsert `{progress_percent, chapter_title,
  locator}` with the server normalising any locator into
  `reader.portable-semantics.v1` (`href`, `epub_cfi`, `progression`);
  publication imports `GET /reader/v1/imports/policy`, `POST
  /reader/v1/imports` (consent, format, size ≤ policy cap — 52,428,800 bytes
  hosted today — and sha256), a signed TUS transfer grant to Storage (chunked
  PATCH, HEAD to recover the offset), `POST …/complete`, `GET …/{id}`,
  `POST …/cancel`; a ready import inserts the library item and the sync change
  atomically (reader-db `20260822040000`); identical bytes reuse one canonical
  asset; `POST /v1/reader/assets/{asset_id}/download-grant` for bytes. EPUB's
  account admission is `upload_only` (no conversion) and imports are gated by
  a server flag exposed as `enabled` in the policy. The capabilities document
  carries `reader.sync.v1` with availability and reason; the `reader-android`
  contract requires `clientVersion` on it. reader-api's pinned client generator
  is openapi-generator-cli 7.17.0 with default Kotlin options (OkHttp + Moshi).
- **reader-web** merges local and server progress by furthest-wins
  (`bookProgressMerge.ts`) and reads `href` but not `progression` from a
  foreign locator (definition CP-3/CP-4, filed as #1907/#1908).

## Selected implementation direction

All FastReader work stays in `cedagova/fastReader`. System-level shape:

1. **One new library module, `:reader-library`, owns the account-library
   contract.** It depends on `:reader-auth` for the authenticated transport
   and adds the request/response models and typed operations FastReader uses:
   bootstrap lists, sync mutations and deltas, library-item and progress
   envelopes, the import lifecycle with its resumable transfer, the download
   grant, and the capability gate. `:reader-auth` gains one additive,
   behaviour-neutral `post` beside `get`/`put` (the same kind of change #100
   made for the request id); `CONTRACT.md` is untouched. Transfers to and from
   Storage (TUS upload, grant download) use the grant's own headers on a plain
   HTTP client, never the bearer. The module pins the OpenAPI identity above
   and proves that every request and response shape it uses agrees with that
   pinned document: hand-written kotlinx.serialization models on the existing
   Ktor stack, contract-tested against the committed OpenAPI document (owner
   decision P1, 2026-09-14: Choose A).
2. **Account state lives beside the device catalog, not inside it.** A
   per-account store under private storage, keyed by the account's user id,
   holds the account book rows (canonical id, content identity, title, author,
   language, library status, last-opened, asset reference, cover status), the
   sync cursor, the outbox of pending mutations with their idempotency keys,
   the remote positions and the resume-offer record. The device catalog keeps
   its meaning and schema; the shelf is a merge of the two keyed by content
   SHA-256. This is what makes D4 mechanical: sign-out simply stops reading
   that store, a different account has a different one, the queue is held
   where it was, and nothing in the device catalog is touched.
3. **Sync is foreground-driven, server-authoritative and idempotent.** On the
   first signed-in session and on `rebootstrap_required`, load the library and
   progress lists and take the stream's latest cursor; afterwards, on every
   foreground, after every own write and on manual refresh, drain the outbox
   (one bounded batch at a time, replaying with the stored keys) then read
   deltas from the stored cursor and apply canonical payloads. FastReader never
   decides a winner: `applied`, `superseded`, `replayed` and `conflict` all end
   in adopting the server's canonical payload; `rejected` is shown with the
   backend's code. No WorkManager, no receiver, no new permission: the release
   gate's permission proof stays byte-identical.
4. **The shelf shows one row per content identity.** Account rows and device
   rows merge by SHA-256; an account row without local bytes reads as such;
   `Remove from account` is a `library_item` delete mutation with the
   contract's immediate Undo sent as `restore`; opening records last-opened
   and finishing records `finished` through `library_item` upserts; a remote
   removal leaves an open book readable until close. Capability unavailable,
   offline, session gone and bootstrapping are explicit shelf states, never
   silent.
5. **Adding a device book is the backend's import lifecycle, driven from
   the device.** Consent copy first; then policy (cap, formats, `enabled`),
   admission with the file's SHA-256 and size, chunked resumable transfer
   inside the grant's chunk size with HEAD-based resume, completion, and
   status polling while in the foreground until `ready`, `failed` or
   `cancelled`. The import record persists in the account store so app death
   resumes the same `client_import_id` and never produces a duplicate. On
   `ready`, the row the backend created (and streamed) is matched to the
   device book by content identity; refusals show the backend's category and
   leave the device book untouched.
6. **Reading an account book here is a verified private copy.** The download
   grant is fetched, bytes stream to `filesDir` under the content identity,
   the SHA-256 is verified before the copy is usable, and the copy is opened
   through the existing byte-source interface as a `File` (directory strategy,
   structural fingerprint and all). Because D4 says copies survive sign-out as
   device books, the copy is also a device-catalog row with a new source
   origin (`ACCOUNT_COPY`, path instead of SAF URI) — one forward schema step.
   `Remove downloaded copy` deletes the file and that source; the account row
   stays. Copies inherit the existing all-domain backup exclusion.
7. **The portable position is section plus fraction.** From the reader's
   chapter table: `href = chapter.spinePath`, `progression = tokenIndex /
   totalTokens`, `progress_percent = progression × 100`, `chapter_title` from
   the chapter; sent as a `reading_progress` upsert whose `locator` is the
   portable v1 object. Publishing happens on the writer's non-word flushes
   (pause, jump, background, close) and never per word (AD-25). Consuming a
   remote position maps `href` to the chapter and `progression` to the nearest
   token inside it, offered once per remote change through the front-matter
   pattern; accepting moves the local position, declining settles the offer.
   The local `ReadingState` (token index, pipeline version, fingerprint, WPM)
   is untouched by the protocol.
8. **Every increment leaves the promises true.** Each increment's last leaf
   (or the truthful-promises leaf in 001) edits the four held-equal privacy
   copies, the Spanish twin, the per-sentence table, README and
   `docs/release.md` to describe exactly what leaves the device after that
   increment, and the release gate's verify step passes on a release build
   (AD-27). Version identity follows AD-28.
9. **Increment 005 is the proof and the log.** With 001–004 on `main`, the
   full round trip of the definition's success measure is run once against
   stage with reader-web as the other device and recorded under
   `docs/evidence/104/`; every gap surfaced on the way is filed in the owning
   Chunipers repository and linked from #104 and #110, and the five already
   filed are confirmed linked.

Concrete choices inside these boundaries (store file layout, batch sizes
within the contract's bounds, chunk sizing within the grant, badge wording,
offer copy, evidence capture) are reversible implementation-lead decisions
constrained by the invariants and acceptance below.

## Architecture decisions

- **AD-19 — `:reader-library` is the contract boundary.** The account-library
  client is a library module on top of `:reader-auth`, not app code: the
  reusable, contract-facing half of this work is exactly what the owner may
  later propose upstream, and it keeps `:app` at the product surface as #100
  did with the gateway seam. The module depends on nothing under `:app`. Its
  models are validated against the pinned OpenAPI identity in unit tests so
  drift against the published contract is caught in CI, mirroring reader-web's
  drift check. The models are hand-written on the existing stack (owner
  decision P1, 2026-09-14: Choose A); generated models may replace them inside
  the same boundary once Chunipers publishes archives (#512).
- **AD-20 — Account state is a separate, account-scoped store.** Not the
  device catalog: the codec drops unknown keys, the catalog's schema is the
  device library's contract, and D4 needs per-account isolation. One JSON
  document per account under private storage, written with the same atomic
  tmp/fsync/rename discipline as the catalog and with its own forward-only
  version; a newer document is refused like the catalog's. The device catalog
  changes only in 003 (AD-24).
- **AD-21 — Foreground-driven sync, no background scheduler.** Triggers are
  the existing `ProcessLifecycleOwner` foreground hook, the completion of each
  own write, and manual refresh. The outbox drains before deltas are read so
  a device's own change is never applied twice. No WorkManager or receiver:
  no new permission, no new manifest component, release gate unchanged.
- **AD-22 — Canonical adoption, never local resolution.** Every mutation
  result that carries a canonical payload replaces local account state with
  it; `conflict` results (which the contract says cannot occur for activity
  or membership and may only occur for profile/settings, which FastReader
  never sends) are adopted the same way and logged, never prompted. Cursor
  expiry triggers a full bootstrap that re-sends nothing: the outbox is
  drained first with its original keys, so the ledger replays rather than
  re-admits.
- **AD-23 — Identity mapping is content-only.** A device book and an account
  book are the same shelf row when their SHA-256 agree; the account book's
  canonical UUID is what the protocol keys on, and the account store holds the
  pair. Adding a device book binds the pair when the import reaches `ready`;
  downloading binds it when the copy verifies. No path, name or title ever
  identifies a book.
- **AD-24 — Downloaded copies are device books with an `ACCOUNT_COPY`
  source.** This is a consequence of two owner decisions, not a new choice:
  D2 puts bytes on the device and D4 says those bytes stay usable after
  sign-out, which only a device-catalog row can express. One forward catalog
  schema step (version 11) adds the source
  origin and lets a source point at a private file path; migration is a no-op
  on existing documents. Copies live under `filesDir` keyed by content
  identity, are verified before first use, open through `EpubByteSource` as a
  `File`, and are removed by deleting the file and the source. Sign-out leaves
  them exactly as they are (D4).
- **AD-25 — Position publish moments and mapping.** FastReader publishes a
  `reading_progress` upsert when the position writer flushes for a non-word
  event (pause, jump, background, close) and the section or the rounded
  percent changed since the last publish; never on the per-word throttle.
  Mapping: `href` is the chapter's `spinePath`; `progression` is the
  book-level token fraction; the nearest token to a remote position is
  `progression × totalTokens` clamped into the `href` chapter's range (falls
  back to the chapter start when the href is unknown, and to the fraction alone
  when the href is absent). Backward moves are published like any other.
- **AD-26 — Import records persist with the account.** `client_import_id` is
  derived from the content identity and the account so a retry after app death
  addresses the same admission; the TUS location and grant expiry are stored
  with it; resume uses HEAD then PATCH from the durable offset; an expired
  grant re-admits with the same `client_import_id` (the backend returns the
  existing record) and starts a fresh transfer. Cancel is explicit.
- **AD-27 — Promises follow `main`.** Every increment's completion rule
  includes: the four privacy copies (and the Spanish twin, and the per-sentence
  table) describe exactly what leaves the device with that increment merged;
  `PrivacyStatementTest` passes; `scripts/release.sh` verify passes on a
  release build with the permission and cleartext proofs unchanged.
- **AD-28 — Version identity (owner decision P2, 2026-09-14: Choose A).**
  v1.6.0 is published by the owner before increment 001 merges; INC001's
  Ready-for-owner gate states that precondition. LEAF704 moves
  `version.properties` to 1.7.0 / versionCode 9 and writes
  `docs/release-notes/v1.7.0.md` with the 001 statement; increments 002–004
  edit the current unreleased notes file in place and bump the version only
  when the owner has meanwhile published the current one. No leaf cuts a
  release.

## Execution graph and waves

Strict increment sequence (each increment natively blocked by its immediate
predecessor; no parallel frontier). Leaves depend only on siblings inside their
own increment.

- **001 Account library on the shelf (#106)** — topology `COLLECTOR`.
  - wave 1: LEAF701 Reader library contract module (`:reader-library`, the
    `post` seam in `:reader-auth`, models, bootstrap/sync/capabilities
    operations, contract tests).
  - wave 2: LEAF702 account store and sync engine (blocked by LEAF701).
  - wave 3: LEAF703 shelf surface: account rows, remove with Undo, status and
    last-opened, remote removal rule, states, sign-out per D4 (blocked by
    LEAF702).
  - wave 4: LEAF704 truthful promises and contract pin for 001 (blocked by
    LEAF703).
  - Completion rule: `main` green in CI; acceptance recorded on the leaves
    against stage with reader-web as the other device for REQ-503, REQ-504,
    REQ-508, REQ-509, REQ-512, REQ-513, REQ-514, and for REQ-501, REQ-502 and
    REQ-516 **except their carried clauses**: REQ-501's "a downloaded copy
    shows as a device book" and REQ-516's "the copy still opens in airplane
    mode" are recorded against #108 (increment 003), and REQ-502's "position"
    item against #109 (increment 004). #106 closes with those three carried
    clauses named in its closing comment as owed by #108 and #109. AD-27
    holds; no import, download or position code reachable from the UI.
    Precondition at Ready for owner (AD-28): v1.6.0 is published from `main`
    before this increment merges.
- **002 Add a device book to the account (#107)** — topology `COLLECTOR`.
  - wave 1: LEAF801 publication-import client with resumable transfer (in
    `:reader-library`).
  - wave 2: LEAF802 Add-to-account flow: consent, progress, refusals,
    resume after app death, binding on `ready`, promises updated (blocked by
    LEAF801).
  - Completion rule: REQ-505–507 recorded (a ≤ cap EPUB added from FastReader
    reads on reader-web; > cap refused with the policy's cap; duplicate adds
    no row; app death mid-transfer resumes or restarts without a duplicate);
    AD-27 holds.
- **003 Read an account book here (#108)** — topology `COLLECTOR`.
  - wave 1: LEAF811 verified private-copy store on the download grant and
    the `ACCOUNT_COPY` open path (schema 11).
  - wave 2: LEAF812 shelf surface for not-on-device rows, download progress,
    offline reading, Remove downloaded copy, copies after sign-out; promises
    updated (blocked by LEAF811).
  - Completion rule: REQ-510 recorded (a reader-web upload opens on
    FastReader after one download and again in airplane mode; a tampered
    download is refused; the `docs/evidence/46/` backup procedure still moves
    zero bytes; sign-out keeps the copy openable), plus the clauses carried
    from increment 001: REQ-501's "a downloaded copy shows as a device book"
    and REQ-516's "the copy still opens in airplane mode"; AD-27 holds.
- **004 Position across clients (#109)** — topology `COLLECTOR`.
  - wave 1: LEAF821 portable position mapping and publish/consume through the
    sync engine.
  - wave 2: LEAF822 resume offer and promises updated (blocked by LEAF821).
  - Completion rule: REQ-511 recorded (40 % on FastReader shows on
    reader-web's Continue reading in the right chapter; a chapter move on
    reader-web is offered on FastReader; a backward move is admitted; no token
    index or WPM in any stage row), plus the clause carried from increment
    001: REQ-502's "position" item (a position change on another device is
    reflected on the next foreground); AD-27 holds.
- **005 Round-trip evidence and proposal log (#110)** — topology `DIRECT`.
  - wave 1: LEAF901 integrated stage round trip recorded under
    `docs/evidence/104/`; proposal log closed.
  - Completion rule: the definition's success measure is recorded; every
    proposal surfaced by 001–004 is an issue in the owning Chunipers
    repository linked from #104 and #110; REQ-515 recorded.

Why `COLLECTOR` for 001–004: one repository, cumulative leaves that share the
contract module, the account store and the shelf state, and an increment whose
acceptance is only meaningful on the integrated result. Why `DIRECT` for 005:
one leaf, no internal edge.

## Interfaces and ownership

Everything planned here is owned by `cedagova/fastReader`; the Chunipers
repositories own the contracts and receive proposals only.

- **`:reader-auth` → `:reader-library`:** `:reader-auth` owns the session,
  refresh, error branches and the authenticated `get`/`put`/`post` over
  `JsonObject`; `:reader-library` owns every library, sync, import and asset
  model and operation, plus the plain-transport client for Storage grants. The
  only `:reader-auth` change in this plan is the additive `post` (LEAF701);
  `CONTRACT.md` is untouched.
- **`:reader-library` → `:app`:** the app-side gateway seam grows a
  library-facing interface (typed operations, no generic calls) implemented by
  a pass-through in production and a scripted fake in tests, exactly as the
  account surface does today. Established by LEAF701/LEAF702; consumed by
  every later leaf.
- **Account store (AD-20):** created by LEAF702; extended by LEAF802 (import
  records), LEAF811 (copy references) and LEAF821 (remote positions, resume
  record). Each extension is one forward version step of that store.
- **Device catalog:** unchanged until LEAF811's schema 11 (AD-24). No other
  leaf touches the catalog schema.
- **Shelf state (`buildLibraryUiState` and `LibraryScreen`):** LEAF703 adds
  account rows, the merge, states and the account actions; LEAF802 adds the
  add-to-account action and progress; LEAF812 adds the not-on-device marker,
  download and remove-copy; LEAF822 adds nothing to the shelf (the offer lives
  in the reader). Each adds its own slots and changes no existing row content.
- **Reader open contract (AD-8/AD-9 of plan #36):** LEAF811 adds the
  `ACCOUNT_COPY` origin behind a `File`-backed `EpubByteSource`; LEAF821 reads
  `BookContent.chapters` and hooks the position writer's flushes; neither
  changes the token stream or position semantics.
- **Foreground hook (`FastReaderApplication`):** LEAF702 adds the sync
  trigger beside the existing rescan and session refresh.
- **Promises (AD-27):** LEAF704, LEAF802, LEAF812, LEAF822 each own the
  privacy copies for their increment; `PrivacyStatementTest`'s literal claims
  are edited by the same leaf.
- **Release gate:** untouched by every leaf; each increment proves it still
  passes.
- **Strings:** every UI leaf adds English strings with hand-written Spanish
  twins; lint's translation gate stays on.

## Risks and rabbit holes

- **Stage does not report `reader.sync.v1` available to `reader-android`**
  (A1). Bound: LEAF702's capability gate makes this a visible deferred state,
  not a failure; the owner can read the capabilities document today from the
  #100 account surface. If it reads unavailable, that is a Chunipers proposal
  (filed under #110) and 001's stage acceptance waits on it.
- **Second HTTP/JSON stack.** Generating Kotlin with reader-api's pinned
  options brings OkHttp + Moshi beside Ktor + kotlinx.serialization. Bound:
  owner decision P1 (Choose A avoids it).
- **TUS transfer on a phone network.** Interrupted uploads, expired grants,
  wrong offsets. Bound: AD-26 — chunk within the grant, HEAD before PATCH,
  re-admit on expiry with the same `client_import_id`; acceptance includes an
  app-death mid-transfer.
- **Import polling.** Bound: poll only while foreground and while an import is
  non-terminal, with backoff; no background scheduler.
- **Large download on a phone.** Bound: stream to disk, verify at the end,
  refuse and delete on mismatch; storage-full is a plain refusal; grant TTL is
  observed at planning of the leaf, not assumed.
- **Position mapping is approximate by nature.** Bound: A2 — section +
  fraction, nearest word; the offer can be declined; acceptance is chapter and
  percent, not word.
- **reader-web overrides a backward move** (CP-3, #1907). Bound: FastReader
  publishes the server's admitted position regardless; 004's acceptance
  records what reader-web shows and the divergence is already filed.
- **Sign-out semantics drift.** Bound: D4 is mechanical under AD-20 (stop
  reading the account store, keep the file); acceptance REQ-516 covers copy,
  queue and re-sign-in.
- **Privacy text getting ahead of `main`.** Bound: AD-27 is a completion rule
  per increment, and version identity follows AD-28.
- **Scope creep into notes, bookmarks, covers, preferences, catalog.**
  Bound: non-goals; the contract module exposes only the operations the
  leaves use.
- **Golden churn.** Bound: only the library and reader goldens the leaves
  name are re-recorded; every other golden stays byte-identical.

## Migration, rollout, recovery, and rollback

On-device app plus the stage backend. Rollout is merging each leaf to `main`
under CI, collector integration per increment, owner-authorised merge per
increment. Every leaf leaves `main` building, tested and the app usable signed
out. Recovery is `git revert` of the offending merge. Data: the device catalog
moves one forward step (11) in 003 with a no-op migration for existing
documents and forward-only discipline (AD-3/AD-16 of the earlier plans); the
account store has its own forward-only version and can be deleted without
data loss because the backend is authoritative for everything in it except
the outbox, which is held for the same account (D4) and is at worst re-sent
under the same idempotency keys (replayed, never re-admitted). Downloaded
copies are files under private storage: a reverted build ignores them; a
reinstall removes them (backup exclusion). Server-side data is the account's
and is untouched by any rollback here. No release is cut by this plan; version
identity per AD-28.

## Issue publication manifest

| Key | Kind | Parent | Repository | Title | Delivery | Blocked by | Issue |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ROOT | TRACKING | None | cedagova/fastReader | Account library synced through the Reader API from FastReader (stage) | INCREMENTAL | None | https://github.com/cedagova/fastReader/issues/104 |
| INC001 | GROUP | ROOT | cedagova/fastReader | Account library on the shelf, kept in step, removable with Undo | COLLECTOR | None | https://github.com/cedagova/fastReader/issues/106 |
| INC002 | GROUP | ROOT | cedagova/fastReader | Add a device book to the account through publication import | COLLECTOR | INC001 | https://github.com/cedagova/fastReader/issues/107 |
| INC003 | GROUP | ROOT | cedagova/fastReader | Read an account book on this device from a verified downloaded copy | COLLECTOR | INC002 | https://github.com/cedagova/fastReader/issues/108 |
| INC004 | GROUP | ROOT | cedagova/fastReader | Reading position portable between FastReader and reader-web | COLLECTOR | INC003 | https://github.com/cedagova/fastReader/issues/109 |
| INC005 | GROUP | ROOT | cedagova/fastReader | Chunipers alignment proposals filed and linked | DIRECT | INC004 | https://github.com/cedagova/fastReader/issues/110 |
| LEAF701 | LEAF | INC001 | cedagova/fastReader | Reader library contract module on the authenticated client | None | None | Pending |
| LEAF702 | LEAF | INC001 | cedagova/fastReader | Account store and foreground sync engine | None | LEAF701 | Pending |
| LEAF703 | LEAF | INC001 | cedagova/fastReader | Account books on the shelf: keep in step, remove with Undo, sign out | None | LEAF702 | Pending |
| LEAF704 | LEAF | INC001 | cedagova/fastReader | Truthful promises and contract pin for the account library | None | LEAF703 | Pending |
| LEAF801 | LEAF | INC002 | cedagova/fastReader | Publication-import client with resumable transfer | None | None | Pending |
| LEAF802 | LEAF | INC002 | cedagova/fastReader | Add a device book to the account with consent | None | LEAF801 | Pending |
| LEAF811 | LEAF | INC003 | cedagova/fastReader | Verified private copies on the download grant | None | None | Pending |
| LEAF812 | LEAF | INC003 | cedagova/fastReader | Download, read offline and free an account book's copy | None | LEAF811 | Pending |
| LEAF821 | LEAF | INC004 | cedagova/fastReader | Portable position published and consumed through sync | None | None | Pending |
| LEAF822 | LEAF | INC004 | cedagova/fastReader | Resume offer from another client's position | None | LEAF821 | Pending |
| LEAF901 | LEAF | INC005 | cedagova/fastReader | Stage round-trip evidence and Chunipers proposal log closure | None | None | Pending |

### Planned leaf contracts (summaries; full contracts go to the issues)

- **LEAF701 — Reader library contract module.** New `:reader-library` module
  (AD-19) depending on `:reader-auth`: models and typed operations for `GET
  /v1/reader/library`, `GET /v1/reader/progress`, `POST
  /v1/reader/sync/mutations`, `GET /v1/reader/sync/deltas` and the
  `reader.sync.v1` capability read (with `clientVersion`); the `library_item`
  and `reading_progress` payload shapes; error outcomes reuse
  `ReaderAuthException`'s branches unchanged. `:reader-auth` gains an additive
  `post(path, body)` beside `get`/`put`, pinned by its mock-engine tests,
  `CONTRACT.md` untouched. The OpenAPI document at the pinned identity is
  committed under the module with its sha256 recorded, and a unit test proves
  every shape the module sends or reads agrees with it (P1: hand-written
  models, contract test as the drift gate). App-side: the typed
  library gateway interface, production pass-through and test fake. Owns
  REQ-514's contract pin. Validation: mock-engine tests for each operation
  including `replayed`, `rejected`, `cursor_expired`, 401/429/502 branches;
  the contract test; no UI.
- **LEAF702 — Account store and sync engine.** Account-scoped store (AD-20)
  and the engine of AD-21/AD-22/AD-23: bootstrap from the lists with the
  stream's latest cursor, outbox drain then delta read on foreground, after
  own writes and on manual refresh; idempotency keys per queued mutation;
  rebootstrap on `cursor_expired`; canonical adoption; capability gate
  (`reader.sync.v1` unavailable → deferred state with the reason, no
  requests); D4 sign-out (stop reading the store; keep it; a different
  account gets its own store and the held queue of the previous one is
  discarded on that account's first sign-in only when the user id differs);
  session-gone handled through the existing `SignedOut` branch. Exposes a
  state flow the shelf builds from. Owns REQ-502, REQ-503, REQ-504, REQ-516's
  queue rule. Validation: engine unit tests with the fake gateway (bootstrap,
  delta apply, outbox replay returns `replayed`, expiry rebootstrap sends
  nothing twice, sign-out/sign-in/different account), store round-trip and
  refuse-newer tests.
- **LEAF703 — Account books on the shelf.** `buildLibraryUiState` merges
  device and account rows by SHA-256 (AD-23) into one row each; account-only
  rows show a "not on this device" line (no open action until 003); `Remove
  from account` (confirm copy names every device) sends the delete and offers
  the contract's immediate Undo which sends `restore`; opening an account book
  that is also local records `last_opened_at` and `reading`, finishing records
  `finished`; a remote removal keeps an open book readable until close and
  the row then reads removed; shelf states for bootstrapping, offline (actions
  queue with a note), capability unavailable, session gone (reason once), and
  signed out per D4 (account-only rows gone). The foreground hook triggers
  sync. TalkBack labels, 48 dp targets, Spanish twins. Owns REQ-501, REQ-508,
  REQ-509, REQ-516's shelf rule, REQ-512's UI half. Validation: state-builder
  unit tests; goldens for each shelf state (reference and compact phone, one
  Spanish, one large font); emulator against stage with reader-web as the
  other device for REQ-501/502/503/508/509/516; `AndroidRuntime:E` empty.
- **LEAF704 — Truthful promises and contract pin.** The four privacy copies,
  the Spanish `settings_privacy`, the per-sentence table, README and
  `docs/release.md` say what leaves the device after 001 (membership, status,
  last-opened for account books; nothing about device books, positions yet,
  WPM, settings or crash reports); `PrivacyStatementTest`'s literal claims
  updated; the pinned OpenAPI identity documented for REQ-514; `version.
  properties` to 1.7.0 / 9 and `docs/release-notes/v1.7.0.md` written with the
  new block, `v1.6.0.md` untouched (AD-28); `docs/agent-first-development.md`
  gains the `:reader-library` row (mock-engine tests, contract test, the stage
  loop with reader-web). Owns REQ-513 for 001 and REQ-514's documentation.
  Validation: `PrivacyStatementTest`; `scripts/release.sh` verify on a release
  build (permission set and cleartext proofs unchanged); lint translation gate.
- **LEAF801 — Publication-import client.** In `:reader-library`: policy read
  (`enabled`, formats, caps, chunk size), admission with `client_import_id`,
  `ownership_intent: account_library`, `upload_consent: true`, format, MIME,
  size and sha256; the TUS creation, chunked PATCH within the grant's chunk
  size, HEAD offset recovery, completion, status read and cancel, with the
  grant's headers on a plain transport (never the bearer); the persisted import
  record shape (AD-26) with re-admission on grant expiry. Validation:
  mock-transport tests for a full transfer, an interrupted transfer resumed
  from HEAD, an expired grant re-admitted with the same id, each refusal
  category, `enabled: false`.
- **LEAF802 — Add a device book to the account.** Shelf action on device
  books: consent copy stating the file's bytes will be uploaded to the Reader
  account on stage and kept there (Add / Cancel); the import runs with
  progress on the row, cancellable, resumed after app death from the account
  store; on `ready` the streamed account row binds to the device book
  (AD-23); refusals show the backend's category with the cap from the policy;
  identical bytes already in the account bind without a second row; the
  action is hidden when the policy says `enabled: false` (reason shown) and
  absent for account books. Promises updated for 002 (book bytes and
  metadata of books the owner adds). Owns REQ-505, REQ-506, REQ-507, REQ-513
  for 002. Validation: state and flow unit tests; goldens for consent,
  progress, refused; emulator against stage: ≤ cap EPUB appears on reader-web
  and reads there, > cap refused, duplicate add, app death mid-transfer,
  stage import records show zero imports before consent.
- **LEAF811 — Verified private copies.** In `:reader-library`: the download
  grant operation. In `:app`: a copy store under `filesDir` keyed by content
  identity that streams the grant's bytes to a temporary file, verifies the
  SHA-256, and only then places the copy; `ACCOUNT_COPY` source origin with a
  file path (catalog schema 11, no-op migration; AD-24); a `File`-backed
  `EpubByteSource` so the reader opens the copy with the directory strategy;
  deletion of a copy removes the file and the source. Validation: unit tests
  for verify-then-place, tampered bytes refused and nothing placed, schema 11
  migration of a v10 document, open through the existing pipeline yielding a
  structural fingerprint; the existing backup-exclusion test extended to the
  copy directory.
- **LEAF812 — Download, read offline, free the copy.** Shelf: account-only
  rows offer Open (downloads with progress, then opens), storage-full and
  grant failures shown with the book kept listed; downloaded copies read in
  airplane mode; `Remove downloaded copy` frees the bytes and the row returns
  to account-only; after sign-out the copy is an ordinary device book (D4);
  signing back in re-binds it. Promises updated for 003 (downloaded copies in
  private storage, excluded from backup, removable). Owns REQ-510, REQ-516's
  copy rule, REQ-513 for 003. Validation: goldens for not-on-device,
  downloading, downloaded; emulator against stage: reader-web upload opens on
  FastReader after one download and again in airplane mode, tampered download
  refused, `docs/evidence/46/` backup procedure moves zero bytes, sign-out
  keeps the copy openable.
- **LEAF821 — Portable position through sync.** AD-25: derive `href`,
  `progression`, `progress_percent` and `chapter_title` from `BookContent`
  and the token index; publish a `reading_progress` upsert on non-word flushes
  when section or rounded percent changed; consume remote `reading_progress`
  from bootstrap and deltas into the account store as a remote position per
  account book; map a remote position to the nearest token. The local
  `ReadingState` is unchanged in shape and semantics; WPM and token index
  never leave. Owns REQ-511's protocol half and REQ-512's position clause.
  Validation: mapping unit tests both ways on the existing fixtures
  (chapter boundaries, unknown href, missing href, empty sections); engine
  tests that publishes happen only on flush events; contract test for the
  portable locator object against the pinned OpenAPI.
- **LEAF822 — Resume offer.** In the reader, a remote position newer than the
  last settled one is offered once through the front-matter pattern ("Resume
  where you left off on another device: <chapter>, <percent>%"), accepting
  moves to the mapped token, declining settles it; the record lives in the
  account store; the shelf's status line shows the account percent when it is
  ahead. Promises updated for 004 (portable position of account books). Owns
  REQ-511's user-facing half, REQ-513 for 004. Validation: ViewModel/route
  unit tests; goldens for the offer (reference, Spanish); emulator against
  stage: 40 % on FastReader → reader-web Continue reading in the right
  chapter; chapter move on reader-web → offer on FastReader; backward move
  admitted (`applied`); no token index or WPM in any stage row.
- **LEAF901 — Round-trip evidence and proposal log.** With 001–004 on
  `main`: run the definition's success measure once end to end on
  `Phone_Mid_API36` against stage with reader-web as the other device (add
  both ways, remove and Undo both ways, offline remove replayed once, position
  both ways, sign-out keeping the copy) and record it under
  `docs/evidence/104/` with no secret or code in it; file every gap surfaced
  in 001–004 as an issue in the owning Chunipers repository with the
  Chunipers Claude worker identity; confirm #511, #512, #513, #1907, #1908
  and any new issue are linked from #104 and #110; note in #110 the
  observations for A1 and reader-web's behaviour on CP-3/CP-4. Owns REQ-515
  and the root's success measure. Validation: the evidence directory exists
  and is referenced from #104; every proposal link resolves.

## Acceptance coverage

| Requirement | Leaves |
| --- | --- |
| REQ-501 account and device rows merged, one per identity; signed out = v1.6.0 | LEAF703 (merge, states), LEAF702 (store), LEAF812 (copies as device books after sign-out) |
| REQ-502 bootstrap then change stream; remote add/remove/Undo/status/position reflected on foreground; expiry rebootstrap | LEAF702, LEAF703 (surface), LEAF821 (position changes) |
| REQ-503 offline actions held and admitted once | LEAF702 (outbox, keys), LEAF703 (offline state copy) |
| REQ-504 no conflict prompt; canonical adoption | LEAF702 (AD-22), LEAF703 |
| REQ-505 add requires consent; sign-in/open never adds | LEAF802 (consent), LEAF702 (bootstrap sends nothing), LEAF801 |
| REQ-506 import lifecycle per policy; ready → same book on both clients; dedup | LEAF801, LEAF802 |
| REQ-507 refusal/failure/cancel leaves the device book untouched; app-death resume | LEAF801 (resume), LEAF802 |
| REQ-508 Remove from account with Undo; device file untouched | LEAF703, LEAF702 |
| REQ-509 remote removal keeps open session, blocks reopen | LEAF703 |
| REQ-510 verified download, offline reading, Remove downloaded copy, backup exclusion | LEAF811, LEAF812 |
| REQ-511 portable position both ways; backend decides | LEAF821, LEAF822 |
| REQ-512 unchanged product; FastReader-only data never sent | LEAF703 (goldens outside new surfaces unchanged), LEAF702 (only `library_item` mutations in 001), LEAF821 (only `reading_progress`, no token index/WPM), constraint on every leaf |
| REQ-513 truthful promises; release gate unchanged | LEAF704 (001), LEAF802 (002), LEAF812 (003), LEAF822 (004); AD-27 completion rule of every increment |
| REQ-514 every request declared by the pinned contract; `reader-android`; capability honoured | LEAF701 (pin, contract test), LEAF702 (gate), LEAF704 (documentation) |
| REQ-515 proposals filed and linked; no private protocol | LEAF901 (log closure); constraint on every leaf (no request outside the contract) |
| REQ-516 sign-out/session-gone: rows leave, copies stay, queue held for same account, nothing deleted | LEAF702 (queue, store), LEAF703 (rows), LEAF812 (copies) |
| Success measure: round trip recorded under `docs/evidence/104/` | LEAF901 (integrated), each increment's leaves (per-increment evidence) |
| Guardrail: zero calls/bytes/mutations for device books until added | LEAF702 (bootstrap and deltas touch account state only), LEAF802 (consent gate) |
| Guardrail: release gate permission and cleartext proofs unchanged | AD-21 (no scheduler), LEAF704/802/812/822 proofs |

Root #104 acceptance: OUT501–OUT505 → INC001–INC005 completion rules; the
recorded round trip → LEAF901. No orphan or overlap: the contract module
(LEAF701/801/811-grant) is split from the engine (LEAF702) at the typed
gateway; the engine is split from the shelf (LEAF703) at the state flow; each
increment's promises leaf owns only that increment's privacy delta; 005 owns
only evidence and issue hygiene.

## Validation and feedback

Per the repository's agent-first loop, extended by the #100 row: `:reader-
library` gates on mock-transport unit tests and the contract test (no network,
no device); the app gates on unit tests, `verifyRoborazziDebug` and lint in
CI on every push and PR (hosted runner has no backend values and stays
green); every UI leaf re-records only the goldens it names and proves every
other golden byte-identical; behaviour against the real backend is proven on
`Phone_Mid_API36` against stage with the owner's stage test account signed in
and reader-web on stage as the other device, screencaps and `adb logcat -d -s
AndroidRuntime:E` committed under `docs/evidence/<issue>/` with no value,
token or code in them; each increment's collector runs `scripts/release.sh`
verify on a release build and `PrivacyStatementTest` before Ready for owner.
The sync engine's idempotency is proven by the stage ledger (`applied` then
`replayed` on retry), not by client logs alone.

## Assumptions and open questions

None open. Both material decisions were presented as the Owner decision
brief below and decided by the owner on 2026-09-14, verbatim "Choose A
(Recommended)" for each; the brief is kept as the record and the decided
values are carried in AD-19/AD-28, LEAF701, LEAF704 and INC001's completion
rule.

### Owner decision brief (decided 2026-09-14)

**P1 — How FastReader obtains the Reader API request/response shapes.**

*Problem.* `:reader-library` needs Kotlin types for roughly fifteen shapes
(library item, book, asset, progress, sync envelope and result, delta
change, import request/record/grant/policy, download grant, capability
entry). The definition requires every request to be one the pinned published
contract declares and the owner's purpose is to exercise the contract the
way a real integrator would.

*Facts.* reader-api pins openapi-generator-cli 7.17.0 with default Kotlin
options, whose output uses OkHttp and Moshi; FastReader's `:reader-auth`
uses Ktor (OkHttp engine) and kotlinx.serialization with hand-written models
for the auth shapes; reader-api has never published the Kotlin archive
(filed as #512); reader-web generates TypeScript from the same OpenAPI file
and fails CI on drift. *Assumption:* the fifteen shapes are stable across
the identities this work will see; a drift test catches the rest.

*Options.*
- **A (recommended) — Hand-written kotlinx.serialization models, contract-
  tested against the pinned OpenAPI.** Behaviour: the models FastReader
  uses are written by hand on the existing stack; a unit test loads the
  committed OpenAPI document and checks every field name, type and enum the
  module sends or reads against the schemas. Benefit: one HTTP and one JSON
  stack, consistent with `:reader-auth`, small module, no build tooling.
  Cost: fifteen shapes typed by hand; the contract test is the drift gate,
  not a generator. Reversible: yes — generated models can replace them later
  without changing the operations. Execution: LEAF701 as written.
- **B — Generate with reader-api's exact pinned generator and options.**
  Behaviour: run openapi-generator 7.17.0 (Docker) on the pinned document,
  vendor the `models` output, discard `apis`. Benefit: the models are the
  published artifact byte-for-byte, the strongest test of #512's path.
  Cost: Moshi and its Kotlin codegen enter the app beside
  kotlinx.serialization (two JSON stacks, converters at the boundary), plus a
  Docker step in the loop. Reversible: yes, at the cost of rewriting the
  boundary. Execution: LEAF701 grows a generation script and a drift check.
- **C — Generate with the kotlinx.serialization option.** Behaviour: same
  generator, `serializationLibrary=kotlinx_serialization`. Benefit: generated
  and single-stack. Cost: diverges from reader-api's pinned configuration, so
  it proves a path Chunipers does not publish; generated Ktor `apis` still
  discarded. Reversible: yes. Execution: as B without Moshi.

*Recommendation.* A. It matches how `:reader-auth` already consumes the
contract, keeps the app on one stack, and the contract test gives the same
drift guarantee reader-web relies on. B becomes attractive only once #512
publishes real archives; that is a later swap inside LEAF701's boundary.

*Blocked.* LEAF701's model text; nothing else.

*Decision (2026-09-14).* Choose A.

**P2 — Version identity while the increments land.**

*Problem.* `version.properties` is 1.6.0 / 8 and v1.6.0 (the sign-in
release) is **not published**. `PrivacyStatementTest` requires the release-
notes file for the current `versionName` to carry the statement. Increment
001 changes the statement. If 001 merges before 1.6.0 is published, the only
truthful 1.6.0 notes describe a build that includes the account library, and
the sign-in release is never published on its own.

*Facts.* #100's plan bumped to 1.6.0 and wrote `v1.6.0.md` without cutting a
release (owner decision 1 of that plan); the definition's non-goals exclude
cutting a release here; the owner still owes the emailed-code stage run
before publishing 1.6.0. *Assumption:* the owner wants the sign-in release
to exist as its own artifact.

*Options.*
- **A (recommended) — Publish 1.6.0 first; each increment bumps only when the
  current version is already published.** Behaviour: the owner publishes
  v1.6.0 before INC001 merges (the increment's Ready-for-owner gate states it
  as a precondition). LEAF704 moves `version.properties` to 1.7.0 / 9 and
  writes `v1.7.0.md` with the 001 statement; 002–004 edit the current
  unreleased notes in place, and bump again only if the owner has meanwhile
  published the current version. No release is cut by any leaf. Benefit:
  every published version's notes are true for that artifact; the sign-in
  release stands alone. Cost: one owner action before 001 merges. Reversible:
  yes. Execution: adds a precondition to INC001's completion rule.
- **B — Fold the account library into 1.6.0.** Behaviour: 1.6.0 is never
  published as sign-in only; LEAF704 edits `v1.6.0.md` in place and no leaf
  bumps the version; the owner publishes 1.6.0 whenever they choose after
  some increment. Benefit: no owner action now. Cost: the sign-in milestone
  has no artifact; the 1.6.0 notes drift with each increment until cut.
  Reversible: partly (a later bump is easy; the lost standalone release is
  not). Execution: LEAF704 loses the bump.
- **C — Do nothing about versions in this plan.** Behaviour: as B but the
  decision of when to bump is left to the owner at release time. Cost: the
  same drift, plus an implicit rule nobody owns. Not recommended.

*Recommendation.* A. It costs one action the owner already intends (publish
1.6.0 after the manual stage run) and keeps every release's promise text
exact.

*Blocked.* LEAF704's version lines and INC001's precondition; nothing else.

*Decision (2026-09-14).* Choose A.

### Other assumptions (non-material, recorded)

- The owner provides a stage test account for the emulator loop and uses
  reader-web on stage as the other device; no value, token or code appears
  in evidence.
- Stage's `reader.sync.v1` availability for `reader-android` (A1) is read by
  the owner from the #100 account surface before 001's stage acceptance; the
  gate handles either answer.
- The import policy on stage admits EPUB (`upload_only`) with `enabled:
  true` (A3); if `enabled` is false, LEAF802's hidden-action state is the
  observed behaviour and #513/#511 gain a note.
- The library item's asset exposes the content SHA-256 the merge needs;
  LEAF701's contract test names the exact field from the pinned document.
- The download grant's TTL suffices for a 50 MiB download on a phone
  network; LEAF811 observes it and re-fetches a grant on expiry.
- Planner choices recorded as ordinary and reversible: `:reader-library` as a
  module (AD-19), the account-scoped store (AD-20), foreground-only sync
  (AD-21), canonical adoption (AD-22), content-only mapping (AD-23),
  `ACCOUNT_COPY` source (AD-24), publish moments and mapping (AD-25), import
  record persistence (AD-26), promises per increment (AD-27), `COLLECTOR`
  for 001–004 and `DIRECT` for 005.

## Satisfaction proof

Not applicable — every requirement names behaviour absent at the pinned
baseline; implementation work remains.

## Publication verification

Owner decisions P1 and P2 recorded 2026-09-14. Pending: content review, leaf
publication, graph reconciliation and verification, semantic-anchor review.
