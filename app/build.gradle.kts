import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// --- Release versioning -----------------------------------------------------
// version.properties is tracked and is the single source of truth shared by
// this build and scripts/release.sh. See docs/release.md.
val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val appVersionCode = requireNotNull(versionProperties.getProperty("versionCode")) {
    "version.properties is missing versionCode"
}.trim().toInt()
val appVersionName = requireNotNull(versionProperties.getProperty("versionName")) {
    "version.properties is missing versionName"
}.trim()

// --- Release signing --------------------------------------------------------
// Signing material is machine-local and never committed. The build reads a
// keystore.properties from, in order of precedence:
//   1. -Pfastreader.keystoreProperties=<path>
//   2. FASTREADER_KEYSTORE_PROPERTIES=<path>
//   3. ~/.config/fastreader/signing/keystore.properties
// Its storeFile may be absolute or relative to that file's own directory, so a
// backup directory is self-contained. See docs/release.md.
val defaultKeystorePropertiesPath =
    File(System.getProperty("user.home"), ".config/fastreader/signing/keystore.properties")
val keystorePropertiesPath: File =
    (providers.gradleProperty("fastreader.keystoreProperties").orNull
        ?: providers.environmentVariable("FASTREADER_KEYSTORE_PROPERTIES").orNull)
        ?.let { File(it) }
        ?: defaultKeystorePropertiesPath
val keystoreProperties: Properties? = keystorePropertiesPath
    .takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cedagova.fastreader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cedagova.fastreader"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Created only when machine-local signing material is present, so a
        // clone without a keystore still configures, builds debug, and tests.
        if (keystoreProperties != null) {
            create("release") {
                val declaredStoreFile = requireNotNull(keystoreProperties.getProperty("storeFile")) {
                    "$keystorePropertiesPath is missing storeFile"
                }.trim()
                storeFile = File(declaredStoreFile).let { declared ->
                    if (declared.isAbsolute) declared
                    else File(keystorePropertiesPath.parentFile, declaredStoreFile)
                }
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // minSdk 26: every supported device verifies APK Signature
                // Scheme v2/v3, so legacy JAR signing is not needed.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // EPUB fixtures are shared by the JVM tests and the on-device SAF test.
    sourceSets {
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }

    testOptions {
        unitTests {
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
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.uiautomator)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Never publish an unsigned release artifact: fail loudly at the packaging step
// rather than quietly emitting app-release-unsigned.apk.
if (keystoreProperties == null) {
    val missingSigningMessage = buildString {
        append("Release signing material not found at ")
        append(keystorePropertiesPath)
        append(". A release APK must be signed with the one fastReader release key. ")
        append("Point -Pfastreader.keystoreProperties or FASTREADER_KEYSTORE_PROPERTIES ")
        append("at the machine-local keystore.properties (see docs/release.md).")
    }
    tasks.matching { it.name == "packageRelease" || it.name == "packageReleaseBundle" }
        .configureEach { doFirst { throw GradleException(missingSigningMessage) } }
}
