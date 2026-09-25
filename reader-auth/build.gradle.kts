// The reusable Reader authentication library (#92, A83-F007; contract and
// implementation #93, A83-F008).
//
// This module is what the real Reader client will depend on, so it must stay
// liftable: no dependency on :app, no com.cedagova.fastreader symbol, no
// FastReader naming anywhere. It owns the one thing every host needs merged in
// from it — the INTERNET permission in src/main/AndroidManifest.xml — and it
// documents in README.md the host obligations a library cannot enforce through
// manifest merging. CONTRACT.md is the client contract this module implements;
// contracts/ holds the one pinned reader-api document both libraries are gated
// against (#208), and ReaderAuthContractTest is this module's drift gate.
plugins {
    // Shared SDK, JVM, lint, test and formatter settings (build-logic, #205).
    id("conventions.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cedagova.reader.auth"

    defaultConfig {
        // What a shrinking host must keep for this module's dependencies; see
        // the file for why.
        consumerProguardFiles("consumer-rules.pro")
    }

    // The reader-api contract checker (#208): test support, shared with
    // :reader-library's contract test, which compiles the same source.
    sourceSets {
        getByName("test").kotlin.directories.add("src/contractTest/kotlin")
    }
}

// ReaderAuthContractTest reads contracts/ from the filesystem, not from the
// test classpath, so the pinned document is declared an input of the test task;
// otherwise a changed contract file returns the last green result from the
// build cache and the drift gate never runs.
tasks.withType<Test>().configureEach {
    inputs.dir("contracts").withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    // The identity provider's own Kotlin SDK (reader-auth/CONTRACT.md records
    // the three defaults this module overrides) over Ktor's OkHttp engine.
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
