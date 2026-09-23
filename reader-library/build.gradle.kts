import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
// contracts/ holds the pinned OpenAPI document and its sha256;
// ReaderLibraryContractTest is the drift gate (owner decision P1).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cedagova.reader.library"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// ReaderLibraryContractTest reads contracts/ from the filesystem, not from the
// test classpath, so Gradle does not see the pinned document as an input of
// the test task on its own. Without this declaration a changed contract file
// still returns the last green result FROM-CACHE / UP-TO-DATE and the drift
// gate never runs; the pin only bit under --rerun-tasks --no-build-cache.
tasks.withType<Test>().configureEach {
    inputs.dir("contracts").withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    // api, not implementation: this module's operations take a ReaderApiClient
    // and every failure they raise is a ReaderAuthException, so a host that
    // depends on :reader-library must see :reader-auth's types.
    api(project(":reader-auth"))
    implementation(libs.kotlinx.serialization.json)
    // The account sync engine (#147) is process-scoped and flow-driven: its
    // public API takes a CoroutineScope and a Flow and publishes a StateFlow, so
    // a host must see the coroutine types.
    api(libs.kotlinx.coroutines.android)
    // The publication-import transfer (#116) speaks TUS straight to the storage
    // provider under the grant's signed headers. It is a SECOND, plain client on
    // purpose — it holds no session and cannot reach a token — so it brings its
    // own engine rather than borrowing :reader-auth's authenticated one.
    implementation(libs.ktor.client.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    // The mock-engine harness builds a real ReaderAuthClient over a mock
    // engine (ReaderAuthClient.createForTests), so the tests need the session
    // type its store holds.
    testImplementation(platform(libs.supabase.bom))
    testImplementation(libs.supabase.auth)
}
