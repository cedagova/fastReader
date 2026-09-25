# How another repository takes the Reader libraries

**Mode: source copy at a tagged library version.** A host — the Reader
Android client first — copies the files listed below from a library tag of
this repository into its own tree. There is no published artifact, no
registry, no submodule and no cross-repository build.

Decided by the owner on 2026-09-25 (decision D1 = A in the
[#211 plan](https://github.com/cedagova/fastReader/pull/212); recorded by
[#207](https://github.com/cedagova/fastReader/issues/207), audit finding
A197-F010). It is reversible: the same self-contained copy set is what an
included build or a published artifact would need first.

## The copy set

This block is the exact list, and the one place to extend it.
[`scripts/library-copy-check.sh`](../scripts/library-copy-check.sh) reads it,
so a module, file or catalog entry that is not listed here is not in the copy
the check builds.

<!-- copy-set:begin -->
```text
# module <dir> [<package>]
#   A library module directory, copied whole (sources, tests, api dump,
#   README, CHANGELOG, consumer rules) and included as :<dir>. <package> names
#   the package whose @Serializable types the module's own consumer rules keep;
#   the check proves those rules suffice on their own.
module reader-auth
module reader-library com.cedagova.reader.library

# path <file or directory>
#   Shared build pieces the modules need, copied verbatim.
path build-logic
path .editorconfig
# Inside reader-auth/ and so already copied with it; listed on their own
# because :reader-library's contract test reads both through ../reader-auth/,
# so a host that trims reader-auth/ must keep them.
path reader-auth/contracts
path reader-auth/src/contractTest

# test <module> <test class pattern>
#   Copied tests the check runs inside the copy: the two contract drift gates,
#   which read the pinned document and the shared checker above.
test reader-auth *ContractTest
test reader-library *ContractTest

# fixtures <module>...
#   Listed modules whose test fixtures (src/testFixtures, copied with the
#   module) a host's unit tests use (#199); the check runs a host test on them.
fixtures reader-auth reader-library

# versions | libraries | plugins <keys...>
#   The gradle/libs.versions.toml entries the copied build files and
#   build-logic read. A host merges these lines into its own catalog.
versions compileSdk targetSdk minSdk jvmTarget agp kotlin spotless ktlint
versions coroutines serialization supabase ktor
versions junit robolectric androidxTestCore androidxTestJunit
libraries android-gradlePlugin kotlin-gradlePlugin spotless-gradlePlugin
libraries kotlinx-coroutines-android kotlinx-serialization-json
libraries supabase-bom supabase-auth ktor-client-core ktor-client-okhttp
libraries junit robolectric androidx-test-core androidx-test-ext-junit kotlinx-coroutines-test ktor-client-mock
plugins android-library kotlin-serialization spotless
```
<!-- copy-set:end -->

What each piece is for:

| Piece | Why the modules need it |
| --- | --- |
| `reader-auth/` | The auth library. |
| `reader-auth/contracts/` | The one pinned reader-api contract document, its `.sha256` and `PINNED.md` (identity and update procedure, #208). Both libraries' contract tests read it from the filesystem. |
| `reader-auth/src/contractTest/` | The shared contract checker. `:reader-auth`'s test source set includes it, and `:reader-library`'s build adds `../reader-auth/src/contractTest/kotlin` to its own, so the two module directories stay siblings. |
| `reader-library/` | The account-library client and sync engine. |
| Test fixtures (`src/testFixtures/` of both modules) | The one test-fixture surface (#199): the scripted doubles of the modules' interfaces, the one mock reader-api/identity-provider server and a real client over it. A host's tests take them with `testImplementation(testFixtures(project(":reader-auth")))` (and `":reader-library"`); a host writes no fake of a library type. |
| `build-logic/` | The convention plugins every module applies (`conventions.android.library`; the app and root conventions come along in the same build). |
| `.editorconfig` | The ktlint rules the conventions' formatter reads from the root. |
| Catalog entries | SDK levels, JVM target and tool versions build-logic reads, the Gradle plugins it compiles against, and every dependency the two build files declare. |

## Versions and change records

- Each module declares its own `version = "x.y.z"` near the top of its
  `build.gradle.kts` and keeps a `CHANGELOG.md` beside it, newest entry first,
  one `## x.y.z — date` heading per version.
- A change to a module's files bumps its version and adds the entry in the
  same change: patch for a fix or an internal change a host cannot see, minor
  for a new or changed public API (the `api/<module>.api` dump shows which).
  Until 1.0.0 a minor bump may break a host; the entry says what to change.
- A library version is tagged once it is on `main`: `<module>/v<version>`
  (for example `reader-library/v0.1.0`), on the `main` commit that first
  carries it. App releases keep their own `v<versionName>` tags.
- The copy check refuses a module whose `CHANGELOG.md` has no entry for the
  version its build file declares.

## Copying (and updating) into a host

1. Pick a tag for each module, and use the same commit for modules that
   depend on each other (`:reader-library` depends on `:reader-auth`).
2. Replace the host's copies of every path in the block with the files
   tracked at that commit (`git archive <tag> <paths> | tar -x`); do not copy
   build output or `local.properties`.
3. Merge the listed catalog lines into the host's `gradle/libs.versions.toml`.
4. In the host's settings, `includeBuild("build-logic")` inside
   `pluginManagement` and `include(":reader-auth", ":reader-library")`; in the
   host's root build file, declare the three listed plugins `apply false` so
   build-logic runs against those plugin classes.
5. Set `android.useAndroidX=true` in the host's `gradle.properties` — and
   `android.experimental.enableTestFixturesKotlinSupport=true`, which Kotlin
   in the modules' test fixtures needs — and meet
   the host obligations in [reader-auth/README.md](../reader-auth/README.md)
   (backup exclusion, no cleartext in release, its own application id).
6. Read each module's `CHANGELOG.md` between the old and new versions for
   anything the host must change.

The template the check builds, `scripts/library-copy-check/`, is a working
example of steps 3–4 with nothing else in it.

## Keep rules

Each module ships the R8 rules a shrinking host needs as consumer rules
(`consumer-rules.pro`, wired with `consumerProguardFiles`), so a host adds no
rule of its own for them:

- `:reader-library` keeps its own `@Serializable` types (package
  `com.cedagova.reader.library`): the `Companion` field and its
  `serializer()`, or an object's `INSTANCE` and `serializer()`, which
  kotlinx.serialization looks up by reflection when a host resolves a
  serializer by class or type.
- `:reader-auth` keeps the same members for every `@Serializable` class
  (its own and the identity provider SDK's) plus the JSON element
  serializers; see the file.

Today the serialization runtime's jar embeds the same rules for every package,
so nothing breaks without the modules' rules. The modules still carry their
own so that none of them depends on another module's rules, or on a host
keeping a dependency's embedded rules (AGP lets a host drop them with
`keepRules { ignoreFrom(…) }`).

## The check

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
scripts/library-copy-check.sh          # --keep leaves the temp project for a look
```

It copies the set into a fresh temp directory with a catalog of only the listed
entries, adds the throwaway host in `scripts/library-copy-check/` (an app that
applies the copied app convention and depends on every copied module), runs
its `:consumer:minifyReleaseWithR8` together with the `test` entries' copied
tests and — for the `fixtures` entries — the host's own unit test against the
modules' test fixtures, and reads R8's mapping. It fails when:

- a module has no version, or no `CHANGELOG.md` entry for it;
- a listed path or catalog key does not exist;
- the copied builds need a file or catalog entry the list lacks (the host's
  Gradle build fails);
- a listed copied test fails in the copy (for the contract tests: the pinned
  document or the shared checker is missing or not where the builds read it);
- a `fixtures` module has no test fixtures, or the host's test cannot build or
  pass against them;
- after R8, a `@Serializable` type of a module named with a package in the
  block has lost the members above. The host build ignores the serialization
  jar's embedded rules, and the script empties the copied `consumer-rules.pro`
  of every module listed without a package (today `:reader-auth`, whose rules
  reach every package; AGP's `ignoreFrom` cannot skip a project module), so
  only the module's own rules can keep them. The check also fails if any rule
  R8 received still reaches every package's `@Serializable` classes, since
  then it could not tell.

The hosted gate (`.github/workflows/checks.yml`) runs it on every pull-request
commit and every `main` commit. It deletes its temp directory on exit and
writes nothing else.

## Adding a module

A new reusable module (`:reader-account` of #200, the engine modules of
#201) joins in the same change
that creates it:

1. Give it `version = "0.1.0"`, a `CHANGELOG.md` and, if it has
   `@Serializable` types, a `consumer-rules.pro` scoped to its own package
   like `reader-library/consumer-rules.pro`.
2. Add a `module <dir> [<package>]` line to the block above, plus any catalog
   keys its build file reads that the block does not list yet; if it ships
   test fixtures, add it to the `fixtures` line.
3. Run `scripts/library-copy-check.sh`.
