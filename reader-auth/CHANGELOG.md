# `:reader-auth` changelog

What changed between two versions of this module, newest first. A host that
copies the module (docs/library-consumption.md) reads the entries between its
old and new version for anything it must change. Every change to this
module's files bumps `version` in `build.gradle.kts` and adds an entry here
in the same change.

## 0.1.0 — 2026-09-25

First recorded version (#207). No code change; it names the module as it
stands after these changes, which had no version of their own:

- The client contract ([CONTRACT.md](CONTRACT.md)) and its implementation:
  email-code and password sign-in, the Keystore-encrypted session store, the
  single-flight refresh, the reader-api 401/403/429/502 policy and sign-out
  (#92, #93, #99; hardened by #173, #178, #180, #182, #184, #185, #187, #189,
  #191).
- The public surface is explicit-API and recorded in `api/reader-auth.api`;
  the provider SDK's session type is wrapped in the module's own
  `StoredSession` (#198).
- Host seams and test fixtures (#199): `ReaderAuthClient` implements
  `ReaderAuthOperations` and `ReaderApiClient` implements
  `ReaderApiOperations`, the interfaces a host holds to substitute them. The
  test fixtures in `src/testFixtures/` (`com.cedagova.reader.auth.testing`)
  ship the one mock server (`FakeServers`), `ReaderAuthHarness` (the real
  client over it) and `FakeReaderAuthOperations`. Removed:
  `ReaderAuthClient.createForTests` and `StoredSession.forTests` — use
  `ReaderAuthHarness`. `SessionStore` and `StoredSession` are internal:
  session storage is not a host seam. Ktor is no longer in the surface (an
  `implementation` dependency).
- The one pinned reader-api contract document in `contracts/` and the shared
  contract checker in `src/contractTest/`, which `:reader-library` uses too
  (#208).
- Shared build conventions from `build-logic` (#205).
- Consumer keep rules in `consumer-rules.pro` (#100).
- Documentation (#209): README and doc comments name no host app; the
  README says where SDK levels and versions come from (`build-logic`, the
  catalog). No code change.
