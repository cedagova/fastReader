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
| Build / static | `./gradlew assembleDebug`, `./gradlew lint` (Gradle 9.7.1 wrapper, AGP 9.4.0, Kotlin 2.4.20, Compose BOM 2026.09.00, `compileSdk`/`targetSdk` 37, `minSdk` 26; versions in `gradle/libs.versions.toml`) |
| Isolated tests | `./gradlew testDebugUnitTest` (JUnit4 + Robolectric) |
| Rendered UI | Roborazzi: `./gradlew recordRoborazziDebug` → PNGs + goldens in `app/screenshots/`; `verifyRoborazziDebug` is the regression gate |
| Real runtime | AVD matrix (`Phone_Low_API33` … `Tablet_Mid_API36`, plus `Phone_Mid_API37` — same 1080x2400 @420dpi as `Phone_Mid_API36`, and the only device that can exercise an Android 17 behaviour change now that `targetSdk` is 37): `emulator -avd <name> -no-window` → poll `sys.boot_completed` → `./gradlew installDebug` → `adb shell am start` → `adb exec-out screencap -p` → `adb logcat -d -s AndroidRuntime:E` → `adb emu kill` |
| Flows | `adb shell input tap/swipe/text` today; Maestro when flows warrant it |
| Hosted gate | `.github/workflows/checks.yml` on GitHub Actions (`ubuntu-latest`, free tier): `testDebugUnitTest`, `verifyRoborazziDebug`, `lint` on every push and pull request |
| Machine-local config | `local.properties` (`sdk.dir=$HOME/Library/Android/sdk`, plus `platforms;android-37.0` and `build-tools;37.0.0` installed); `JAVA_HOME` must be JDK 21 — the only JDK this project builds and validates on |

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
`unit tests, goldens, lint`.**
