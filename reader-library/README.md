# `:reader-library` — the Reader account-library client

Typed, contract-tested access to the Reader API's account library, reading
progress and sync protocol, on top of the authenticated client `:reader-auth`
owns. Added by [#112](https://github.com/cedagova/fastReader/issues/112)
(LEAF701 of [#104](https://github.com/cedagova/fastReader/issues/104)).

## What it is

Typed operations for the Reader library API, and — since #147 — the account sync
engine built on them (see below). Six library operations, and nothing else:

| Operation | Route |
| --- | --- |
| `library()` | `GET /v1/reader/library` |
| `progress()` | `GET /v1/reader/progress` |
| `applyMutations(…)` | `POST /v1/reader/sync/mutations` (1–50 envelopes) |
| `deltas(afterCursor, limit)` | `GET /v1/reader/sync/deltas` (limit ≤ 500) |
| `syncCapability()` | `GET /v1/reader/capabilities?clientVersion=…`, the `reader.sync.v1` entry |
| `publicationImportCapability()` | the same document's `reader.publication-import.v1` entry, available only when there is exactly one (#139) |

`ReaderLibraryOperations` is the interface; `ReaderLibraryClient` is the one
implementation, constructed with the `ReaderApiClient` a `ReaderAuthClient`
exposes. There is no generic `call(path, body)` on it: every request this module
can send is one of the six above or one of the publication-import and download-grant routes `ReaderLibraryOperations` declares.

## What it is not

- It holds no session, no token, no refresh and no retry. All of that is
  `:reader-auth`'s, and tokens never leave that module.
- It defines no exception type. Every failure is an existing
  `ReaderAuthException` branch — `SignedOut`, `Forbidden`, `TryLater`,
  `NetworkUnavailable`, `ApiError` — thrown through unchanged.
- It depends on nothing under `:app`, declares no permission and adds no
  manifest component, so it stays liftable exactly as `:reader-auth` does.

Outcomes that are answers rather than failures stay answers: `replayed`,
`superseded`, `conflict`, `rejected`, `cursor_expired` and `cursor_invalid` are
typed results a caller reads, not exceptions it catches.

## The account sync engine (`com.cedagova.reader.library.sync`)

Added by [#147](https://github.com/cedagova/fastReader/issues/147): the account
sync logic any Android Reader client can reuse, moved here from FastReader's
`:app`. It depends on nothing under `:app` and knows no host concept — no token
index, no device catalog, no downloaded copy, no UI.

| Type | What it is |
| --- | --- |
| `AccountSyncEngine` | The one writer of an account's document. Bootstraps by merging the library/progress snapshot into the stored rows (and repairs that way after a non-retryable rejection), drains the outbox in batches of ≤ 50 with persisted idempotency keys, reads the change stream to `has_more = false` (re-bootstrapping on an expired/invalid cursor), adopts every result and change from the backend's canonical payload, and publishes `state: StateFlow<AccountLibraryState>`. Runs only when asked: `requestSync(trigger)`. |
| `AccountSession` | What the host tells the engine about the session (`Loading`, `NotConfigured`, `SignedOut`, `SignedIn(userId)`), as a `Flow`. Signing out keeps the file; a different user discards the other accounts' held queues (D4). |
| `AccountLibraryActions` | The mutations a shelf performs: `refresh`, `removeFromAccount`, `undoRemove`, `recordOpened`, `recordFinished`, `recordStatus`, `recordPosition`. Only `library_item` and `reading_progress` envelopes are ever built. |
| `AccountImportRecords` | The durable publication-import records (AD-26), under the same writer. |
| `AccountHostRecords` | The host's own records in the same document — values the engine stores verbatim and never reads, queues or sends. Document-level (`hostRecord`, `updateHostRecord`) and per book row (`updateBookHostRecord`, read back as `AccountBook.host`). FastReader keeps its copy references and answered resume offers here. |
| `ReaderLibraryGateway` | The five library operations the engine calls; `ReaderApiLibraryGateway` passes them to `ReaderLibraryOperations`. Tests substitute a scripted fake. |
| `AccountLibraryStores` / `FileAccountLibraryStores` | Storage the host supplies: one atomically written JSON file per account, named after the SHA-256 of the user id. |
| `AccountLibraryDocument`, `AccountBook`, `AccountRemotePosition`, `AccountOutboxEntry`, `AccountLibraryCodec`, `AccountLibrarySchema` | The persisted canonical state (schema 6), migrated forward step by step and refused when newer. Keys a level does not declare are host records, kept verbatim and written back at the same level. |
| `PortableProgress`, `LocalReadingPosition`, `RemoteReadingPosition`, `ProgressRecord` | The portable position: the `reading_progress` payload built from `PutReaderProgressRequest` (a closed key set), which book a record is about, and what a canonical payload states. Mapping these to and from a host's own reading unit is the host's. |

**Freshness (contract `core.md` §7.3, §7.4).** A mutation result or stream change
never moves a book's state backwards: one whose revision is older than the
stored one never replaces it, for positions and library items alike. A stream
change must be *strictly* newer (#149) — an equal-revision echo is the state
already held, and applying it would revert a local intent still queued in the
outbox. A library item's mutation *result* at an equal revision still replaces
the row: `superseded` and `conflict` are the backend's verdict on this device's
queued mutation, and they discard the optimistic row. Such a result's presence
comes from its canonical payload, not from the mutation kind: a removal answered
with the live book ends on the shelf, and an upsert answered with the tombstone
(the empty payload) ends off it.

**Repair by merging, never rebuilding (§7.2, #151).** A bootstrap or
re-bootstrap merges the library/progress snapshot into the stored rows:

- a book the snapshot still lists takes every field the snapshot states, and
  keeps its host records and its known revision — a list read carries no
  revision, so the one the backend already stated stays as a lower bound and
  the strictly-newer rule above keeps holding across the re-bootstrap;
- a book the snapshot no longer lists is gone from the account, and its row
  goes with its host records;
- every change still queued in the outbox is re-applied on top, in queue order
  (§7.2 step 5), from the entry alone — the same intent function that applied
  it when it was queued. The queue is untouched: same entries, same keys.

A **non-retryable rejection** of a library change carries `{}`, so nothing in
the answer says what the row was before the optimistic change. The engine drops
the entry and, in the same run and under the same lock, runs that merge-style
bootstrap as a repair read instead of reading the stream: the refused change
leaves the shelf, every other queued change is re-applied, host records stay. A
refused position changed no row and triggers no repair.

**Host records (`AccountHostRecords`).**

- **A `transform` must not call back into the engine** — no `AccountHostRecords`,
  `AccountImportRecords` or `AccountLibraryActions` call and no `requestSync`.
  It runs under the engine's lock, which is not re-entrant, so a callback that
  waits on the engine would wait for ever. A call made from the transform's own
  thread throws `IllegalStateException` instead of hanging; one handed to another
  thread and awaited cannot be detected and deadlocks. Compute inputs before the
  update and act on its outcome after it returns.
- **A `transform` must be pure** — a function of its argument with no side
  effects. The re-entrancy guard is a flag on the transform's thread, so a side
  effect that synchronously resumes another coroutine there (completing a
  deferred, emitting to a flow collected on `Dispatchers.Unconfined`, a nested
  `runBlocking`) would make that coroutine's engine calls throw as if they were
  the transform's own.
- **Reserved keys are refused** (#149). A key the schema declares at that level
  (`AccountLibraryCodec.RESERVED_DOCUMENT_KEYS` / `RESERVED_BOOK_KEYS`) or `host`
  itself throws `ReservedHostRecordKeyException` before anything is written: a
  record under it would be stored and then silently lost on the next load.

**What it never does.** It resolves no conflict and prompts for none, picks no
winner between two positions (the backend's admission order does), runs no
scheduler, and sends no `profile`, `settings`, `note` or `bookmark` envelope.

## The contract pin

`contracts/reader-api.openapi.json` is the published Reader API document at a
pinned identity, with its sha256 beside it; see
[contracts/PINNED.md](contracts/PINNED.md). The models are hand-written on the
existing kotlinx.serialization stack (owner decision P1, 2026-09-14) and
`ReaderLibraryContractTest` is the drift gate: it recomputes the digest and then
compares every field name, JSON type, enum member and required flag the models
use against the document's schemas, deriving the expectations from the models'
own serializer descriptors. Its negative case runs the same checker over
deliberately broken copies of four models, so the gate is known to fail when it
should.

Drift is a proposal to Chunipers (reader-api #511 / #512), never a local
workaround.

## Running its tests

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :reader-library:test
```

Every test runs against a Ktor mock engine driving a **real** `ReaderAuthClient`
— real bearer, real single-flight refresh, real 401/403/429/502 policy — so the
module is proved to inherit that behaviour rather than restate it. No network,
no device and no backend value is involved.
