# #112 — `:reader-library`, the Reader library contract module

LEAF701 of [#104](https://github.com/cedagova/fastReader/issues/104), on the
`collector/106-account-library` branch. Everything below was run on this
worktree with JDK 21; no device and no backend value is involved.

## The contract pin

`reader-library/contracts/reader-api.openapi.json` was extracted from the
pinned identity and its digest verified before it was committed:

```
$ git -C ~/chunipers/reader-api show \
    a517fc6db64560df309bce656ec4c34e0bc7e1bd:contracts/reader-api.openapi.json > doc.json
$ shasum -a 256 doc.json
a550abfd7046368681d02aec50e80e80e372b152416c744669dd72ee534c6f9e  doc.json
```

which is the sha256 the plan and the leaf record.

`reader-library/contracts/reader-api.openapi.json.sha256` carries that digest
and `ReaderLibraryContractTest.the committed document is the pinned identity,
byte for byte` recomputes it on every run, so the committed document cannot
drift silently from the identity it claims.

**Note for whoever re-pins next.** The convenience command in the effort
conventions reads `origin/stage`, which has already moved past the pinned
commit: at the time of this work `origin/stage` was
`5b999d62febbbc52a722e89aeee649a0f65d5cd2` and its document hashes
`d01c14b2e92c788eb9b85626ce17a834f4e709fc9d81c29fa1de05bea7ea5c85`, not the
pinned digest. The pinned commit is the authority; read the document at
`a517fc6db64560df309bce656ec4c34e0bc7e1bd`.

## Validation

```
$ export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
$ ./gradlew :reader-library:test :reader-auth:test testDebugUnitTest lint
BUILD SUCCESSFUL
$ ./gradlew verifyRoborazziDebug
BUILD SUCCESSFUL
```

- `:reader-library` — 33 tests, 0 failures (28 operation tests, 5 contract tests).
- `:app` — 721 tests, 0 failures.
- No golden changed: `git status --porcelain app/screenshots/` is empty after
  both runs. This leaf renders no UI and names no golden.
- `reader-auth/CONTRACT.md` is untouched; it does not appear in `git status`
  and no commit in this branch modifies it.

### Without the stage values (what the hosted runner builds)

`local.properties` was reduced to `sdk.dir` alone (the three `reader.*` lines
moved aside, never deleted) and the same gate re-run:

```
$ ./gradlew --no-build-cache :reader-library:test :reader-auth:test testDebugUnitTest lint
BUILD SUCCESSFUL
```

with `BuildConfig.READER_API_BASE_URL`, `READER_SUPABASE_URL` and
`READER_SUPABASE_PUBLISHABLE_KEY` all generated as `""`. No value from that
file appears in this repository, in any test fixture, or in this document.

## The drift gate, demonstrated

`ReaderLibraryContractTest` carries its own negative case — `a renamed,
retyped or dropped field is caught` runs the same checker over deliberately
broken copies of four models — so the gate is proved in CI rather than only by
hand. The check below additionally breaks the **real** models, to show the
failure a future drift would actually produce.

Three edits to `reader-library/src/main/kotlin/.../model/Sync.kt`:

1. renamed `ReaderSyncDeltaResponse.latest_cursor` → `latest_cursors`;
2. retyped `ReaderSyncConflict.remote_revision` from `Long` to `String`;
3. dropped the required `ReaderSyncChange.server_admitted_at`.

```
$ ./gradlew :reader-library:testDebugUnitTest
33 tests completed, 7 failed
BUILD FAILED
```

The contract test's own message:

```
ReaderSyncConflict.remote_revision: the schema's type is integer, the model's is string
ReaderSyncDeltaResponse.latest_cursors: not declared by the pinned schema
ReaderSyncDeltaResponse.latest_cursor: the schema marks it required but the model has no such field
ReaderSyncChange.server_admitted_at: the schema marks it required but the model has no such field
```

All three mistakes are named with the schema, the field and the reason. The
edits were reverted and `./gradlew :reader-library:test` is green again.

## What this leaf does not prove

- **No device run and no stage round trip.** This leaf adds no UI and sends no
  request outside a mock engine; the first real call to reader-api is the sync
  engine's (LEAF702) and the stage round-trip evidence is LEAF901's.
- **Nothing owner-relayed.** No sign-in and no reader-web step was needed or
  requested for this leaf.
