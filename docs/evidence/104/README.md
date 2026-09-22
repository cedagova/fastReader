# #104 — integrated stage round trip and the Chunipers proposal log

Evidence for leaf [#122](https://github.com/cedagova/fastReader/issues/122)
(increment 005 of root [#104](https://github.com/cedagova/fastReader/issues/104),
effort [#110](https://github.com/cedagova/fastReader/issues/110)). Definition
PR #105 @ `418e14b`, plan PR #111 @ `91176fe`, base `main` @ `1d6a3d5`.

This file has two halves:

1. **The stage round trip** — one run on `Phone_Mid_API36` with reader-web on
   stage as the other device. It needs the owner signed in on the emulator and
   acting on reader-web. Every slot below that needs that is marked
   **PENDING OWNER RUN**. Nothing in this directory is simulated: a slot is either
   a recorded artefact or it is empty and says so.
2. **The proposal log** — every gap surfaced in increments 001–004, checked
   against the artifacts and against the Reader API contract as delivered on
   reader-api's default branch, with its issue. This half is complete.

Rule for every artefact added here: no token, password, code, session value,
`local.properties` value or stage secret. Account and book UUIDs are cut to their
first 8 characters.

## 1. The stage round trip

### Before the run — read this first

**Stage has moved past FastReader's pinned contract with a breaking change.**
reader-api PR Chunipers/reader-api#517 (`stage` @ `f58bfb95`, deployed to stage
2026-09-22T17:31Z) changed the `reading_progress` payload from `locator` to
`location` (`ReaderPortableLocationV1`), and stage rejects the old shape with
`invalid_payload`. FastReader pins `a517fc6d` and sends `locator`. Reading the
code (not observed on stage), steps 6–7 are expected to fail against current
stage until [#139](https://github.com/cedagova/fastReader/issues/139) re-pins.
If they fail that way, record the rejection as the result — it is a finding, not
a hidden step (#122, "Failure and edge behavior"). Library, import and download
schemas are unchanged between the pin and `stage`, so steps 1–5 and 8 are not
affected by this.

PR #517 also describes a "bounded clean pre-launch reset of obsolete
Reader-managed activity/import state". Whether that reset has run on stage is
not known from here; if the account's library or positions look empty, that is
the likely cause.

### Steps and artefact slots

Devices: **E** = `Phone_Mid_API36` running the build from `main`; **W** =
reader-web on stage, same account. Owner procedure:
`~/worktrees/fastReader/.delivery/owner-test-list-110.md` (coordinator-owned).

| # | Step (#122 "In scope") | Artefact slot | Status |
| --- | --- | --- | --- |
| 1 | Add from FastReader → read on web | `01-add-to-account-consent-phone-mid-api36.png`, `01-web-library-shows-book.png`, `01-web-reading.png` | PENDING OWNER RUN |
| 2 | Add on web → download and read on FastReader | `02-account-only-row-phone-mid-api36.png`, `02-downloaded-and-open-phone-mid-api36.png` | PENDING OWNER RUN |
| 3 | Remove and Undo, FastReader → web | `03-remove-undo-offer-phone-mid-api36.png`, `03-web-after-undo.png` | PENDING OWNER RUN |
| 4 | Remove and Undo, web → FastReader | `04-web-remove-undo.png`, `04-shelf-after-foreground-phone-mid-api36.png` | PENDING OWNER RUN |
| 5 | Offline remove replayed once — result `applied`, then `replayed` for the same idempotency key | `05-offline-queued-notice-phone-mid-api36.png`, `05-ledger-excerpt.txt` | PENDING OWNER RUN — see note A |
| 6 | Position FastReader → web | `06-fastreader-paused-phone-mid-api36.png`, `06-web-continue-reading.png`, `06-progress-row.txt` | PENDING OWNER RUN — expected to fail until #139 |
| 7 | Position web → FastReader (includes a backward move, CP-3) | `07-web-position.png`, `07-resume-offer-phone-mid-api36.png` | PENDING OWNER RUN — expected to fail until #139 |
| 8 | Sign out keeping the downloaded copy, sign in again | `08-signed-out-copy-still-opens-phone-mid-api36.png`, `08-after-re-sign-in-phone-mid-api36.png` | PENDING OWNER RUN |
| 9 | Crash check over the whole run | `09-androidruntime-e-phone-mid-api36.txt` (`adb logcat -d -s AndroidRuntime:E`) | PENDING OWNER RUN |

**Note A — the ledger.** FastReader has no on-device view of per-mutation results:
`applied` / `replayed` exist only in the Reader API's response to
`POST /v1/reader/sync/mutations`, and the app reconciles them silently. A
`replayed` result also needs the *same* envelope sent again after the server
admitted it — for example, the response lost to airplane mode after the request
left. There is no deterministic way to force that from the UI. The excerpt
therefore has to come from the owner's own access to stage's mutation receipts
(status, resource type, revision, cursor, idempotency key — no user id). If no
`replayed` can be produced, record exactly that. The behaviour is proven locally
by `AccountSyncEngineTest` — *the same entry sent twice is applied once and
replayed once, and the row does not move* (increment 001, PR #123).

### Observations for #110

| Observation | Slot | Status |
| --- | --- | --- |
| A1 — stage's capabilities document lists `reader.sync.v1` as available for `reader-android` | `obs-a1-capabilities.txt` (entry id, availability, reason only) | PENDING OWNER RUN |
| A3 — the import policy is `enabled` and has an EPUB entry | `obs-a3-import-policy.txt` | PENDING OWNER RUN |
| CP-3 — what reader-web shows after a backward move made on FastReader | `obs-cp3-web-backward.png` | PENDING OWNER RUN (blocked by #139 for FastReader's side) |
| CP-4 — what reader-web shows for a position FastReader wrote (href + progression) | `obs-cp4-web-portable.png` | PENDING OWNER RUN (blocked by #139) |

### Evidence already on `main` that this run confirms rather than replaces

- `reading_progress.resource_id` is the book id — verified from the code stage ran
  at `96ee0c28`: [`docs/evidence/109/resource-id-verification.md`](../109/resource-id-verification.md).
  The rule still holds at `stage` @ `f58bfb95` (`repository_db.py:655`). Step 6's
  `06-progress-row.txt` is the live confirmation.
- Increment 004's device run and the privacy statement renders:
  [`docs/evidence/109/README.md`](../109/README.md).
- Increment 003 (download, verified copy, backup exclusion):
  `docs/evidence/108/leaf-119-*`.
- Increment 002 (consent before any byte leaves): `docs/evidence/107/leaf-117-*`.
- Increment 001 (signed-out app unchanged, privacy statement):
  `docs/evidence/106/`.
- Contract pin and the contract test: [`docs/evidence/112/README.md`](../112/README.md).

### "No FastReader request outside the pinned contract"

- **Local half, proven:** `ReaderLibraryContractTest` checks every model
  `:reader-library` sends or reads against the committed OpenAPI document, and
  `the committed document is the pinned identity, byte for byte` recomputes sha256
  `a550abfd…6f9e` (`Chunipers/reader-api@a517fc6d`). This leaf changes no code, so
  that proof stands as on `main`.
- **Stage half, PENDING OWNER RUN:** the ledger from step 5 and the progress row
  from step 6 are the stage records to compare against it.
- **Finding:** every request is inside the *pinned* contract, but stage no longer
  honours the pinned `reading_progress` body (see "Before the run"). Tracked in
  #139.

## 2. The proposal log

Read-only sources: reader-api default branch `stage` @
`f58bfb957a6ada5f4a8f69c096ea6032f0a30569` (OpenAPI sha256 `e2c184db…ade90`),
`docs/client-contracts/{README,core,reader-android}.md`; reader-web issue
timelines. All checked 2026-09-22 with the Chunipers worker identity.

### The definition's proposals (CP-1…CP-5)

| CP | Issue | State | What was delivered |
| --- | --- | --- | --- |
| CP-1 library and sync chapter | [reader-api#511](https://github.com/Chunipers/reader-api/issues/511) | CLOSED 2026-09-14 | `docs/client-contracts/core.md` §7 (PR reader-api#529). New evidence commented: [5781300534](https://github.com/Chunipers/reader-api/issues/511#issuecomment-5781300534), correction [5781324089](https://github.com/Chunipers/reader-api/issues/511#issuecomment-5781324089). |
| CP-2 generated client archives | [reader-api#512](https://github.com/Chunipers/reader-api/issues/512) | CLOSED 2026-09-14 | Release assets `reader-api-kotlin-<sha>.tar.gz` / `-openapi-` / `-manifest-` published (e.g. pre-release `reader-api-client-dev-93a39963…`). |
| CP-3 reader-web progress merge vs `activity-convergence.v1` | [reader-web#1907](https://github.com/Chunipers/reader-web/issues/1907) | CLOSED 2026-09-22 (completed) | Retitled "A1906-F001 Apply API-authoritative resume positions in the Web replica"; reader-web PRs #1926, #1989, #1922 merged. Behaviour on stage: observation CP-3 above. |
| CP-4 reader-web resume from a portable locator | [reader-web#1908](https://github.com/Chunipers/reader-web/issues/1908) | CLOSED 2026-09-22 (completed) | Retitled "A1906-F002 Connect persisted reading locations to the portable contract"; reader-web PR #1922 merged. Behaviour on stage: observation CP-4 above. |
| CP-5 import availability in capabilities | [reader-api#513](https://github.com/Chunipers/reader-api/issues/513) | CLOSED 2026-09-14 | `reader.publication-import.v1` capability with `availability` and typed reasons (PR reader-api#528); FastReader adopts it with #139. |

### What the delivered contract now says (recorded on #110)

| Question | Answer at `stage` @ `f58bfb95` |
| --- | --- |
| Does it define `reading_progress`'s resource id? | **By example only.** §7.5 states the book UUID normatively for `library_item`; §7.4 shows it for `reading_progress` only inside the example envelope. The server code fixes it as the book id. Noted on reader-api#511. |
| Does the change stream have an origin filter? | **No, and it does not need one.** `ReaderSyncChange` has no client/origin field. §7.3/§7.4 give the rule instead: store an admitted result's `revision`; apply a stream change only when its revision is newer. FastReader's echo problem is its own — #140. |
| Does it define import availability? | **Yes.** §6 and §7.6: offer import only with exactly one `reader.publication-import.v1` entry that is `available`; typed reasons include exhausted active capacity. |

### Gaps surfaced in 001–004, verified

| Source | Gap | Verdict | Issue |
| --- | --- | --- | --- |
| 001 (PR #123), 004 (PR #136) | `reading_progress.resource_id` meaning unverified | Verified from server code (`docs/evidence/109/`). The contract states it by example only — small doc gap. | Comment on [reader-api#511](https://github.com/Chunipers/reader-api/issues/511#issuecomment-5781300534) |
| Found during this log | Stage's `reading_progress` payload moved `locator` → `location` (PR reader-api#517), but the contract chapter still documents `locator` | **Chunipers gap** (doc contradicts deployed behaviour) | [reader-api#548](https://github.com/Chunipers/reader-api/issues/548) (new) |
| Found during this log | FastReader's pin is behind that breaking change | FastReader | [#139](https://github.com/cedagova/fastReader/issues/139) |
| 004 (PR #136, risk 2) | No origin filter, so your own echoed position is offered back after a rewind | Not a Chunipers gap (revisions suffice) → FastReader | [#140](https://github.com/cedagova/fastReader/issues/140) |
| 004 (PR #136, risk 1) | `progression` at full precision can be turned back into the exact word | FastReader wording call for the owner | [#141](https://github.com/cedagova/fastReader/issues/141) |
| 002 (PR #130, risk 4) | `max_active_imports` / `max_active_bytes` cannot be checked ahead by a client | Resolved upstream: the capability's typed reasons (CP-5) → adopt with the re-pin | [#139](https://github.com/cedagova/fastReader/issues/139) |
| 003 (PR #133, risk 2) | Is one fresh grant enough for a ~50 MiB download? | Not a gap: §7.7 says an expired URL is recovered with a fresh grant, and `expires_at` is in the grant. How many re-fetches is FastReader's choice; checked in the owner's step 2. | — |
| 002 (PR #130, risk 5), 003 (PR #133 notes) | Grant headers can follow a redirect or `Location` to another host | FastReader hardening; the contract sets no host limit on purpose | [#142](https://github.com/cedagova/fastReader/issues/142) |
| 001 (PR #123, step 18) | Cursor expiry cannot be triggered on stage | Not a gap: §7.2/§7.3 state 30-day retention and expose `minimum_valid_cursor`; the path is pinned locally | — |
| 003 (PR #133), 004 (PR #136) reviews | Other non-blocking notes (test names, goldens, lock hygiene, cancel race) | FastReader-internal, recorded in those PR bodies; not contract gaps | — |
