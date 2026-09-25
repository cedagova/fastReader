// The Reader account pipeline (#200, A197-F003).
//
// Account session state, the verified private copies of account books, their
// download and the add-to-account import, the account shelf, and the one call
// that assembles all of it over :reader-auth and :reader-library for a host
// (ReaderAccountGraph). It was written inside the host app's :app but is what
// any Reader Android client needs, so it lives here: no dependency on :app, no
// symbol of the host app, and every host coupling it used to have — the device
// catalog, how a device book is identified, the resume-offer note — is a small
// interface the host implements.
//
// It holds no session and no token: every account call goes through
// :reader-auth's ReaderAuthOperations or :reader-library's gateways, and every
// failure it maps is one of ReaderAuthException's branches, mapped in one file
// (AccountErrors.kt).
//
// The public surface is recorded in api/reader-account.api and checked by
// `./gradlew check` (build-logic, #198); each `api` dependency below says why a
// type of it is in that surface.
plugins {
    // Shared SDK, JVM, lint, test and formatter settings (build-logic, #205).
    id("conventions.android.library")
    alias(libs.plugins.kotlin.serialization)
}

// The module's own version: a host copies this directory at the tag
// `reader-account/v<version>` (docs/library-consumption.md); CHANGELOG.md
// beside this file records what changed between two versions.
version = "0.1.0"

android {
    namespace = "com.cedagova.reader.account"

    // src/testFixtures: scripted doubles of this module's host seams, for a
    // host's own tests: testImplementation(testFixtures(project(":reader-account"))).
    testFixtures {
        enable = true
    }

    defaultConfig {
        // What a shrinking host must keep for this module's own serialized
        // types; see the file for why.
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    // api: the pipeline's public types are built from :reader-library's (the
    // sync engine, its state, AccountBook, the import and download gateways),
    // and :reader-library exposes :reader-auth's (ReaderAuthOperations,
    // ReaderAuthClient, ReaderAuthException) and the coroutine types in turn.
    api(project(":reader-library"))
    // The copy references are a @Serializable host record the engine stores
    // verbatim as a JsonElement.
    implementation(libs.kotlinx.serialization.json)

    // The fixtures build on :reader-library's (the one fixture surface, #199).
    testFixturesApi(testFixtures(project(":reader-library")))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // AccountCopyStoreTest places a real EPUB and opens it through the content
    // pipeline; test-only, and :reader-engine is in the same copy set.
    testImplementation(project(":reader-engine"))
    testImplementation(testFixtures(project(":reader-engine")))
}
