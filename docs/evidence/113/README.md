# #113 — the account store and the foreground sync engine

LEAF702 of [#104](https://github.com/cedagova/fastReader/issues/104), on the
`collector/106-account-library` branch, on top of LEAF701 (#112). Everything
below was run on this worktree with JDK 21. **No device run and no stage round
trip is in here** — see "What this leaf does not prove".

## Validation

```
$ export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
$ ./gradlew testDebugUnitTest verifyRoborazziDebug lint
BUILD SUCCESSFUL
```

- `:app` — 743 tests, 0 failures (22 of them new: 6 store, 16 engine).
- `verifyRoborazziDebug` green and **no golden changed**:
  `git status --porcelain app/screenshots/` is empty. This leaf renders no UI
  and names no golden.
- `PrivacyStatementTest` passes unchanged. This leaf edits no promise copy:
  AD-27 gives increment 001's privacy wording to LEAF704 (#115), and nothing
  here sends a book, a position, a setting or a crash report — the engine
  reads the *account's* library and sends only `library_item` mutations the
  shelf asks for.
- `ReaderLibraryContractTest` (the pinned-OpenAPI drift gate) passes unchanged;
  this leaf adds no request outside the five operations `:reader-library`
  already declares.

### Without the stage values (what the hosted runner builds)

`local.properties` was reduced to `sdk.dir` alone (the three `reader.*` lines
moved aside by renaming the file, never deleted) and the same gate re-run:

```
$ ./gradlew testDebugUnitTest verifyRoborazziDebug lint
BUILD SUCCESSFUL
```

No value from that file appears in this repository, in any test fixture, or in
this document.

## What the tests prove

`AccountLibraryStoreTest` (AD-20, one JSON document per account user id):

- a saved document comes back exactly as it went in;
- an account this device has never seen loads as an empty document;
- **a document from a newer build is refused and left byte-for-byte alone** —
  the test compares the file's text before and after the refused load;
- a damaged document is set aside (`.damaged-<millis>`), not deleted;
- each account gets its own file and the user id is not in the file name;
- `exceptUser` names every other account's document and never this one's.

`AccountSyncEngineTest` (AD-21, AD-22, D4), all against LEAF701's scripted
`FakeReaderLibraryGateway` — no SDK, no network, no Keystore:

| Acceptance clause | Test |
| --- | --- |
| bootstrap populates rows and cursor | `bootstrap populates the rows and the cursor from the account's lists` |
| a delta upsert/delete/restore updates rows | `a delta upsert, delete and restore each move the row` |
| deltas read until `has_more` is false | `the stream is read until has_more is false` |
| an outbox entry sent twice → one `applied`, one `replayed`, row unchanged | `the same entry sent twice is applied once and replayed once, and the row does not move` |
| the outbox drains before the stream is read | `the outbox drains before the stream is read` |
| `cursor_expired` re-bootstraps and sends nothing new | `an expired cursor re-bootstraps and sends nothing new` |
| `reader.sync.v1` unavailable → no library call, reason in state | `an unavailable capability defers with the reason and sends no library request`, `an undeclared capability is unavailable, not an error` |
| never prompt on `conflict` | `a conflict result is adopted, never prompted` |
| surface `rejected` with the backend's code | `a rejection is surfaced with the backend's own code and does not move the row` |
| offline: rows last known, queue kept | `offline keeps the queue and the last known rows` |
| D4 — sign-out keeps the store | `signing out keeps the store and takes the account rows off the shelf` |
| D4 — same account re-sends the held queue once | `signing in again to the same account restores the rows and sends the held queue once` |
| D4 — a different user id starts empty, old queue discarded | `a different account starts empty and discards the previous account's held queue` |
| session gone through the existing `SignedOut` branch | `a session the backend rejects ends in the signed-out state with its reason` |
| no `profile`/`settings`/`note`/`bookmark` mutation is ever produced | `nothing but library_item mutations is ever produced` |

### How the replay test proves key stability across process death

The engine mints an idempotency key once, at enqueue, and *persists* it. The
test captures the store file's bytes while the entry is still queued, lets the
drain run to `applied`, then **writes those bytes back** — which is exactly
what a process death between the send and the save leaves behind — and starts a
second engine on the same file. The second engine submits the *same* key, the
backend answers `replayed`, and the row is byte-identical to the one the first
drain produced. A freshly minted UUID would have failed that assertion.

## Two mechanical decisions worth naming

- **The head cursor is read before the lists, not after.** `bootstrap()` calls
  `deltas("0", limit = 1)` first and uses nothing but its `latest_cursor`, then
  reads `library()` and `progress()`. Reading the cursor afterwards would place
  it past a change the lists never showed, and that change would be lost;
  reading it first means such a change is still after the stored cursor and
  arrives with the next delta read. `latest_cursor` is a required field of
  every delta answer, an expired one included, so the head read works on an
  account whose stream start has already aged out.
- **The account file is named after the SHA-256 of the user id.** The id is a
  provider subject with no promised shape a filesystem accepts, and there is no
  reason for it to be legible in a directory listing or a bug report. The id
  itself is stored *inside* the document, and a document naming a different
  account is not adopted.

## What this leaf does not prove

- **The stage half of the acceptance criteria is not done here.** The leaf
  contract says that run happens "with LEAF703's surface", and there is no UI
  in this increment yet: nothing can drive a remove, an Undo or a foreground
  refresh on a device. The remote-remove / Undo / airplane-mode ledger evidence
  (`applied` then `replayed`, under `docs/evidence/106/`) is **owed by
  LEAF703's device run** and is explicitly *not* claimed by this leaf.
- **Nothing owner-relayed was performed.** No sign-in on the emulator and no
  reader-web step was requested or done for this leaf.
- **No device run.** The engine is reachable only from the foreground hook when
  a session exists; signed out it calls nothing, so a signed-out device run
  would show exactly the v1.6.0 app and prove nothing about this change.
- **The canonical payload's per-resource shape is not pinned by the contract.**
  The document types it as a free-form object, so `AccountCanonicalPayload`
  reads the keys the library documents use and keeps the stored value for every
  key an answer omits. The real shapes are confirmed by LEAF703's stage run,
  not here.
