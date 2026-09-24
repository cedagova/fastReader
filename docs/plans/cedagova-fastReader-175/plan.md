# Implementation Plan: Keep the catalog until its replacement is in place

- Planning issue: https://github.com/cedagova/fastReader/issues/175
- Planning PR: https://github.com/cedagova/fastReader/pull/194
- Status: Ready for implementation
- Root classification: LEAF
- Delivery topology: DIRECT
- Planner: Planning lead (Claude)
- Started: 2026-09-24

## Pinned baselines

| Repository | Baseline |
| --- | --- |
| `cedagova/fastReader` | `ed02e5e3ff9ef989c78cce1099cac0132b5ff5b5` |

`cedagova/fastReader` is `origin/main` on 2026-09-24 (after #189, #190, #193).
It hosts the root, the FastReader `:app` module that owns the catalog store,
and the `:reader-library` module whose #164 fix (PR #172, merged
`9ed30a08cfdb2757c68ac030efd456bec7a110bd`, contained in the baseline) is the
precedent.

## Preserved objective and boundaries

Root #175 (bug, found while delivering #164 in effort #169). Objective
preserved: `FileCatalogStore.save` must never remove the live catalog before
its replacement is in place. The replace is one atomic step; if it fails, the
temporary file is deleted, `save` throws, and the previous catalog document is
left untouched and still loads.

Proof preserved: a test injecting a failing replace shows `save` throws, the
previous catalog bytes are unchanged and still load, and no temp file remains.

Boundaries. In: `FileCatalogStore.save`'s replace step, a test seam for it,
and its tests in `:app`. Out: the catalog schema, codec, load/set-aside
behaviour, callers' handling of a failed save, `:reader-library` and
`:reader-auth` stores (already atomic), and any new shared file-utility module.

## Classification

- **ROOT #175 — `LEAF`.** One defect in one class, one repository, one PR to
  `main`. The replace change and its failure test together produce the one
  observable result; neither is independently useful. No children; the
  fast-leaf path applies.

Not `ALREADY_SATISFIED`: at the baseline the fallback still deletes the live
file before retrying the rename (evidence below). Not `NEEDS_DECISION`: the
issue's one open question (share a helper or not) is settled below from the
module layout, and recorded so the owner can overturn it with one reply.

## Current-state evidence

At `ed02e5e3ff9ef989c78cce1099cac0132b5ff5b5`:

- **The bug.** `FileCatalogStore.save`
  (`app/src/main/java/com/cedagova/fastreader/library/store/CatalogStore.kt`
  lines 72-90) writes and fsyncs `<file>.tmp`, then calls
  `temporary.renameTo(file)`. If that returns `false` it runs
  `file.delete()` and retries the rename. A process death between the delete
  and the second rename, or a second failure, leaves no catalog on disk; the
  next `load()` sees no file and returns an empty `Catalog()` without setting
  anything aside — the whole local library index is lost silently.
- **Failure contract already exists.** On the final failure path `save`
  already deletes the temp file and throws `IOException`, and every caller in
  `LibraryRepository` (lines 162, 454, 614, 642) wraps `save` in a `try`
  that catches the exception and reports it as a persistence failure
  (`_persistenceFailure`); `mutateCatalog` saves before writing the theme
  mirror, so a throwing save leaves both at the old value. The fix changes
  when `save` throws, not what a throw means.
- **Precedent, same shape.** #164 / PR #172 fixed the identical code in
  `FileAccountLibraryStore`
  (`reader-library/.../sync/AccountLibraryStore.kt`): an `internal`
  constructor takes `replace: (source, target) -> Unit`; the public
  constructor passes a private `atomicReplace` that calls
  `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`; on `IOException` the temp
  is deleted and the save rethrows; after the move the parent directory is
  best-effort fsynced. Its test `a failed replace throws and the previous
  document survives` injects a throwing `replace`. `:reader-auth`'s
  `FileSessionStore` has its own private copy of the same one-line helper.
- **Platform.** `java.nio.file.Files.move` with `ATOMIC_MOVE` is available
  from API 26, which is the app's `minSdk`; on Android it is `rename(2)`.
- **Construction site.** Production builds the store once in
  `LibraryGraph.kt` with the public constructor; tests build it directly in
  `CatalogStoreTest` and several repository tests.

## Selected implementation direction

One PR on `main`, `:app` only.

1. **Atomic replace, no delete-first fallback.** `save` replaces the live
   catalog with the synced temporary file in one
   `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` step. The `delete()`-then-retry
   branch is removed. If the move fails, the temp file is deleted and `save`
   throws `IOException` (with the cause), leaving the old document as it was.
2. **Same seam as #164.** `FileCatalogStore` gains an `internal` constructor
   parameter for the replace step, defaulted by the public constructor to the
   atomic move, so a test can inject a failing replace. Public call sites
   (`LibraryGraph`, existing tests) do not change.
3. **Durability matches #164.** After a successful move, best-effort fsync of
   the parent directory, as `FileAccountLibraryStore` does; a platform that
   cannot open a directory for syncing is not an error.
4. **No shared helper.** Decided: keep a private, module-local helper in
   `:app`, as `:reader-auth` and `:reader-library` each do. The helper is one
   `Files.move` call; sharing it would mean either a public file utility on
   `:reader-library` (a sync library that `:app` should not depend on for
   generic I/O) or a new shared module, both larger than the defect and
   against keeping those modules extractable. Overturn with the reply
   `Share the helper` (implementation then exposes one internal-to-repo
   helper and reuses it in all three stores).
5. **KDoc.** The class comment states the old document is never removed
   before the new one is in place, as `FileAccountLibraryStore`'s does.

## Issue publication manifest

| Key | Kind | Parent | Repository | Title | Delivery | Blocked by | Issue |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ROOT | LEAF | None | cedagova/fastReader | app: catalog store save fallback deletes the catalog before replacing it | None | None | https://github.com/cedagova/fastReader/issues/175 |

## Acceptance coverage

| Root acceptance condition | Covered by |
| --- | --- |
| A failing replace makes `save` throw | ROOT — injected-failure unit test |
| The previous catalog bytes are unchanged and still load | ROOT — same test compares bytes and calls `load()` |
| No temp file remains after the failure | ROOT — same test asserts `<file>.tmp` is absent |
| The live catalog is never deleted before the replacement is in place | ROOT — direction 1 removes the delete-first branch |

No gaps or overlaps.

## Validation and feedback

- `./gradlew :app:testDebugUnitTest` with the new failing-replace test and the
  existing `CatalogStoreTest` suite (normal save, repeated save leaves no temp
  file, damaged-file set-aside, newer-schema block) passing.
- `./gradlew assembleDebug` and `lint` as the repo's normal gate.
- No emulator run and no Roborazzi change: no UI or lifecycle behaviour
  changes.

## Assumptions and open questions

None open. One recorded decision (direction 4, no shared helper) can be
overturned with the reply `Share the helper`.

## Satisfaction proof

Not applicable: implementation work remains (the delete-first fallback is
present at the baseline).

## Publication verification

- `plan validate --phase publication-ready`: run on the candidate head.
- `plan verify-graph`: one row, zero children; the root carries the
  `Planning root`/`Planning plan`/`Planning kind: LEAF` lines.
