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
  verify on every run; a diff is a finding, not noise.
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
| Build / static | `./gradlew assembleDebug`, `./gradlew lint` (Gradle 8.14.2 wrapper, AGP 8.11.1, versions in `gradle/libs.versions.toml`) |
| Isolated tests | `./gradlew testDebugUnitTest` (JUnit4 + Robolectric) |
| Rendered UI | Roborazzi: `./gradlew recordRoborazziDebug` → PNGs + goldens in `app/screenshots/`; `verifyRoborazziDebug` is the regression gate |
| Real runtime | AVD matrix (`Phone_Low_API33` … `Tablet_Mid_API36`): `emulator -avd <name> -no-window` → poll `sys.boot_completed` → `./gradlew installDebug` → `adb shell am start` → `adb exec-out screencap -p` → `adb logcat -d -s AndroidRuntime:E` → `adb emu kill` |
| Flows | `adb shell input tap/swipe/text` today; Maestro when flows warrant it |
| Machine-local config | `local.properties` (`sdk.dir=$HOME/Library/Android/sdk`); `JAVA_HOME` must be JDK 21 — Gradle 8.x cannot run on Android Studio's bundled Java 25 |
