import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The minimal host application for :reader-auth (#92, A83-F007; sign-in
// screen #93, A83-F008).
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

// The three service values the host hands :reader-auth (CONTRACT.md, "Host
// requirements"). They are public browser-runtime values of the Reader
// deployment, but they are never committed: they come from the untracked
// local.properties or the environment, and an absent value becomes an empty
// BuildConfig string. The hosted CI runner has none of them, so every build,
// lint and unit test must pass with all three empty; at runtime the host then
// shows "not configured" and calls nothing.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun readerValue(propertyKey: String, environmentKey: String): String =
    (localProperties.getProperty(propertyKey) ?: System.getenv(environmentKey) ?: "")
        .trim()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

android {
    namespace = "com.cedagova.reader.auth.host"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cedagova.reader.auth.host"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "READER_SUPABASE_URL", "\"${readerValue("reader.supabaseUrl", "READER_SUPABASE_URL")}\"")
        buildConfigField(
            "String",
            "READER_SUPABASE_PUBLISHABLE_KEY",
            "\"${readerValue("reader.supabasePublishableKey", "READER_SUPABASE_PUBLISHABLE_KEY")}\"",
        )
        buildConfigField("String", "READER_API_BASE_URL", "\"${readerValue("reader.apiBaseUrl", "READER_API_BASE_URL")}\"")
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
        buildConfig = true
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
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
