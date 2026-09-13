import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The minimal host application for :reader-auth (#92, A83-F007).
//
// It exists so the library can be installed, launched and exercised on an
// emulator under an application id of its own, beside FastReader and never
// inside it. It depends on :reader-auth and on nothing under :app. It is not
// signed with the FastReader release key, is not versioned for release, and is
// not built by scripts/release.sh; an unsigned assembleRelease is deliberately
// allowed so the release manifest can be inspected with aapt2.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.cedagova.reader.auth.host"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cedagova.reader.auth.host"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            // Nothing to shrink and no signing config: the release build is
            // only ever an unsigned artifact for manifest inspection.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // HostManifestTest reads the merged manifest back through the
            // package manager.
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
    implementation(project(":reader-auth"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
