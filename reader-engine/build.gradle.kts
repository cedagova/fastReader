// The EPUB, content and RSVP timing engines (#201, A197-F004).
//
// Archive → XHTML → tokens → per-word timing, in three packages that import in
// one direction only: `timing` → `content` → `epub`. They were written inside
// :app but never needed Android, and a Reader client needs them unchanged, so
// they live here as a plain Kotlin/JVM module: the Android SDK is not on the
// compile classpath, and nothing here depends on :app.
//
// The packages keep their original `com.cedagova.fastreader.*` names: the move
// is behavior-neutral and every caller's import stays as it was.
//
// The public surface is recorded in api/reader-engine.api and checked by
// `./gradlew check` (build-logic); each `api` dependency below says why a type
// of it is in that surface.
plugins {
    // Shared JVM target, formatter and public-surface settings (build-logic).
    id("conventions.kotlin.library")
    alias(libs.plugins.kotlin.serialization)
    // The EPUB and content fixtures in src/testFixtures, shared with :app's
    // JVM and on-device tests.
    `java-test-fixtures`
}

// The module's own version: a host copies this directory at the tag
// `reader-engine/v<version>` (docs/library-consumption.md); CHANGELOG.md
// beside this file records what changed between two versions.
version = "0.1.0"

dependencies {
    // api: EpubContentPipeline takes a CoroutineDispatcher and parses in a
    // suspend function, so a host must see the coroutine types.
    api(libs.kotlinx.coroutines.core)
    // api: the timing settings types are @Serializable (a host stores them in
    // its own settings document), and their generated serializers are public.
    api(libs.kotlinx.serialization.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
