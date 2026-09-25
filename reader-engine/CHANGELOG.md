# `:reader-engine` changelog

What changed between two versions of this module, newest first. A host that
copies the module (docs/library-consumption.md) reads the entries between its
old and new version for anything it must change. Every change to this
module's files bumps `version` in `build.gradle.kts` and adds an entry here
in the same change.

## 0.1.0 — 2026-09-25

First version: the EPUB, content and RSVP timing engines moved out of `:app`
unchanged (#201, A197-F004). Tokenizer output and
`ContentPipelineVersion.CURRENT` (1) are unchanged.

- `epub`, `content` and `timing` as a plain Kotlin/JVM module with no Android
  dependency, built by `conventions.kotlin.library` (build-logic).
- Packages renamed from `com.cedagova.fastreader.{epub,content,timing}` to
  `com.cedagova.reader.engine.{epub,content,timing}`; a host changes its
  imports. Nothing persisted names a package: `PauseStrength` is stored by
  value.
- `BookDigest` is now in `content` (was `epub`), so `epub` imports nothing from
  `content`.
- `RemainingTimeIndex` is now in `timing` (was `:app`'s `reader` package).
- The public surface is explicit-API and recorded in `api/reader-engine.api`.
  `Tokenizer` and `ContentBlock`, internal while the engines were in `:app`,
  are public because the host's on-device tests use them.
- Keep rules for the module's `@Serializable` types ship in the jar
  (`META-INF/proguard/reader-engine.pro`).
- EPUB and content test fixtures are published as the module's test fixtures.
