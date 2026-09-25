# Agent-first development

This project is developed **entirely through coding agents** (Claude Code,
Codex, or similar). No IDE is part of the workflow. This document defines the
approach in general-purpose terms so it can be copied into any repository;
only the [Project bindings](#project-bindings) section at the end is
project-specific — replace it when reusing this doc.

## Core principle

**If an agent cannot drive it and verify it from a shell, it is not part of
the workflow.** IDEs, GUIs, and wizards are replaced by three things:

1. **Headless tooling** — every action (scaffold, build, test, run, inspect,
   release) is a command with readable output and a meaningful exit code.
2. **Observable results** — the agent must be able to *see* outcomes:
   rendered images for UI, logs for behavior, structured output for data.
   An exit code alone is not verification.
3. **Written context** — everything an agent needs to work cold (stack,
   commands, quirks, machine-local setup) lives in the agent guides, updated
   the moment a decision is made.

## Feedback loops, ordered by cost

Run the cheapest loop that can catch the mistake; escalate only when the
cheaper loop can't see the problem.

| Loop | Cost | Catches | Typical form |
| --- | --- | --- | --- |
| 1. Static | seconds | syntax, types, style | compile / typecheck / lint |
| 2. Isolated tests | seconds | logic, regressions | unit tests on the dev machine |
| 3. Rendered UI | seconds | layout, theming | deterministic screenshot render, **no device/browser** |
| 4. Real runtime | ~1 min | integration, lifecycle, platform behavior | app on emulator/simulator/browser + screen capture + logs |
| 5. Flows | minutes | multi-step user journeys | scripted E2E over the real runtime |

Rules that make the loops work:

- **Loop 3 is the workhorse for UI.** Deterministic image rendering without a
  device (Roborazzi/Paparazzi on Android, Storybook/Playwright snapshots on
  web, etc.) gives the agent eyes at unit-test speed. Wire it before writing
  the first screen.
- **Committed golden images are the UI regression gate.** Record on change,
  verify on every run; a diff is a finding, not noise. Goldens must be
  reproducible on the machine that verifies them; when the recording machine
  and the gate disagree, make the gate's platform authoritative (or pin a
  matching image) — never widen the comparison tolerance to make a diff go
  away.
- **Goldens must be a declared input of the verify task.** Otherwise the build
  tool sees an unchanged source tree, skips the task as up to date, and reports
  a green gate over a reference image that changed.
- **The hosted gate runs the same loops, not a subset.** Loops 1–3 run on every
  push and pull request, and any failure is red.
- **Loop 4 must capture, not assume.** Launch headless, screenshot the actual
  screen, read the actual crash log. The agent inspects the artifact — never
  reports "it should work now."
- **Silence is not success.** Any wait/poll loop reports its failure path;
  a failed status query is UNKNOWN, not "fine".

## Toolchain rules

- **Pin everything executable.** Build tool via a committed wrapper/lockfile,
  dependency versions centralized in one manifest. A fresh clone plus the
  documented one-time setup must build.
- **Machine-local config is isolated and reconstructable.** SDK paths, JDK
  locations, secrets: ignored files or env vars, each with a documented
  one-line recreate command in the agent guides.
- **Agent shells are minimal.** Tool-invoked shells often skip rc files;
  guides state required env vars explicitly (e.g. `JAVA_HOME`), and builds
  prefer project-local config over inherited environment.
- **Keep the IDE optional.** Installing one for occasional profiling is fine;
  no workflow step may require it.

## Documentation contract

- **Two agent guides, one substance**: `AGENTS.md` (Codex) and `CLAUDE.md`
  (Claude) carry the same facts — stack, commands, verification loops,
  quirks. They are machine-local and untracked; this doc is the tracked,
  shareable statement of the approach.
- **Record decisions immediately**: stack choices, version constraints, and
  discovered quirks (e.g. "build tool X can't run on JDK Y") go into the
  guides in the same session they're learned.
- **Guides are for cold starts**: written so an agent with zero conversation
  history can build, verify, and ship.

## Bootstrapping a new project (checklist)

1. Scaffold by hand or template — no wizard. Commit a minimal building
   skeleton first.
2. Pin the toolchain (wrapper + version manifest) and prove `build` and
   `test` from a bare shell.
3. Wire loop 3 (deterministic UI render) and commit the first goldens.
4. Prove loop 4 end-to-end once: headless runtime → install/serve → capture
   → log check → teardown.
5. Write the agent guides: stack, commands, env vars, recreate commands,
   verification loops.
6. Copy this document into `docs/` and rewrite [Project bindings](#project-bindings).
7. Commit at each step above — working increments, not end-of-session batches.

## Project bindings

*(Project-specific — replace this section when copying the doc.)*

**fastReader** — Android app, Kotlin + Jetpack Compose.

| General concept | Binding here |
| --- | --- |
| Build / static | `./gradlew assembleDebug`, `./gradlew lint`, `./gradlew spotlessCheck` (ktlint through Spotless; `./gradlew spotlessApply` fixes what it can; rules in `.editorconfig`), or `./gradlew check` for tests + lint + format together (Gradle 9.7.1 wrapper, AGP 9.4.0, Kotlin 2.4.20, Compose BOM 2026.09.00, `compileSdk`/`targetSdk` 37, `minSdk` 26; versions and SDK levels in `gradle/libs.versions.toml`) |
| Shared build conventions (#205) | `build-logic/` is an included build of convention plugins: `conventions.android.application` and `conventions.android.library` apply SDK levels, the JVM target, lint gates, unit-test settings and the formatter to every module, and `conventions.root` formats the root and build-logic scripts. A new module applies one of them and repeats none of those settings |
| Isolated tests | `./gradlew testDebugUnitTest` (JUnit4 + Robolectric) |
| Rendered UI | Roborazzi: `./gradlew recordRoborazziDebug` → PNGs + goldens in `app/screenshots/`; `verifyRoborazziDebug` is the regression gate |
| Real runtime | AVD matrix (`Phone_Low_API33` … `Tablet_Mid_API36`, plus `Phone_Mid_API37` — same 1080x2400 @420dpi as `Phone_Mid_API36`, and the only device that can exercise an Android 17 behaviour change now that `targetSdk` is 37): `emulator -avd <name> -no-window` → poll `sys.boot_completed` → `./gradlew installDebug` → `adb shell am start` → `adb exec-out screencap -p` → `adb logcat -d -s AndroidRuntime:E` → `adb emu kill` |
| Flows | `adb shell input tap/swipe/text` today; Maestro when flows warrant it |
| Hosted gate | `.github/workflows/checks.yml` on GitHub Actions (`ubuntu-latest`, free tier): `testDebugUnitTest`, `verifyRoborazziDebug`, `lint`, `spotlessCheck`, `:app:minifyReleaseWithR8` (no signing key) and `compileDebugAndroidTestSources`, once per pull-request commit and once per `main` commit; `scripts/release.sh --publish` refuses a commit it has not passed on |
| Machine-local config | `local.properties` (`sdk.dir=$HOME/Library/Android/sdk`, plus `platforms;android-37.0` and `build-tools;37.0.0` installed); `JAVA_HOME` must be JDK 21 — the only JDK this project builds and validates on |
| Emulator lock | One emulator per machine, shared by every worktree: `mkdir ~/worktrees/fastReader/.emulator.lock && echo $$ > ~/worktrees/fastReader/.emulator.lock/pid` before booting — a failing `mkdir` means another session's run owns the emulator, so wait rather than kill it — and `rm ~/worktrees/fastReader/.emulator.lock/pid && rmdir ~/worktrees/fastReader/.emulator.lock` after `adb emu kill` |
| Reader auth library, hosted by FastReader (#92, #93, #100) | `:reader-auth` is an Android library beside `:app` (`com.cedagova.reader.auth`, declares `INTERNET`; contract in `reader-auth/CONTRACT.md`, see `reader-auth/README.md`). Since #100 `:app` depends on it and is its host: the Reader account screen under Settings is the library's sign-in, session and capabilities surface. The library depends on nothing under `:app`. Same loops: `./gradlew :reader-auth:testDebugUnitTest` runs against a Ktor mock engine, a fake cipher and a fake clock, so no network or device is involved; the app's `account/` package is tested against a scripted `FakeReaderAccountGateway` (`ReaderAccountControllerTest`) and rendered into the `account_*` goldens with no SDK behind them. FastReader's host obligations — nine-domain backup exclusion, no cleartext outside the debug-only `10.0.2.2` allowance in `app/src/debug/`, the `BuildConfig` → `ReaderAuthConfig` mapping, no committed value — are pinned by `ReaderAccountManifestTest` and `ReaderAccountConfigTest`. `scripts/release.sh` proves on the signed APK that the permissions are exactly `INTERNET` plus the platform self-permission and that the release manifest carries no `networkSecurityConfig` and no `usesCleartextTraffic` |
| Reader account-library module, hosted by FastReader (#112) | `:reader-library` is a second Android library beside `:app` (`com.cedagova.reader.library`), depending on `:reader-auth` and on nothing under `:app`; see `reader-library/README.md`. It owns the typed models and the typed operations `ReaderLibraryOperations` declares — library, progress, sync mutations and deltas, the `reader.sync.v1` and `reader.publication-import.v1` capability reads, the publication-import routes and the asset download grant (the table in `reader-library/README.md` lists them) — no generic call, no session, no token, no new exception type. Loops: `./gradlew :reader-library:test` only, with no network, device or backend value, and no rendered UI or golden in this module. The reader-api operation tests (`ReaderLibraryClientTest`, `PublicationImportEngineTest`) drive a **real** `ReaderAuthClient` (built through `ReaderAuthClient.createForTests`) over a Ktor mock engine, so the bearer, the single-flight refresh and every 401/403/429/502 branch are exercised; the storage-transfer tests drive the session-less transfer clients over a mock provider; the sync tests use the scripted `FakeReaderLibraryGateway` named below. The drift gate is `ReaderLibraryContractTest`: `reader-library/contracts/reader-api.openapi.json` is the published Reader API document at a pinned identity with its sha256 recorded beside it, and the test recomputes that digest and then checks every field name, JSON type, enum member and required flag the models use against the document's schemas, deriving the expectations from the models' own serializer descriptors (owner decision P1: hand-written models, contract test, no generator and no Docker step). Its negative case runs the same checker over deliberately broken copies of four models, so the gate is known to fail when it should. Since #147 the account sync engine lives here too (`com.cedagova.reader.library.sync`: `AccountSyncEngine`, its document, codec and store, and the `ReaderLibraryGateway` seam — `ReaderApiLibraryGateway` is the production pass-through and `FakeReaderLibraryGateway` the scripted double the sync tests run against); `:app` keeps only FastReader's token mapping, copies, catalog and UI |
| Reader sign-in run against stage from FastReader (#100) | The app reads three public runtime values from the untracked `local.properties` — `reader.supabaseUrl`, `reader.supabasePublishableKey`, `reader.apiBaseUrl` (or the environment: `READER_SUPABASE_URL`, `READER_SUPABASE_PUBLISHABLE_KEY`, `READER_API_BASE_URL`) — into `BuildConfig`; the stage web app publishes the same values at `https://stage.chunipers.com/env.js`. Never commit them and never paste the key into evidence. With them absent (the hosted runner) the app builds, lints and passes its tests, and on a device **Settings → Reader account** reads "Not configured" and calls nothing. Device loop under the emulator lock: `./gradlew installDebug` → `adb shell am start -n com.cedagova.fastreader/.MainActivity` → open Settings, tap **Reader account** → drive the screen with `adb shell input tap/text` (find bounds with `adb shell uiautomator dump`; every control carries a Compose test tag `account_*` and a visible label) → the emailed 6-digit code is read by a person from the inbox and typed with `adb shell input text` (it is valid for 60 minutes; nothing automates it) → `adb exec-out screencap -p` → `adb logcat -d -s AndroidRuntime:E`. Process death and reboot: `adb shell am force-stop com.cedagova.fastreader`, relaunch; `adb reboot`, poll `sys.boot_completed`, relaunch — both must show the screen signed in as the same address. Backup proof on the signed-in app: the `docs/evidence/46/` procedure (`bmgr backupnow` on the local and D2D transports, inspect what the transport stored), transcript in `docs/evidence/100/`. After sign-out, `adb shell run-as com.cedagova.fastreader ls no_backup/reader-auth/` must list no `session.bin`. To build the CI-shaped "Not configured" APK on a machine that has the values, comment the three `reader.*` lines out of `local.properties`, build, and restore them |
| Account library run against stage, with reader-web as the other device (#113, #114, #115) | The one loop no emulator can close by itself: the account library is only observable when **two** clients look at the same stage Reader account. Device **E** is `Phone_Mid_API36` running the built app; device **W** is reader-web signed in to the same account at `https://stage.chunipers.com`. W is where a book is uploaded, where a removal is confirmed to have reached the backend, and where the account's own records are inspected; E is where the shelf, the merge by checksum, the account-only row, **Remove from account** and its Undo are driven with `adb shell input tap/text` under the emulator lock. The loop: upload an EPUB on W, sign in on E (**Settings → Reader account**, six-digit code read from the inbox by a person), refresh the shelf on E, act on E, then refresh W to see whether the change arrived — and back the other way, removing on W while E has the book open. Neither half is a substitute for the other: a mutation E queued and never drained looks identical on E to one the backend accepted, so **only W distinguishes them**. Everything below that boundary is proven without either device — `AccountSyncEngineTest`, `AccountShelfTest` and `LibraryAccountUiStateTest` drive a scripted `FakeReaderLibraryGateway`, `:reader-library`'s own tests drive a real `ReaderAuthClient` over a Ktor mock engine, and the `library_account_*` goldens render the shelf's account states with no SDK behind them. A run that needs a signed-in stage account is recorded as `PENDING OWNER TEST — step N of the owner list`, never as done: an agent cannot read the emailed code and cannot drive reader-web. |

### Goldens and the hosted gate

Roborazzi renders through Robolectric's native graphics, and the goldens in
`app/screenshots/` are **byte-reproducible on both the macOS arm64 development
machine and the `ubuntu-latest` runner**. That was measured, not assumed: the
macOS-recorded goldens verified green unmodified on the runner, and inverting
0.07% of the pixels of one golden turned the same job red. So:

- **Record goldens anywhere** (`./gradlew recordRoborazziDebug`) and commit
  them; the hosted job verifies the same bytes.
- **If that ever stops holding** — a Robolectric, Roborazzi, AGP or runner
  image bump makes the runner render differently — the runner becomes the
  golden-recording platform (record on it and commit its output, or pin a
  runner image that matches). Re-measure and update this section. Do **not**
  raise the comparison tolerance or mark the golden job optional.
- `app/screenshots/` is declared as an input of the unit-test task in
  `app/build.gradle.kts`. Without it, editing a golden left the task up to date
  and `verifyRoborazziDebug` passed over a changed reference image.
- **A dialog golden now includes the scrim.** Under the old Compose/Roborazzi
  pair an `AlertDialog` golden showed an undimmed background, which is not what
  a device shows. Since the 2026-09 refresh the dim layer is composited into the
  capture, so a dialog golden is darker than its pre-refresh reference and now
  matches the device.
- **A menu golden needs both a screen capture and a clock nudge.** A popup —
  `DropdownMenu` as much as `AlertDialog` — is its own window, so
  `onRoot().captureRoboImage(...)` fails with "expected exactly 1 node but found
  2" and `captureScreenRoboImage(...)` is the one that composites it. That alone
  is not enough for a menu: it opens through an enter transition, and a capture
  taken straight after `performClick()` succeeds and records the screen *without*
  the menu — a green test and an empty golden. Advance the compose clock
  (`composeRule.mainClock.advanceTimeBy(500)`, then `waitForIdle()`) before
  capturing, and look at the PNG.

### Toolchain refresh notes (2026-09, AGP 9)

Hard-won facts from the AGP 8.11 → 9.4 / Gradle 8.14 → 9.7 move. They are not
obvious from the error messages, so check here before re-deriving them:

- **No `org.jetbrains.kotlin.android` plugin.** AGP 9 compiles Kotlin itself and
  refuses to configure when that plugin is applied. `kotlin.plugin.compose` and
  `kotlin.plugin.serialization` are still applied, at the `kotlin` version in the
  catalog; the resolved stdlib must match it (`./gradlew :app:dependencies
  --configuration debugRuntimeClasspath` to confirm).
- **Extra source directories go on `kotlin`, not `java`.** AGP 9's built-in
  Kotlin reads the source set's `kotlin` directories. Registering
  `src/sharedTest/java` on `java` alone still configures and still builds the
  app, and then fails test compilation with every shared fixture unresolved.
  Use `getByName("test").kotlin.directories.add(...)`.
- **Icons are a direct dependency now.** Compose Material3 1.4.0 dropped its
  transitive `material-icons-core`, so `androidx.compose.material.icons.Icons`
  stops resolving until the artifact is declared. The Compose BOM still pins it
  (1.7.8), so no version is chosen by hand.
- **`OutlinedButton` labels are `onSurface`, not `primary`.** Material3 1.4.0
  changed the default, so every outlined button's text went from blue to near
  black. That is a library default, not an app change; the goldens record it.
- **Older AGP lint could not run on this Mac at all.** At AGP 8.11.1 on Homebrew
  JDK 21.0.12.1, `./gradlew lint` died with "Can't initialize detector
  androidx.compose.runtime.lint.AutoboxingStateCreationDetector" while the same
  commit's hosted `lint` was green on Temurin 21. AGP 9.4.0's lint runs locally
  again. If lint ever dies in a detector constructor rather than reporting
  issues, suspect the lint/JDK pair, not the code.

### Proving a claim about frames, not screenshots

Some acceptance is about what is on screen *during* a transition — "no light
frame during launch" — where a screenshot proves nothing, because the frame you
have to rule out is the one you did not happen to catch. The loop that does
work:

```bash
adb shell screenrecord --time-limit 8 --size 540x1200 /sdcard/rec.mp4 &
adb shell am start -n <package>/<activity>          # while it records
adb pull /sdcard/rec.mp4
ffprobe -v error -f lavfi -i "movie=rec.mp4,signalstats" \
  -show_entries frame=pts_time:frame_tags=lavfi.signalstats.YAVG -of csv=p=0
```

That prints one average-luminance value per captured frame, which turns "no
light frame" into a number to compare against — and `ffmpeg -vf
"select=...,tile=12x1"` turns the same file into a filmstrip a human can read in
one glance. Two things make it trustworthy:

- **Record smaller than the display.** At full 1080x2400 the emulator's encoder
  falls behind and leaves 40 ms gaps between captured frames; `--size 540x1200`
  brings the median gap down to ~20 ms on a 60 Hz display.
- **Stretch the transition.** `adb shell settings put global
  animator_duration_scale 5.0` (and `window_`/`transition_animation_scale`)
  spreads a 250 ms launch over more than a second, so a single wrong frame
  becomes tens of captured frames instead of one that sampling could miss. Put
  the scales back to `1.0` afterwards.

### Inducing a crash on a device

Debug builds carry `com.cedagova.fastreader.debug.CrashInducerActivity`, which
throws in `onCreate` and does nothing else. It is in `src/debug`, so neither the
class nor its manifest entry exists in a release build, and
`CrashInducerIsDebugOnlyTest` keeps it there.

```bash
adb shell am start -n com.cedagova.fastreader/com.cedagova.fastreader.debug.CrashInducerActivity
adb logcat -d -s AndroidRuntime:E                              # the crash was delivered
adb shell run-as com.cedagova.fastreader cat files/crash/report.txt
adb shell am start -n com.cedagova.fastreader/.MainActivity    # the offer, once
```

It is exported, because `am start` runs as the shell user and the shell may only
start an activity another uid has exported.

Two things about the run itself:

- **Crashing twice in a row brings up the platform's own "FastReader keeps
  stopping" dialog**, on top of the app. Dismiss it (`Close app`) and
  `am force-stop` before relaunching, or the screenshot is of that dialog.
- **The task restarts itself.** When the inducer crashes above a live
  `MainActivity`, the system rebuilds the process and resumes the activity
  underneath — so the app can be back on screen, offer and all, before the
  `am start` that was meant to relaunch it. Check with `pidof`, not the clock.

### What a shared crash report may contain

Anything that reaches `files/crash/report.txt` is something a reader can hand to
another app, so the renderer in `crash/CrashReport.kt` keeps a closed shape:
fixed header lines, exception *types*, and call sites — no exception messages at
all, and every interpolated value filtered to characters that cannot spell a
path. Adding a field means adding its line shape to `CrashReportTest`'s
allow-list, which is the point at which to ask what that field could carry.
### Compose layout quirks

- **A lazy grid measures its items with an unbounded height.** Inside a
  `LazyVerticalGrid` item, `Modifier.fillMaxHeight()` does nothing and a
  `Modifier.weight()` in a `Column` resolves against infinity. The first draft of
  the tablet library used both to line the row dividers up, and rendered four
  books at zero height — a build that passed, a screen that was empty, and no
  error anywhere. The fix that does line them up is a *leading* divider: every
  cell in a grid row starts at the same y, so a rule above each item draws an
  unbroken line while a rule below each item draws a staircase.
- **Compare a `dp` breakpoint in whole pixels.** `600.dp` is the width of
  `Tablet_Low_API33` exactly, and converting that device's pixel width back to
  `Dp` can land on 599.99997 at a non-integral density (420 dpi is 2.625x). Round
  both sides to pixels through the same `Density` and the boundary device lands
  on the side of its own breakpoint that the `sw600dp` resource qualifier would
  have put it on.

### Testing a text field under Robolectric

- **Pause the clock before a field takes focus.** A focused text field blinks
  its cursor forever, and with `mainClock.autoAdvance` left on, `waitForIdle`
  chases those frames until the test JVM runs out of heap (the symptom is an
  `OutOfMemoryError` inside `ShadowTrace`, not a hang). Set
  `composeRule.mainClock.autoAdvance = false` before the field can be focused.
- **With the clock paused, settle as sync → frames → sync.** A press only gets
  composed on the next `waitForIdle`, and what that composes only gets bounds on
  a later frame. `ReaderSpeedEntryTest.settle()` is the working sequence:
  `waitForIdle()`, `advanceTimeBy(500)`, `waitForIdle()`. Skip the first sync
  and the new node is "not displayed"; skip the frames and it is "not found".
- **A text field inside a `Dialog` never settles at all.** Opening an
  `AlertDialog` that contains an `OutlinedTextField` makes Robolectric's layout
  loop even before the field is focused, and under the paused clock the dialog
  window never registers with the test harness. The WPM entry is an inline swap
  of the readout for exactly this reason; don't put a text field in a dialog
  expecting to test it here.

### Every new string needs a Spanish one (#55)

The app ships two locales: `values/strings.xml` and `values-es/strings.xml`.
`MissingTranslation`, `ExtraTranslation` and `MissingQuantity` are declared lint
**errors** with `abortOnError` in the `lint` block of `app/build.gradle.kts`, so
adding a string to `values/strings.xml` and stopping there turns the hosted
`lint` step red:

```
values/strings.xml:202: Error: "settings_chapter_pause" is not translated in "es" (Spanish) [MissingTranslation]
```

That is the gate working. The fix is the Spanish line, not a lint suppression.
The one legitimate escape is `translatable="false"` on the string itself, for a
value that genuinely must not be translated.

Two things that catch people out:

- **Spanish has a plural category English does not.** CLDR gives `es` a `many`
  bucket for whole millions, which take `de` before the noun — "2.000.000 **de**
  libros" against "5 libros". Every `<plurals>` in `values-es` needs
  `one`/`many`/`other`, and `MissingQuantity` will say so if it does not.
- **A *stale* Spanish string is invisible to the gate.** Lint catches a missing
  translation, never a Spanish sentence that no longer says what the English one
  says. When you edit English copy, edit its Spanish line in the same commit.
  This bites hardest on `settings_privacy`, which `PrivacyStatementTest` already
  pins to three published Markdown copies — the Spanish copy is a fourth that no
  test pins.

Register and conventions for the Spanish itself are written at the top of
`values-es/strings.xml`. Spanish also runs longer than English, so a new string
wants a look at the `*_spanish_compact_large_font` goldens, not only the
reference phone.

### Resource-folder quirk

`mipmap-anydpi` without a version qualifier does not link: `aapt2` reports
`resource mipmap/ic_launcher not found`. With `minSdk 26`, put an adaptive-icon
XML in plain `mipmap/` — lint's `ObsoleteSdkInt` is right that `-v26` is
redundant, and its suggested `mipmap-anydpi` is the one form that fails.

The hosted job runs each check even when an earlier one failed, so one red run
shows every problem. It carries no secrets and no signing material — release
signing and publication stay local in `scripts/release.sh` (see
[release.md](release.md)). Requiring the check before merge is a repository
setting, not part of the workflow: **Settings → Branches → add a branch
protection rule for `main` → Require status checks to pass → select
`unit tests, goldens, lint, R8, instrumented-test compile`.**
