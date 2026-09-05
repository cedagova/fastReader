# Implementation Plan: Consumer-ready FastReader: v1.1.0 shippable to strangers, then v1.2.0 reading depth

- Planning issue: https://github.com/cedagova/fastReader/issues/36
- Planning PR: https://github.com/cedagova/fastReader/pull/40
- Status: Review
- Root classification: INCREMENTAL
- Delivery topology: INCREMENTAL
- Planner: Planning lead (Claude)
- Started: 2026-09-05
- Product definition: https://github.com/cedagova/fastReader/pull/37
- Product definition head: da4394c35e305a4e2a1d6516cababf126fa277c0

## Pinned baselines

| Repository | Baseline |
| --- | --- |
| `cedagova/fastReader` | `a8caa0964bf892efa6ba5f1a33b47a06309bba6a` |

## Preserved objective and boundaries

The approved objective is the product definition at the pinned head
(`docs/product-definitions/cedagova-fastReader-36/definition.md`, PR #37 at
`da4394c`; Requirements Brief:
https://github.com/cedagova/fastReader/issues/36#issuecomment-5555054300):
turn FastReader v1.0.1, a private sideload, into an app a stranger can
install from a link, get their own EPUB into, and keep using through
updates, delivered as two sequential in-place releases — **v1.1.0**
(shippable to strangers, #38, REQ-101..REQ-113) then **v1.2.0**
(reading-experience depth, #39, REQ-201..REQ-208), with REQ-301..REQ-303
constraining both.

Everything in that definition is preserved unchanged: the requirements and
their acceptance, the flows and failure states, the owner decisions D1–D6
(D1 keep the v1.0.1 cue set incl. the opt-in alignment with #33's residual
risk stated; D2 MIT; D3 exclude all app data from device backup; D4 chapter
pause becomes a setting default on; D5 Spanish UI in v1.2.0; D6 GitHub
Releases by link only, no store or catalogue while #33 is open), the
evidence, assumptions, remaining uncertainty, non-goals and success
measures. The two published outcome issues #38 and #39 remain the native
children of root #36 and keep their `Definition handoff: PLANNING_REQUIRED`
provenance. The owner-directed delivery constraints recorded in the
definition (automated checks on every push and PR that block a broken
golden or test; the v1 definition and plan documents on `main` at the paths
the code cites; toolchain and libraries brought to current stable with
goldens re-recorded in the same change, in v1.2.0) are carried as planned
work below. This plan adds system-level HOW and executable leaves; it does
not reinterpret the product contract.

## Classification

- **ROOT #36 — `INCREMENTAL`.** The two native outcome children are each a
  delivery-sized effort with its own release boundary, and the definition
  itself orders them: v1.2.0 ships after v1.1.0, nothing in OUT006 is
  user-visible before OUT005 is released, and REQ-208 needs the *published*
  v1.1.0 artifact. Flattening both into one project run would put two
  releases under one coordinator; a strict sequence gives v1.2.0 a
  known-good published baseline. Root #36 becomes a tracking-only root.
- **#38 v1.1.0 — `GROUP` (ready), sequence 001.** Nine leaves covering the
  repository shell, app identity and first frame, the reader's open path,
  "Open with", library housekeeping, version/backup/privacy, the focused-
  mode speed gesture, the bundled sample, and the v1.1.0 release with its
  update proof. Topology `COLLECTOR`.
- **#39 v1.2.0 — `GROUP` (ready), sequence 002.** Seven leaves: toolchain
  refresh, chapter control, library order and rescan, tablet/landscape
  layouts, crash report, Spanish interface, and the v1.2.0 release with its
  update proof. Topology `COLLECTOR`.

No increment is `DEFERRED`. Every v1.2.0 requirement is fixed by the pinned
definition and its execution path is knowable from the pinned code today;
nothing in it depends on evidence only v1.1.0's delivery can produce. The
sequence edge (002 blocked by 001) carries the product ordering; it is not
a deferral.

## Current-state evidence

All at `a8caa09` (definition evidence re-verified by reading the source):

- **Manifest** declares no icon, no backup attributes, a light-only launch
  theme (`Theme.FastReader` parents `Theme.Material.Light.NoActionBar`, no
  `values-night`), and only the `MAIN/LAUNCHER` intent filter — no
  `ACTION_VIEW`/`ACTION_SEND`.
- **Book identity** is the SHA-256 of the whole file (`EpubInspector`
  computes it at ingestion; the catalog `Book.id` and `readingStates` keys
  are that digest). The reader's open path (`EpubContentPipeline.parse` →
  `openPackage`) recomputes the same whole-file digest on every open with
  the zip scanner's default `computeDigest = true`; the later spine read
  passes `false`. This is the REQ-110 cost the audit found.
- **Positions** live in `Catalog.readingStates`, keyed by identity and kept
  independently of the `books` list (REQ-004/REQ-005), so a position can
  exist for a book that has no library row. `ReadingState` already carries
  `updatedAtEpochMs`; `Book` carries `addedAtEpochMs`.
- **Folder removal** exists at the repository level (`removeFolder` drops
  only sources that folder provided and keeps books with other sources) but
  no screen calls it; book removal is one tap with no undo.
- **Rescan** runs on every foreground (`FastReaderApplication` observes
  process start) with a 2 s minimum interval; manual refresh always runs.
- **Chapters:** every spine item is a `Chapter`; `ReaderSession` holds at
  every chapter change (v1 REQ-015, mandatory).
- **Settings/schema:** `ReaderSettings` is stored inside the catalog
  document, schema version 4 with three forward migrations; every field has
  a documented default and absent keys read it back (AD-3 discipline).
- **Resources:** one `values/strings.xml` (114 strings), no other locale;
  one layout at every width.
- **Repository:** public, `license: null`, no `.github/workflows`, no
  README; `docs/` holds `agent-first-development.md`, `release.md` and
  `evidence/`; the v1 definition and plan live only on closed PR branches
  (#2, #7). `scripts/release.sh` builds, verifies (signature pin, no
  INTERNET permission, minSdk 26, version match) and publishes a GitHub
  Release with an unauthenticated re-download check. Releases v1.0.0 and
  v1.0.1 (versionCode 2) are published; `version.properties` is the single
  version source.
- **Verification loop:** 330 unit tests and 40 Roborazzi goldens under
  `app/src/test`, emulator AVD matrix documented in the repo guide.

Nothing is `ALREADY_SATISFIED`: every requirement names behaviour absent at
the baseline.

## Selected implementation direction

All work stays in `cedagova/fastReader`, single `:app` module, existing
packages (`epub`, `content`, `library`, `reader`, `settings`, `ui`). Each
increment ends with a published release and a proven in-place update over
the previous published release. System-level shape:

1. **Book identity stays the whole-file SHA-256** (no migration, so REQ-113
   and REQ-208 keep every position). What changes is *who computes it*: the
   reader's open path takes identity as an input — the catalog id for a
   library book, a digest computed once by the external-open path for a
   book outside the catalog — and never re-hashes. The content pipeline
   reads only the container, package, navigation and spine entries it
   needs, through the archive's directory rather than by streaming the
   whole file, so image payload is never read (REQ-110).
2. **The reader accepts a book that is not a catalog row.** One open
   contract — bytes source plus identity plus a "how it got here" origin —
   serves the library, "Open with"/share (REQ-103) and the bundled sample
   (REQ-109). Positions for such books use the existing identity-keyed
   `readingStates`; a session-only open persists no URI grant and no
   library row, only that position (REQ-103, REQ-107 privacy copy).
3. **"Open with" is the system association.** `ACTION_VIEW` and
   `ACTION_SEND` intent filters for EPUB (MIME plus a file-name fallback for
   sources that send a generic type); a persistable grant is taken when
   offered and the book is added exactly like a pick; otherwise the session-
   only path with the one-line notice and "Add to library" (which launches
   the picker). A running app receiving a new intent switches book without
   a second process. Non-EPUB or DRM input lands on the existing reader
   explanation and adds nothing.
4. **The first frame is drawn from a cheap, pre-Compose mirror of the theme
   setting.** The window theme/background and the system splash resolve
   light/dark before Compose runs, from a value kept in sync on every
   settings write; "System" resolves through night resources. An adaptive
   icon with a monochrome layer feeds launcher, recents and splash
   (REQ-101/REQ-102).
5. **Housekeeping is repository-first.** Folder list with status and removal
   with a computed "books that will leave" count (only sources this folder
   alone provided, files untouched, positions kept) and an undo window for
   book removal that restores the row with its position and progress
   (REQ-104/REQ-105). Removal semantics already exist at the repository
   level; the leaf exposes them and adds undo.
6. **Identity and honesty in Settings.** Version row from the build's
   `versionName`, "Check for updates" as a browser hand-off to the releases
   page (no network permission), backup exclusion for everything the app
   stores, and a privacy statement whose every sentence maps to a manifest
   declaration or an observed behaviour (REQ-106/REQ-107, D3).
7. **Focused-mode speed is one gesture on the reading surface** with a
   static, text-only, self-dismissing readout in 25 WPM steps; tap-to-pause
   and long-press keep working (REQ-108, REQ-302). Which gesture is the
   implementation lead's choice inside the definition's stated latitude.
8. **The sample is an asset, not a book:** one short public-domain passage
   in English and one in Spanish bundled with the app, source named in the
   UI, offered from the empty library (Spanish first on a Spanish device)
   and from Settings once real books exist, streamed through the same
   reader without writing anything outside private storage (REQ-109).
9. **Publishing hygiene** — MIT license, README (screenshots, install steps,
   privacy statement, by-link personal-use statement linking #33), the v1
   definition and plan documents on `main`, and GitHub Actions running unit
   tests, golden verification and lint on every push and pull request
   (REQ-111 plus the owner's delivery constraints).
10. **v1.2.0 depth** — toolchain and libraries to current stable with goldens
    re-recorded first; then a chapter-pause setting (schema field, default
    on) and a one-time front-matter skip on first open; library order
    (title / recently read / recently added, default recently read,
    persisted) plus a longer return-to-app rescan interval; tablet-width and
    landscape layouts (controls beside the stream, library using the width);
    an in-process crash capture that offers a share-sheet text report once
    on the next launch; a complete Spanish resource set; and the v1.2.0
    release with its update proof.

Concrete choices inside these boundaries (icon artwork, gesture, undo
mechanism, exact rescan interval, front-matter detection heuristics, the
sample passages, CI runner) are reversible implementation-lead decisions
constrained by the invariants and acceptance below.

## Architecture decisions

- **AD-8 — Identity unchanged, computation moved.** Book identity remains the
  whole-file SHA-256 (v1 AD-2), so no catalog migration and no orphaned
  positions. The reader never recomputes it: identity is an input to the
  open contract. REQ-110's cost is removed by not hashing on open and by
  reading only needed archive entries through the archive directory.
  Consequence: an external open with session-only access still computes the
  digest once to key its position; that one hash is outside REQ-110's
  measured library-book path and is accepted.
- **AD-9 — Non-catalog books in the reader.** The reader's open contract is
  (bytes source, identity, origin). Origin distinguishes library, external-
  keepable (already added), external-session-only (notice + "Add to
  library"), and sample (no library semantics, no persisted grant). Positions
  for external books reuse identity-keyed `readingStates`, extending the
  v1 "position kept in case it comes back" rule exactly as the definition
  states.
- **AD-10 — Pre-Compose theme mirror.** The theme choice is mirrored into a
  store readable before the first frame (kept in sync by the settings
  write path) and applied to the window theme, window background and splash
  before Compose composes; "System" resolves through `values-night`
  resources. The catalog document remains the source of truth; the mirror
  is a cache with the same default.
- **AD-11 — Whole-app backup exclusion.** Everything FastReader stores is
  excluded from device backup and device-to-device transfer through
  manifest backup attributes/rules; nothing is opted back in. Verified on
  the emulator with the platform backup tooling, not by reading the
  manifest alone.
- **AD-12 — Published-to-published update proof per increment.** Each
  increment's release leaf installs the previous *published* GitHub Release
  asset on the reference emulator, seeds books/position/settings, installs
  the new published asset in place, and records the preserved state under
  `docs/evidence/<issue>/` (REQ-113, REQ-208). Migrations stay forward-only
  under v1 AD-3.
- **AD-13 — Hosted checks gate `main`.** GitHub Actions runs
  `testDebugUnitTest`, `verifyRoborazziDebug` and `lint` on push and pull
  request. Goldens must be reproducible on the runner; if the runner's
  rendering differs from the recording machine, the leaf makes the runner
  the golden-recording platform (or pins a matching runner image) and
  documents it in the repo guide, rather than loosening the gate.
- **AD-14 — Crash capture is local and explicit.** An uncaught-exception
  handler writes a plain-text report (app version, device model, Android
  version, stack trace) to private storage and re-throws; the next launch
  offers to share it through the system share sheet once; decline deletes
  it. No book text, file names or paths. No network.
- **AD-15 — Localization through resources only.** Spanish is a complete
  `values-es` set including content descriptions and the visual-only
  statement; lint's missing-translation check becomes a gate so no new
  string can ship untranslated. The sample already carries both languages.
- **AD-16 — Schema evolution continues under v1 AD-3.** New persisted fields
  (chapter pause, library order, per-book front-matter-offer-shown) each get
  a documented default and one forward migration step; leaves that bump the
  schema in the same increment serialize through the collector (the second
  rebases onto the first's version). Existing readers see defaults that
  reproduce v1 behaviour (pause on; order recently read).
- **AD-17 — Release identity.** v1.1.0 is versionCode 3 and v1.2.0 is
  versionCode 4 via `version.properties`; both are cut with the existing
  `scripts/release.sh` and keep the pinned signing certificate. No store
  publication (D6).

## Execution graph and waves

Strict increment sequence (002 natively blocked by 001; no parallel
frontier). Leaves depend only on siblings inside their own increment.

- **001 v1.1.0 (#38)** — topology `COLLECTOR`.
  - wave 1: LEAF501 repository shell (CI, MIT, v1 docs on `main`); LEAF502
    icon and first frame; LEAF503 open path without re-hashing; LEAF505
    folder list, removal and undo; LEAF506 version, updates, backup,
    privacy; LEAF507 focused-mode speed gesture.
  - wave 2: LEAF504 "Open with"/share (blocked by LEAF503); LEAF508 bundled
    sample (blocked by LEAF503).
  - wave 3: LEAF509 README, cue-set check, v1.1.0 release and update proof
    (blocked by LEAF501, LEAF502, LEAF504, LEAF505, LEAF506, LEAF507,
    LEAF508).
  - Completion rule: `main` builds and is green in CI; every REQ-101..113
    acceptance recorded on its leaf; v1.1.0 published from `main` with the
    update-over-published-v1.0.1 evidence; `Fixed focus letter` still
    opt-in (D1). The owner then performs the definition's unaided
    stranger test and records the result on #38 (human-performed measure;
    see Acceptance coverage).
- **002 v1.2.0 (#39)** — topology `COLLECTOR`.
  - wave 1: LEAF601 toolchain and library refresh with goldens re-recorded.
  - wave 2: LEAF602 chapter control; LEAF603 library order and rescan;
    LEAF604 tablet and landscape layouts; LEAF605 crash report (each
    blocked by LEAF601).
  - wave 3: LEAF606 Spanish interface (blocked by LEAF602, LEAF603, LEAF604,
    LEAF605 — it translates every string those leaves add).
  - wave 4: LEAF607 v1.2.0 release and update proof (blocked by LEAF606).
  - Completion rule: `main` green in CI on the refreshed toolchain; every
    REQ-201..208 acceptance recorded; v1.2.0 published with the update-
    over-published-v1.1.0 evidence showing defaults for the new settings.

Why `COLLECTOR` for both: one repository, cumulative leaves that share the
reader open contract and the settings schema, and a release that must be
cut from the integrated result after aggregate verification.

## Interfaces and ownership

Everything is owned by `cedagova/fastReader`; no cross-repository
contracts. Internal boundaries the leaves must respect:

- **Reader open contract (AD-8/AD-9):** established by LEAF503 (identity as
  input, no re-hash, directory-based archive reads); consumed by LEAF504
  (external books, session-only origin) and LEAF508 (sample origin).
  LEAF503 must not change position semantics or the token stream model
  (v1 AD-4).
- **Catalog and settings schema (AD-3/AD-16):** LEAF505 uses existing
  removal semantics and adds no schema field unless undo needs one; LEAF602
  adds chapter-pause and the per-book front-matter flag; LEAF603 adds the
  persisted order. Version bumps serialize through the collector.
- **Theme mirror (AD-10):** written by the settings write path (LEAF502
  owns the mirror; the settings screen from v1 keeps writing the catalog).
- **Manifest:** LEAF502 (icon, theme, splash), LEAF504 (intent filters,
  launch mode), LEAF506 (backup attributes). Three leaves touch the
  manifest in different elements; the collector resolves textual overlap.
- **Strings:** every leaf adds English strings; LEAF606 owns `values-es`
  and the missing-translation gate. Before LEAF606, lint must not fail on
  missing translations (no `values-es` exists yet).
- **Release pipeline:** LEAF509/LEAF607 own version bumps, release notes and
  publication through the existing `scripts/release.sh`; they change no
  application behaviour except the version.
- **CI (AD-13):** LEAF501 owns the workflow; every later leaf is gated by it.

## Risks and rabbit holes

- **Golden drift between macOS and the CI runner.** Bound: AD-13 — make the
  runner authoritative for goldens or pin a matching image; do not raise
  tolerances silently.
- **Archive access that cannot seek.** Some content providers hand back
  non-seekable streams; the directory-based read then needs a fallback.
  Bound: fallback streams without hashing; REQ-110 is measured on the
  reference device with a local file, and the leaf documents which sources
  fall back.
- **Intent-filter coverage.** Apps send EPUBs as `application/epub+zip`,
  `application/octet-stream` or `*/*` with an `.epub` name. Bound: MIME
  filters plus a name-pattern fallback; acceptance is the Files app and one
  share source; do not chase every third-party app.
- **Persistable grants are rare from `ACTION_VIEW`/`ACTION_SEND`.** The
  session-only path is the common case, not the exception; the notice copy
  and "Add to library" must be first-class, not an error state.
- **Explicit Dark on a light device before Compose.** AD-10's mirror must be
  applied before `super.onCreate` sets the window; verify with the 60 fps
  recording, not by inspection.
- **Front-matter detection.** Bound: use navigation landmarks / package
  guide when present, else a small front-matter title heuristic; when
  unsure, make no offer. Never build a general document classifier.
- **Tablet/landscape scope.** Bound: two layout variants (reader controls
  beside the stream; library using width), verified by goldens on the
  `sw600dp` boundary AVD and a landscape phone; no adaptive-everything
  redesign.
- **Toolchain refresh breakage** (AGP/Kotlin/Compose BOM/Robolectric/
  Roborazzi majors). Bound: current stable only; a library that cannot move
  without breaking tests is pinned with the reason recorded; goldens are
  re-recorded and inspected in the same change.
- **REQ-110 measurement noise.** Bound: a written protocol (same AVD, cold
  process, N runs, median) recorded once on the published v1.0.1 before the
  change and once on v1.1.0; evidence committed.
- **Crash handler swallowing crashes.** Bound: write-then-rethrow; the
  induced-crash acceptance proves the process still dies and the offer
  appears once.
- **Sample content.** Bound: short, unambiguous public-domain passages with
  the source named in the UI; no sample library, no downloads.
- **Backup exclusion verification.** Reading the manifest is not proof;
  use the emulator's backup tooling to show no FastReader data is captured.

## Migration, rollout, recovery, and rollback

On-device app with no server. Rollout is merging each leaf to `main`
(gated by CI from LEAF501 on), collector integration per increment, then a
signed GitHub Release per increment. Every leaf leaves `main` building,
tested and the app usable. Recovery is `git revert` of the offending
merge. Data: schema forward-only under AD-3/AD-16; each new field defaults
to v1-equivalent behaviour; the published-to-published update proof
(AD-12) is the gate before an increment closes. Android does not downgrade
in place, so rolling back an installed release means reinstalling the older
APK; migrations must therefore never drop data. Signing material stays
machine-local and uncommitted; the certificate pin in `scripts/release.sh`
is unchanged. Backup exclusion (AD-11) means a reinstall starts empty by
design (D3), which the privacy copy states.

## Issue publication manifest

| Key | Kind | Parent | Repository | Title | Delivery | Blocked by | Issue |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ROOT | TRACKING | None | cedagova/fastReader | Consumer-ready FastReader: v1.1.0 shippable to strangers, then v1.2.0 reading depth | INCREMENTAL | None | https://github.com/cedagova/fastReader/issues/36 |
| INC001 | GROUP | ROOT | cedagova/fastReader | v1.1.0: shippable to strangers | COLLECTOR | None | https://github.com/cedagova/fastReader/issues/38 |
| INC002 | GROUP | ROOT | cedagova/fastReader | v1.2.0: reading-experience depth | COLLECTOR | INC001 | https://github.com/cedagova/fastReader/issues/39 |
| LEAF501 | LEAF | INC001 | cedagova/fastReader | Repository shell: hosted checks, MIT license, v1 documents on main | None | None | Pending |
| LEAF502 | LEAF | INC001 | cedagova/fastReader | Launcher icon and theme-correct first frame | None | None | Pending |
| LEAF503 | LEAF | INC001 | cedagova/fastReader | Reader open path: identity as input, no whole-file read | None | None | Pending |
| LEAF504 | LEAF | INC001 | cedagova/fastReader | Open with and share-sheet entry for EPUB files | None | LEAF503 | Pending |
| LEAF505 | LEAF | INC001 | cedagova/fastReader | Folder list, folder removal and undo for removed books | None | None | Pending |
| LEAF506 | LEAF | INC001 | cedagova/fastReader | Version, check for updates, backup exclusion and privacy statement | None | None | Pending |
| LEAF507 | LEAF | INC001 | cedagova/fastReader | Focused-mode speed gesture | None | None | Pending |
| LEAF508 | LEAF | INC001 | cedagova/fastReader | Bundled English and Spanish sample | None | LEAF503 | Pending |
| LEAF509 | LEAF | INC001 | cedagova/fastReader | README, cue-set check, v1.1.0 release and update proof | None | LEAF501, LEAF502, LEAF504, LEAF505, LEAF506, LEAF507, LEAF508 | Pending |
| LEAF601 | LEAF | INC002 | cedagova/fastReader | Toolchain and library refresh with goldens re-recorded | None | None | Pending |
| LEAF602 | LEAF | INC002 | cedagova/fastReader | Chapter control: pause setting and front-matter skip | None | LEAF601 | Pending |
| LEAF603 | LEAF | INC002 | cedagova/fastReader | Library order and return-to-app rescan | None | LEAF601 | Pending |
| LEAF604 | LEAF | INC002 | cedagova/fastReader | Tablet and landscape layouts | None | LEAF601 | Pending |
| LEAF605 | LEAF | INC002 | cedagova/fastReader | Crash report offered on next launch | None | LEAF601 | Pending |
| LEAF606 | LEAF | INC002 | cedagova/fastReader | Spanish interface | None | LEAF602, LEAF603, LEAF604, LEAF605 | Pending |
| LEAF607 | LEAF | INC002 | cedagova/fastReader | v1.2.0 release and update proof | None | LEAF606 | Pending |

### Planned leaf contracts (summaries; full contracts go to the issues)

- **LEAF501 — Repository shell.** GitHub Actions workflow on push and pull
  request running unit tests, `verifyRoborazziDebug` and lint, red on any
  failure (AD-13, golden reproducibility on the runner included); `LICENSE`
  with MIT (D2) so the repository page shows it; the v1 definition
  (`docs/product-definitions/cedagova-fastReader-1/`) and plan
  (`docs/plans/cedagova-fastReader-1/`) placed on `main` from their
  reviewed heads (`dde30d5`, `de442b6`), unchanged in content. Owns
  REQ-111's license half and the CI/docs delivery constraints. Validation:
  a deliberately broken golden on a scratch branch turns the check red; the
  repository API reports MIT.
- **LEAF502 — Launcher icon and theme-correct first frame.** Adaptive icon
  with foreground, background and monochrome layers referenced from the
  manifest; launch theme/window background and splash resolved from the
  pre-Compose theme mirror (AD-10) with `values-night` for "System"; the
  mirror kept in sync by the settings write path with the same default as
  `ReaderSettings.theme`. Owns REQ-101, REQ-102. Validation: launcher,
  recents and cold-start splash screenshots on the reference AVD; 60 fps
  launch recordings with Dark selected and with System on a dark device
  showing no light frame.
- **LEAF503 — Reader open path.** The reader's open contract takes (bytes
  source, identity, origin) (AD-8/AD-9); the content pipeline no longer
  hashes on open and reads only container, package, navigation and spine
  entries via the archive directory, with a documented streaming fallback
  for non-seekable sources; library books pass their catalog id. No change
  to token stream, positions or catalog schema. Owns REQ-110. Validation:
  the REQ-110 protocol — v1.0.1 recordings (published APK) for the owner's
  largest illustrated EPUB (≥50 MB, owner-supplied test input) and its
  image-stripped copy, then the same on this build: illustrated within 25%
  of stripped, neither slower than its v1.0.1 recording; unit tests prove
  image entries are never read.
- **LEAF504 — Open with and share-sheet entry.** `ACTION_VIEW` and
  `ACTION_SEND` filters for EPUB (MIME plus name fallback); new-intent
  handling in the running activity; keepable grant → added like a pick and
  opened; session-only → opened via the external origin with the one-line
  dismissible notice ("opened from another app; only the reading position
  will be remembered") and "Add to library" launching the picker; position
  keyed by identity so a later add resumes; non-EPUB/DRM → existing
  explanation, nothing added; closing returns to the library without that
  book. TalkBack labels and 48 dp targets on the notice (REQ-301). Owns
  REQ-103 and the session-only clause of REQ-107's copy. Validation:
  emulator flows from the Files app and from a session-only share source,
  including the resume-after-add check.
- **LEAF505 — Folder list, removal and undo.** Library surface listing added
  folders with status and a remove action whose confirmation names the
  count of books that folder alone provides; removal releases the grant,
  drops only those rows, touches no file, keeps positions; book removal
  becomes undoable for a short window with copy saying file and position
  are kept, undo restoring the row with its progress. Owns REQ-104,
  REQ-105, REQ-301 for these controls. Validation: repository unit tests for
  count and removal semantics (three books, one also picked → "two");
  Roborazzi renders for folder list, confirmation and undo; emulator undo
  flow.
- **LEAF506 — Version, updates, backup, privacy.** Settings rows for the
  installed version (from the build) and "Check for updates" opening the
  releases page in the browser (copy says it opens the browser); manifest
  backup exclusion for all app data (AD-11); in-app privacy statement
  covering what leaves the device (browser hand-off; later, reader-initiated
  crash share), that nothing stored is backed up, and that a session-only
  open keeps only the position; release-notes text for the same statement
  handed to LEAF509. No network permission. Owns REQ-106, REQ-107 (with
  LEAF504's clause), REQ-303 copy. Validation: version equals
  `version.properties`; emulator backup run after adding books captures no
  FastReader data; release script's no-INTERNET check; statement sentences
  mapped to declarations/behaviours in the PR.
- **LEAF507 — Focused-mode speed gesture.** One gesture on the reading
  surface changes speed in 25 WPM steps within the v1 range while playback
  continues in focused mode; a text-only, static readout appears and is gone
  within two seconds; tap-to-pause and long-press unchanged; no luminance
  change in the stream (REQ-302); the new speed persists like any speed
  change. Owns REQ-108. Validation: Roborazzi render of the readout;
  emulator gesture flow at max speed confirming no stutter and both
  existing gestures.
- **LEAF508 — Bundled sample.** English and Spanish public-domain passages as
  bundled assets with source named in the UI; "Try a sample" in the empty
  library (Spanish first on a Spanish device) reaching a playing stream in
  two taps through the sample origin (AD-9); reachable from Settings once
  books exist and absent from the library then; nothing written outside
  private storage; no library row, no persisted grant. Owns REQ-109,
  REQ-302 for the sample. Validation: fresh-install emulator flow with a
  storage listing before/after; Roborazzi for the empty-library offer and
  the Settings entry; Spanish-locale ordering check.
- **LEAF509 — README, cue-set check, v1.1.0 release and update proof.**
  README with screenshots of this build, install steps, the privacy
  statement and the by-link personal-use statement linking #33; REQ-112
  check that "Fixed focus letter" is offered off by default exactly as in
  v1.0.1 and stays on for a reader who enabled it; `version.properties` to
  versionCode 3 / 1.1.0; release notes carrying the privacy statement;
  publish with `scripts/release.sh --publish`; AD-12 update proof: published
  v1.0.1 with two books, a mid-book position, a non-default colour and Fixed
  focus letter on → install published v1.1.0 in place → same books,
  position, colour and toggle; evidence under `docs/evidence/`. Owns
  REQ-111 (README half), REQ-112, REQ-113 and the release-level REQ-106
  (version equals tag) and REQ-303 proofs. Validation: the release script's
  own gates plus the recorded update flow.
- **LEAF601 — Toolchain and library refresh.** AGP, Gradle wrapper, Kotlin,
  Compose BOM, AndroidX, Robolectric, Roborazzi, coroutines, serialization
  to current stable (or pinned with a recorded reason); `compileSdk`/
  `targetSdk` current; goldens re-recorded and inspected in the same change;
  CI green on the new toolchain; repo guide updated. Owns the owner's
  toolchain constraint. Validation: full unit/golden/lint run locally and in
  CI; a debug install on the reference AVD.
- **LEAF602 — Chapter control.** "Pause at chapters" setting (default on,
  schema field with forward migration, AD-16) controlling the session's
  chapter hold (REQ-201, D4); a one-time "skip front matter" offer on the
  first open of a book that has front matter, landing on the first
  chapter's first word and never offered again for that book (persisted
  per-book flag), with bounded detection (nav landmarks/guide, then a small
  title heuristic, else no offer) (REQ-202). REQ-301 on the new controls.
  Validation: session unit tests for both settings states; pipeline tests
  on a fixture with cover/title/copyright pages; Roborazzi for the setting
  row and the offer; migration test that a v1.1.0 document reads pause on.
- **LEAF603 — Library order and rescan.** Order control (title / recently
  read / recently added), default recently read, persisted (schema field,
  AD-16), computed from existing `updatedAtEpochMs` and `addedAtEpochMs`;
  the return-to-app rescan interval raised to the definition's "short
  interval" so an app-switch-and-return shows no scanning banner while
  manual refresh always rescans (REQ-203, REQ-204). Validation: state-
  builder unit tests for the three orders; repository test for the
  interval; emulator app-switch flow and manual refresh finding a newly
  copied EPUB.
- **LEAF604 — Tablet and landscape layouts.** Reader layout with controls
  beside the stream and a library that uses the width at tablet width and in
  landscape; no clipped text at the largest font size; REQ-301 preserved.
  Owns REQ-205. Validation: new goldens for `sw600dp` and landscape at the
  largest font size; emulator check on `Tablet_Low_API33` and a rotated
  phone.
- **LEAF605 — Crash report.** In-process uncaught-exception capture writing
  a plain-text report (version, device model, Android version, stack trace;
  no book text, file names or paths) to private storage and re-throwing;
  next launch offers once to share via the system share sheet; decline
  deletes; no repeat for the same crash (REQ-207, REQ-303). Validation:
  debug-only induced crash on the emulator showing offer-once, decline-
  discard, and a shared text with the four fields and no book words.
- **LEAF606 — Spanish interface.** Complete `values-es` covering every
  string including content descriptions and the visual-only statement;
  lint's missing-translation check enforced in CI so future strings cannot
  ship untranslated (AD-15); TalkBack announces Spanish labels (REQ-206,
  D5). Validation: Spanish-locale goldens for library, reader, settings,
  folder list, notices; lint gate; emulator TalkBack spot check.
- **LEAF607 — v1.2.0 release and update proof.** `version.properties` to
  versionCode 4 / 1.2.0; release notes and README updated (Spanish, new
  settings); publish; AD-12 proof: published v1.1.0 with books, a position
  and non-default settings → install published v1.2.0 in place → same
  books, position and settings, "Pause at chapters" on, library ordered by
  recently read (REQ-208). Validation: release script gates plus the
  recorded flow under `docs/evidence/`.

## Acceptance coverage

| Requirement | Leaves |
| --- | --- |
| REQ-101 icon; REQ-102 first frame | LEAF502 |
| REQ-103 Open with / share, session-only path | LEAF504 (on LEAF503's open contract) |
| REQ-104 folder list and removal; REQ-105 undo | LEAF505 |
| REQ-106 version and check for updates | LEAF506 (rows), LEAF509 (version equals tag, no-network proof on the artifact) |
| REQ-107 backup exclusion and privacy statement | LEAF506 (manifest, in-app copy, release-notes text), LEAF504 (session-only clause), LEAF509 (release notes) |
| REQ-108 focused-mode speed gesture | LEAF507 |
| REQ-109 bundled sample | LEAF508 (on LEAF503's open contract) |
| REQ-110 open time independent of image payload | LEAF503 |
| REQ-111 MIT license and README | LEAF501 (license), LEAF509 (README) |
| REQ-112 v1.0.1 cue set unchanged | LEAF509 (check), constraint on every 001 leaf |
| REQ-113 update over published v1.0.1 | LEAF509 |
| REQ-201 chapter-pause setting; REQ-202 skip front matter | LEAF602 |
| REQ-203 library order; REQ-204 cheaper rescans | LEAF603 |
| REQ-205 tablet and landscape | LEAF604 |
| REQ-206 Spanish interface | LEAF606 |
| REQ-207 crash report | LEAF605 |
| REQ-208 update over published v1.1.0 | LEAF607 |
| REQ-301 accessibility on new controls | Every UI leaf (LEAF504, 505, 506, 507, 508, 602, 603, 604, 605, 606) |
| REQ-302 static luminance | LEAF507 (readout), LEAF508 (sample), constraint on LEAF503/LEAF604 |
| REQ-303 on-device only | LEAF506 (copy, no-network), LEAF505/LEAF504 (no data leaves), LEAF605 (share is reader-initiated), LEAF509/LEAF607 (artifact proof) |
| Delivery constraint: hosted checks | LEAF501 |
| Delivery constraint: v1 docs on `main` | LEAF501 |
| Delivery constraint: toolchain refresh with goldens (v1.2.0) | LEAF601 |

Root #36 acceptance: (1) both outcomes delivered → INC001, INC002 completion
rules; (2) one person outside the project reaches a playing stream unaided
from the release link → made possible by LEAF509 (published link, README
install steps) on top of LEAF502/LEAF504/LEAF508; the observation itself is
a human-performed success measure that no agent can execute, so it is
recorded by the owner on #38 after v1.1.0 is published and is part of the
tracking root's closure, not a gate on merging any leaf; (3) update in place
keeps everything → LEAF509 (v1.0.1→v1.1.0), LEAF607 (v1.1.0→v1.2.0);
(4) D1–D6 recorded with dates and the D1 residual risk → already satisfied
by the pinned definition (PR #37 `da4394c`, decision table); no leaf.

No orphan or overlapping outcome remains: the open path (LEAF503) is split
from its two consumers (LEAF504, LEAF508) at the open contract; housekeeping
(LEAF505) is split from Settings identity (LEAF506) at the screen boundary;
each release leaf owns only the version, notes, README and update proof.

## Validation and feedback

Per the repo's agent-first loop. From LEAF501 on, every PR is gated by
hosted unit tests, golden verification and lint. Pure-logic changes
(LEAF503 pipeline, LEAF505 removal semantics, LEAF602 session, LEAF603
ordering) gate on unit tests; UI leaves gate on `verifyRoborazziDebug`
plus targeted emulator flows from the AVD matrix (`Phone_Mid_API36`
baseline; `Tablet_Low_API33` for LEAF604; `Phone_Low_API33` for process-
death and cramped layouts where relevant); platform behaviours that
Roborazzi cannot render (launch first frame, intent entry, backup capture,
crash offer, update in place) are proven on the emulator with screenshots
or recordings committed under `docs/evidence/<issue>/`. REQ-110 follows a
written measurement protocol recorded twice (published v1.0.1, then
v1.1.0). Each increment's release leaf performs the published-to-published
update proof before the increment closes.

## Assumptions and open questions

None open. All material product, policy, privacy and persistence choices
were resolved by the owner in the pinned definition (D1–D6, distribution,
languages, cue set). Planner choices recorded here are ordinary and
reversible: identity computation moved rather than changed (AD-8), a theme
mirror rather than a synchronous catalog read before the first frame
(AD-10), whole-app backup exclusion (AD-11 implements D3), GitHub Actions
as the hosted check (AD-13; free for this public repository), and
`COLLECTOR` topology for both increments.

Owner-provided test inputs (not decisions): the largest owned illustrated
EPUB (≥50 MB) for REQ-110, made available to the implementation lead on the
reference emulator; the unaided stranger test after v1.1.0 is published.

## Satisfaction proof

Not applicable — every requirement names behaviour absent at the pinned
baseline; implementation work remains.

## Publication verification

Pending: content review on this candidate, then issue publication, graph
reconciliation and verification are recorded here.
