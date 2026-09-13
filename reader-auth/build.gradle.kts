import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The reusable Reader authentication library (#92, A83-F007).
//
// This module is what the real Reader client will depend on, so it must stay
// liftable: no dependency on :app, no com.cedagova.fastreader symbol, no
// FastReader naming anywhere. It owns the one thing every host needs merged in
// from it — the INTERNET permission in src/main/AndroidManifest.xml — and it
// documents in README.md the host obligations a library cannot enforce through
// manifest merging. The auth implementation itself lands under #93.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.cedagova.reader.auth"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // The unit test reads the library's *merged* manifest back through
            // the package manager, which needs the Android resources packaged.
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
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
