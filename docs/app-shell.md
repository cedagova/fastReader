# The app shell

How `:app` assembles its objects, holds screen state, navigates, and which
way its packages depend. These are the conventions to follow when adding a
screen or copying the shell into a new client (A197-F005, #202). Two of them
are checked by tests, named below.

## One composition root

- **`AppGraph`** (`app/src/main/java/com/cedagova/fastreader/AppGraph.kt`) is
  the one place objects are built: plain constructor calls, one `val` each,
  read top to bottom. There is no DI framework; the app is small enough that
  one class costs less than a framework would.
- **`FastReaderApplication`** installs crash reporting first, builds the one
  `AppGraph`, and forwards the process's foreground and background to it.
- **`Context.appGraph`** is the only way to reach the graph, and the only
  place the application object is cast. An activity asks for the graph, never
  for the application type.
- **The `:reader-account` assembly** is one `ReaderAccountGraph(...)` call
  inside `AppGraph`, over FastReader's host seams (#200).
- **The device library assembly** is one `DeviceLibrary(...)` call inside
  `AppGraph` (#204). It builds one narrow type per concern — `LibraryRepository`
  (books, folders, removal, account copies), `ReaderSettingsStore`,
  `ReadingPositions` and `BookBytes` — over one `CatalogDocument`, the single
  writer of `catalog.json`. Every write is a transform of the current document
  under that one lock, so no concern can overwrite another's change. Never
  build a second `CatalogDocument` over the same file.
- **Routes receive only what they use.** The shell (`FastReaderApp`) hands each
  Route the objects it needs — `LibraryRepository`, `ReaderSettingsStore`,
  `ReadingPositions`, `BookBytes`, `CoverStore`, the account shelf, the
  `ExternalOpenController` — never the graph. A feature package
  cannot import `AppGraph` anyway: that would be a cycle with the root package
  (see "Packages").

To add a long-lived object: add a `val` to `AppGraph`, then pass it to the
Route that uses it.

### Process-death rules this wiring keeps

- The handed-over "Open with" book lives on `AppGraph.external`, a
  process-scoped object. It survives a rotation and does not survive process
  death, after which the reader is back in the library with no row for that
  book and its position kept (REQ-103, AD-9).
- `MainActivity` accepts an incoming intent only when `savedInstanceState` is
  null. A rotation does not re-accept it, and a rebuild after process death does
  not resurrect a session-only book.

## One state-holder convention

Every screen has the same four parts:

| Part | Lives in | Job |
|---|---|---|
| **Store** | `AppGraph` (process-scoped) | Owns data. Exposes `StateFlow`s and `request…` functions. Examples: `LibraryRepository`, `ReaderSettingsStore`, `ReaderAccountController`, `AccountShelf`. |
| **Route** — the state holder | `<feature>/ui/<Screen>Route.kt` | Collects the stores with `collectAsStateWithLifecycle()`, keeps screen-only state (a query, an open dialog) in `rememberSaveable`, builds the screen state, and turns taps into store calls. |
| **UI state** | `<feature>/ui/<Screen>UiState.kt` | A plain data type plus a pure builder, such as `buildLibraryUiState(...)`. The Route calls the builder inside `remember(inputs)`. |
| **Screen** | `<feature>/ui/<Screen>Screen.kt` | Stateless: state in, callbacks out. This is what the Roborazzi goldens render, so every state is drawable without a device. |

**Collection is lifecycle-aware everywhere.** `:app` never calls
`collectAsState()`: a flow is collected only while the screen is at least
started. `ShellConventionTest` enforces this.

One consequence to keep in mind: an effect keyed on collected state runs when
that state arrives *while the screen is started*. `LibraryRoute`'s effects —
opening a finished download, recording a finished book for the account — see a
change that landed while the app was in the background only when the app
returns to the foreground, and run then. Saving the reading position does not
depend on this: `ReaderRoute` writes it on `ON_PAUSE`.

**A `ViewModel` only when the screen owns work** that must survive a
configuration change but must not outlive the activity. Today that is only the
reader's parse and playback (`reader/ui/ReaderViewModel.kt`); re-parsing a novel
on every rotation would lose the position. Rules for such a `ViewModel`:

- It lives in `<feature>/ui` next to its Route.
- It exposes one `StateFlow` of the screen's UI state.
- The Route creates it with `viewModelFactory`, from the stores it was handed.

Library and Settings own no such work, so their Route is the whole state
holder.

## One typed navigation model

- **`Destination`** (`Navigation.kt`) is a sealed, `@Serializable` type with
  one subtype per destination: `Library`, `Reader`, `Settings` and
  `ReaderAccount`.
- **`BackStack`** is the app's whole navigation state:
  - the last entry is on screen, and back pops it;
  - it is empty until launch routing decides where the app opens (REQ-009);
  - its transitions are pure functions, unit-tested by `NavigationTest`.
- **`FastReaderApp`** keeps one `BackStack` in `rememberSaveable` through
  `BackStack.SAVER` (a JSON string), so rotation and process death restore the
  same destination. A saved string that does not decode restores nothing, and
  launch routing simply runs again.
- **The handed-over book is not a destination.** It belongs to the process
  (see above), so it is drawn in place of any destination whose
  `showsHandedOverBook` is true (the library and a library book). Settings,
  and the account opened over them, still sit on top of it.

To add a destination:

1. Declare one subtype of `Destination`, with its `showsHandedOverBook`.
2. The `when (top)` in `FastReaderApp` is exhaustive, so the build fails until
   the new destination has a branch that draws it.
3. Navigate to it with `stack.push(...)`.

There are no flags, no reset block and no precedence order to edit.

## Packages: one direction, no cycles

- **Every Kotlin package is a node**, and a feature's `ui` package is its own
  layer. For example, `library.ui` depends on `library`, and never the other
  way.
- **The root package is the composition root** (`AppGraph`,
  `FastReaderApplication`, `MainActivity`, the navigation). It sits at the top:
  it may depend on everything, and nothing depends on it.
- **The generated `R` and `BuildConfig` classes are exempt.** They are in the
  root package only because it is the namespace; they are not code of that
  package.

The graph as of #204, each package with what it imports
(`com.cedagova.fastreader` omitted):

| Package | Depends on |
|---|---|
| (root) | the packages it wires: every one below except `reader`, `reader.catalog`, `ui` and `ui.components` |
| `settings.ui` | `library`, `reader.ui`, `settings`, `ui.components`, `ui.theme` |
| `reader.ui` | `account.library`, `external`, `library`, `library.saf`, `library.ui`, `reader`, `reader.catalog`, `settings`, `ui`, `ui.components`, `ui.theme` |
| `reader.catalog` | `account.library`, `library`, `library.ui`, `reader` |
| `library.ui` | `library`, `library.saf`, `library.store`, `settings`, `ui`, `ui.components`, `ui.theme` |
| `external` | `library`, `reader` |
| `account.library` | `library` |
| `library.saf` | `library` |
| `library` | `library.store`, `settings` |
| `crash.ui` | `crash`, `ui.components`, `ui.theme` |
| `account.ui` | `ui.components`, `ui.theme` |
| `ui.components` | `ui.theme` |
| `ui.theme` | `settings` |
| `account`, `crash`, `library.store`, `reader`, `settings`, `ui` | nothing in `:app` |

**The check.** `PackageGraphTest` (`app/src/test/.../app/`) reads the `import`
lines of `app/src/main/java` and fails on any cycle, naming the files and
imports that form it. It is a plain JVM unit test, so it runs:

- in `./gradlew check`;
- on CI, in the **"Unit tests"** step (`testDebugUnitTest`).

The main sources are a declared input of the test task, so an import-only
change cannot leave it up to date.

**It is a guard, not a proof.** The test follows `import` lines only. A
reference written as a fully qualified name, or a use of another package's
type that needs no import (a value reached through a member, such as
`a.b.c`, whose type is never named), is invisible to it. It catches the
cycles that realistically get written; it does not prove the graph above
complete.

## Shared UI primitives and tokens

Every screen draws with one component and token layer (A197-F006, #203). To
build a new screen, or copy one into a new client, take these along:

- **Tokens** (`ui/theme/Dimens.kt`):
  - `Spacing` is the spacing scale, from `XXSmall` (2 dp) to `XXLarge` (24 dp).
    `Spacing.Large` (16 dp) is the screens' gutter.
  - `Sizes.TouchTarget` (48 dp) is the smallest control the app draws. It is
    declared nowhere else.
  - `Sizes.Icon` and `Sizes.ListRowMinHeight` cover the other shared sizes.
  - A value only one component uses stays a named constant beside that
    component, such as the reader's control-column widths.
- **Components** (`ui/components/`):
  - `Banner` is the full-width strip, in one of three tones: problem, notice
    or progress. `ProblemBanner` is the "your change was not kept" banner used
    by the library, the reader and settings.
  - `UndoBar` is the bottom bar that offers a take-back.
  - `ConfirmDialog` is the two-button question before a removal, an upload or
    a crash report leaving the device.
  - `BackButton` is the top bar's way back.
  - `SectionHeading`, `ChoiceRow`, `OptionChip`, `SwitchRow` and
    `CheckboxRow` are the settings-style rows. Each row owns its one
    accessibility description.
- **Screen files are split by section.** For example, the library screen is
  `LibraryScreen.kt` (the entry and its dialogs' state), plus:
  - `LibraryHeader.kt`, `LibraryBanners.kt` and `LibraryBookList.kt`;
  - `LibraryAccountSlots.kt` and `LibraryAccountDialogs.kt` for the account
    UI.

  Pieces a section file shares with the entry are `internal`. The rest stay
  `private`.
- **Adapters are not UI.** `reader.catalog` holds `CatalogBooks` and
  `CatalogPositions`, the reader's view of the library store. The reader's
  Route builds them; they hold no Compose code.

**The checks.** `ShellConventionTest` fails when a Kotlin file under
`app/src/main/java/**/ui/` is longer than 600 lines (plan AD-7). It also fails
when `TouchTarget` is declared anywhere but `Dimens.kt`.

## Compose stability of library types

`:reader-engine` is a plain Kotlin/JVM module built without the Compose
compiler, so Compose infers every engine type as unstable. The reader's state
types in `reader/ui/ReaderUiState.kt` carry engine types (`BookContent`,
tokens, `RemainingTimeIndex`), so a composable taking them cannot skip
recomposition when its inputs are equal. Nothing visible depends on this
today. If profiling ever shows it matters, the remedy is a stability
configuration file in `:app` (`composeCompiler { stabilityConfigurationFiles }`)
that lists the engine's immutable types — not a Compose dependency in the
engine, which must stay Android-free.
