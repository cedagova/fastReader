# Step 14 — what `reading_progress.resource_id` carries on stage

Closes the assumption increment 004 (#109) shipped as **unverified**: that a
`reading_progress` record's `resource_id` in the `reader.sync.v1` change
stream is the **account book id**. FastReader's one seam,
`PortableReadingPosition.recordFor`, recognises a record by its `resource_id`
(or a `book_id` in the payload) and otherwise surfaces
`AccountSyncError.UnrecognizedProgressRecord`.

Verified 2026-09-22 against the code stage actually runs, not against a live
row. The server is the only writer of the stream, so its code is the
authority on the record's shape.

## Which code stage runs

| Fact | Value | Source |
| --- | --- | --- |
| Live stage deploy | `dep-daouas5g1s2s738n9720` (2026-09-22T02:19Z) | Render, service `reader-api-stage` |
| Image | `ghcr.io/chunipers/reader-api@sha256:e9643361…003c93` | Render deploy record |
| Commit | `96ee0c2812b35f999277858319497c02bb0b76ca` (reader-api `stage` head) | Chunipers/reader-api#541, delivery comment 2026-09-22T02:21Z; immutable record `stage-deployment-record-96ee0c28…` |
| FastReader pin | `a517fc6db64560df309bce656ec4c34e0bc7e1bd` | `reader-library/contracts/PINNED.md` |
| Pin → stage head | ancestor: yes. Commits touching `app/features/reader_sync/{repository_db,service}.py`: **none** | `git log a517fc6d..origin/stage -- <files>` |

## The rule, at the deployed commit

- `app/features/reader_sync/service.py:90`
  `_UUID_RESOURCE_TYPES = {"book", "library_item", "reading_progress", "note"}`
  — the envelope's `resource_id` must be a UUID for these types.
- `app/features/reader_sync/repository_db.py:660`
  `if mutation.resource_type in {"library_item", "reading_progress"}: book_id = mutation.resource_id`
  — the book a mutation is about **is** its resource id.
- `repository_db.py:918-931` — a `reading_progress` upsert writes
  `public.reading_progress (user_id, book_id, …)` with `book_id` taken from
  that resource id, `ON CONFLICT (user_id, book_id)`.
- `repository_db.py:1156-1161` — the canonical payload for a
  `reading_progress` resource is read back
  `WHERE user_id = %s AND book_id = UUID(resource_id)`.
- The stream emits `mutation.resource_id` unchanged
  (`repository_db.py:160-184, 360`).

## The other client

reader-web `stage` @ `dad8b8d1`, `src/app/readerSyncTransport.ts:23`:
`const bookIdentityResourceTypes = new Set(['book', 'library_item', 'reading_progress'])`
— `reading_progress` is a book-identity resource whose `resource_id` is
resolved as the canonical book id. The payload's `book_id` is deliberately
stripped before sending (line 46: "the admitted reader.sync.v1 progress
schema identifies the book exclusively through resource_id").

## FastReader's side

`app/src/main/java/com/cedagova/fastreader/account/library/PortableReadingPosition.kt:199-220`
— `recordFor` returns `Recognized(resourceId)` when the resource id names a
book the account holds, which is exactly the shape above. The `Unrecognized`
branch stays a guard for a future contract change, not an expected path.

## What this does not prove

No live row was read; the stage database is not reachable from this machine.
The owner's increment-005 round trip captures one real record as
confirmation. A mismatch there surfaces as `UnrecognizedProgressRecord` on the
shelf, by design.
