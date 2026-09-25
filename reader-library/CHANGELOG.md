# `:reader-library` changelog

What changed between two versions of this module, newest first. A host that
copies the module (docs/library-consumption.md) reads the entries between its
old and new version for anything it must change. Every change to this
module's files bumps `version` in `build.gradle.kts` and adds an entry here
in the same change.

## 0.1.0 — 2026-09-25

First recorded version (#207). It names the module as it stands after these
changes, which had no version of their own, plus one addition:

- Added: consumer keep rules for the module's own `@Serializable` types
  (`consumer-rules.pro`), so a shrinking host keeps their serializer lookup
  without relying on `:reader-auth`'s rules (#207).
- The typed reader-api operations and models: library, progress, sync
  mutations and deltas, capabilities, publication import and asset download
  grants (#112, #116, #118, #139, #142).
- The account sync engine in `com.cedagova.reader.library.sync` (#147, #148,
  #150, #152, #164, #176, #177), split by responsibility into separate files
  with its public API unchanged (#210).
- The public surface is explicit-API and recorded in
  `api/reader-library.api` (#198).
- Contract-tested against the one pinned reader-api document in
  `../reader-auth/contracts/` (#208).
- Shared build conventions from `build-logic` (#205).
- Host seams and test fixtures (#199): `AssetDownloadGateway` and
  `PublicationImportGateway` (with `ReaderApiAssetDownloadGateway` and
  `ReaderApiPublicationImportGateway`) are the module's own interfaces;
  `ReaderLibraryClient` takes `:reader-auth`'s `ReaderApiOperations`. The test
  fixtures in `src/testFixtures/` (`com.cedagova.reader.library.testing`)
  ship the scripted doubles and `ReaderLibraryHarness`. Removed:
  `AssetDownloadClient.createForTests` and
  `PublicationTransferClient.createForTests` — use the fixtures'
  `assetDownloadClientOver` and `publicationTransferClientOver`.
