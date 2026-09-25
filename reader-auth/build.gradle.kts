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

// The public surface is recorded in api/reader-auth.api and checked by
// `./gradlew check` (build-logic, #198). A dependency is `api` only when a
// type of it is in that surface on purpose, and the reason is written beside
// it; everything else stays `implementation`, invisible to a host.
dependencies {
    // The identity provider's own Kotlin SDK (reader-auth/CONTRACT.md records
    // the three defaults this module overrides) over Ktor's OkHttp engine.
    // Implementation only: no provider type is in the surface — a stored
    // session is the module's own opaque StoredSession (#198).
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.ktor.client.okhttp)
    // api: reader-api speaks JSON and the surface says so. PreAuthDocument and
    // ReaderProfileUpdate are @Serializable (their generated serializers are
    // public), ReaderProfileUpdate.metadata is a JsonObject, and
    // ReaderApiClient.get/put/post hand a host the reader-api document as the
    // JsonObject it is, so a host can reach any further route.
    api(libs.kotlinx.serialization.json)
    // api: ReaderAuthClient.sessionState is a Flow.
    api(libs.kotlinx.coroutines.android)
    // api, for one reason that is due to go: ReaderAuthClient.createForTests
    // takes a Ktor HttpClientEngine so a layered module's tests can run the
    // real call policy over a mock engine. The library-owned test seams of
    // #199 (A197-F002) replace it; then this returns to implementation.
    api(libs.ktor.client.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
