# `:reader-engine` — EPUB, content and RSVP timing engines

The pure-Kotlin half of reading a book: open an EPUB, turn its spine into one
stable token stream, and time each word for RSVP. Moved unchanged out of `:app`
by [#201](https://github.com/cedagova/fastReader/issues/201) (audit finding
A197-F004) so a Reader client can reuse it instead of copying it.

## Contract

- **Android-free.** A plain Kotlin/JVM module (`conventions.kotlin.library`):
  the Android SDK is not on its compile classpath. It uses only the JDK
  (`java.util.zip`, `javax.xml`, `MessageDigest`), kotlinx.coroutines and the
  kotlinx.serialization runtime.
- **Depends on nothing under `:app`.** `:app` is its host, exactly as it is
  `:reader-auth`'s and `:reader-library`'s.
- **Deterministic positions.** The same bytes always yield the same tokens at
  the same indices. Any change that would move a stored position bumps
  `ContentPipelineVersion.CURRENT` (see `content/TokenStream.kt`).
- **Explicit public surface**, recorded in `api/reader-engine.api` and checked
  by `./gradlew check`.

## Packages

Imports run one way only: `timing` → `content` → `epub`.

| Package | What it holds | Start here |
| --- | --- | --- |
| `com.cedagova.fastreader.epub` | Archive access and inspection: central-directory or streaming zip reads, safe XML, metadata and cover, the structural fingerprint that guards a stored position. | `EpubInspector.inspect`, `EpubByteSource`, `FileEpubByteSource`, `EpubArchives.open` |
| `com.cedagova.fastreader.content` | XHTML → token stream: markup scanning, front matter, TOC titles, tokenizer and word classes; the book's identity. | `EpubContentPipeline.parse` → `BookContentResult`; `BookIdentity`, `BookDigest.of`; `Tokenizer.tokenize` for text that is already in blocks |
| `com.cedagova.fastreader.timing` | RSVP pacing: per-token duration, ramp-up, pause strength, and the remaining-time index that also yields a book's mean multiplier. | `RsvpTimingEngine`, `TimingSettings`, `RemainingTimeIndex.build` |

The packages keep the `com.cedagova.fastreader` names they had in `:app`; the
move changed no caller's import.

## What it is not

- It keeps no state between calls and persists nothing. Where a book's bytes
  come from (`EpubByteSource`), where positions are stored, and on which
  dispatcher the host waits are the host's.
- It has no UI and no reading-session logic: which token is on screen, when
  playback pauses and how a position is saved stay in the host.

## Tests and fixtures

- `src/test` holds the engine tests (`./gradlew :reader-engine:test`; the
  repository-wide `testDebugUnitTest` runs them too).
- `src/testFixtures` holds the EPUB and content fixtures (`EpubFixtures`,
  `ContentFixtures`, `TestByteChannel`). A host's tests use them with
  `testImplementation(testFixtures(project(":reader-engine")))`, as `:app`'s
  JVM and on-device tests do.

## Taking it into another repository

Copy it as part of the copy set in
[docs/library-consumption.md](../docs/library-consumption.md); its version and
changes are in `build.gradle.kts` and [CHANGELOG.md](CHANGELOG.md). The keep
rules a shrinking host needs ship inside the jar
(`src/main/resources/META-INF/proguard/reader-engine.pro`).
