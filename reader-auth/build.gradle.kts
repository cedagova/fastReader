import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The reusable Reader authentication library (#92, A83-F007; contract and
// implementation #93, A83-F008).
//
// This module is what the real Reader client will depend on, so it must stay
// liftable: no dependency on :app, no com.cedagova.fastreader symbol, no
// FastReader naming anywhere. It owns the one thing every host needs merged in
// from it — the INTERNET permission in src/main/AndroidManifest.xml — and it
// documents in README.md the host obligations a library cannot enforce through
// manifest merging. CONTRACT.md is the client contract this module implements.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cedagova.reader.auth"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        // What a shrinking host must keep for this module's dependencies; see
        // the file for why.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // The manifest test reads the library's *merged* manifest back
            // through the package manager, and the store test writes under the
            // application's real no-backup directory; both need Android
            // resources packaged.
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
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
