# Implementation Plan: RSVP fast reader for EPUB books

- Planning issue: https://github.com/cedagova/fastReader/issues/1
- Planning PR: https://github.com/cedagova/fastReader/pull/7
- Status: In progress
- Root classification: INCREMENTAL
- Delivery topology: INCREMENTAL
- Planner: Planning lead (Claude)
- Started: 2026-09-01
- Product definition: https://github.com/cedagova/fastReader/pull/2
- Product definition head: dde30d59a01b27cf3fc5a60ef3cbddc911b5a78a

## Pinned baselines

| Repository | Baseline |
| --- | --- |
| `cedagova/fastReader` | `90bfa63c79389fec9db9dd7b5625d920c098505c` |

## Preserved objective and boundaries

The approved objective is the product definition at the pinned head
(docs/product-definitions/cedagova-fastReader-1/definition.md, PR #2 at
`dde30d5`): an Android RSVP speed reader for the user's own DRM-free EPUBs —
minimal library in, research-backed one-word-at-a-time reading, bounded cue
customization with a fully focused mode, and a versioned installable release
shareable with friends by link.

The definition's requirements (REQ-001–REQ-071), owner decisions, evidence
(research-rsvp.md), assumptions, non-goals (no Play Store, cloud sync,
annotations, page mode, TTS, stats, chunking, non-English UI), and success
measures are preserved unchanged. The four published product outcomes
(#3 Library, #4 Core RSVP, #5 Cues and focused mode, #6 Shareable release)
remain the native children of root #1 and keep their `Definition handoff:
PLANNING_REQUIRED` provenance. This plan adds system-level HOW and executable
leaves; it does not reinterpret the product contract.

## Classification

- **ROOT #1 — `INCREMENTAL`.** Each of the four native outcome children is a
  real delivery-sized effort with its own independent delivery boundary;
  flattening them into one project run would create nested efforts under a
  single coordinator. A strict sequence gives each effort a known-good merged
  baseline, which matters here because later outcomes are built directly on
  earlier ones (the reader consumes the library's catalog; cues restyle the
  reader; the release packages the finished data schema). Root #1 becomes a
  tracking-only root.
- **#3 Library — `GROUP` (ready), sequence 001.** Two leaves: a data/ingestion
  layer and the library screen. Foundation for everything else (book identity,
  catalog, file access).
- **#4 Core RSVP — `GROUP` (ready), sequence 002.** Four leaves: content
  pipeline, timing engine, reader screen/playback, persistence and launch
  resume. The product core.
- **#5 Cues and focused mode — `GROUP` (ready), sequence 003.** Two leaves:
  cue rendering plus focused mode, and settings with live preview.
- **#6 Shareable release — `GROUP` (ready), sequence 004.** One leaf: the
  signed, versioned release pipeline and update-preservation proof.

No increment is `DEFERRED`: the pinned definition fixes every contract these
plans need, and no later increment's leaf boundaries depend on evidence that
only an earlier delivery can produce. All four are planned now as ready
groups.

## Current-state evidence

At `90bfa63` the repository is a scaffold only: Kotlin 2.1.21 + Compose
(Material 3), single `:app` module, package `com.cedagova.fastreader`, minSdk
26, `MainActivity` with placeholder UI, Roborazzi screenshot tests and
goldens, agent-first verification loop documented in
`docs/agent-first-development.md`. No product feature, persistence, or EPUB
code exists, so there is nothing to reconcile against and no
`ALREADY_SATISFIED` question. The repository is currently **private**
(verified via the GitHub API 2026-09-01), which intersects with the
release-by-link outcome — see the owner decision brief below.

## Selected implementation direction

All work stays in `cedagova/fastReader`, single `:app` module, with feature
code in separate packages (`epub`, `reader`, `library`, `settings`) so
concerns stay extractable. System-level shape:

1. **Books are read in place** through Storage Access Framework document and
   tree picks with persisted URI permissions — never copied. A catalog store
   records books, added folders, per-book reading position, progress, and
   settings, all on device.
2. **Stable book identity** is derived from book content/metadata, not from
   the URI, so a removed-and-re-added or moved book keeps its position
   (REQ-004/REQ-005). The persisted schema is treated as a versioned,
   migratable contract from the first release onward, because REQ-040
   (update in place preserves everything) depends on it.
3. **EPUB processing is a bounded pipeline**: container/OPF/spine parsing,
   metadata + cover extraction, DRM and corruption detection that rejects
   with a plain-language reason (never crashes, never unlocks), and
   spine-ordered text extraction into a token stream model — words annotated
   with sentence/clause/paragraph/heading boundaries, image/table skip
   markers, dropped footnote markers, chapter boundaries with titles, and
   stable word-index position addressing. Spanish and English punctuation
   (accents, ¿¡, dialogue dashes) is part of this model's contract.
4. **Timing is a pure, deterministic engine**: per-word display time from WPM
   plus the research-default multipliers, scaled by the single pause-strength
   setting, with ramp-up and re-orientation hold. Unit-testable with no
   Android dependencies.
5. **Presentation is an instantaneous text swap on a static background** —
   no animation, fades, or luminance change in the stream (REQ-062). The
   reader screen owns playback state, tap-to-pause context view, navigation,
   chapter pauses, progress/time remaining, screen-awake, and
   foreground-loss auto-pause.
6. **Persistence is continuous**: position/speed/settings survive pause, app
   switch, process death, and restart; app launch routes to the last-read
   book's paused view or to the library with the reason visible (REQ-009).
7. **Cues are a rendering layer over the same reader**: pivot alignment,
   pivot color, guide marks (visually distinct from the patented Spritz
   redicle), overflow shrink-to-fit; focused mode hides chrome without
   changing playback. Settings apply immediately with live preview.
8. **Release is a repeatable pipeline**: machine-local signing, monotonic
   versioning, signed APK attached to a GitHub Release, and an explicit
   install-N-then-N+1 data-preservation check.

Concrete library/technology picks inside these boundaries (parser
implementation, DataStore vs Room, exact gesture mapping) are reversible
implementation-lead choices, constrained by the invariants above.

## Architecture decisions

- **AD-1 — In-place SAF access, no import copies** (owner decision in the
  definition). Consequence: permission loss and missing files are first-class
  library states, and folder rescan happens on app open plus manual refresh.
- **AD-2 — Content-derived book identity** decoupled from URIs; positions
  keyed by that identity. Required by REQ-004/REQ-005; makes dedup (same file
  reachable twice) natural.
- **AD-3 — Versioned on-device schema from increment 001.** Every later
  increment (and every post-release update) must migrate, never wipe.
  REQ-040's acceptance depends on discipline starting at the first persisted
  byte.
- **AD-4 — Token stream model as the reader's contract.** The EPUB pipeline
  produces it; the timing engine and reader consume it; position addressing
  and progress math are defined on it. This is the one internal interface
  worth pinning at planning level.
- **AD-5 — Pure-logic timing engine** separated from UI so research-default
  modulation (REQ-011) and ramp/re-orientation behavior (REQ-013) are proven
  by unit tests, not by eyeballing.
- **AD-6 — Static-luminance presentation** (REQ-062) is an architectural
  constraint on the stream renderer, not a feature: text swaps only.
- **AD-7 — Guide marks are an original design** distinct from Spritz US
  8,903,174 trade dress (definition constraint; design freedom inside
  REQ-021).

## Execution graph and waves

Strict increment sequence (each blocked by its predecessor; no parallel
frontier):

- **001 Library (#3)** — wave 1: LEAF101 catalog/ingestion; wave 2: LEAF102
  library screen (blocked by LEAF101). Topology `COLLECTOR`.
- **002 Core RSVP (#4)** — wave 1: LEAF201 content pipeline; wave 2: LEAF202
  timing engine (blocked by LEAF201, shares the token model); wave 3:
  LEAF203 reader screen and playback (blocked by LEAF201, LEAF202); wave 4:
  LEAF204 persistence and launch resume (blocked by LEAF203). Topology
  `COLLECTOR`.
- **003 Cues and focused mode (#5)** — wave 1: LEAF301 cue rendering and
  focused mode; wave 2: LEAF302 settings with live preview (blocked by
  LEAF301). Topology `COLLECTOR`.
- **004 Shareable release (#6)** — single wave: LEAF401 signed versioned
  release pipeline. Topology `DIRECT`.

Leaves depend only on siblings inside their own increment; cross-increment
ordering lives on the increment issues (#4 blocked by #3, #5 by #4, #6 by
#5).

## Interfaces and ownership

Everything is owned by `cedagova/fastReader`; there are no cross-repository
contracts. Internal ownership boundaries the leaves must respect:

- **Token stream model + position addressing** (AD-4): produced by LEAF201,
  consumed by LEAF202/LEAF203/LEAF204 and by progress math. Changing it later
  is a schema migration concern (AD-3).
- **Catalog and settings store**: produced by LEAF101, extended (positions,
  speed, cue settings) by LEAF204 and LEAF302 under the AD-3 migration rule.
- **Reader rendering surface**: LEAF203 owns playback and layout; LEAF301
  restyles the word presentation and chrome visibility without changing
  playback semantics.
- **Release artifact**: LEAF401 owns signing, versioning, and publishing; it
  changes no application behavior.

## Risks and rabbit holes

- **Real-world EPUB variability** (malformed zips, weird OPFs, encodings).
  Bound: reject unreadable books with a plain-language state; never build a
  general rendering engine — text extraction only. Definition's pitfalls
  research (research-rsvp.md) applies.
- **Word/sentence segmentation depth.** Bound: rule-based segmentation for
  Spanish/English punctuation per the definition; "rare word" detection may
  be a simple heuristic (length/frequency); no NLP rabbit hole.
- **Timer smoothness at 1000 WPM** (~60 ms/word). Risk of jank from naive
  delays; the guardrail (no visible stutter at max speed on the reference
  device) must be validated on an emulator/device in increment 002, with
  frame-aligned scheduling if needed.
- **SAF folder rescan cost** on large trees. Bound: scan for `.epub` only,
  off the main thread, with the loading state from the definition.
- **Schema drift breaking updates.** Mitigated by AD-3 plus LEAF401's
  explicit N→N+1 preservation check.
- **Spritz patent trade dress** in guide marks. Mitigated by AD-7; reviewer
  checks the delivered design.
- **Process-death and foreground-loss edge cases** (REQ-016/REQ-071).
  Must be exercised with the repo's emulator loop (force-stop, app switch),
  not assumed from code reading.

## Migration, rollout, recovery, and rollback

Single-user, on-device app with no server: rollout is merging to `main`
per leaf and, from increment 004, publishing versioned releases. Every
increment leaves `main` building, tested, and the app usable at its level.
Recovery/rollback is `git revert` of the offending merge; there is no remote
state to unwind. Data: schema versioned from 001 (AD-3); updates migrate
forward; Android does not support in-place downgrades, so rollback of an
installed release means reinstall of the older APK (data loss acceptable
only in the pre-release period; after the first shared release, migrations
are forward-only and preservation is REQ-040-gated). Signing material stays
machine-local and uncommitted per repo policy.

## Issue publication manifest

| Key | Kind | Parent | Repository | Title | Delivery | Blocked by | Issue |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ROOT | TRACKING | None | cedagova/fastReader | RSVP fast reader for EPUB books | INCREMENTAL | None | https://github.com/cedagova/fastReader/issues/1 |
| INC001 | GROUP | ROOT | cedagova/fastReader | Library: pick, add-folder, search, progress | COLLECTOR | None | https://github.com/cedagova/fastReader/issues/3 |
| INC002 | GROUP | ROOT | cedagova/fastReader | Core RSVP reading experience | COLLECTOR | INC001 | https://github.com/cedagova/fastReader/issues/4 |
| INC003 | GROUP | ROOT | cedagova/fastReader | Cue customization and focused mode | COLLECTOR | INC002 | https://github.com/cedagova/fastReader/issues/5 |
| INC004 | GROUP | ROOT | cedagova/fastReader | Shareable installable release | DIRECT | INC003 | https://github.com/cedagova/fastReader/issues/6 |
| LEAF101 | LEAF | INC001 | cedagova/fastReader | Book catalog and EPUB ingestion | None | None | Pending |
| LEAF102 | LEAF | INC001 | cedagova/fastReader | Library screen: list, search, states | None | LEAF101 | Pending |
| LEAF201 | LEAF | INC002 | cedagova/fastReader | EPUB content pipeline and token stream model | None | None | Pending |
| LEAF202 | LEAF | INC002 | cedagova/fastReader | RSVP timing engine | None | LEAF201 | Pending |
| LEAF203 | LEAF | INC002 | cedagova/fastReader | Reader screen and playback | None | LEAF201, LEAF202 | Pending |
| LEAF204 | LEAF | INC002 | cedagova/fastReader | Continuous persistence and launch resume | None | LEAF203 | Pending |
| LEAF301 | LEAF | INC003 | cedagova/fastReader | Cue rendering and focused mode | None | None | Pending |
| LEAF302 | LEAF | INC003 | cedagova/fastReader | Settings with live preview | None | LEAF301 | Pending |
| LEAF401 | LEAF | INC004 | cedagova/fastReader | Signed versioned release pipeline | None | None | Pending |

### Planned leaf contracts (summaries; full contracts go to the issues)

- **LEAF101 — Book catalog and EPUB ingestion.** SAF single-file and folder
  picks with persisted permissions; recursive `.epub` discovery; rescan on
  app open plus a manual-refresh entry point; EPUB metadata/cover
  extraction; DRM and corruption detection with reject reasons; dedup via
  content-derived identity (AD-2); versioned catalog store (AD-3) with a
  position/progress slot per book. Verified by unit tests over the store and
  parser plus instrumented ingestion checks. Owns the data halves of
  REQ-001–REQ-005.
- **LEAF102 — Library screen.** Empty-state guidance, book list
  (title/author/cover/placeholder, % read), live search over
  title/author/filename, distinct plain-language states (corrupt, DRM,
  missing, permission-lost) with re-grant/remove paths, loading state,
  manual refresh, TalkBack labels and touch-target/font-scale compliance
  for this surface (REQ-060 library). Verified by Roborazzi renders of each
  state plus an emulator pass with TalkBack. Owns the UI halves of
  REQ-001–REQ-005.
- **LEAF201 — EPUB content pipeline and token stream model.** Spine-ordered
  text extraction into the AD-4 token stream: word tokens; sentence, clause,
  paragraph, heading and chapter boundaries with titles from the TOC;
  image/table skip markers; footnote markers dropped; front/back matter in
  book order; long/number/rare-word and abbreviation classification needed
  by timing; stable word-index positions; totals for progress and
  time-remaining math. Correct Spanish/English handling (UTF-8, accents,
  ¿¡, dialogue dashes as clause punctuation). Pure logic, unit-tested
  against fixture EPUBs including malformed ones. Owns the content side of
  REQ-015/REQ-017/REQ-019.
- **LEAF202 — RSVP timing engine.** Per-word durations from WPM (default
  250, range 100–1000) with research-default multipliers (sentence ~3.0×,
  clause ~2.0×, paragraph ~3.5×, heading ≥4×, long/number/rare ~1.5×,
  abbreviations exempt) scaled by pause strength (off/subtle/normal/strong);
  ramp-up from ~80% over ~15–30 s; re-orientation hold (~3× first word)
  after any resume/jump; deterministic and unit-tested. Owns the timing side
  of REQ-011/REQ-012/REQ-013.
- **LEAF203 — Reader screen and playback.** Paused context view (surrounding
  paragraph, current word highlighted), play/pause with tap-to-pause,
  mid-stream speed control with the non-blocking >450 WPM hint, sentence and
  paragraph back/forward, chapter picker, progress scrub, chapter-boundary
  auto-pause with title, skip-marker display, progress % and time remaining,
  end-of-book state, screen-awake during playback, foreground-loss
  auto-pause at the current word, static-luminance instantaneous text swaps
  (REQ-062 base), TalkBack for reader controls (REQ-060 reader surface).
  Verified by Roborazzi for stable states plus emulator flows (timeout,
  app-switch). Owns REQ-010–REQ-015, REQ-017, REQ-018, REQ-070, REQ-071
  observable behavior.
- **LEAF204 — Continuous persistence and launch resume.** Continuous
  position/speed/settings persistence across pause, app switch, process
  death, and restart; launch routing into the last-read book's paused view
  or the library with the reason visible (REQ-009); library progress %
  live end to end; end-to-end re-verification of position retention on
  remove/re-add and missing/restored books (REQ-004/REQ-005 acceptance);
  ≤2 interactions and <3 s launch-to-streaming on the reference emulator.
  Verified by emulator process-death/force-stop flows. Owns REQ-009,
  REQ-016, and the persistence sides of REQ-010/REQ-018.
- **LEAF301 — Cue rendering and focused mode.** Pivot-letter alignment with
  colored pivot (on by default, toggleable), original-design guide marks
  (AD-7), overflow shrink-to-fit, focused mode: one gesture hides all
  chrome including progress and hint, one gesture restores, tap-to-pause
  still works; stream stays static-luminance with cues enabled (REQ-062).
  Verified by Roborazzi cue-state matrix plus emulator gesture pass. Owns
  REQ-020/REQ-021/REQ-030 rendering and the cue side of REQ-062.
- **LEAF302 — Settings with live preview.** Settings surface for pivot cue
  on/off and color palette, guide marks, font size, light/dark/system
  theme (applies across reader and library), pause strength; live preview
  of cue/timing changes; reset-to-defaults; the plain visual-only statement
  (REQ-061); TalkBack for settings (REQ-060 settings surface); settings
  persisted under AD-3. Owns REQ-020 toggling, REQ-022, REQ-023, REQ-061.
- **LEAF401 — Signed versioned release pipeline.** Machine-local signing
  config (never committed), monotonic versionCode/versionName scheme,
  repeatable release build producing a signed APK attached to a versioned
  GitHub Release obtainable by link (per the owner decision on
  distribution), minSdk 26 install check, explicit N→N+1 update check
  proving library/positions/settings survive (REQ-040), and verification
  that the release build requests no network access for reading data
  (REQ-050). Owns REQ-040 and the release-level REQ-050 proof.

## Acceptance coverage

Every definition requirement maps to at least one leaf:

| Requirement | Leaves |
| --- | --- |
| REQ-001–REQ-003 (add, folders, search) | LEAF101 (data), LEAF102 (UI) |
| REQ-004–REQ-005 (progress, removal, missing states) | LEAF101, LEAF102; end-to-end re-proof in LEAF204 |
| REQ-009 (launch resume) | LEAF204 |
| REQ-010 (paused context view) | LEAF203, persistence via LEAF204 |
| REQ-011–REQ-013 (modulation, speed, ramp) | LEAF202 (engine), LEAF203 (observable) |
| REQ-014 (pause/navigate) | LEAF203 |
| REQ-015 (chapters, skip markers) | LEAF201 (model), LEAF203 (display) |
| REQ-016 (continuous persistence) | LEAF204 |
| REQ-017 (progress, time remaining) | LEAF201 (math), LEAF203 (display) |
| REQ-018 (end state) | LEAF203, LEAF204 (library 100%) |
| REQ-019 (Spanish/English) | LEAF201 |
| REQ-020–REQ-021 (pivot, guide marks) | LEAF301, toggles in LEAF302 |
| REQ-022–REQ-023 (font/theme, preview/reset) | LEAF302 |
| REQ-030 (focused mode) | LEAF301 |
| REQ-040 (versioned updatable release) | LEAF401 |
| REQ-050 (on-device only) | Constraint on every leaf; release-level proof in LEAF401 |
| REQ-060 (TalkBack surfaces) | LEAF102 (library), LEAF203 (reader), LEAF302 (settings) |
| REQ-061 (visual-only statement) | LEAF302 |
| REQ-062 (static luminance) | LEAF203 (base), LEAF301 (with cues) |
| REQ-070–REQ-071 (screen awake, foreground loss) | LEAF203, persistence via LEAF204 |

Root #1 acceptance: the four outcome checkboxes map to INC001–INC004; the
two-interaction/<3 s launch measure to LEAF204; Spanish/English end-to-end to
LEAF201 plus increment 002's completion rule; friend-installs-by-link to
LEAF401 (subject to the visibility decision below). No orphan or overlapping
outcome remains: library data/UI split at the store boundary, reader
timing/UI split at the engine boundary, cue rendering/settings split at the
preview boundary.

## Validation and feedback

Per the repo's agent-first loop: pure-logic leaves (LEAF201, LEAF202) gate on
unit tests; UI leaves (LEAF102, LEAF203, LEAF301, LEAF302) gate on
`verifyRoborazziDebug` plus targeted emulator flows from the AVD matrix;
lifecycle leaves (LEAF204) gate on emulator process-death/force-stop/
app-switch flows; LEAF401 gates on real install/update on an emulator and a
link-download check. Each increment's completion rule additionally
demonstrates its group acceptance on the reference emulator before the
increment closes. The max-speed smoothness guardrail is checked in increment
002 and re-checked with cues in 003.

## Assumptions and open questions

**Owner decision brief — release link vs. private repository (blocks
increment 004 mechanics only).**

1. **Problem.** The approved outcome says friends obtain the release by
   link and install it (REQ-040; owner decision "shareable signed APK via
   GitHub Releases"). `cedagova/fastReader` is currently **private**, and
   release assets on a private repository are not reachable by a bare link —
   a friend would need a GitHub account with repository access. As is, the
   decided distribution channel cannot satisfy the decided outcome.
2. **Facts vs. assumptions.** Fact: repo visibility is PRIVATE (GitHub API,
   2026-09-01); private release assets require authenticated access; no
   secrets are committed (identity guards and gitignore already enforce
   this). Assumption: friends should not need GitHub accounts ("without
   store ceremony" rationale).
3. **Options.**
   - **A (recommended): make `cedagova/fastReader` public** before increment
     004. Release links then work for anyone. Benefit: simplest, exactly the
     decided channel. Cost/risk: source code becomes public (personal
     project, no secrets in-repo). Reversibility: can be flipped back to
     private at any time (existing links stop working). Execution: LEAF401
     unchanged.
   - **B: keep source private; add a public releases-only repository**
     (e.g. `cedagova/fastReader-releases`) that the release step uploads
     APKs to. Benefit: source stays private with working public links.
     Cost: a second repository and a cross-repo publish step to maintain.
     Reversibility: easy. Execution: LEAF401 gains the publish-to-second-
     repo step; delivery stays `DIRECT` in this repository.
   - **C: keep private and grant friends collaborator access.** Benefit: no
     public exposure. Cost: every friend needs a GitHub account and login to
     download — contradicts the "by link without ceremony" rationale. Not
     recommended.
4. **Recommendation.** Option A: this is a personal reader app with guarded
   identity/secrets hygiene already in place, and it keeps the release path
   trivial for a solo developer.
5. **Blocked.** Only LEAF401's acceptance mechanics ("friend can go from
   link to reading"); increments 001–003 are unaffected.
6. **To resume:** reply `Choose A`, `Choose B`, or `Choose C`.

No other open questions. All other material choices were resolved by the
owner in the pinned definition (distribution channel, languages, timing
package, single-word RSVP, in-place library, accessibility bar).

## Satisfaction proof

Not applicable — the repository contains no product features at the pinned
baseline; implementation work remains.

## Publication verification

PLANNING-TODO — recorded after independent content review, issue
publication, graph reconciliation, and verification.
