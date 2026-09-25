# `:reader-account` — the Reader account pipeline

What any Reader Android client needs on top of `:reader-auth` and
`:reader-library` to put a Reader account in front of a reader: the sign-in
surface's state, the account shelf, verified private copies of account books,
their download, and adding a device book to the account. Moved out of the
host app's `:app` by [#200](https://github.com/cedagova/fastReader/issues/200)
(audit finding A197-F003) so a second client reuses it instead of rewriting it.

## Contract

- **Depends on `:reader-library` (and through it `:reader-auth`), nothing under
  `:app`.** No symbol of the host app.
- **Holds no session and no token.** Every account call goes through
  `:reader-auth`'s `ReaderAuthOperations` or a `:reader-library` gateway; the
  manifest is empty, so a host gains no permission from it.
- **One error mapping.** `AccountErrors.kt` turns each `ReaderAuthException`
  branch into an `AccountOutcome.Failure`; the sign-in surface shows it, and
  the download and import rows are projections of that one result. The sync
  engine's own `AccountSyncError` is mapped by `toSyncError` inside
  `:reader-library` (`sync/AccountLibraryState.kt`): the engine lives there,
  and moving the mapping here would make `:reader-library` depend on this
  module — a cycle.
- **Formats and locations are fixed.** Copies live in
  `filesDir/account-copies/`, the account documents in
  `filesDir/account-library/`, and the copy references in schema 3's `copies`
  host record — the ones the pipeline used before it became a module, so its
  first host migrated nothing. `filesDir` must be app-private and excluded
  from backup and device transfer.
- **Explicit public surface**, recorded in `api/reader-account.api` and checked
  by `./gradlew check`.

## Assembling it: one call

```kotlin
val account = ReaderAccountGraph(
    gateways = authClient?.let(ReaderAccountGateways::over), // null: not configured, calls nothing
    missingValues = missingConfigurationKeys,
    filesDir = context.filesDir,
    copyCatalog = myCopyCatalog,               // AccountCopyCatalog
    publicationSources = myDeviceBooks,        // DevicePublicationSources
    bookIdentity = myBookIdentity,             // DeviceBookIdentity
    resumeOffers = { records -> myResumeOfferNote(records) }, // ResumeOfferRecords
    scope = applicationScope,
    undoWindowMs = myUndoWindowMs,
)
// Process lifecycle observer:
//   onStart -> account.onForeground()   (copy sweep once, session refresh, sync, imports)
//   onStop  -> account.onBackground()
```

Make the call once per process, in whatever object the host's `Application`
builds as its composition root. The graph exposes `account` (sign-in state
model), `sync` (the `:reader-library` engine), `shelf`, `imports`, `copies`
and `downloads`. Its `onForeground()` covers `:reader-auth`'s foreground hook
and `:reader-library`'s foreground sync and session bridge; the other host
requirements of both libraries (backup exclusion, cleartext policy,
configuration) stay the host's — see
[docs/architecture.md](../docs/architecture.md#host-obligations-library-by-library).

## Host seams

| Seam | What the host decides |
| --- | --- |
| `AccountCopyCatalog` | What makes a verified, placed copy readable (a catalog row, a source). |
| `DevicePublicationSources` | The bytes behind a device book, for an import. |
| `DeviceBookIdentity` | Its own device-book id for a content SHA-256, and back. |
| `ResumeOfferRecords` | Where an answered resume offer is noted (UX policy). |
| `undoWindowMs` (a value) | How long an account removal can be taken back. |

This repository's host implementations are listed in
[docs/architecture.md](../docs/architecture.md#reader-account).

## Packages

| Package | What it holds |
| --- | --- |
| `com.cedagova.reader.account` | `ReaderAccountGraph`, `ReaderAccountGateways`, `ReaderAccountController`, `ReaderAccountState`, `AccountOutcome`, the error mapping |
| `com.cedagova.reader.account.library` | Shelf, copies, downloads, imports and the host seams |

## Tests and fixtures

- `src/test`: the controller, the error mapping, the graph's foreground hooks,
  the copy store, copy references and the shelf
  (`./gradlew :reader-account:testDebugUnitTest`). The download and import
  pipelines are proved in the host with its real seams: in this repository,
  `:app`'s `AccountBookCopiesTest`, `AccountDownloadsTest` and
  `AccountImportsTest`.
- `src/testFixtures` (`com.cedagova.reader.account.testing`): scripted doubles
  of the seams and of `AccountCopyReferences`, for a host's tests —
  `testImplementation(testFixtures(project(":reader-account")))`.

## Taking it into another repository

Copy it as part of the copy set in
[docs/library-consumption.md](../docs/library-consumption.md), with
`:reader-library` and `:reader-auth` at the same commit; its version and
changes are in `build.gradle.kts` and [CHANGELOG.md](CHANGELOG.md).
