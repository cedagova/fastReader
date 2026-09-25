# `:reader-library` — the Reader account-library client

Typed, contract-tested access to the Reader API's account library, reading
progress and sync protocol, on top of the authenticated client `:reader-auth`
owns. Added by [#112](https://github.com/cedagova/fastReader/issues/112)
(LEAF701 of [#104](https://github.com/cedagova/fastReader/issues/104)).

## What it is

Typed operations for the Reader library API, and — since #147 — the account sync
engine built on them (see below). The operations are exactly the ones
`ReaderLibraryOperations` declares, and nothing else:

| Operation | Route |
| --- | --- |
| `library()` | `GET /v1/reader/library` |
| `progress()` | `GET /v1/reader/progress` |
| `applyMutations(…)` | `POST /v1/reader/sync/mutations` (1–50 envelopes) |
| `deltas(afterCursor, limit)` | `GET /v1/reader/sync/deltas` (limit ≤ 500) |
| `syncCapability()` | `GET /v1/reader/capabilities?clientVersion=…`, the `reader.sync.v1` entry |
| `publicationImportCapability()` | the same document's `reader.publication-import.v1` entry, available only when there is exactly one (#139) |
| `importPolicy()` | `GET /reader/v1/imports/policy` (#116) |
| `admitImport(request)` | `POST /reader/v1/imports`, idempotent on `client_import_id`; refuses locally without explicit upload consent |
| `importRecord(importId)` | `GET /reader/v1/imports/{id}` |
| `completeImport(importId)` | `POST /reader/v1/imports/{id}/complete` |
| `cancelImport(importId, reason)` | `POST /reader/v1/imports/{id}/cancel` |
| `assetDownloadGrant(assetId)` | `POST /v1/reader/assets/{asset_id}/download-grant` (#118) |

`ReaderLibraryOperations` is the interface; `ReaderLibraryClient` is the one
implementation, constructed with the `ReaderApiClient` a `ReaderAuthClient`
exposes (it takes `:reader-auth`'s `ReaderApiOperations` interface, #199). There is no generic `call(path, body)` on it: every request this module
sends to reader-api is one of the operations above.

## Packages

| Package | What it holds |
| --- | --- |
| `com.cedagova.reader.library` | `ReaderLibraryOperations`, `ReaderLibraryClient` and the JSON setup: the typed reader-api operations above. |
| `…library.model` | The hand-written request and response models, checked against the pinned contract (see below). |
| `…library.sync` | The account sync engine (see below). |
| `…library.imports` | Publication import: `PublicationImportEngine` runs one add from policy to completion over a caller-held `PublicationImportRecord`; `PublicationTransferClient` is the TUS upload to the storage provider, with no session and no bearer; `UploadConsent`, `PublicationSource` and `PublicationImportRefusal` are its inputs and local refusals. `PublicationImportGateway` is the seam a host holds (production: `ReaderApiPublicationImportGateway`, #199). |
| `…library.downloads` | `AssetDownloadClient`: fetches a book's bytes from the provider URL an `assetDownloadGrant` returns, sending only the grant's signed headers — no session, no bearer. Checking the digest and placing the file are the host's. `AssetDownloadGateway` is the seam a host holds: the grant from reader-api and the bytes from the session-less transport, as two methods over two clients (production: `ReaderApiAssetDownloadGateway`, #199). |

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
| `ReaderLibraryGateway` | The five library operations the engine calls (`library`, `progress`, `applyMutations`, `deltas`, `syncCapability`); `ReaderApiLibraryGateway` passes them to `ReaderLibraryOperations`. Tests substitute a scripted fake. |
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
the entry and clears the stored cursor **in the same save**, so the owed repair
is durable: that run — or, if its read fails or the process dies first, the next
one — finds no cursor and runs the merge-style bootstrap as a repair read
instead of reading the stream. The refused change leaves the shelf, every other
queued change is re-applied, host records stay, and the bootstrap stores a fresh
cursor, so the repair runs once. A repair over a populated shelf does not
announce `BOOTSTRAPPING`. A refused position changed no row and triggers no
repair.

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

`reader-auth/contracts/reader-api.openapi.json` is the published Reader API
document at a pinned identity, with its sha256 beside it; see
[../reader-auth/contracts/PINNED.md](../reader-auth/contracts/PINNED.md). It is
the one document both libraries are gated against (#208): `:reader-auth` owns
it, and this module reads it from there. The models are hand-written on the
existing kotlinx.serialization stack (owner decision P1, 2026-09-14) and
`ReaderLibraryContractTest` is the drift gate: through the checker both
libraries share (`ReaderApiContract`), it recomputes the digest and then
compares every field name, JSON type, enum member and required flag the models
use against the document's schemas, deriving the expectations from the models'
own serializer descriptors. Its negative case runs the same checker over
deliberately broken copies of four models, so the gate is known to fail when it
should.

Drift is a proposal to Chunipers (reader-api #511 / #512), never a local
workaround.

## Taking it into another repository

A host copies this directory, with `:reader-auth` and the shared build pieces,
at a tag `reader-library/v<version>`: the copy set, the steps and the check are
in [docs/library-consumption.md](../docs/library-consumption.md) (#207). The
version is `version` in `build.gradle.kts`; [CHANGELOG.md](CHANGELOG.md) says
what changed between two versions, and every change to this module adds an
entry. `consumer-rules.pro` keeps this module's own `@Serializable` types for
a shrinking host, and `scripts/library-copy-check.sh` proves it keeps them
without `:reader-auth`'s rules.

## Substituting it in a host's tests

A host holds the module's interfaces — `ReaderLibraryOperations`,
`ReaderLibraryGateway`, `PublicationImportGateway`, `AssetDownloadGateway`
and the sync engine's `AccountLibraryActions`, `AccountHostRecords` and
`AccountImportRecords` — and takes the module's test fixtures
(`src/testFixtures/`, package `com.cedagova.reader.library.testing`, #199)
instead of writing its own doubles:

```kotlin
testImplementation(testFixtures(project(":reader-library")))  // brings :reader-auth's fixtures too
```

- Scripted doubles: `FakeReaderLibraryGateway`, `FakePublicationImportGateway`
  (its `refuse` is the real engine's), `FakeAssetDownloadGateway`,
  `RecordingAccountLibraryActions`, `RecordingHostRecords` and
  `InMemoryImportRecords`.
- `ReaderLibraryHarness`: a signed-in `ReaderLibraryClient` over the real
  `ReaderAuthClient` and `:reader-auth`'s one mock server, so a host's code is
  proven against the real call policy.
- `assetDownloadClientOver(engine)` and `publicationTransferClientOver(engine)`:
  the production transfer clients over a mock storage provider (they replace
  the former `createForTests`).

## Running its tests

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :reader-library:test
```

No network, no device and no backend value is involved. The tests use three
kinds of double:

- **reader-api operations** (`ReaderLibraryClientTest`, and the import
  lifecycle in `PublicationImportEngineTest`) drive a **real** `ReaderAuthClient`
  over a Ktor mock engine (`ReaderLibraryHarness` from the test fixtures) — real bearer, real single-flight refresh, real
  401/403/429/502 policy — so the module is proved to inherit that behaviour
  rather than restate it.
- **Storage transfers** (`PublicationTransferClientTest`,
  `AssetDownloadClientTest`) drive the real transfer clients over a mock
  storage provider; no `ReaderAuthClient` is involved, because those clients
  hold no session.
- **The sync engine and its gateway seam** (`AccountSyncEngineTest`,
  `ReaderLibraryGatewayTest`) run against scripted doubles —
  `FakeReaderLibraryGateway` and a recording `ReaderLibraryOperations` — not an
  HTTP stack.
