// The Reader account-library client (#112, LEAF701 of #104).
//
// It is the contract boundary: typed models and typed operations for the
// account library, reading progress and the sync protocol, on top of the one
// authenticated client :reader-auth owns — and, since #147, the account sync
// engine any Reader client can reuse (package com.cedagova.reader.library.sync). Like :reader-auth it must stay
// liftable — no dependency on :app, no com.cedagova.fastreader symbol, no
// FastReader naming — because it is the half of this work the owner may later
// propose upstream (AD-19).
//
// Tokens never leave :reader-auth: this module holds no session, no refresh
// and no error policy of its own, and every failure it surfaces is one of
// ReaderAuthException's existing branches, thrown through unchanged.
//
// The pinned OpenAPI document both libraries share lives in
// reader-auth/contracts/ (#208); ReaderLibraryContractTest is this module's
// drift gate against it (owner decision P1).
//
// The public surface is recorded in api/reader-library.api and checked by
// `./gradlew check` (build-logic, #198); each `api` dependency below says why a
// type of it is in that surface.
plugins {
    // Shared SDK, JVM, lint, test and formatter settings (build-logic, #205).
    id("conventions.android.library")
    alias(libs.plugins.kotlin.serialization)
}

// The module's own version: a host copies this directory at the tag
// `reader-library/v<version>` (docs/library-consumption.md); CHANGELOG.md
// beside this file records what changed between two versions.
version = "0.1.0"

android {
    namespace = "com.cedagova.reader.library"

    defaultConfig {
        // What a shrinking host must keep for this module's own serialized
        // types; see the file for why.
        consumerProguardFiles("consumer-rules.pro")
    }

    // The shared reader-api contract checker (#208) is :reader-auth's test
    // support; this module's contract test compiles the same source.
    sourceSets {
        getByName("test").kotlin.directories.add("../reader-auth/src/contractTest/kotlin")
    }
}

// ReaderLibraryContractTest reads reader-auth/contracts/ from the filesystem,
// not from the test classpath, so Gradle does not see the pinned document as an
// input of the test task on its own. Without this declaration a changed
// contract file still returns the last green result FROM-CACHE / UP-TO-DATE and
// the drift gate never runs; the pin only bit under --rerun-tasks
// --no-build-cache.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("reader-auth/contracts")).withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    // api, not implementation: this module's operations take a ReaderApiClient
    // and every failure they raise is a ReaderAuthException, so a host that
    // depends on :reader-library must see :reader-auth's types.
    api(project(":reader-auth"))
    // api: the contract models are @Serializable (their generated serializers
    // are public), and a host's own records in the account document
    // (AccountHostRecords) are the JsonElement values the engine stores
    // verbatim. :reader-auth exposes it for the same reason.
    api(libs.kotlinx.serialization.json)
    // The account sync engine (#147) is process-scoped and flow-driven: its
    // public API takes a CoroutineScope and a Flow and publishes a StateFlow, so
    // a host must see the coroutine types.
    api(libs.kotlinx.coroutines.android)
    // The publication-import transfer (#116) speaks TUS straight to the storage
    // provider under the grant's signed headers. It is a SECOND, plain client on
    // purpose — it holds no session and cannot reach a token — so it brings its
    // own engine rather than borrowing :reader-auth's authenticated one.
    // AssetDownloadClient.createForTests and PublicationTransferClient.createForTests
    // take a Ktor HttpClientEngine; that type reaches a host through
    // :reader-auth's `api` Ktor core, for the same reason and until the same
    // change (#199, A197-F002).
    implementation(libs.ktor.client.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
