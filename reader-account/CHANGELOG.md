# `:reader-account` changelog

What changed between two versions of this module, newest first. A host that
copies the module (docs/library-consumption.md) reads the entries between its
old and new version for anything it must change. Every change to this
module's files bumps `version` in `build.gradle.kts` and adds an entry here
in the same change.

## 0.1.0 — 2026-09-25

First version: FastReader's general account pipeline moved out of `:app`
(#200, A197-F003), with its behaviour, its persisted formats and its file
locations unchanged.

- Account session state and the sign-in surface's state model
  (`ReaderAccountState`, `ReaderAccountController`), the verified private
  copies of account books (`AccountCopyStore`, `AccountBookCopies`,
  `AccountCopyReferences`), their download (`AccountDownloads`), the
  add-to-account import (`AccountImports`, `BookImportState`) and the account
  shelf (`AccountShelf`). Packages renamed from
  `com.cedagova.fastreader.account[.library]` to
  `com.cedagova.reader.account[.library]`.
- Host seams for what is FastReader's: `AccountCopyCatalog` (the device
  catalog that makes a placed copy readable), `DevicePublicationSources` (a
  device book's bytes for an import, with `PublicationSourceProblem` and
  `PublicationSourceResult` moved here), `DeviceBookIdentity` (the host's
  device-book id for a content identity) and `ResumeOfferRecords` (the
  answered resume-offer note). `AccountShelf`'s undo window is the host's to
  pass.
- One mapping of `ReaderAuthException` to what a reader is shown
  (`AccountErrors.kt`): `AccountOutcome.Failure` is its result, and the
  download and import rows are projections of it, with every row state as
  before.
- `ReaderAccountGraph` assembles the libraries for a host in one call over
  `ReaderAccountGateways` (one `ReaderLibraryClient` for all three library
  gateways), and owns the foreground hooks a host used to wire by hand.
- The copy references (`AccountCopy`) keep schema 3's `copies` host record,
  byte for byte; the copies stay in `filesDir/account-copies/` and the account
  documents in `filesDir/account-library/`.
- Explicit-API surface recorded in `api/reader-account.api`; consumer keep
  rules for its own `@Serializable` types (`consumer-rules.pro`); scripted
  doubles of its seams in the test fixtures
  (`com.cedagova.reader.account.testing`).
