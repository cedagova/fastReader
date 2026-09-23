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
| `AccountSyncEngine` | The one writer of an account's document. Bootstraps from the library/progress snapshot, drains the outbox in batches of ≤ 50 with persisted idempotency keys, reads the change stream to `has_more = false` (re-bootstrapping on an expired/invalid cursor), adopts every result and change from the backend's canonical payload, and publishes `state: StateFlow<AccountLibraryState>`. Runs only when asked: `requestSync(trigger)`. |
| `AccountSession` | What the host tells the engine about the session (`Loading`, `NotConfigured`, `SignedOut`, `SignedIn(userId)`), as a `Flow`. Signing out keeps the file; a different user discards the other accounts' held queues (D4). |
| `AccountLibraryActions` | The mutations a shelf performs: `refresh`, `removeFromAccount`, `undoRemove`, `recordOpened`, `recordFinished`, `recordStatus`, `recordPosition`. Only `library_item` and `reading_progress` envelopes are ever built. |
| `AccountImportRecords` | The durable publication-import records (AD-26), under the same writer. |
| `AccountHostRecords` | The host's own records in the same document — values the engine stores verbatim and never reads, queues or sends. Document-level (`hostRecord`, `updateHostRecord`) and per book row (`updateBookHostRecord`, read back as `AccountBook.host`). FastReader keeps its copy references and answered resume offers here. |
| `ReaderLibraryGateway` | The five library operations the engine calls; `ReaderApiLibraryGateway` passes them to `ReaderLibraryOperations`. Tests substitute a scripted fake. |
| `AccountLibraryStores` / `FileAccountLibraryStores` | Storage the host supplies: one atomically written JSON file per account, named after the SHA-256 of the user id. |
| `AccountLibraryDocument`, `AccountBook`, `AccountRemotePosition`, `AccountOutboxEntry`, `AccountLibraryCodec`, `AccountLibrarySchema` | The persisted canonical state (schema 6), migrated forward step by step and refused when newer. Keys a level does not declare are host records, kept verbatim and written back at the same level. |
| `PortableProgress`, `LocalReadingPosition`, `RemoteReadingPosition`, `ProgressRecord` | The portable position: the `reading_progress` payload built from `PutReaderProgressRequest` (a closed key set), which book a record is about, and what a canonical payload states. Mapping these to and from a host's own reading unit is the host's. |

**Freshness (contract `core.md` §7.3).** A mutation result or stream change
never moves a book's state backwards: one whose revision is older than the
stored one never replaces it, for positions and library items alike.

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
