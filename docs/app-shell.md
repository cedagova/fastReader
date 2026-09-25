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
- **Routes receive only what they use.** The shell (`FastReaderApp`) hands each
  Route the objects it needs — `LibraryRepository`, `CoverStore`, the account
  shelf, the `ExternalOpenController` — never the graph. A feature package
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
| **Store** | `AppGraph` (process-scoped) | Owns data. Exposes `StateFlow`s and `request…` functions. Examples: `LibraryRepository`, `ReaderAccountController`, `AccountShelf`. |
| **Route** — the state holder | `<feature>/ui/<Screen>Route.kt` | Collects the stores with `collectAsStateWithLifecycle()`, keeps screen-only state (a query, an open dialog) in `rememberSaveable`, builds the screen state, and turns taps into store calls. |
| **UI state** | `<feature>/ui/<Screen>UiState.kt` | A plain data type plus a pure builder, such as `buildLibraryUiState(...)`. The Route calls the builder inside `remember(inputs)`. |
| **Screen** | `<feature>/ui/<Screen>Screen.kt` | Stateless: state in, callbacks out. This is what the Roborazzi goldens render, so every state is drawable without a device. |

**Collection is lifecycle-aware everywhere.** `:app` never calls
`collectAsState()`: a flow is collected only while the screen is at least
started. `ShellConventionTest` enforces this.

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

The graph at #202, each package with what it imports (`com.cedagova.fastreader`
omitted):

| Package | Depends on |
|---|---|
| (root) | the packages it wires: every one below except `reader` and `ui` |
| `settings.ui` | `library`, `reader.ui`, `settings` |
| `reader.ui` | `account.library`, `external`, `library`, `library.saf`, `library.ui`, `reader`, `settings`, `ui` |
| `library.ui` | `library`, `library.saf`, `library.store`, `settings`, `ui` |
| `external` | `library`, `reader` |
| `account.library` | `library` |
| `library.saf` | `library` |
| `library` | `library.store`, `settings` |
| `crash.ui` | `crash` |
| `ui.theme` | `settings` |
| `account`, `account.ui`, `crash`, `library.store`, `reader`, `settings`, `ui` | nothing in `:app` |

**The check.** `PackageGraphTest` (`app/src/test/.../app/`) reads the `import`
lines of `app/src/main/java` and fails on any cycle, naming the files and
imports that form it. It is a plain JVM unit test, so it runs:

- in `./gradlew check`;
- on CI, in the **"Unit tests"** step (`testDebugUnitTest`).

The main sources are a declared input of the test task, so an import-only
change cannot leave it up to date.
