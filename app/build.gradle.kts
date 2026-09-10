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
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cedagova.fastreader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cedagova.fastreader"
        minSdk = 26
        targetSdk = 37
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

    androidResources {
        // The bundled samples (#48) are stored, not deflated, inside the APK.
        // An EPUB is already a deflated zip, so compressing it again saves
        // almost nothing, and only an uncompressed asset has a file descriptor
        // AssetManager.openFd can hand out — which is what lets the reader seek
        // to the four entries it wants instead of streaming past the rest
        // (REQ-110). See app/src/main/java/.../content/SampleBookSource.kt.
        noCompress += "epub"
    }

    // EPUB fixtures are shared by the JVM tests and the on-device SAF test.
    // AGP 9 compiles Kotlin through its built-in Kotlin support, which reads the
    // source set's `kotlin` directories, not `java`: registering the shared
    // directory on `java` alone left every fixture unresolved at test compile.
    sourceSets {
        getByName("test").kotlin.directories.add("src/sharedTest/java")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/java")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // The missing-translation gate (AD-15, REQ-206).
    //
    // From #55 the app ships `values-es` as well as `values`, and the failure
    // this block exists to prevent is silent: Android resolves a string that
    // `values-es` does not define by falling back to the default resource, so a
    // Spanish device shows an English sentence and nothing anywhere reports it.
    // The only signal is a reader noticing. `MissingTranslation` is exactly that
    // report, and `ExtraTranslation` is its mirror — a Spanish string whose
    // English original was renamed or deleted, which is dead weight the next
    // translator would trust.
    //
    // Both are declared here rather than left at their defaults so that the gate
    // is a property of this repository and survives a lint baseline, a severity
    // default changing between AGP versions, or a future `lint.xml`. `lint` runs
    // on every push and pull request (.github/workflows/checks.yml), and
    // `abortOnError` makes either finding a red run rather than a warning
    // somebody reads later.
    //
    // The escape hatch, when a string genuinely must not be translated, is
    // `translatable="false"` on that string in `values/strings.xml` — a visible,
    // reviewable edit next to the string itself. Loosening this block is not.
    lint {
        error += listOf("MissingTranslation", "ExtraTranslation", "MissingQuantity")
        abortOnError = true
        warningsAsErrors = false
    }
}

// The committed goldens are read by Roborazzi at compare time but are not part
// of any source set, so Gradle did not see them as an input: editing a golden
// left :app:testDebugUnitTest UP-TO-DATE and `verifyRoborazziDebug` reported a
// green gate over a changed reference image. Declaring the directory makes a
// golden edit invalidate the task locally and invalidate the build-cache key on
// CI, so the regression gate always actually runs. This tightens the gate; it
// changes no tolerance.
tasks.withType<Test>().configureEach {
    inputs.dir(layout.projectDirectory.dir("screenshots"))
        .withPropertyName("roborazziGoldens")
        .withPathSensitivity(PathSensitivity.RELATIVE)
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
    implementation(libs.androidx.compose.material.icons.core)
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
