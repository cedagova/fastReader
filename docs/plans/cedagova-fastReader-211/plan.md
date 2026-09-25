# Implementation Plan: A197 outcomes: fastReader as the base for the Reader Android client

- Planning issue: https://github.com/cedagova/fastReader/issues/211
- Planning PR: https://github.com/cedagova/fastReader/pull/212
- Status: Review
- Root classification: EFFORT
- Delivery topology: COLLECTOR
- Planner: Planning lead (Claude)
- Started: 2026-09-25

## Pinned baselines

| Repository | Baseline |
| --- | --- |
| `cedagova/fastReader` | `7978c711e3f207ac6c727f6479cf61c87cdbdc63` |

The audit dossier (PR #197, approved head
`c3a9690276bf61f524eee80a606ec0cb94e120d7`) was produced against the same
baseline; every `path:line` in the child issues resolves against it.

## Preserved objective and boundaries

Root #211 is the A197 audit-outcome umbrella (`Audit handoff:
PLANNING_REQUIRED`, audit key `reader-android-base-readiness-20260924`). Its
approved outcome: leave fastReader as a base the future Chunipers Reader
Android client can reuse unchanged (`:reader-auth`, `:reader-library`, and any
reusable app parts) or copy patterns from, through maintainability,
modularity, extensibility and documentation changes only. The owner accepted
all thirteen findings on 2026-09-25 ("all accepted", including F013 against
the audit's defer recommendation). Their thirteen native sub-issues #198–#210
are the canonical outcomes and remain native children of #211.

Boundaries carried from the audit and preserved here:

- No new product features and no user-facing behavior change (owner
  instruction 2026-09-24). Existing tests and Roborazzi goldens stay green
  unless a leaf below names and justifies a specific change.
- The Chunipers backend repositories and the future Reader client repository
  are out of scope; no Reader client repository exists yet.
- No fresh security review of auth/session handling beyond what a structural
  change exposes (A83, #167 and #168 covered it). Leaves that move auth or
  session code still preserve every existing security invariant.
- Owner decision P1 (hand-written models, contract-tested, not generated)
  stands.
- Root completion rule (unchanged): close #211 after every native outcome
  child is delivered, superseded with a durable link, or explicitly deferred by
  the decision owner.

## Classification

- **Root #211: `EFFORT`.** It already owns a native tree of thirteen
  accepted outcomes in one repository. Planning preserves every child, refines
  each to the executable leaf contract, and adds the dependency graph. It
  becomes a `GROUP` root with `Implementation delivery: COLLECTOR`.
- **#198–#210: `LEAF` each.** Every finding is one cohesive property of one
  area, one repository, normally one PR, independently verifiable by its own
  acceptance (the audit's cohesion rationale holds on inspection). None
  contains two independently releasable outcomes that would justify a split;
  F009's CI and release-script items share one "the shipped artifact is gated"
  acceptance and one small PR.
- **Why not `INCREMENTAL`.** Increments would have to become the logical
  parents of these audit outcomes. The audit contract requires the native
  audit-umbrella parent (#211) to be preserved, and a retained-outcome native
  parent may not be a planning-owned row (#211 is `ROOT`), so an increment
  layer cannot be expressed without re-parenting audit records. No child is
  itself a nested effort, and no later leaf's path depends on evidence an
  earlier leaf produces (the three material decisions are resolved below), so
  one bounded effort with an explicit dependency order is the smallest correct
  graph.
- **Why `COLLECTOR`.** All leaves are in `cedagova/fastReader` and several
  have real ordering edges (surface before versioning, conventions before new
  modules, library assembly before the composition root), so `DIRECT` is not
  allowed.

## Current-state evidence

Pinned `cedagova/fastReader@7978c711e3f207ac6c727f6479cf61c87cdbdc63`:

- Modules: `:app`, `:reader-auth`, `:reader-library` (`settings.gradle.kts`);
  libraries consumed as `project(...)` and depend on root catalog aliases; no
  `build-logic`, no convention plugin; SDK/JVM settings repeated in all three
  build files (F008 evidence).
- Library surface: no explicit-API mode or API dump; Supabase `UserSession`,
  kotlinx `JsonObject` and a Ktor engine appear in public signatures while
  declared `implementation` (F001 evidence).
- `:reader-library` has no keep rules of its own; `:reader-auth` ships
  `consumer-rules.pro` (F010 evidence).
- Engines: `epub/` 1,392 lines, `content/` 2,181 lines, `timing/` 442 lines,
  all Android-free; `epub/BookDigest.kt` imports `content`, while
  `content/TocReader.kt` and `content/EpubContentPipeline.kt` import `epub` —
  the cycle F004 names. Shared engine fixtures live in `app/src/sharedTest`.
- General account logic: 2,068 lines under `app/.../account/` (controller,
  state, copy store, references, downloads, imports, shelf, import state);
  `ReaderLibraryClient(auth.api)` built three times in
  `FastReaderApplication.kt`; four error-to-outcome mappings (F003 evidence).
- Screens: `LibraryScreen.kt` 1,814 lines, `ReaderScreen.kt` 1,538,
  `SettingsScreen.kt` 765; `TouchTarget = 48.dp` declared in six files; three
  drifted persistence-failure banners (the library copy drops
  `FontWeight.SemiBold`) (F006 evidence).
- CI (`.github/workflows/checks.yml`) runs unit tests, Roborazzi verify and
  lint only, on both `push` and `pull_request` without a concurrency group;
  `scripts/release.sh:112,119-121` fail open when `gh` fails;
  `scripts/test-release-publish.sh` is the existing release-script proof
  (F009 evidence).
- The repository is public, so any git-based consumption needs no credential.

## Selected implementation direction

System-level direction by outcome; exact files and code shape belong to each
leaf's implementation lead.

1. **Build foundation first (F008, F009).** One convention source (Gradle
   convention plugins in an included `build-logic` build is the expected
   shape) applies SDK levels, JVM target, lint and test settings to every
   module; one formatter plus static check joins the normal `check` task, with
   its one-time mass reformat isolated as a reformat-only commit inside the F008
   PR so review can separate it (squash delivery means no stable reformat SHA
   reaches `main`, so no `.git-blame-ignore-revs` entry is promised). CI gains an R8 step for the release variant that
   needs no signing key, `androidTest` compilation, one run per PR commit and
   SHA-pinned actions; `release.sh` fails loud on any failed state query and
   refuses a commit whose gates have not passed.
2. **Deliberate library surface (F001, F011, F013).** Explicit-API mode plus a
   committed API dump checked in CI for each library. Provider and transport
   types are wrapped behind library-owned types (planner decision below), so
   `implementation` dependencies stay invisible. One pinned reader-api contract
   document gates both libraries' shapes. `AccountSyncEngine` is split by
   responsibility with its public API and single-writer semantics unchanged.
3. **Source-copy consumption (F010, owner D1 = A).** The recorded mode is
   "copy the reusable module directories at a tagged library version." Every
   reusable module carries its own version and `CHANGELOG.md`, its own
   consumer keep rules, and belongs to a documented copy set (module
   directories plus whatever shared build logic and catalog entries they
   need). A scripted check copies that set into a throwaway standalone Gradle
   project and runs a minified build against it; CI runs it. New reusable
   modules created later in this effort (F002 fixtures if a module, F003,
   F004) join the copy set and the check.
4. **Library-owned seams and fixtures (F002).** Each library owns the
   interfaces a host substitutes (auth client, API client, downloads,
   publication transfer) and ships one reusable test-double and mock-engine
   surface that is part of the copy set; `:app` and both library suites use it.
5. **Reusable modules (F003 with D2 = A, F004 with D3 = A).** A new library
   module (working name `:reader-account`) takes the general account pipeline,
   the single error mapping, and one documented assembly call site, behind
   host seams for the device catalog and book identity. The engines move
   unchanged — `epub/`, `content/` and `timing/` — into Android-free Gradle
   module(s) with the cycle broken, their tests and `sharedTest` fixtures
   moved with them, and `ContentPipelineVersion` unchanged.
6. **Copyable app shell (F005, F007, F006).** One composition root that
   consumes the F003 assembly call; one written state-holder convention;
   a typed saveable destination model; a mechanically checked acyclic package
   graph. `LibraryRepository` splits settings, positions and book bytes behind
   narrow interfaces with `catalog.json` byte-compatible. Shared UI primitives
   and spacing/size tokens are defined once, screen files are split by
   section.
7. **Map last (F012).** A tracked architecture and "starting a new client"
   document written against the delivered structure, library READMEs matched
   to the code, historical evidence marked historical.

## Architecture decisions

- **AD-1 (owner D1 = A, 2026-09-25): source copy.** The Reader client copies
  the reusable modules at a tagged version; versions and change records make
  later fixes portable. No publication, registry, submodule or cross-repository
  build. Reversible: the self-contained copy set is also the prerequisite of an
  included build.
- **AD-2 (owner D2 = A, 2026-09-25): general account logic becomes a library
  module** consumed by FastReader, not a documented pattern in `:app`.
- **AD-3 (owner D3 = A, 2026-09-25): RSVP is reusable.** `timing/` moves with
  `epub/` and `content/`; the tokenizer is not split out of `content/`.
- **AD-4 (planner, reversible): wrap provider and transport types.** Library
  public signatures use library-owned types instead of Supabase `UserSession`,
  kotlinx `JsonObject` and Ktor engine types; those dependencies stay
  `implementation`. No external consumer exists, so the change is free now and
  keeps a provider change internal later. Any type deliberately left exposed is
  declared `api` with a written reason.
- **AD-5 (planner): conventions before modules.** F008 lands before any leaf
  that adds or reshapes a module so no new module copies build settings.
- **AD-6 (planner): F006 banner unification.** The shared banner uses the
  `FontWeight.SemiBold` rendering the reader and settings copies already use;
  only goldens that render the library persistence-failure banner may change,
  and the leaf names them in its PR. Every other golden stays byte-identical.
- **AD-7 (planner): F006 size bound.** No Kotlin file under
  `app/src/main/java/**/ui/` exceeds 600 lines after the split.
- **AD-8 (planner): F007 storage.** Settings keep living in `catalog.json`
  behind their own narrow type; no format change, no migration.
- **AD-9 (planner): F009 shrink settings.** Resource shrinking stays off; the
  shipped APK is unchanged. CI proves the existing R8 code-shrinking step.
- **AD-10: dependency-update automation is outside this effort.** It is in no
  A197 acceptance; the audit records it as a small owner preference to record,
  not assume. It stays an open owner preference, not decided here.
- **AD-11 (planner): F012 historical content is marked or moved, never
  deleted** (removal from `main` does not shrink history).

## Execution graph and waves

All leaves are native children of #211 and land through the effort's
collector branch in `cedagova/fastReader`. Native blocked-by edges:

| Leaf | Blocked by | Reason |
| --- | --- | --- |
| F008 #205 | None | Foundation; isolates the formatter's mass reformat. |
| F009 #206 | None | CI/release gates; touches no Kotlin source. |
| F001 #198 | F008 | Explicit-API rule rides the conventions; avoids reformat conflicts. |
| F011 #208 | F008 | Contract gate edits library tests after the reformat. |
| F010 #207 | F001, F009, F011 | Freeze the surface before versioning; CI R8 exists; contract location settled before the copy set is defined. |
| F013 #210 | F001 | Restructure only after the surface is frozen. |
| F002 #199 | F001, F010 | Seams extend the frozen surface; fixtures ship inside the copy set. |
| F004 #201 | F008, F010 | New engine module(s) use conventions and join the copy set. |
| F003 #200 | F002, F010 | New module uses library-owned seams/fixtures and joins the copy set. |
| F005 #202 | F003, F004 | Composition root consumes F003's one assembly call (shared `FastReaderApplication` wiring); the mechanical acyclic-package check needs F004 to have removed the `epub`↔`content` cycle from `:app`. |
| F007 #204 | F003, F005 | `AccountBookCopies` coupling resolved by F003; call sites touched once after F005. |
| F006 #203 | F005 | F005 owns Route wiring; F006 reshapes screen internals after it. |
| F012 #209 | F001–F011, F013 | Documents the delivered structure. |

Waves (a leaf starts when all its blockers are merged into the collector):

1. F008, F009
2. F001, F011
3. F010, F013
4. F002, F004
5. F003
6. F005
7. F006, F007
8. F012

## Interfaces and ownership

- **Library surfaces** (`:reader-auth`, `:reader-library`, new
  `:reader-account`, engine module(s)): each owns its public API dump; a
  surface change is visible in review and fails CI until the dump is updated
  (F001 establishes the check; later module-creating leaves adopt it).
- **Copy set and consumer check** (F010): the recorded consumption document
  and the scripted standalone build are the contract with the future Reader
  client; any leaf adding a reusable module extends both.
- **Test fixtures** (F002): one library-provided fixture surface; `:app`
  defines no fake of a library type.
- **Contract document** (F011): one pinned reader-api document and update
  procedure used by both libraries; `CONTRACT.md` cites pinned commits only.
- **Host seams** (F003): the device catalog and book identity stay FastReader
  implementations behind library-owned interfaces; `PortableReadingPosition`,
  `AccountResumeOffers`, `DeviceBookPublicationSource`,
  `ReaderAccountConfiguration` and account UI stay in `:app`.
- **App shell** (F005 wiring/navigation, F006 screen internals/primitives,
  F007 repository split): ownership split exactly as the audit's
  cross-finding analysis states.
- **Docs** (F012): the tracked architecture document; every structural leaf
  updates the docs it changes.

## Risks and rabbit holes

- **Long-lived collector.** Thirteen leaves in one collector keep `main`
  unchanged until final delivery. Mitigation: waves keep leaves small; the
  coordinator refreshes the collector from `main` between waves.
- **Formatter churn.** The first formatter run touches many files; it lands
  first, as a reformat-only commit inside the F008 PR, so later leaves rebase
  once.
- **Concurrency (F013, F003).** The sync engine's single-writer ordering and
  account-switch guarantees (#169) and the real-threads queue test must stay
  unchanged; review both as concurrency risk.
- **Security-sensitive moves (F001, F002, F003).** Wrapping `UserSession`,
  moving session presentation state and substituting auth seams must not
  weaken session storage, clearing rules or the A83/#167/#168 invariants;
  review as auth-sensitive.
- **Test fixtures on Android library modules (F002).** Gradle test fixtures
  with Kotlin on Android libraries may need an AGP flag or may not fit; a
  dedicated fixtures module is the fallback. Do not prototype beyond choosing.
- **R8 without signing (F009).** Prove minification through the R8 task, not
  by weakening the unsigned-packaging refusal (`app/build.gradle.kts:227-236`).
- **Parallel wave 7 (F006, F007).** Both can touch Library and Settings call
  sites; the coordinator may run them one after the other (no edge needed).
- **Golden drift (F004, F006, F007).** Pure moves must not change a pixel; the
  only permitted golden change is AD-6's banner.
- **Rabbit holes to avoid:** Kotlin Multiplatform, dependency upgrades,
  generated API clients, publishing, restructuring tests beyond moving them,
  UX changes, and documenting structure that does not exist yet.

## Migration, rollout, recovery, and rollback

- No persisted-format change anywhere: `catalog.json` byte-compatible (F007),
  account stores and pipeline version unchanged (F003, F004). No data
  migration is needed.
- No release is cut by any leaf; the app version changes only through the
  existing release process. The effort ships to `main` through one collector
  delivery after aggregate review.
- Rollback: each leaf is one squash commit on the collector; before final
  delivery a leaf is reverted by reverting its commit on the collector; after
  delivery the collector result reverts as one change. Library versions and
  change records (F010) let a copied library identify what changed.

## Issue publication manifest

| Key | Kind | Parent | Repository | Title | Delivery | Blocked by | Issue |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ROOT | GROUP | None | cedagova/fastReader | A197 outcomes: fastReader as the base for the Reader Android client | COLLECTOR | None | https://github.com/cedagova/fastReader/issues/211 |
| F001 | LEAF | ROOT | cedagova/fastReader | A197-F001 — The Reader libraries' public API is accidental: provider and transport types leak and nothing checks the surface | None | F008 | https://github.com/cedagova/fastReader/issues/198 |
| F002 | LEAF | ROOT | cedagova/fastReader | A197-F002 — A host cannot substitute or test against the libraries without writing its own seams and fakes | None | F001, F010 | https://github.com/cedagova/fastReader/issues/199 |
| F003 | LEAF | ROOT | cedagova/fastReader | A197-F003 — About 2,070 lines of general Reader-client logic live in `:app`, where a second client must rewrite them | None | F002, F010 | https://github.com/cedagova/fastReader/issues/200 |
| F004 | LEAF | ROOT | cedagova/fastReader | A197-F004 — The EPUB and tokenizer engines are Android-free but trapped in `:app` behind a package cycle | None | F008, F010 | https://github.com/cedagova/fastReader/issues/201 |
| F005 | LEAF | ROOT | cedagova/fastReader | A197-F005 — The app shell has no copyable wiring, state-holder or navigation convention | None | F003, F004 | https://github.com/cedagova/fastReader/issues/202 |
| F006 | LEAF | ROOT | cedagova/fastReader | A197-F006 — Screen files are god-sized and the design system is colours only, so UI primitives are copied per screen | None | F005 | https://github.com/cedagova/fastReader/issues/203 |
| F007 | LEAF | ROOT | cedagova/fastReader | A197-F007 — `LibraryRepository` owns about nine unrelated concerns | None | F003, F005 | https://github.com/cedagova/fastReader/issues/204 |
| F008 | LEAF | ROOT | cedagova/fastReader | A197-F008 — Build settings are copied into every module and static analysis is thin | None | None | https://github.com/cedagova/fastReader/issues/205 |
| F009 | LEAF | ROOT | cedagova/fastReader | A197-F009 — CI never builds what ships, and the release script runs no gates and hides a failed query | None | None | https://github.com/cedagova/fastReader/issues/206 |
| F010 | LEAF | ROOT | cedagova/fastReader | A197-F010 — The libraries have no recorded consumption mode, version or self-sufficient shrink rules | None | F001, F009, F011 | https://github.com/cedagova/fastReader/issues/207 |
| F011 | LEAF | ROOT | cedagova/fastReader | A197-F011 — The reader-api contract gate covers `:reader-library` only | None | F008 | https://github.com/cedagova/fastReader/issues/208 |
| F012 | LEAF | ROOT | cedagova/fastReader | A197-F012 — There is no tracked map of the codebase for a new client, and several docs contradict the code | None | F001, F002, F003, F004, F005, F006, F007, F008, F009, F010, F011, F013 | https://github.com/cedagova/fastReader/issues/209 |
| F013 | LEAF | ROOT | cedagova/fastReader | A197-F013 — `AccountSyncEngine.kt` is one 1,431-line file with about ten responsibilities | None | F001 | https://github.com/cedagova/fastReader/issues/210 |

### Planned leaf refinements

Each existing issue keeps its audit body verbatim (audit handoff marker,
source links, finding ID, evidence, desired outcome, boundary, acceptance,
constraints, limitations, decision). Publication prepends the planning
metadata and appends the leaf-contract sections below. Refinements only
resolve the audit's planning inputs; they add no new outcome.

- **F008 #205 — Build conventions and static analysis.** One convention
  source for SDK levels, JVM target, lint and test settings applied to every
  module; one formatter plus static check in the normal `check` task for every
  module; the mass reformat as a reformat-only commit inside the PR
  (review-level isolation; no blame-ignore promise under squash delivery);
  stale catalog comment and unused `kotlin-android` alias removed.
  Dependency-update automation is not decided here (AD-10). Validation:
  `./gradlew check` green; the release APK is compared before and after the
  convention change alone (excluding the reformat commit), with identical DEX,
  manifest and resources.
- **F009 #206 — CI builds what ships; release fails loud.** CI runs the
  release variant's R8 step without a signing key and without loosening the
  unsigned-packaging refusal, compiles `androidTest`, runs once per PR commit
  (trigger/concurrency change) and pins actions to SHAs. `release.sh` stops on
  any failed `gh` query (existing-tag check, forward-only guard) and refuses to
  publish a commit whose gates have not passed (runs them or checks the
  commit's CI result). Resource shrinking stays off (AD-9). Validation: a
  deliberately broken keep rule or `androidTest` source fails the CI steps
  (shown once in the PR, not committed); `scripts/test-release-publish.sh`
  gains the failing-`gh` cases and passes.
- **F001 #198 — Deliberate library surface.** Explicit-API mode and a
  committed API dump with a CI check for `:reader-auth` and `:reader-library`;
  provider/transport types wrapped behind library-owned types (AD-4);
  `:reader-library` tests no longer add `supabase-auth`. `createForTests`
  moves to F002. Validation: all tests green; a signature change fails the API
  check; `:app` behavior unchanged. Auth-sensitive review: session store
  semantics identical.
- **F011 #208 — One contract gate for both libraries.** One pinned reader-api
  document and one written update procedure used by both libraries; a test
  fails when a `PreAuthDocument` or `ReaderProfileUpdate` field or its pinned
  schema changes; `CONTRACT.md` cites only pinned commits. The pin itself is
  not moved (whether `909174af` matches stage stays out of scope). Validation:
  a local field change fails the new test (shown in the PR, not committed).
- **F010 #207 — Source-copy consumption recorded and proven.** A tracked
  document records AD-1 and the exact copy set; each reusable module has a
  version and `CHANGELOG.md`; `:reader-library` declares consumer keep rules
  for its own serialized types; a scripted check copies the set into a
  throwaway standalone Gradle project and runs a minified build that does not
  rely on `:reader-auth`'s rules for `:reader-library` types; CI runs it.
  Validation: the check passes in CI; removing a needed `:reader-library` keep
  rule makes it fail (shown once).
- **F013 #210 — Sync engine split by responsibility.** Host contracts,
  outbox, canonical-state adoption and orchestration in separate files/types;
  public API dump unchanged; F013's diff modifies no test, and all
  `:reader-library` tests stay green, including the real-threads queue test. Concurrency-sensitive review.
- **F002 #199 — Library-owned seams and fixtures.** Library interfaces for the
  auth client, API client, downloads and publication transfer; one
  library-provided fixture surface (test fixtures or a fixtures module, in the
  copy set) with test doubles and one mock-engine harness shared by both
  library suites and `:app`; `:app` wrapper interfaces and fakes of library
  types removed; any test-only production API marked and documented.
  Validation: all suites green; API dumps updated deliberately.
- **F004 #201 — Engines in their own modules.** `epub/`, `content/` and
  `timing/` (AD-3) moved unchanged into Android-free, acyclic module(s) using
  the conventions and the copy set, each with a short contract README; the
  `epub`↔`content` cycle broken; engine tests and `sharedTest` fixtures moved;
  `ContentPipelineDeviceTest` still compiles against them. Validation: engine
  tests green in the new module(s); `ContentPipelineVersion` and all goldens
  unchanged.
- **F003 #200 — `:reader-account` library module.** Account session state,
  verified-copy store and download/import orchestration move into a new
  library module (AD-2) with no FastReader catalog types, behind host seams for
  the device catalog and book identity; account errors map to outcomes in one
  place; one documented call site assembles the libraries for a host; the
  module joins the copy set, conventions and API check. Validation: moved
  tests green in the new module, `:app` tests and goldens unchanged.
  Auth-sensitive and concurrency-sensitive review (session bridge, foreground
  hooks).
- **F005 #202 — Copyable app shell.** One composition root using F003's
  assembly call; Routes receive only what they use; one written state-holder
  convention with lifecycle-aware collection across Library, Reader and
  Settings; typed saveable destinations where adding one touches one
  declaration; a mechanical acyclic-package check for `:app`. Process-death
  rules (`LibraryGraph.kt:41-48`, `MainActivity.kt:62-70`), the
  Route/stateless-Screen split and REQ-009/REQ-103 routing preserved.
  Validation: goldens and routing tests unchanged; one emulator
  process-death smoke on `Phone_Mid_API36`.
- **F007 #204 — `LibraryRepository` split.** Settings (still stored in
  `catalog.json`, AD-8), positions and book bytes behind separate narrow types;
  `LibraryRepository` no longer exposes them. Validation: existing migration
  tests unchanged and green, all tests green.
- **F006 #203 — Shared UI primitives and tokens.** Banner, undo bar, confirm
  dialog, touch target, spacing and setting rows defined once and used by
  every screen; named spacing/size tokens for values used by more than one
  screen; screen files split by section within AD-7's 600-line bound; account
  UI out of the library screen file; reader adapters out of `ui/`.
  Validation: `verifyRoborazziDebug` green with only AD-6's banner goldens
  re-recorded and named in the PR.
- **F012 #209 — Tracked map for a new client.** A tracked architecture and
  "starting a new client" document naming every module, dependency direction,
  what to copy (the F010 copy set) versus use as a pattern, and each library's
  host obligations; module READMEs and library doc comments matched to the
  code without FastReader naming; historical evidence and stale plan statuses
  marked or moved (AD-11). Validation: every contradiction the audit lists is
  checked off in the PR.

## Acceptance coverage

| Root acceptance | Covered by |
| --- | --- |
| Every accepted A197 outcome delivered (root completion rule) | F001–F013, one leaf per outcome, no overlap |
| F001 acceptance (explicit API, surface check, test classpath, tests green) | F001 |
| F002 acceptance (library seams, one fixture surface, one harness, test-only API marked) | F002 |
| F003 acceptance (module outside `:app`, unchanged behavior, one error mapping, one assembly call) | F003 (module option per AD-2) |
| F004 acceptance (engines out of `:app`, acyclic, Android-free, tests moved, pipeline version and goldens unchanged) | F004 (all three engines per AD-3) |
| F005 acceptance (composition root, state convention, typed destinations, acyclic packages, goldens) | F005 |
| F006 acceptance (primitives once, tokens, size bound, goldens) | F006 (AD-6, AD-7 resolve the audit's golden and threshold inputs) |
| F007 acceptance (narrow types, `catalog.json` compatible, tests green) | F007 |
| F008 acceptance (settings once, same lint + formatter, clean catalog, APK unchanged) | F008 |
| F009 acceptance (R8 + `androidTest` in CI, one run per commit, fail-loud release, release proof) | F009 |
| F010 acceptance (owner-approved mode, versions + change records, own keep rules, outside consumer) | F010 (mode per AD-1) |
| F011 acceptance (auth shapes gated, one document + procedure, pinned citations) | F011 |
| F012 acceptance (tracked map, contradictions resolved, history separated) | F012 |
| F013 acceptance (responsibilities separated, API and tests unchanged) | F013 |

Overlaps are resolved by the audit's ownership split: `createForTests` to
F002, not F001; Routes wiring to F005, internals to F006; `AccountBookCopies`
seam to F003, repository split to F007; keep rules to F010, their CI proof to
F009. No gap: each child's own acceptance remains its completion test.

## Validation and feedback

- Every leaf: `./gradlew check` (unit tests, lint, formatter/static check
  once F008 lands, API check once F001 lands), `verifyRoborazziDebug`, and
  its own acceptance evidence as listed above; CI must pass on the leaf PR.
- Structural moves (F003, F004, F007, F013) prove "no behavior change" by the
  unchanged test suites and goldens, not by new manual testing.
- Emulator runs only where lifecycle or wiring changes (F005 process-death
  smoke; F003 optional smoke of sign-in state after launch).
- No manual stage testing (owner, 2026-09-22); what stays unobserved on stage
  is stated in the PR.
- Auth-sensitive leaves (F001, F002, F003) and concurrency-sensitive leaves
  (F003, F013) get independent review per the effort's normal reviewer.

## Assumptions and open questions

None open. Recorded owner decision and assumptions:

### Owner decision brief (decided 2026-09-25: `D1 A, D2 A, D3 A`)

**Problem.** Three accepted outcomes could not be planned without
product-level choices the audit left to the owner: how the future Reader
Android client takes the libraries (F010, which also decides how F002 ships
test doubles), whether the general account logic becomes a library or a
documented pattern (F003), and whether RSVP is part of the reusable engine
(F004).

**Facts (pinned `cedagova/fastReader@7978c71`).** The repository is public, so
a git-based dependency needs no credential. No Reader Android repository
exists yet. Both libraries are consumed as `project(...)` and depend on root
catalog aliases; `:reader-library` has no keep rules of its own. `epub/`
(1,392 lines) and `content/` (2,181) are Android-free; `timing/` (442) is RSVP
only; `content/` also holds the RSVP tokenizer. **Assumptions:** the Reader
client talks to the same Supabase-backed reader-api; the Reader client lives in
the Chunipers org.

**D1 — how the Reader client takes the libraries (F010, scopes F002/F011).**
- A (recommended, **chosen**) Source copy at a tagged version with a version
  and CHANGELOG per library. Cheapest, no cross-org dependency; copies can
  drift. Reversible.
- B Git submodule + included build of this public repo: byte-identical, but a
  work repo depends on a personal repo.
- C Published Maven artifact: real versions, but registry, signing/credentials
  and a publish job for two libraries with one consumer.

**D2 — general account logic (F003).**
- A (recommended, **chosen**) New library module consumed by FastReader.
- B Keep it in `:app` as a documented reference pattern.

**D3 — should the Reader client be able to reuse RSVP (F004)?**
- A (recommended, **chosen**) Yes: move `epub/`, `content/` and `timing/`
  unchanged.
- B Not now: `timing/` stays in `:app`.
- C No: also split the tokenizer out of `content/` (higher risk).

**Blocked (now unblocked):** F002, F003, F004, F010, F011 and the delivery
order.

### Other assumptions (non-material, recorded)

- The Reader client keeps Supabase as its identity provider; AD-4 wraps the
  types anyway, so a different answer changes only library internals.
- The API check uses a Kotlin binary-compatibility dump or an equivalent tool
  chosen by the F001 implementer; the requirement is the failing check.
- Planner decisions AD-4 to AD-9 and AD-11 are reversible and recorded above;
  AD-10 leaves dependency-update automation as an open owner preference outside
  this effort.

## Satisfaction proof

Not applicable: implementation work remains for every child. Root
classification is `EFFORT`, not `ALREADY_SATISFIED`.

## Publication verification

Pending until content review: `plan validate --phase review-ready` on the
candidate head; after approval, leaf-body refinement, `plan reconcile-graph`
and `plan verify-graph` results are recorded here.
