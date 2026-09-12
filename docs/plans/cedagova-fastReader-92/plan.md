# Implementation Plan: Standalone Android auth module and host app beside FastReader

- Planning issue: https://github.com/cedagova/fastReader/issues/92
- Planning PR: https://github.com/cedagova/fastReader/pull/96
- Status: Ready for implementation
- Root classification: LEAF
- Delivery topology: DIRECT
- Planner: Planning lead (Claude)
- Started: 2026-09-12

## Pinned baselines

| Repository | Baseline |
| --- | --- |
| `cedagova/fastReader` | `ed90c4a6479e849c7f0001951870c139e5072df5` |

`origin/main` on 2026-09-12 (release 1.5.0, versionCode 7). It hosts the root
issue, the audit record (dossier PR #83, reviewed head
`1b99102603eb05975e655b26be02a5a79abd646e`, finding A83-F007, native umbrella
#94), and receives the change. Every audit evidence line about the manifest,
backup rules, release gate, privacy statement, and dependency catalog was
re-read at this baseline and still holds.

## Preserved objective and boundaries

Root #92 is audit outcome A83-F007, accepted by the owner on 2026-09-11 and
carrying `Audit handoff: PLANNING_REQUIRED` under the native audit umbrella
#94. Its desired outcome is preserved verbatim: the proving ground is not the
FastReader app; the Android auth work lives as a standalone, reusable module
with its own minimal host app and its own application id, built to be lifted
into the real Reader client unchanged; FastReader's product, manifest,
release gate, and privacy statement are left exactly as they are. Owner
direction (2026-09-11, audit Scope changes): optimise for the long-term
Reader product; this repository's own product constraints are irrelevant to
the outcome.

Boundaries preserved from the root. In: where the reusable module and its
host app live, and the build rules that keep it out of the FastReader
artifact. Out: any change to the FastReader app, its definition, or its
guarantees; the auth implementation itself (A83-F008, #93); anything in
Chunipers. Constraint preserved: the host app carries the same full-domain
backup exclusion FastReader already proves, because it will store tokens.
Known dependency preserved: #92 gates #93; nothing client-side can be
exercised until the module and host app exist. Planning input preserved from
#93 (reviewer R6): a debug-only network security configuration for the
emulator loopback `10.0.2.2` is needed later and must be confined to a build
that never reaches a released artifact.

## Classification

- **ROOT #92 — `LEAF`.** One coherent, independently verifiable outcome in
  one repository: two new Gradle modules (a reusable Android library and its
  minimal host application) plus the build and documentation rules that keep
  them apart from `:app`, delivered as one PR to `main`. The outcome is
  observable on its own: the host app installs under its own application id,
  reaches the network, and FastReader's release gate still passes unchanged.
  Splitting "module", "host", and "isolation rules" would ship pieces that
  cannot be verified alone (a library nobody hosts; a host that proves
  nothing). No children are published; the fast-leaf path applies.

Not `ALREADY_SATISFIED`: at the baseline `settings.gradle.kts` includes only
`:app`, no library module exists, and no artifact in the tree declares
`android.permission.INTERNET`. Not `NEEDS_DECISION`: the one material choice
the root left to planning, placement, is resolved below as a recorded
assumption the owner and reviewer can overturn (Assumptions and open
questions); every other choice is a reversible naming or wiring choice inside
the invariants. Not `INCREMENTAL`: #93 is its own root with its own planning
run; this plan does not publish or reparent it.

## Current-state evidence

At `ed90c4a6479e849c7f0001951870c139e5072df5`:

- `settings.gradle.kts` includes `:app` only; `build.gradle.kts` declares
  `android.application`, `kotlin.compose`, and `roborazzi` with
  `apply false`; `gradle/libs.versions.toml` has no `android.library` plugin
  alias and no HTTP client artifact (AGP 9.4.0, Kotlin 2.4.20, Gradle 9.7.1,
  Compose BOM 2026.09.00, compileSdk/targetSdk 37, minSdk 26).
- `app/src/main/AndroidManifest.xml` declares no permission;
  `allowBackup="false"`, `dataExtractionRules`, and `fullBackupContent`
  exclude all nine domains from cloud backup and device-to-device transfer
  (`app/src/main/res/xml/data_extraction_rules.xml`, `backup_rules.xml`), and
  `app/src/test/.../app/AppVersionTest.kt` is the static regression gate for
  that declaration (merged-manifest `FLAG_ALLOW_BACKUP` plus the rules file
  content). The runtime proof lives in `docs/evidence/46/`.
- `scripts/release.sh` builds `:app:assembleRelease` explicitly and dies if
  the APK badging shows `android.permission.INTERNET`; without `--publish`
  it is a build-and-verify dry run. `app/build.gradle.kts` scopes its
  signing config, the unsigned-release guard, the translation lint gate, and
  the Roborazzi golden input to `:app`.
- `.github/workflows/checks.yml` runs `./gradlew testDebugUnitTest`,
  `verifyRoborazziDebug`, and `lint` from the root on every push and pull
  request, so any new module's unit tests and lint join the hosted gate
  without a workflow change; its artifact upload globs name `app/build/`
  only.
- `README.md` states FastReader has no internet permission at all;
  `docs/privacy-statement.md` says the same and is pasted into release
  notes. `docs/agent-first-development.md` "Project bindings" describes a
  single-module repository.
- Backend fact for the later #93 plan, not for this leaf: stage reader-api
  serves a per-client bootstrap keyed by `X-Reader-Client`, identifier
  `reader-android`, version line `1.0.0` (Chunipers/reader-api#489, closed
  2026-09-12). This leaf needs no reader-api call.

## Selected implementation direction

One PR on `main` adding two modules and the rules that isolate them:

1. **Reusable library module** (Android library plugin; suggested Gradle
   path `:reader-auth`, directory `reader-auth/`, namespace
   `com.cedagova.reader.auth`). It is the module the real Reader client will
   depend on, so it owns what any host needs merged in: its manifest
   declares `android.permission.INTERNET`. It ships a short module README
   stating the host requirements a library cannot enforce through manifest
   merging: full-domain backup exclusion (`allowBackup="false"`,
   `dataExtractionRules`, `fullBackupContent`) and no cleartext policy in a
   release build. Its content at this leaf is a skeleton with one unit test,
   so the hosted gate proves module test wiring from day one; the auth
   implementation is #93.
2. **Minimal host application module** (Android application plugin;
   suggested `:reader-auth-host`, directory `reader-auth-host/`,
   applicationId `com.cedagova.reader.auth.host`). It depends on the library
   and on nothing in `:app`. Its manifest carries the same nine-domain
   exclusion rules as FastReader in both `dataExtractionRules` and
   `fullBackupContent`, guarded by a unit test modelled on `AppVersionTest`
   (merged-manifest `FLAG_ALLOW_BACKUP` clear; rules file lists every domain
   in both sections; no `<include>`). Its debug source set alone carries a
   network security configuration permitting cleartext to `10.0.2.2` only;
   the main manifest references none, so a release build of the host has no
   `networkSecurityConfig` attribute. Its single screen performs one HTTPS
   `GET` to a compiled-in public URL through the platform's
   `HttpsURLConnection` and shows the HTTP status, so "reaches a network" is
   observable on an emulator without choosing an HTTP stack; the stack
   decision (Supabase Kotlin SDK versus direct REST) belongs to #93.
3. **Isolation rules.** `settings.gradle.kts` includes both modules;
   `gradle/libs.versions.toml` and the root `build.gradle.kts` gain the
   `android.library` plugin alias. No module name, directory, Gradle path,
   package, namespace, applicationId, or resource contains `fastreader`.
   Nothing under `app/`, `scripts/release.sh`, `docs/privacy-statement.md`,
   or the FastReader release-note text changes. The host app is never
   signed with the FastReader release key and never enters
   `scripts/release.sh`.
4. **Documentation.** `docs/agent-first-development.md` "Project bindings"
   gains a row for the two modules (install and launch of the host under its
   own application id). `README.md` gains one short section stating that the
   repository also carries the standalone Reader auth module and its host
   app under a separate application id, and that FastReader's no-network
   statement is unchanged and applies to the FastReader app; the privacy
   statement itself is not edited.

Reversibility: every choice above is a naming or wiring choice except
placement (see Assumptions). Lifting the library later is a directory move;
the invariant "no dependency on `:app` and no `fastreader` naming" is what
makes that move a copy rather than a refactor.

## Issue publication manifest

| Key | Kind | Parent | Repository | Title | Delivery | Blocked by | Issue |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ROOT | LEAF | None | cedagova/fastReader | A83-F007 — This repository's no-network product guarantee (REQ-050) is enforced by a release gate and a published privacy statement, so the Android auth work cannot live inside the FastReader app | None | None | https://github.com/cedagova/fastReader/issues/92 |

## Acceptance coverage

Every acceptance condition of root #92 maps to ROOT; the direction gives each
an observable check:

| Root acceptance | Covered by |
| --- | --- |
| The reusable module and its host app build and run without any change to the FastReader application module, manifest, release script, or privacy statement | Directions 1–3; `./gradlew :reader-auth:assembleDebug :reader-auth-host:assembleDebug testDebugUnitTest lint`; `git diff --stat main -- app/ scripts/release.sh docs/privacy-statement.md` empty on the PR; emulator install and launch of the host with the HTTPS status visible |
| `scripts/release.sh` on the FastReader release APK still passes with no network permission | Direction 3; `scripts/release.sh` without `--publish` on the PR head (build, signature, badging: no `INTERNET`, minSdk 26, version match) |
| The module has no dependency on FastReader code, so the real Reader client can depend on it directly | Directions 1 and 3; `./gradlew :reader-auth:dependencies` shows no `project :app`; `:app:dependencies` shows neither new module; naming invariant checked by review |
| Constraint: host carries the full-domain backup exclusion | Direction 2; host unit test modelled on `AppVersionTest` |
| Constraint: debug-only cleartext config never reaches a release artifact | Direction 2; `aapt2 dump xmltree` of the host's release APK (unsigned is fine) shows `INTERNET` and no `networkSecurityConfig`; the debug config file lists `10.0.2.2` only |

No orphan or overlapping outcome: the root is the only node. #93 remains a
separately planned root that will record its native dependency on #92 in its
own plan.

## Validation and feedback

- Bounded builds and tests: `./gradlew testDebugUnitTest lint` (JDK 21),
  plus the two module `assembleDebug` tasks and the host `assembleRelease`
  for the manifest check; hosted `checks.yml` on the PR. The implementer may
  extend the workflow's report-upload globs to the new modules; not required.
- `scripts/release.sh` dry run (no `--publish`) with the machine-local
  keystore; its own output is the REQ-050 proof.
- One emulator run on `Phone_Mid_API36` under the shared emulator lock
  (`mkdir ~/worktrees/fastReader/.emulator.lock`, pid inside, `rmdir` when
  done): `:reader-auth-host:installDebug`, `am start` on the host's
  application id, screencap showing the HTTPS status, `adb logcat -d -s
  AndroidRuntime:E` empty, and `pm list packages` showing both
  `com.cedagova.fastreader` (if installed) and the host id as distinct
  packages. Screencaps are read, not inferred from exit codes.
- Backup exclusion at runtime (`bmgr`) is not re-proven here: the static
  gate is this leaf's acceptance and #93's own acceptance carries the
  backup/transfer runtime test once a token store exists.
- Reviewer verifies in its own detached worktree and never writes into the
  lead's worktree or `docs/evidence/`.

## Assumptions and open questions

### Placement — proceeding on the recommendation; owner or reviewer may overturn

The root says placement is decided at planning. The lead proceeds on the
option below and records the brief so the owner can overturn it with one
reply; no other decision is open.

**Problem.** The reusable module and its host app need a home. It must let
the module be lifted into the real Reader client unchanged, and it must keep
FastReader's shipped artifact and guarantees untouched.

**Facts.** The dossier, umbrella #94, #92, and #93 live in this repository.
CI (`checks.yml`), the emulator matrix, the backup-exclusion pattern and its
runtime proof, and the agent-first loop already exist here. `checks.yml`
picks up new modules without edits. `scripts/release.sh` builds `:app` only.
The real Reader client's repository and package naming are not yet known.

**Assumptions.** A self-contained module directory with no `:app` dependency
can be moved to another repository later by copying or `git subtree split`
with history. The owner will not want FastReader's release process to ever
package the host app.

**Option A — this repository, two new modules beside `:app` (recommended).**
Behaviour: `settings.gradle.kts` grows two modules; hosted checks cover them
immediately. Benefit: zero setup, evidence and issues stay in one place, #93
can start as soon as this leaf merges. Risk: a reader of the repository sees
`INTERNET` in the tree; mitigated by the README section and the naming
invariant. Reversibility: high (directory move later). Execution: one PR.

**Option B — a dedicated new repository.** Behaviour: new repo, new CI,
identity guards, emulator docs, and evidence conventions recreated;
#92/#93 remain here with cross-repository links. Benefit: the module is
"already extracted". Risk: a second toolchain to keep current; issue and
audit provenance split across repositories; delays #93 by the bootstrap.
Reversibility: high but wasteful. Execution: repository creation plus a
multi-repository plan.

**Option C — inside `:app` behind a flavour.** Rejected by the owner's
2026-09-11 direction: it would put `INTERNET` and an HTTP stack into the
FastReader artifact family and falsify the privacy statement.

**Recommendation.** Option A, because it is the smallest safe step, is fully
reversible, and every acceptance condition of the root is provable here
today. **Blocked if overturned:** the whole leaf (its Gradle wiring is the
outcome). **Exact reply to overturn:** `Choose B` on the planning PR; the
lead then re-plans as `MULTI_REPOSITORY` after the owner creates the
repository.

### Non-material assumptions

- Module names, directories, namespace, and applicationId are the
  suggestions in the direction; the implementer may pick others within the
  invariant (no `fastreader` anywhere; applicationId differs from
  `com.cedagova.fastreader`).
- The probe URL is any public HTTPS endpoint compiled into the host; it is a
  reachability smoke, not a reader-api contract, and #93 replaces it.

## Satisfaction proof

Implementation work remains; this is not an `ALREADY_SATISFIED` plan.

## Publication verification

- `plan validate --phase review-ready` on this directory: valid (recorded
  on the planning PR with the semantic digest of the reviewed head).
- `plan verify-graph`: the live root #92 has no sub-issues and no
  blocked-by or blocking edges, matching the one-row manifest; its native
  parent remains the audit umbrella #94, which this plan neither claims nor
  changes.
- The root issue carries `Planning root`, `Planning plan`, and
  `Planning kind: LEAF` beside its preserved `Audit handoff:
  PLANNING_REQUIRED` marker, and the implementation leaf contract below the
  preserved audit record.
- Exact-head approval lives in the native PR review, not here.
