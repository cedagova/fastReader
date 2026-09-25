# Architecture, and starting a new client from it

This repository is two things at once:

- **FastReader** (`:app`), an Android RSVP reader for EPUB files;
- **four reusable Reader libraries** that FastReader hosts, written to be
  copied into the Reader Android client unchanged.

A new client **copies the libraries** and **uses the app shell as a
pattern**. This page is the map: every module, which way they depend, what to
copy, what to imitate, and what each library needs from its host. It is the
tracked source of these facts; the personal agent guides (`AGENTS.md`,
`CLAUDE.md`) are untracked and nothing here depends on them.

## Modules

| Module | Kind | Package | Depends on | What it is | Read |
| --- | --- | --- | --- | --- | --- |
| `:reader-auth` | Android library | `com.cedagova.reader.auth` | no project module | Sign-in, the Keystore-encrypted session store, single-flight refresh, and the one authenticated reader-api client with its 401/403/429/502 policy. Declares `INTERNET`. | [README](../reader-auth/README.md), [CONTRACT](../reader-auth/CONTRACT.md) |
| `:reader-library` | Android library | `com.cedagova.reader.library` | `:reader-auth` (`api`) | Twelve typed reader-api operations (library, progress, sync, capabilities, publication import, asset download grant), the two session-less transfer clients, and the account sync engine. | [README](../reader-library/README.md) |
| `:reader-account` | Android library | `com.cedagova.reader.account` | `:reader-library` (`api`) | The account pipeline a reader app puts in front of a reader: sign-in state, shelf, verified private copies, downloads, imports, one error mapping, and `ReaderAccountGraph`, the one assembly call. | [README](../reader-account/README.md) |
| `:reader-engine` | Kotlin/JVM library (no Android) | `com.cedagova.reader.engine.{epub,content,timing}` | no project module | EPUB archive and inspection → one stable token stream → RSVP timing. | [README](../reader-engine/README.md) |
| `:app` | Android application | `com.cedagova.fastreader` | all four (`implementation`) | FastReader: the device library (`catalog.json`), the reader UI, settings, crash reports, and the host side of every library. | [app-shell.md](app-shell.md) |

`build-logic/` is not a module but an included build: the convention plugins
(`conventions.android.application`, `conventions.android.library`,
`conventions.kotlin.library`, `conventions.root`) that apply SDK levels, the
JVM target, lint, test settings, the ktlint formatter and — for the libraries
— explicit-API mode with a committed `api/<module>.api` dump. Versions and SDK
levels live once in `gradle/libs.versions.toml`.

## Dependency direction

```text
:app (FastReader, the host)
 ├──► :reader-account ──api──► :reader-library ──api──► :reader-auth
 ├──► :reader-library
 ├──► :reader-auth
 └──► :reader-engine          (:reader-account's unit tests also use it)
```

- **Arrows point one way.** No library depends on `:app` or on any
  `com.cedagova.fastreader` symbol, and each library's build file says so at
  the top.
- **The account chain is layered:** `:reader-account` → `:reader-library` →
  `:reader-auth`. Each exposes the one below as `api`, because its public
  types are built from the lower module's.
- **`:reader-engine` stands alone.** It depends on no project module, and none
  of the account libraries depend on it in production. `:reader-account`'s
  unit tests use it (`testImplementation`) to open a real EPUB.
- **Inside `:app`** packages form an acyclic graph with the root package
  (the composition root) on top; see [app-shell.md](app-shell.md#packages-one-direction-no-cycles).

What holds these directions: Gradle refuses a project cycle; the library
build files declare only the dependencies above; `checkKotlinAbi` fails on any
public-surface change the committed dump does not show; and
`scripts/library-copy-check.sh` builds the libraries with no `:app` present.

## What to copy, what to use as a pattern

### Copy: the libraries, at a tag

Take the four library modules, `build-logic/`, `.editorconfig` and the listed
catalog entries as a **source copy at a tagged library version**. The exact
list, the steps and the check live in one place:
[library-consumption.md](library-consumption.md). Do not copy from this page;
that list is what `scripts/library-copy-check.sh` builds and CI runs.

### Pattern: the app shell, re-implemented in the client

These are FastReader's own code. Read them and write the client's version;
do not copy them as a unit.

| Pattern | Where | Guide |
| --- | --- | --- |
| One composition root, no DI framework | `AppGraph.kt`, `FastReaderApplication.kt`, `Context.appGraph` | [app-shell.md](app-shell.md#one-composition-root) |
| One state-holder convention: Store → Route → UiState → Screen | `<feature>/ui/*Route.kt`, `*UiState.kt`, `*Screen.kt` | [app-shell.md](app-shell.md#one-state-holder-convention) |
| One typed, saveable navigation model | `Navigation.kt` (`Destination`, `BackStack`) | [app-shell.md](app-shell.md#one-typed-navigation-model) |
| Acyclic package graph, checked by a test | `PackageGraphTest` | [app-shell.md](app-shell.md#packages-one-direction-no-cycles) |
| Shared UI primitives and spacing/size tokens | `ui/theme/Dimens.kt`, `ui/components/` | [app-shell.md](app-shell.md#shared-ui-primitives-and-tokens) |
| The `:reader-account` host seams | `account/library/AccountCatalogSeams.kt`, `DeviceBookPublicationSource.kt`, `AccountResumeOffers.kt` | [below](#reader-account) |
| Host-obligation tests | `ReaderAccountManifestTest`, `ReaderAccountConfigTest` (`app/src/test/.../account/`) | [below](#reader-auth) |
| Backup exclusion rules | `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml` | [reader-auth README](../reader-auth/README.md#what-every-host-must-declare-for-itself) |
| Hosted gate and release script | `.github/workflows/checks.yml`, `scripts/release.sh` | [release.md](release.md), [agent-first-development.md](agent-first-development.md) |

### Leave behind

FastReader's product code stays here: the device library and its
`catalog.json` migrations (`library/`, `library/store/`), the reader session
and UI (`reader/`), settings, crash reports and the privacy statement. So do
the [historical records](#historical-records).

## Host obligations, library by library

What a host must do that the library cannot do for it. Each library's README
has the detail; this is the checklist.

### Every library

- **Build wiring.** `includeBuild("build-logic")` in `pluginManagement`,
  the listed catalog lines, and the plugins declared `apply false` in the root
  build file ([library-consumption.md](library-consumption.md#copying-and-updating-into-a-host)).
- **`gradle.properties`:** `android.useAndroidX=true` and
  `android.experimental.enableTestFixturesKotlinSupport=true`. AGP still
  gates Kotlin in `src/testFixtures` behind that flag; without it the
  libraries' test fixtures do not compile.
- **Keep rules come with the module.** Each Android library ships
  `consumer-rules.pro` (wired with `consumerProguardFiles`); `:reader-engine`
  ships its rules inside its jar (`src/main/resources/META-INF/proguard/`). A
  shrinking host adds no rule for them
  ([library-consumption.md](library-consumption.md#keep-rules)).
- **Test against the fixtures, not your own fakes.** Each module's
  `src/testFixtures/` is its one test-double surface:
  `testImplementation(testFixtures(project(":reader-…")))`.

### `:reader-auth`

The full list is `reader-auth/CONTRACT.md`, "Host requirements":

1. **Configuration.** Hand the module one `ReaderAuthConfig` read from
   untracked values; with any value absent, build and test normally and show
   "not configured" instead of calling anything.
2. **Backup exclusion.** `allowBackup="false"` plus both rule files excluding
   all nine domains, in both `cloud-backup` and `device-transfer`.
3. **No cleartext in release.** Only a debug-only network security config may
   allow the emulator loopback `10.0.2.2`.
4. **Its own application id.**
5. **The foreground hook.** Call `onForeground()` when the process returns to
   the foreground; the module runs no timer. (`ReaderAccountGraph.onForeground`
   does this for a host that uses `:reader-account`.)

Seams a host may substitute: `ReaderAuthOperations` (the client) and
`ReaderApiOperations` (its reader-api half). Session storage is **not** a
seam.

### `:reader-library`

See [its README, "Host requirements"](../reader-library/README.md#host-requirements):

- **Drive the sync engine from the foreground.** It has no scheduler:
  call `requestSync(AccountSyncTrigger.FOREGROUND)` when the process returns
  to the foreground.
- **Bridge the session.** Hand the engine a `Flow<AccountSession>` derived
  from `:reader-auth`'s session state.
- **Store documents where backups cannot reach.** `FileAccountLibraryStores`
  writes under the directory the host gives it, which must be private and
  backup-excluded (it holds the account's shelf and queued changes).
- **One engine per account directory**, and host-record transforms that are
  pure and never call back into the engine.

Seams: `ReaderLibraryOperations`, `ReaderLibraryGateway`,
`PublicationImportGateway`, `AssetDownloadGateway`.

### `:reader-account`

- **Make one `ReaderAccountGraph(...)` call per process**, and call its
  `onForeground()` / `onBackground()` from the process lifecycle observer.
  This covers the `:reader-auth` foreground hook and the `:reader-library`
  foreground sync, session bridge and store location above.
- **Implement four host seams:**

  | Seam | The host decides | FastReader's implementation |
  | --- | --- | --- |
  | `AccountCopyCatalog` | What makes a verified, placed copy readable | `LibraryAccountCopyCatalog` over `LibraryRepository` |
  | `DevicePublicationSources` | The bytes behind a device book, for an import | `DeviceBookSources` |
  | `DeviceBookIdentity` | Its device-book id for a content SHA-256, and back | `CatalogBookIdentity` (`sha256:<hex>`) |
  | `ResumeOfferRecords` | Where an answered resume offer is noted | `AccountResumeOffers` |

- **Give it a private, backup-excluded `filesDir`.** Copies go to
  `filesDir/account-copies/`, documents to `filesDir/account-library/`.
- **Choose the undo window** for an account removal (`undoWindowMs`).

FastReader makes the call in `AppGraph` (`AppGraph.readerAccount`), which
`FastReaderApplication.onCreate` builds.

### `:reader-engine`

- **Supply the bytes and the thread.** The host hands `EpubContentPipeline`
  an `EpubByteSource` (or a `FileEpubByteSource`) and the dispatcher the parse
  runs on.
- **Own persistence.** The engine keeps no state; the host stores positions
  and must treat a change of `ContentPipelineVersion.CURRENT` as "stored token
  indices may have moved".
- **Compose note.** The engine has no Compose compiler, so Compose infers its
  types as unstable. A host's UI state that holds them (in FastReader, the
  reader's state types in `reader/ui/ReaderUiState.kt`, which carry
  `BookContent`, tokens and a `RemainingTimeIndex`) cannot skip recomposition
  on equal inputs. Nothing visible depends on it
  today; the remedy, if profiling ever shows it, is a Compose stability
  configuration file in the host (`composeCompiler {
  stabilityConfigurationFiles }`) listing the engine's immutable types — not a
  Compose dependency in the engine.

## The reader-api contract pin

- **What:** `reader-auth/contracts/reader-api.openapi.json`, a byte-for-byte
  copy of the published document at one reader-api commit, with its sha256
  beside it.
- **Gate:** `ReaderAuthContractTest` and `ReaderLibraryContractTest`, through
  one shared checker, recompute the digest and check every field, type, enum
  member and required flag either library uses.
- **Update:** the one procedure in
  [reader-auth/contracts/PINNED.md](../reader-auth/contracts/PINNED.md#updating-the-pin).
  Drift is a proposal to Chunipers, never a local workaround.

## Where errors are mapped

- **`:reader-account` `AccountErrors.kt`** is the one mapping from a
  `ReaderAuthException` branch to what the sign-in surface, a download row or
  an import row shows.
- **`:reader-library` `AccountLibraryState.kt` (`toSyncError`)** maps the
  same exception into the sync engine's `AccountSyncError`. It stays in
  `:reader-library` because the engine lives there, and moving it up would
  make `:reader-library` depend on `:reader-account` — a cycle.

## Library versions

All four libraries are at **`0.1.0`, untagged**. The first
`<module>/v0.1.0` tags are cut once the #211 effort reaches `main`; until
then a change amends the `0.1.0` entry of the module's `CHANGELOG.md` rather
than bumping. After the tags, the rules in
[library-consumption.md](library-consumption.md#versions-and-change-records)
apply.

## Starting a new client: the order

1. Copy the set in [library-consumption.md](library-consumption.md) at one
   tag and run its steps.
2. Meet [every host obligation above](#host-obligations-library-by-library);
   copy the obligation tests' idea (`ReaderAccountManifestTest`,
   `ReaderAccountConfigTest`) so each one is pinned.
3. Build the composition root the way [app-shell.md](app-shell.md) describes,
   with one `ReaderAccountGraph(...)` call over the client's own four seams.
4. Write screens with the Store → Route → UiState → Screen convention and one
   primitives/tokens layer.
5. Run `scripts/library-copy-check.sh` here before cutting a tag you copy.

## Where the docs are

**Current** — kept true to the code:

| Doc | What |
| --- | --- |
| [README.md](../README.md) | What FastReader is, install, the privacy statement |
| This page | Module map and new-client guide |
| [app-shell.md](app-shell.md) | `:app`'s composition, state, navigation, packages, UI layer |
| [library-consumption.md](library-consumption.md) | The copy set and how a host takes the libraries |
| Module READMEs, `reader-auth/CONTRACT.md`, `reader-auth/contracts/PINNED.md` | Each library |
| [agent-first-development.md](agent-first-development.md) | How the project is built and verified |
| [release.md](release.md) | The release procedure |
| [privacy-statement.md](privacy-statement.md) | What each privacy sentence rests on |
| `docs/release-notes/` | One file per shipped version, written by the release procedure |

### Historical records

Kept for provenance and linked from issues and PRs; **not** current and not
part of the copy set. Each folder says so in its own `README.md`.

| Folder | What |
| --- | --- |
| `docs/evidence/` | Per-issue proof (screenshots, transcripts, APK checks), ~32 MB. |
| `docs/plans/`, `docs/product-definitions/` | The first definition and plan (#1), delivered. |
| `docs/audits/` | Audit A83's dossier (Android client auth). |

Removing them from `main` would not shrink the repository's history, so they
stay in place, marked.
