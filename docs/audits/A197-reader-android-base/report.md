# fastReader as the base for the Reader Android client

- Audit ID: `A197`
- Audit key: `reader-android-base-readiness-20260924`
- Status: Review
- Dossier PR: https://github.com/cedagova/fastReader/pull/197
- Started: 2026-09-24
- Decision owner: cedagova (Cesar Gonzalez)
- Lead investigator: Claude audit-lead (claude-opus-5-high)
- Independent reviewer: Claude audit-reviewer (fresh internal subagent, cedagova-reviewer identity)

## Question

How ready is fastReader, at the pinned main, to serve as the base for the Chunipers Reader Android client — both as code the new client can reuse unchanged (:reader-auth, :reader-library, and any reusable app parts) and as an example codebase to copy patterns from — and which maintainability, modularity, extensibility and documentation changes (no new features) would close the gap?

## Why this matters

FastReader is the owner's stepping stone toward a Chunipers Reader Android
client. The auth and account-library work (A83, #92/#93, #100, #104, #167–#170)
proved the backend contract from a real Android app. The owner now wants the
codebase left in a state where the new client can either reuse its code
unchanged or copy its patterns, without adding features. This audit supports
one decision: which structural, documentation and tooling changes are worth
making in fastReader *before* the Reader client starts, and which are not.

## Targets and baselines

| Repository | Full commit SHA |
| --- | --- |
| `cedagova/fastReader` | `7978c711e3f207ac6c727f6479cf61c87cdbdc63` |

## Scope

### Included

- `:reader-auth` and `:reader-library`: public API surface, internal
  structure, configuration and extension points, contract pinning, and how a
  second app would consume them.
- `:app`: package structure, composition root and dependency wiring, state and
  navigation patterns, size and cohesion hot spots, the pure-Kotlin engines
  (`epub/`, `content/`, `timing/`), and Reader-account code that lives in the
  app rather than in the libraries.
- Build logic (`build.gradle.kts` files, `gradle/libs.versions.toml`,
  `gradle.properties`, `version.properties`), the CI workflow
  (`.github/workflows/checks.yml`) and static analysis.
- Test suites and verification tooling across the three modules.
- Repository documentation and tooling as an onboarding and example surface:
  `README.md`, module READMEs, `reader-auth/CONTRACT.md`,
  `reader-library/contracts/`, `docs/`, `bin/`, `scripts/`.

### Excluded

- New product features or user-facing behavior changes (owner instruction).
- The Chunipers backend repositories (`reader-api`, `reader-db`,
  `reader-web`): read only as contract references, never as audit targets.
- A fresh security review of authentication and session handling, which A83,
  #167 and #168 covered, except where a modularity change would expose it.
- UI and visual design, Play Store distribution, runtime performance tuning.
- The future Reader Android client itself: no repository exists yet, so its
  shape is an owner decision, not audit evidence.

### Scope changes

None.

## Methods

- **Pinned source reading.** Every `path:line` reference in this report
  resolves against
  `https://github.com/cedagova/fastReader/blob/7978c711e3f207ac6c727f6479cf61c87cdbdc63/<path>#L<line>`.
  Source reading proves structure, visibility, imports, duplication and
  documentation claims (Indirect for behavior, Direct for "this text/declaration
  exists").
- **Mechanical scans** (`rg`, `wc -l`, `git ls-files`, `du`) on the pinned
  tree, rerunnable from the checkout. They prove counts, import graphs and
  absence claims (for example "no `explicitApi`", "no `android.*` import").
- **One local run of the gates**, JDK 21, warm build cache:
  `./gradlew testDebugUnitTest lint verifyRoborazziDebug --console=plain` →
  `BUILD SUCCESSFUL` (exit 0). Counts from `build/test-results` XML: `:app`
  925 tests / 78 classes, `:reader-auth` 106 / 14, `:reader-library` 188 / 10,
  0 failures, 0 skipped. Lint: `:app` 22 warnings, 0 errors; both libraries 0.
  Roborazzi verify passed. This proves the baseline is green; it does not
  measure cold-build time.
- **CI history**, read-only: the last 200 runs of `checks.yml` (197 success,
  3 failures, all on unmerged branches; none on `main`).
- **Bounded specialists.** Three lead-sponsored, read-only audit specialists
  with disjoint surfaces: (1) the Reader libraries and the app's account
  wiring (architecture-ownership + api-contracts lenses); (2) `:app` outside
  `account/` (architecture-ownership + offline-mobile-portability); (3) build,
  CI, tests, docs and tooling (testing-evidence + reliability-observability),
  which also ran the gates above. The lead re-verified every claim used below
  against the pinned tree with the scans listed in each finding; specialist
  conversations are not evidence.
- **Not used:** no emulator run, no release (R8) build, no cold build, no
  contact with the Chunipers repositories beyond the pinned OpenAPI copy in
  `reader-library/contracts/`.

## Coverage inventory

| Surface | Scope | Evidence examined | Result |
| --- | --- | --- | --- |
| `:reader-auth` public API and build | In | All 17 main files' declarations, `build.gradle.kts`, `consumer-rules.pro` | Clean dependency direction; provider/transport types leak into public API (F001). |
| `:reader-library` public API and build | In | All 26 main files' declarations, `build.gradle.kts` | Acyclic packages; accidental public surface, no own keep rules (F001, F010). |
| `:reader-library` sync engine internals | In | `AccountSyncEngine.kt` structure by line range, its 2,267-line test | One 1,431-line file with ~10 responsibilities (F013). |
| Reader contract pinning | In | `reader-library/contracts/PINNED.md`, `.sha256` recomputed, `ReaderLibraryContractTest`, `reader-auth/CONTRACT.md` | Strong gate for `:reader-library`; `:reader-auth` shapes unchecked (F011). |
| App-side Reader account code (`account/**` except `ui/`) | In | All files, line counts, imports | ~2,070 lines of general Reader-client logic and 281 lines of forced seams live in `:app` (F002, F003). |
| App account UI (`account/ui/`) | In | Imports and shared-primitive scan only | Shares the duplicated primitives of F006; no separate finding. |
| Composition root and navigation (`FastReaderApplication`, `MainActivity`, `LibraryGraph`) | In | Full read | Service-locator Application, flag-based navigation, three state patterns (F005). |
| Feature screens and state (`library/ui`, `reader/ui`, `settings/ui`, `crash/ui`) | In | Declaration outlines, duplicate scans, dp-literal counts | God-sized screens, duplicated primitives, colour-only theme (F006). |
| Domain classes (`library/`, `reader/`, `settings/`, `external/`) | In | `LibraryRepository` in full, others by imports and constructors | `LibraryRepository` owns ~9 concerns (F007); IO seams otherwise well injected. |
| Engines (`epub/`, `content/`, `timing/`) | In | Imports of all 21 files, test runners | Android-free but trapped in `:app` with an `epub`↔`content` cycle (F004). |
| Build logic and version catalog | In | Root and three module build files, `libs.versions.toml`, `gradle.properties`, `version.properties` | Settings copied three times, dead alias, thin static analysis (F008). |
| CI workflow | In | `.github/workflows/checks.yml`, last 200 runs | No release/R8 or `androidTest` gate, double runs (F009). |
| Release script | In | `scripts/release.sh` | Runs no gates; swallows a failed query (F009). |
| Test suites and fixtures | In | Per-module test trees, both `TestHarness.kt` files, fakes, local run | Green; harness copy-pasted and diverged; no consumable fixtures (F002). |
| Instrumented tests (`app/src/androidTest`) | In | File list (2 files) | Not compiled by CI (F009); not executed in this audit. |
| Repository docs (`README.md`, module READMEs, `CONTRACT.md`, `docs/agent-first-development.md`, `docs/release.md`) | In | Sampled concrete claims against code | Several contradictions; no architecture/module map (F012). |
| Agent guides (`AGENTS.md`, `CLAUDE.md`) | In | `.gitignore`, local copies | Untracked and stale (F012). |
| Historical records (`docs/evidence`, `docs/plans`, `docs/product-definitions`, `docs/release-notes`, `docs/audits`) | In | Sizes, file counts, status lines | 61% of the tracked tree is evidence media; #1 plan/definition statuses stale (F012). |
| Identity tooling (`bin/`, `.githooks/`) | In | File contents, diff against `.github-infra/bin/gh-personal` | Reusable but a divergent second copy (F012). |
| Security of token/session handling | Out | Charter exclusion (A83, #167, #168) | Not re-assessed. |
| Chunipers backend repositories | Out | Charter exclusion | Pinned OpenAPI copy read only; whether the pin matches reader-api HEAD not checked. |
| UI/visual design, performance, Play distribution | Out | Charter exclusion | Not assessed. |

## Findings

### Finding index

| ID | Title | Decision | Confidence | Review | Planning readiness | Outcome issue | Outcome umbrella |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `A197-F001` | The Reader libraries' public API is accidental: provider and transport types leak and nothing checks the surface | Candidate | High | Pending | Pending | Not required | Not required |
| `A197-F002` | A host cannot substitute or test against the libraries without writing its own seams and fakes | Candidate | High | Pending | Pending | Not required | Not required |
| `A197-F003` | About 2,070 lines of general Reader-client logic live in `:app`, where a second client must rewrite them | Candidate | Medium | Pending | Pending | Not required | Not required |
| `A197-F004` | The EPUB and tokenizer engines are Android-free but trapped in `:app` behind a package cycle | Candidate | High | Pending | Pending | Not required | Not required |
| `A197-F005` | The app shell has no copyable wiring, state-holder or navigation convention | Candidate | High | Pending | Pending | Not required | Not required |
| `A197-F006` | Screen files are god-sized and the design system is colours only, so UI primitives are copied per screen | Candidate | High | Pending | Pending | Not required | Not required |
| `A197-F007` | `LibraryRepository` owns about nine unrelated concerns | Candidate | Medium | Pending | Pending | Not required | Not required |
| `A197-F008` | Build settings are copied into every module and static analysis is thin | Candidate | High | Pending | Pending | Not required | Not required |
| `A197-F009` | CI never builds what ships, and the release script runs no gates and hides a failed query | Candidate | High | Pending | Pending | Not required | Not required |
| `A197-F010` | The libraries have no recorded consumption mode, version or self-sufficient shrink rules | Candidate | High | Pending | Pending | Not required | Not required |
| `A197-F011` | The reader-api contract gate covers `:reader-library` only | Candidate | High | Pending | Pending | Not required | Not required |
| `A197-F012` | There is no tracked map of the codebase for a new client, and several docs contradict the code | Candidate | High | Pending | Pending | Not required | Not required |
| `A197-F013` | `AccountSyncEngine.kt` is one 1,431-line file with about ten responsibilities | Candidate | Medium | Pending | Pending | Not required | Not required |

## A197-F001 — The Reader libraries' public API is accidental: provider and transport types leak and nothing checks the surface

- Decision: Candidate
- Confidence: High
- Review: Pending
- Planning readiness: Pending
- Cause status: Hypothesis
- Expected implementation repositories: `cedagova/fastReader`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

A library meant to be reused unchanged by a second app exposes a deliberate
public surface: third-party types appear in it only on purpose (and are then
declared `api`), everything else is `internal`, and an unintended change to the
surface fails a check.

### Condition and evidence

- Direct: no `explicitApi()`, API dump or binary-compatibility validator in any
  build file (`rg -n 'explicitApi|apiValidation|binary-compatibility'` over the
  tree: no match).
- Direct: Supabase SDK types are public in `:reader-auth`. `SessionStore` takes
  and returns `io.github.jan.supabase.auth.user.UserSession`
  (`reader-auth/src/main/kotlin/com/cedagova/reader/auth/session/SessionStore.kt:3,16-17`);
  `StoreSessionManager` implements the SDK's `SessionManager`
  (`.../session/StoreSessionManager.kt:29`);
  `ReaderAuthPolicy.SESSION_CLEARING_REFRESH_CODES` is a `Set<AuthErrorCode>`
  (`.../ReaderAuthPolicy.kt:51`).
- Direct: kotlinx `JsonObject` is the return type of the public
  `ReaderApiClient.capabilities/upsertProfile/get/put/post`
  (`.../api/ReaderApiClient.kt:80,94,98,101`), and a Ktor `HttpClientEngine` is
  a parameter of the public `ReaderAuthClient.createForTests`
  (`.../ReaderAuthClient.kt:384-391`).
- Direct: those dependencies are `implementation`, not `api`
  (`reader-auth/build.gradle.kts:53-57`). Indirect consequence already visible:
  `:reader-library` adds `supabase-auth` to its own test classpath just to build
  a `ReaderAuthClient` (`reader-library/build.gradle.kts:72-76`).
- Direct: `:reader-library` declares 117 public top-level declarations against
  10 internal/private ones, mostly models (specialist count, spot-checked).
- Counterexample (Direct): `:reader-library` deliberately exposes `:reader-auth`
  and coroutines as `api`, with the reason written down
  (`reader-library/build.gradle.kts:54-62`). The libraries never import `:app`.

### Cause

Hypothesis: both libraries were grown for exactly one host inside the same
Gradle build, where any visibility compiles and nothing forced a boundary.

### Effect

A second client compiles against provider and transport types by accident, so
a Supabase, Ktor or serialization upgrade inside the library can break the
client without any library change looking like an API change. Nothing tells a
maintainer which declarations are the contract and which are incidental.

### Recommended outcome

Each library has a deliberate, reviewed public surface. Provider and transport
types are either absent from it or exposed on purpose as `api` dependencies.
An unintended surface change fails the normal checks.

### Outcome boundary

In: visibility, dependency configuration, and a surface check for
`:reader-auth` and `:reader-library`. Out: behavior changes, new operations,
the host seams of F002, consumption mode (F010), and whether the Reader client
keeps Supabase.

### Cohesion rationale

All items are one property — "what a consumer can see and depend on" — of the
same two libraries, and one check proves it for both.

### Outcome acceptance

- Both libraries build with an explicit-API style rule, or an equivalent check,
  and the committed surface lists no type from a dependency declared
  `implementation`.
- Changing a public signature in either library fails CI until the surface
  record is updated.
- `:reader-library`'s tests build a `ReaderAuthClient` without adding a
  provider SDK to their own dependencies, or the dependency is declared `api`
  with a written reason.
- All existing tests stay green; `:app` needs no behavior change.

### Planning inputs

- Surfaces: `reader-auth/src/main/**`, `reader-library/src/main/**`, both
  `build.gradle.kts`.
- Open question for planning: wrap `UserSession` and `JsonObject` behind
  library types, or expose them deliberately. The answer depends on whether the
  Reader client keeps Supabase (unknown).
- `createForTests` belongs with the test-fixture outcome in F002.
- Sequence before F010 (a published or copied library freezes its surface).

### Limitations

The leak was established by reading signatures; no separate consumer was
compiled against the libraries.

### Decision rationale

Pending.

## A197-F002 — A host cannot substitute or test against the libraries without writing its own seams and fakes

- Decision: Candidate
- Confidence: High
- Review: Pending
- Planning readiness: Pending
- Cause status: Confirmed
- Expected implementation repositories: `cedagova/fastReader`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

Every library entry point a host needs to replace in tests ships as a library
interface, and the library ships reusable test doubles, so each consumer does
not write its own.

### Condition and evidence

- Direct: the main library classes have internal constructors and no interface:
  `ReaderAuthClient` (`reader-auth/.../ReaderAuthClient.kt:56`),
  `ReaderApiClient` (`.../api/ReaderApiClient.kt:63`), `FileSessionStore`,
  `KeystoreSessionCipher`, `AssetDownloadClient`
  (`reader-library/.../downloads/AssetDownloadClient.kt:49`),
  `PublicationTransferClient` (`.../imports/PublicationTransferClient.kt:54`),
  `FileAccountLibraryStore` (`rg -n 'internal constructor'`).
- Direct: `:app` therefore defines its own seams, each saying so in its doc
  comment: `app/src/main/java/com/cedagova/fastreader/account/ReaderAccountGateway.kt`
  (91 lines), `AssetDownloadGateway.kt` (67), `PublicationImportGateway.kt`
  (123) — 281 lines — plus the fakes `app/src/test/.../FakeReaderAccountGateway.kt`
  (100) and `FakePublicationImportGateway.kt` (207).
- Direct: `FakeReaderLibraryGateway` (121 lines) lives in
  `reader-library/src/test` and no module has `testFixtures` or a shared test
  module, so no other project can use it.
- Direct: the mock-engine harness is copy-pasted between
  `reader-auth/src/test/.../TestHarness.kt` and
  `reader-library/src/test/.../TestHarness.kt` (`FakeClock` :44/:43,
  `InMemorySessionStore` :54/:52, `FakeServers` :129/:103) and has diverged:
  the auth copy guards its route lookup with `synchronized(routes)` (:137), the
  library copy does not (:110).
- Direct: the only cross-module test seam is test-only code in the production
  API, `ReaderAuthClient.createForTests` (`ReaderAuthClient.kt:384`).
- Counterexample (Direct): `ReaderLibraryGateway` is already a library-owned
  interface and is the pattern to follow
  (`reader-library/.../sync/ReaderLibraryGateway.kt:23-27`).

### Cause

Confirmed by the app files' own comments: the library types were made
non-constructible outside the module and no interface was provided, so the
host wrapped them.

### Effect

The Reader client would re-write ~280 lines of seams and ~430 lines of fakes
that already exist, and would copy a test harness that has already drifted
once. Test-only factories in the production API widen the surface F001 tries
to bound.

### Recommended outcome

Each library owns the interfaces a host substitutes and ships one reusable set
of test doubles and mock-server helpers that another Gradle project can depend
on. The two library test suites share one harness.

### Outcome boundary

In: library-owned seams for the auth client, the API client, downloads and
publication transfer; a consumable test-fixture surface; one shared harness.
Out: moving app logic into the libraries (F003); public-surface checks (F001);
any behavior change.

### Cohesion rationale

One outcome — "a host can test against the libraries with what the libraries
ship" — covering the seams and the doubles that implement them.

### Outcome acceptance

- `:app` no longer declares its own wrapper interfaces for library entry
  points; it uses library-owned ones.
- `:app` tests and both library test suites use test doubles from one
  library-provided fixture surface; no fake of a library type is defined in
  `:app`.
- There is one mock-engine harness, not two.
- No production API exists only for tests, or any that remains is marked and
  documented as such.

### Planning inputs

- Surfaces: `reader-auth/src/{main,test}`, `reader-library/src/{main,test}`,
  `app/.../account/*Gateway.kt`, `app/src/test/.../Fake*Gateway.kt`.
- Gradle `java-test-fixtures` support on Android library modules and the
  consumption mode (F010) decide the mechanism; planning must check both.
- Depends on F001's surface decisions; independent of F003.

### Limitations

Line counts are for the named files only; other app tests may carry smaller
ad-hoc fakes not counted here.

### Decision rationale

Pending.

## A197-F003 — About 2,070 lines of general Reader-client logic live in `:app`, where a second client must rewrite them

- Decision: Candidate
- Confidence: Medium
- Review: Pending
- Planning readiness: Pending
- Cause status: Hypothesis
- Expected implementation repositories: `cedagova/fastReader`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

Logic any Reader Android client needs — account session presentation state,
verified local copies of account books, download and import orchestration, and
the wiring that assembles the libraries — is available outside the FastReader
app, behind host seams for what is genuinely app-specific.

### Condition and evidence

All paths under `app/src/main/java/com/cedagova/fastreader/account/`.

- Direct (no Compose, no FastReader UI): `ReaderAccountController.kt` (200
  lines, including the error-to-outcome map `toOutcome` at :188) and
  `ReaderAccountState.kt` (153).
- Direct/Inference (general once the device-catalog coupling is behind a seam):
  `library/AccountCopyStore.kt` (248, content-addressed digest-verified copies,
  no FastReader imports), `library/AccountCopyReferences.kt` (147),
  `library/AccountDownloads.kt` (306), `library/BookImportState.kt` (165),
  `library/AccountImports.kt` (672; its FastReader coupling is `DeviceBookSources`
  and `bookForId: (String) -> Book?` at :93-95), `library/AccountShelf.kt` (177).
  Total with the two files above: 2,068 lines (`wc -l`).
- Inference (FastReader-specific, stays): `DeviceBookPublicationSource.kt`,
  `PortableReadingPosition.kt` (maps FastReader token positions),
  `ReaderAccountConfiguration.kt` (reads `BuildConfig`),
  `AccountResumeOffers.kt`, and `AccountBookCopies.kt` (coupled to
  `LibraryRepository` at :51-56).
- Direct: the composition is repeated by hand — `ReaderLibraryClient(auth.api)`
  is constructed three times (`app/.../FastReaderApplication.kt:108,122,142`)
  and ~100 lines of wiring, foreground hooks (:237-243, :290-297) and the
  session bridge (`ReaderAccountState.kt:62-67`) must be repeated by any host.
- Direct: the controller's error mapping duplicates the library's own
  (`reader-library/.../sync/AccountLibraryState.kt:64-130`).

### Cause

Hypothesis: features #100–#120 were built as FastReader features first; only
the network layer was deliberately placed in libraries (A83-F007).

### Effect

The Reader client either rewrites ~2,000 lines already proven against stage or
copies them and their tests, after which the two copies drift. The duplicated
error mapping is already a second source of truth inside one repository.

### Recommended outcome

The general account logic and library assembly are consumable without `:app`,
with host seams for the device catalog and book identity; FastReader keeps
only its catalog integration and position mapping. The owner chooses whether
"consumable" means moved into a library module or documented as the reference
pattern to copy (see Planning inputs).

### Outcome boundary

In: the files classified general above, the library composition, and the one
error mapping. Out: account UI (`account/ui/`), FastReader UX policy (undo
window length, resume offers), `PortableReadingPosition`, new behavior, and
the seams/fixtures work of F002.

### Cohesion rationale

These files form one pipeline — session state → shelf → download/import →
verified copy — sharing one host coupling; splitting them would leave half a
pipeline in each place.

### Outcome acceptance

- A Gradle module other than `:app` (or, if the owner chooses the reference
  option, a documented pattern with a named entry point) provides account
  session state, the verified-copy store, and download/import orchestration,
  with no reference to FastReader's catalog types.
- FastReader's behavior, goldens and tests are unchanged.
- Account errors map to user-facing outcomes in one place.
- The libraries can be assembled for a host with one documented call site
  rather than repeated constructor wiring.

### Planning inputs

- Material owner decision: move into a library versus document as the
  reference pattern. It interacts with F010 (how the Reader client consumes
  libraries at all).
- Keep the `ReaderLibraryGateway` interface style (F002) for new seams.
- Watch `AccountBookCopies`' coupling to `LibraryRepository` (F007).
- Validation: existing `:app` account tests must pass unchanged or move with
  the code.

### Limitations

The general/specific split is a classification by imports and responsibilities,
not a trial extraction. Whether the Reader client wants the same shelf and
download UX is unknown.

### Decision rationale

Pending.

## A197-F004 — The EPUB and tokenizer engines are Android-free but trapped in `:app` behind a package cycle

- Decision: Candidate
- Confidence: High
- Review: Pending
- Planning readiness: Pending
- Cause status: Hypothesis
- Expected implementation repositories: `cedagova/fastReader`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

Pure-Kotlin engines that another client would need unchanged live in their own
acyclic modules with their own tests and documentation.

### Condition and evidence

Paths under `app/src/main/java/com/cedagova/fastreader/`.

- Direct: `epub/` (10 files, 1,392 lines), `content/` (9 files, 2,181 lines)
  and `timing/` (2 files, 442 lines) contain no `android.*`/`androidx.*`
  import (`rg -l '^import android' epub content timing` → 0 files). Their tests
  use no Robolectric runner.
- Direct: `epub` and `content` import each other: `epub/BookDigest.kt:3`
  imports `content.BookIdentity`; `content/TocReader.kt` and
  `content/EpubContentPipeline.kt` (6 imports) import `epub`.
- Direct: `timing/RsvpTimingEngine.kt:3-6` depends on `content` token types;
  `settings/ReaderSettings.kt:3` depends on `timing`.
- Inference: reading positions are keyed to the tokenizer
  (`TokenPosition(bookDigest, tokenIndex, pipelineVersion)`,
  `reader/ui/ReaderRoute.kt:397`, `ContentPipelineVersion`). Any client that
  must agree on a synced position with FastReader needs this same pipeline, not
  a copy.

### Cause

Hypothesis: the engines were written inside the single original `:app` module
(plan #1) before any second module existed.

### Effect

The Reader client cannot depend on the engines; copying them risks tokenizer
drift, which would silently break position agreement across clients. The
cycle blocks a clean split as-is.

### Recommended outcome

The EPUB archive/inspection engine and the content/tokenizer pipeline live in
acyclic, Android-free Gradle module(s) with their own tests and a short
contract README; `:app` consumes them unchanged. `timing` is extracted only if
the owner wants RSVP reusable.

### Outcome boundary

In: moving and decoupling `epub/` and `content/` (and optionally `timing/`),
their tests, and the cycle. Out: Kotlin Multiplatform (`javax.xml` DOM is
JVM-only), tokenizer behavior changes, pipeline version bumps.

### Cohesion rationale

The two packages are one pipeline (archive → XHTML → tokens) and the cycle
between them must be cut in the same change that separates them.

### Outcome acceptance

- `:app` depends on the engine module(s) and no longer contains `epub/` or
  `content/` sources.
- The engine module(s) have no Android dependency and no import back into
  `:app`; `epub` and `content` do not import each other in both directions.
- Engine tests run in the new module(s) and pass; `ContentPipelineVersion`
  and all goldens are unchanged.

### Planning inputs

- Owner input: does the Reader client offer RSVP? (scopes `timing`).
- `app/src/androidTest/.../ContentPipelineDeviceTest.kt` exercises the
  pipeline on device and must follow it.
- Where `PortableReadingPosition` lives relative to the content module
  interacts with F003.
- Shares build conventions with F008 (a fourth module multiplies duplication).

### Limitations

Purity is proven by import scan only; no trial compile outside `:app` was run.

### Decision rationale

Pending.

## A197-F005 — The app shell has no copyable wiring, state-holder or navigation convention

- Decision: Candidate
- Confidence: High
- Review: Pending
- Planning readiness: Pending
- Cause status: Hypothesis
- Expected implementation repositories: `cedagova/fastReader`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

An example app shows one way to assemble dependencies, one way to hold screen
state, one typed way to navigate, and a one-directional package graph, each
written down.

### Condition and evidence

Paths under `app/src/main/java/com/cedagova/fastreader/`.

- Direct: `FastReaderApplication.kt` is a service locator with 12
  `lateinit var`/`by lazy` members built in `onCreate`
  (`rg -c 'lateinit var|by lazy'` → 12); activities cast
  `application as FastReaderApplication` (`MainActivity.kt:60,101`).
- Direct: `library/LibraryGraph.kt` (55 lines) is plain constructor wiring with
  a stated no-framework reason (:14-18), but also builds
  `ExternalOpenController` and `SharedPreferencesThemeMirror` (:38,49), and
  Routes receive the whole graph (`settings/ui/SettingsRoute.kt:53`,
  `reader/ui/ReaderRoute.kt:66`).
- Direct: three state patterns coexist — `ReaderViewModel` is the only
  `ViewModel` (`reader/ReaderViewModel.kt:75`); Library and Settings Routes
  collect repository flows directly (`library/ui/LibraryRoute.kt:43-50`,
  `settings/ui/SettingsRoute.kt:59-62`); account uses process-scoped
  controllers. 23 `collectAsState()` calls, 0 `collectAsStateWithLifecycle`,
  although `lifecycle-runtime-compose` is a dependency.
- Direct: navigation is 8 `rememberSaveable` flags in `MainActivity.kt`
  (:150-165) whose precedence is the order of `when` branches (:221-307),
  reset by hand on "Open with" (:172-181); a blocked-resume reason is saved as
  a `.name` string and parsed back (:203, :323-327).
- Direct: package cycles `library`↔`external`
  (`external/ExternalOpenController.kt:6-8`), `settings`↔`library`
  (`settings/ui/SettingsRoute.kt:18`), `account`↔`library`; the account coupling
  sits only in `*/ui` files.
- Direct: no tracked document describes `:app`'s layers or wiring (see F012).

### Cause

Hypothesis: screens were added one issue at a time onto the Application
object, and no convention was recorded to push back.

### Effect

A developer copying the shell has no single pattern to follow and would copy
the service locator, whole-graph passing, flag navigation and cycles. Adding a
screen today means editing flags, a reset block and a precedence order in one
~200-line composable.

### Recommended outcome

The app has one documented composition root, one documented state-holder
convention, a typed saveable destination model where adding a destination
touches one declaration, and a one-directional package graph. No DI or
navigation framework is required.

### Outcome boundary

In: `FastReaderApplication`, `MainActivity`, `LibraryGraph`, Route wiring,
package direction, lifecycle-aware collection. Out: screen internals and shared
UI primitives (F006), `LibraryRepository` (F007), account logic placement
(F003), any routing behavior change (REQ-009 and REQ-103 preserved).

### Cohesion rationale

Wiring, state holding and navigation together are "the app shell" a new
client copies first; all three live in the same two files and change together.

### Outcome acceptance

- One composition root; Routes receive only the dependencies they use; no
  `application as FastReaderApplication` outside it.
- Screen state follows one written convention across Library, Reader and
  Settings, and collection is lifecycle-aware.
- Destinations are a typed, saveable model; adding one touches one declaration.
- The package graph of `:app` has no cycles (checked mechanically).
- All goldens and routing tests unchanged.

### Planning inputs

- Existing process-death rules (`LibraryGraph.kt:41-48`, `MainActivity.kt:62-70`)
  are deliberate and must survive.
- Preserve the Route/stateless-Screen split and pure state builders
  (`library/ui/LibraryUiState.kt:238`), which make every state renderable in
  Roborazzi.
- Planning may split wiring and navigation into separate increments.

### Limitations

"Scales badly" is an inference from structure, not from a trial of adding a
screen.

### Decision rationale

Pending.

## A197-F006 — Screen files are god-sized and the design system is colours only, so UI primitives are copied per screen

- Decision: Candidate
- Confidence: High
- Review: Pending
- Planning readiness: Pending
- Cause status: Hypothesis
- Expected implementation repositories: `cedagova/fastReader`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

Shared UI primitives and layout tokens are defined once and used by every
screen; screen files are split by section so each is readable on its own
(owner rule: centralize design system and components).

### Condition and evidence

Paths under `app/src/main/java/com/cedagova/fastreader/`.

- Direct: `library/ui/LibraryScreen.kt` is 1,814 lines with 33 composables and
  a 23-callback entry point (:142); roughly 640 lines are account sections
  (:454-627, :1261-1728). `reader/ui/ReaderScreen.kt` is 1,538 lines with 35
  composables and 18 callbacks (:199). `settings/ui/SettingsScreen.kt` is 765.
- Direct: `private val TouchTarget = 48.dp` is declared in 6 files
  (`rg -n 'val TouchTarget = 48.dp'` → 6).
- Direct: the persistence-failure banner exists three times —
  `reader/ui/ReaderScreen.kt:471`, `settings/ui/SettingsScreen.kt:509`,
  `library/ui/LibraryScreen.kt:734` — and the copies have drifted (the library
  copy drops `FontWeight.SemiBold`). The undo bar is duplicated
  (`LibraryScreen.kt:414,531`); there are 5 separate confirm dialogs.
- Direct: generic rows (`ChoiceRow`, `OptionChip`, `SwitchRow`,
  `SectionHeading`) are private to `SettingsScreen.kt:527-694`.
- Direct: `ui/theme/Theme.kt:17-30` defines colour schemes only; there is no
  spacing, shape or type token and no `dimens.xml`; the four big screens use
  72, 40, 34 and 13 raw `N.dp` literals.
- Direct: domain adapters `CatalogBooks`/`CatalogPositions` (~150 lines, incl.
  account position publishing) live in `reader/ui/ReaderRoute.kt:360-530`.
- Counterexample (Direct): `ui/LayoutWidth.kt:9-14` centralises the one
  breakpoint.

### Cause

Hypothesis: each screen grew feature by feature and shared UI was never
extracted.

### Effect

Copying a screen copies its private primitives; the Reader client gets no
component or token layer to build on, and fixes to one copy (as with the
banner) miss the others.

### Recommended outcome

A shared component and token layer (banner, undo bar, confirm dialog, touch
target, spacing, setting rows) that all screens use; screen files split by
section, with account sections outside the library screen file and reader
adapters outside `ui/`.

### Outcome boundary

In: `:app` UI primitives, theme tokens, screen file structure, adapter
placement. Out: any visual change (goldens must stay byte-identical), UX
decisions, navigation (F005).

### Cohesion rationale

Extracting primitives and splitting screens are one refactor: the split
exposes the duplicates and the shared layer is where they go.

### Outcome acceptance

- Each primitive listed above is defined once and used from every screen that
  needs it; no screen declares its own `TouchTarget`.
- Spacing and sizes used by more than one screen come from named tokens.
- No screen file exceeds a size the plan sets, and account UI is not in the
  library screen file.
- `verifyRoborazziDebug` passes with unchanged goldens.

### Planning inputs

- The library-copy banner drift means one golden may legitimately change when
  the primitive is unified; planning must decide which rendering is correct and
  call it out rather than hide it.
- `account/ui/ReaderAccountScreen.kt` shares these primitives.
- Size threshold is a planning choice.

### Limitations

The account-section line share of `LibraryScreen.kt` is estimated from line
ranges.

### Decision rationale

Pending.

## A197-F007 — `LibraryRepository` owns about nine unrelated concerns

- Decision: Candidate
- Confidence: Medium
- Review: Pending
- Planning readiness: Pending
- Cause status: Hypothesis
- Expected implementation repositories: `cedagova/fastReader`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

A repository an example app is built around has one reason to change; distinct
concerns sit behind narrow interfaces.

### Condition and evidence

- Direct: `app/src/main/java/com/cedagova/fastreader/library/LibraryRepository.kt`
  (764 lines, one concrete class, :36-53) owns catalog load and mutation,
  ingestion, reader settings (:111, :284-289), reading-position coalescing
  (:74, :264-270), the undo window (:507-560), a front-matter flag (:301), book
  byte access (:377-402), account copies (:417-461) and SAF grant release
  (:573-600). Settings are persisted inside `catalog.json`.
- Counterexample (Direct): its dependencies are injected (`CatalogStore`,
  `DocumentGateway`, dispatcher, clock, `ThemeMirror`) and
  `LibraryRepositoryTest` covers it.

### Cause

Hypothesis: the catalog file was the one persisted store, so every new
persisted concern was added to the class that owns it.

### Effect

It is the class a Reader client would copy first, and it is too broad to reuse
or change safely; F003's `AccountBookCopies` is coupled to it.

### Recommended outcome

Settings, position durability and book-byte access each have a distinct owner
behind a narrow interface; the catalog store remains the single persisted
source of truth.

### Outcome boundary

In: `LibraryRepository` responsibilities and their call sites. Out: the
persisted `catalog.json` format (no migration), behavior changes, account
logic placement (F003).

### Cohesion rationale

One class, one decomposition; each extracted concern is small and they share
the same store.

### Outcome acceptance

- Settings, positions and book bytes are reachable through separate narrow
  types; `LibraryRepository` no longer exposes them.
- `catalog.json` is byte-compatible (existing migration tests pass unchanged).
- All existing tests pass.

### Planning inputs

- Whether settings should keep sharing `catalog.json` is a design choice for
  planning; the audit only requires no format change.
- Sequence after or with F005 (wiring) to avoid touching call sites twice.

### Limitations

Medium confidence: the class is well tested and injected, so the cost is
readability and reuse, not a demonstrated defect.

### Decision rationale

Pending.

## A197-F008 — Build settings are copied into every module and static analysis is thin

- Decision: Candidate
- Confidence: High
- Review: Pending
- Planning readiness: Pending
- Cause status: Hypothesis
- Expected implementation repositories: `cedagova/fastReader`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

Shared build settings live in one place, every module gets the same analysis
gates, and the build files contain nothing stale a copier would inherit.

### Condition and evidence

- Direct: `compileSdk = 37`, `minSdk = 26`, Java 17 compile options and JVM
  target 17 are set separately in `app/build.gradle.kts:65,69,117-118,185`,
  `reader-auth/build.gradle.kts:19,22,29-30,46` and
  `reader-library/build.gradle.kts:26,29,33-34,40`. There is no `buildSrc`,
  `build-logic` or convention plugin.
- Direct: `gradle/libs.versions.toml:11` says compileSdk lives in
  `app/build.gradle.kts` (stale); `libs.versions.toml:72` declares a
  `kotlin-android` plugin no build uses and that the guide says AGP 9 refuses
  (`docs/agent-first-development.md:155-158`).
- Direct: only `:app` has a `lint {}` block (`app/build.gradle.kts:163-167`);
  the libraries run defaults. No detekt, ktlint, spotless or `.editorconfig`.
- Direct: no dependency-update automation (no `.github/dependabot.yml`, no
  renovate config); staleness shows only as lint warnings (6
  `NewerVersionAvailable`, 3 `AndroidGradlePluginVersion`).

### Cause

Hypothesis: modules were added one at a time by copying `:app`'s settings.

### Effect

The Reader client (and the F004 module) would copy three already-drifting
configs; libraries can regress on lint rules the app enforces; formatting and
style are unenforced, so an example codebase cannot show its own conventions.

### Recommended outcome

One convention source for SDK levels, JVM target, lint and test settings used
by every module; a formatter/static-analysis gate the example can demonstrate;
no stale or dead build declarations.

### Outcome boundary

In: build scripts, version catalog, lint/format configuration. Out: dependency
upgrades themselves, CI job structure (F009), library publishing (F010).

### Cohesion rationale

All items are "the build conventions every module shares"; one convention
source is where the gates get applied.

### Outcome acceptance

- SDK levels and JVM target are declared once and applied to all modules.
- Every module runs the same lint configuration and a formatter/static check
  in the normal verification task.
- The catalog has no unused plugin alias and no stale location comment.
- Build output (APK) is unchanged.

### Planning inputs

- Whether to add dependency-update automation is a small owner preference;
  record it, do not assume it.
- Must land before or with F004 if that adds a module.
- A formatter's first run will reformat files; planning should isolate that
  diff.

### Limitations

No cold build was measured; build-time effects are not claimed.

### Decision rationale

Pending.

## A197-F009 — CI never builds what ships, and the release script runs no gates and hides a failed query

- Decision: Candidate
- Confidence: High
- Review: Pending
- Planning readiness: Pending
- Cause status: Confirmed
- Expected implementation repositories: `cedagova/fastReader`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

The artifact that ships (the R8-minified release APK) and every compiled test
source set are built by CI on every change, and the release path fails loud
rather than skipping a guard.

### Condition and evidence

- Direct: `.github/workflows/checks.yml:44,48,52` runs only
  `testDebugUnitTest`, `verifyRoborazziDebug` and `lint`. No `assembleRelease`
  or R8 step, no `androidTest` compile. The minified build is exercised only by
  `scripts/release.sh:132`.
- Direct: the workflow triggers on both `push:` and `pull_request:` (:13-15)
  with no `concurrency` group, so each PR commit runs twice (visible in the run
  list). Actions are pinned to major tags, not SHAs.
- Direct: `scripts/release.sh` runs no tests or lint and does not check CI
  status; its forward-only guard queries releases with `2>/dev/null … || true`
  (:119-120), so a failed `gh` query yields an empty `HIGHEST` and the guard is
  silently skipped (:121).
- Direct: `app/build.gradle.kts` enables `isMinifyEnabled` without
  `isShrinkResources` (lint `NotShrinkingResources`), and
  `app/proguard-rules.pro` is empty.
- Counterexample (Direct): CI holds no secrets, uses
  `permissions: contents: read`, and the build refuses to package unsigned
  (`app/build.gradle.kts:227-236`).

### Cause

Confirmed by the files: CI was set up for the debug gates and the release path
was kept as a local script.

### Effect

A keep-rule regression in either library (see F010) or an `androidTest`
compile break surfaces only at release time, on the owner's machine. A
transient GitHub failure can let a release skip the forward-only version guard
(violates the fail-loud rule). CI minutes are doubled.

### Recommended outcome

CI builds the minified release variant (unsigned is fine) and compiles
instrumented tests on every change, runs once per change, and pins its
actions; the release script fails when a state query fails and refuses to
publish from a commit whose gates have not passed.

### Outcome boundary

In: `checks.yml`, `scripts/release.sh`, release shrink settings. Out: running
instrumented tests on an emulator in CI, signing-key handling, Play
distribution, library keep rules themselves (F010).

### Cohesion rationale

One property — "what CI and the release path prove about the shipped
artifact" — across the two files that define it.

### Outcome acceptance

- A PR that breaks R8 (for example, removing a needed keep rule) or breaks
  `androidTest` compilation fails CI.
- Each PR commit triggers one checks run.
- With `gh` failing, `release.sh` stops with an error instead of skipping the
  version guard; a test in `scripts/test-release-publish.sh` or equivalent
  proves it.
- Release-script proof (the existing release test) still passes.

### Planning inputs

- Whether to enable resource shrinking changes the APK; planning must check
  goldens and the release-script manifest checks.
- The release test `scripts/test-release-publish.sh` exists and is the place
  to prove the fail-loud path.

### Limitations

`scripts/test-release-publish.sh` and most of `docs/release.md` were not read
in full; whether `setup-gradle@v4` validates the wrapper was not verified.

### Decision rationale

Pending.

## A197-F010 — The libraries have no recorded consumption mode, version or self-sufficient shrink rules

- Decision: Candidate
- Confidence: High
- Review: Pending
- Planning readiness: Pending
- Cause status: Confirmed
- Expected implementation repositories: `cedagova/fastReader`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

A library another repository will consume has a recorded consumption mode, its
own version and change record, and everything it needs to survive the
consumer's R8 build.

### Condition and evidence

- Direct: no `maven-publish`, group or version for either library; no library
  changelog; `version.properties` is read only by `:app`
  (`app/build.gradle.kts:7-15`).
- Direct: both libraries depend on root catalog aliases and are consumed as
  `project(":reader-auth")`/`project(":reader-library")`
  (`app/build.gradle.kts:193,196`).
- Direct: `:reader-auth` ships `consumer-rules.pro`
  (`reader-auth/build.gradle.kts:25`); `:reader-library` has no
  `consumerProguardFiles` although it uses `@Serializable` extensively.
  Inference: it survives R8 today only because `:reader-auth`'s global
  serialization rules reach it through the `api` dependency.
- Direct: no reader-android repository exists yet (`~/chunipers` has none).

### Cause

Confirmed: there was only ever one consumer, in the same build.

### Effect

The Reader client can only copy the sources or add a submodule, and cannot pin
a library version or see what changed between two copies. `:reader-library`
would break under R8 if `:reader-auth`'s rules ever narrowed.

### Recommended outcome

The owner-chosen consumption mode (source copy, submodule/included build, or
published artifact) is recorded; each library has its own version and change
record and its own keep rules; the chosen mode is demonstrated to work outside
this build.

### Outcome boundary

In: library versioning, change records, keep rules, the consumption mechanism
and its proof. Out: the Reader client repository itself, public-surface
checks (F001), CI release build (F009, which proves the keep rules).

### Cohesion rationale

All items answer one question — "how does another repository take these
libraries and stay current" — and the mode decides the rest.

### Outcome acceptance

- A written, owner-approved consumption mode.
- Each library carries a version and a change record updated with its changes.
- `:reader-library` declares keep rules for its own serialized types and a
  minified build that consumes it without relying on `:reader-auth`'s rules
  succeeds.
- A throwaway consumer outside `:app` (or the chosen mechanism's own check)
  builds against the libraries using the chosen mode.

### Planning inputs

- Material owner decision: the consumption mode. It also scopes F003
  (library vs reference) and F002 (how fixtures are shipped).
- A published artifact needs a registry and credentials: secrets rules apply.
- Sequence after F001 (freeze the surface before versioning it).

### Limitations

The R8 dependency of `:reader-library` on `:reader-auth`'s rules is inferred
from configuration; no isolated minified build was run.

### Decision rationale

Pending.

## A197-F011 — The reader-api contract gate covers `:reader-library` only

- Decision: Candidate
- Confidence: High
- Review: Pending
- Planning readiness: Pending
- Cause status: Confirmed
- Expected implementation repositories: `cedagova/fastReader`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

Every reader-api request and response shape either library uses is checked
against one pinned contract document with one clear owner, and every contract
citation is pinned to a commit.

### Condition and evidence

- Direct: `reader-library/contracts/PINNED.md` pins
  `Chunipers/reader-api@909174aff6a380514da7b81263d69a4e653cfe76`; the recorded
  sha256 matches the file. `ReaderLibraryContractTest` checks models against
  schemas, routes against paths, negative cases and completeness.
- Direct: its completeness check scans `library/model/` only
  (`reader-library/src/test/.../ReaderLibraryContractTest.kt:661`). That is
  sufficient inside `:reader-library` — the only other `@Serializable` types
  there (`sync/AccountLibraryDocument.kt`, `imports/PublicationImportRecord.kt`)
  are local persistence records — but it cannot see `:reader-auth`.
- Direct: `:reader-auth` has no OpenAPI gate (`rg -n 'openapi|contracts'
  reader-auth` → no match in sources) although its routes
  `/v1/reader/pre-auth`, `/capabilities`, `/profile`
  (`reader-auth/.../api/ReaderApiClient.kt:265-267`) are in the pinned
  document; `PreAuthDocument` and `ReaderProfileUpdate` are never compared.
- Direct: `reader-auth/CONTRACT.md:304-309` cites reader-api#489 without a
  commit pin.
- Direct: updating the pin is a manual three-step procedure
  (`PINNED.md` "Updating the pin").

### Cause

Confirmed: the contract gate was built with `:reader-library` (#112, decision
P1); `:reader-auth` predates it.

### Effect

A reader-api change to pre-auth, capabilities or profile shapes reaches the
Reader client without any failing test, while the same kind of change to
library shapes fails loudly. Two contract owners in one repository is a trap
for the new client.

### Recommended outcome

One pinned contract document gates every shape both libraries send or read,
with one documented owner and update procedure; `CONTRACT.md` cites pinned
commits.

### Outcome boundary

In: the contract location/ownership, extending the drift gate to
`:reader-auth` shapes, citation pins. Out: switching to generated clients
(owner decision P1 stands unless the owner reopens it), changing any shape.

### Cohesion rationale

One gate, one document, both libraries.

### Outcome acceptance

- Changing a field of `PreAuthDocument` or `ReaderProfileUpdate` (or its schema
  in the pinned document) fails a test, as it does for library models today.
- There is one pinned document and one written update procedure used by both
  libraries.
- `CONTRACT.md` has no unpinned reader-api citation.

### Planning inputs

- Where the shared contract lives interacts with F010 (a consumer needs it
  too).
- Whether `909174af` still matches reader-api stage is not checked by this
  audit.

### Limitations

The Chunipers repositories were excluded; current reader-api drift is unknown.

### Decision rationale

Pending.

## A197-F012 — There is no tracked map of the codebase for a new client, and several docs contradict the code

- Decision: Candidate
- Confidence: High
- Review: Pending
- Planning readiness: Pending
- Cause status: Confirmed
- Expected implementation repositories: `cedagova/fastReader`, `cedagova/.github-infra`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

A person or agent starting the Reader client from this repository finds a
tracked, current architecture overview (module map, dependency direction, what
to reuse versus copy), docs that agree with the code, and no historical bulk
or stale status mixed into the template.

### Condition and evidence

- Direct: no architecture overview, module map or "start a new client" guide.
  Module facts sit inside one table in `docs/agent-first-development.md:113-117`
  whose cells run to hundreds of words.
- Direct: `AGENTS.md` and `CLAUDE.md` are gitignored (`.gitignore:54-55`); the
  local `AGENTS.md` still says single `:app`, Kotlin 2.1.21, AGP 8.11.1,
  compileSdk 36, while the build has AGP 9.4.0, Kotlin 2.4.20
  (`libs.versions.toml:14-15`) and compileSdk 37.
- Direct contradictions:
  - `reader-library/README.md:48` ("It defines no exception type") vs
    `AssetDownloadException` (`AssetDownloadClient.kt:225`),
    `PublicationTransferException` (`PublicationTransferClient.kt:351`),
    `ReservedHostRecordKeyException` (`AccountSyncEngine.kt:135`).
  - `README.md:226` ("exactly six typed" operations) vs 12 declared in
    `ReaderLibraryOperations`.
  - `reader-library/build.gradle.kts:9-10` forbids FastReader naming, but
    library doc comments name it (`AccountLibraryDocument.kt:25-34,46-56`,
    `AccountSyncEngine.kt:146`, `Enums.kt:13`, `ReaderLibraryJson.kt:11`).
  - `reader-auth/README.md:10-11` says nothing is pinned inline; SDK levels are
    (F008).
- Direct: `:reader-library` has no host-requirements section (foreground
  `requestSync`, session bridge, backup-excluded storage), while
  `reader-auth/CONTRACT.md:265-295` has one.
- Direct: `docs/evidence` is 39 directories, 289 files, ~32 MB — about 61% of
  the tracked tree. `docs/plans/cedagova-fastReader-1/plan.md:5` still says
  "Ready for implementation" and `definition.md:6` "Ready for planning".
- Direct: `bin/gh-personal` differs from `.github-infra/bin/gh-personal`: two
  copies of the identity tooling.

### Cause

Confirmed: docs were written per issue and never consolidated; the agent
guides were deliberately made personal/untracked.

### Effect

A new client — human or agent — starts from wrong versions, cannot see the
module boundaries without reading code, and trusts library docs that are wrong
in checkable ways. Cloning or copying the repository drags ~32 MB of
historical evidence along.

### Recommended outcome

A tracked architecture and "starting a new client from this" document; library
READMEs that match the code, including a `:reader-library` host-requirements
section; historical evidence and stale statuses separated from the template
content; one home for identity tooling.

### Outcome boundary

In: tracked docs, module READMEs, doc comments naming FastReader in libraries,
the evidence/plan layout, identity-tooling location. Out: rewriting history to
shrink the packed repository; the personal untracked guides themselves (they
may stay personal, but the tracked doc must not depend on them); docs for
structural changes not yet made (each structural outcome updates its own docs).

### Cohesion rationale

All items are the onboarding surface a new client reads first; one pass over
it can check every claim against code.

### Outcome acceptance

- A tracked document names every module, its dependency direction, what the
  Reader client should reuse versus copy, and the host obligations of each
  library.
- Every contradiction listed above is resolved; library doc comments contain
  no FastReader naming.
- Historical evidence and stale plan statuses are either moved out of the
  template path or clearly marked historical.
- One copy of the identity tooling, or a documented reason for two.

### Planning inputs

- The identity-tooling home may be `cedagova/.github-infra`; that is why it is
  in the expected repositories.
- Sequence last, or re-check after F001–F011 land, because they change what
  the map says.
- Removing `docs/evidence` from `main` does not shrink history; whether that
  matters is an owner call.

### Limitations

Doc accuracy was sampled, not exhaustively checked; `docs/release.md` was read
by grep only.

### Decision rationale

Pending.

## A197-F013 — `AccountSyncEngine.kt` is one 1,431-line file with about ten responsibilities

- Decision: Candidate
- Confidence: Medium
- Review: Pending
- Planning readiness: Pending
- Cause status: Hypothesis
- Expected implementation repositories: `cedagova/fastReader`
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

The core engine of a reusable library can be read, and its parts tested,
separately.

### Condition and evidence

- Direct: `reader-library/src/main/kotlin/com/cedagova/reader/library/sync/AccountSyncEngine.kt`
  is 1,431 lines. By line range: host-facing interfaces and an exception
  (47-257), action API and host records (391-518), locking and re-entrancy
  guard (519-575), session lifecycle and account switching (575-672), sync
  orchestration and error mapping (673-765), drain/bootstrap/delta reads
  (766-973), outbox and optimistic intents (974-1199), adopting canonical state
  and freshness (1200-1402), state publication (1403+).
- Direct: one 2,267-line test file mirrors it.
- Counterexample (Inference): the single-writer lock is a real reason to keep
  the state machine together; #169 fixed ordering bugs here, so a careless
  split is risky.

### Cause

Hypothesis: the engine moved wholesale from `:app` in #147 and grew with #169's
fixes.

### Effect

The file most likely to be studied by the Reader client is the hardest to
read. The cost is comprehension and review, not a demonstrated defect.

### Recommended outcome

The engine's responsibilities are readable and testable separately (host
contracts, outbox, canonical-state adoption, orchestration) while the
single-writer guarantee, public API and existing tests stay unchanged.

### Outcome boundary

In: file and type structure inside `sync/`. Out: behavior, locking semantics,
public API (F001), persistence format.

### Cohesion rationale

One file, one decomposition, guarded by one test suite.

### Outcome acceptance

- No single engine source file carries more than one of the responsibilities
  listed above, except the orchestrating state machine.
- The public API and all 188 `:reader-library` tests are unchanged and green,
  including the real-threads queue test.

### Planning inputs

- Concurrency-sensitive: #169's ordering and account-switch guarantees must be
  preserved; review as concurrency risk.
- Lowest priority of this audit; sequence after F001.

### Limitations

Medium confidence: this is a readability judgment; the tests are strong.

### Decision rationale

Pending.

## Cross-finding analysis

### Duplicates and interactions

- F001 and F002 both touch the libraries' public surface: F001 bounds what is
  visible, F002 adds library-owned seams and fixtures. `createForTests` belongs
  to F002.
- F003 and F007 meet at `AccountBookCopies`, which is coupled to
  `LibraryRepository`.
- F005 and F006 both touch Routes; F005 owns wiring and navigation, F006 owns
  screen internals and primitives.
- F009 proves F010's keep rules in CI; F010 owns the rules.
- F012 records the result of every other finding; each structural outcome
  should update its own docs, and F012 covers what exists today.

### Dependencies

- F010's consumption-mode decision scopes F003 (library vs reference) and F002
  (how fixtures ship).
- F001 before F010 and F013 (freeze the surface before versioning or
  restructuring).
- F008 before or with F004 (a new module multiplies duplicated config).
- F012 last, or re-checked after the others.

### Residual unknowns

- Whether the Reader client keeps Supabase as its identity provider (scopes
  F001's wrapping choice).
- Whether the Reader client offers RSVP (scopes `timing/` in F004).
- Whether `909174af` still matches reader-api stage.
- Cold-build time and the flake rate of the real-threads queue test
  (`AccountSyncEngineQueueTest.kt:226-260`) were not measured.

## What is already good and should be kept as the example

- Libraries never import `:app`; `:reader-library`'s packages are acyclic;
  `:reader-library` sends only named operations, never a generic
  `call(path, body)`.
- The pinned OpenAPI contract with a sha256 drift test declared as a Gradle
  input.
- Injected clock, retry waiter, request ids, dispatchers, idempotency keys and
  storage throughout; the real `ReaderAuthClient` tested over a mock engine;
  deterministic concurrency tests (#174).
- Route / stateless Screen split with pure state builders, rendered in
  Roborazzi; byte-reproducible goldens gated in CI.
- Android-free engines; versioned catalog migrations; deliberate
  process-death rules.
- CI with no secrets and least privilege; the build refuses to package
  unsigned; one version source; the quirks log in
  `docs/agent-first-development.md`.
- The known reader-auth "flaky" refresh test is now barrier-driven
  (`ReaderApiPolicyTest.kt:133`, `TestHarness.kt:180`, #174).

## Completion gate

- [ ] The decision owner approved the charter.
- [x] Every target has a full baseline commit SHA.
- [x] The coverage inventory accounts for every in-scope surface.
- [x] Every claim has proportionate, reproducible evidence.
- [ ] The independent review is complete.
- [ ] Every review challenge and gap is reconciled or named as unresolved.
- [ ] Every finding is accepted, rejected, or deferred.
- [x] Every finding records the evidence-backed repositories expected to change if its recommendation is accepted.
- [ ] Every accepted finding has `Planning readiness: Ready` from the independent reviewer.
- [ ] Every accepted finding links a planning-ready outcome issue.
- [ ] Every outcome issue is a native child of the audit's same-repository outcome umbrella.
- [x] `summary.md` answers the original question.
- [ ] The Decision-ready semantic anchor has an approved independent review verdict.
- [ ] Any post-review completion delta is limited to mechanical owner decisions and handoff fields.
- [ ] Structural validation passes.
- [ ] The dossier pull request is complete and ready to coordinate downstream delivery.
