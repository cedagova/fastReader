import java.util.Properties

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
    (
        providers.gradleProperty("fastreader.keystoreProperties").orNull
            ?: providers.environmentVariable("FASTREADER_KEYSTORE_PROPERTIES").orNull
        )
        ?.let { File(it) }
        ?: defaultKeystorePropertiesPath
val keystoreProperties: Properties? = keystorePropertiesPath
    .takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

// --- Reader account configuration (#100) ------------------------------------
// The three service values FastReader hands :reader-auth (reader-auth/CONTRACT.md,
// "Host requirements"). They are public browser-runtime values of the Reader
// stage deployment, but they are never committed: they come from the untracked
// local.properties or the environment, and an absent value becomes an empty
// BuildConfig string. The hosted CI runner has none of them, so every build,
// lint and unit test must pass with all three empty; at runtime the app then
// shows "Not configured" under Settings > Reader account and calls nothing.
// ReaderAccountConfigTest guards that no value fragment is ever committed.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun readerValue(propertyKey: String, environmentKey: String): String =
    (localProperties.getProperty(propertyKey) ?: System.getenv(environmentKey) ?: "")
        .trim()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

plugins {
    // SDK levels, JVM target, lint, unit-test settings and the formatter come
    // from build-logic (#205); this file keeps only what is the app's own.
    id("conventions.android.application")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.kotlin.serialization)
}

// :reader-engine has no Compose compiler; its immutable value types are declared
// stable here so the reader's UI state can skip recomposition on equal inputs.
composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}

android {
    namespace = "com.cedagova.fastreader"

    defaultConfig {
        applicationId = "com.cedagova.fastreader"
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField(
            "String",
            "READER_SUPABASE_URL",
            "\"${readerValue("reader.supabaseUrl", "READER_SUPABASE_URL")}\"",
        )
        buildConfigField(
            "String",
            "READER_SUPABASE_PUBLISHABLE_KEY",
            "\"${readerValue("reader.supabasePublishableKey", "READER_SUPABASE_PUBLISHABLE_KEY")}\"",
        )
        buildConfigField(
            "String",
            "READER_API_BASE_URL",
            "\"${readerValue("reader.apiBaseUrl", "READER_API_BASE_URL")}\"",
        )
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
                    if (declared.isAbsolute) {
                        declared
                    } else {
                        File(keystorePropertiesPath.parentFile, declaredStoreFile)
                    }
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

    buildFeatures {
        compose = true
        // For the three Reader account values above and nothing else.
        buildConfig = true
    }
}

// The committed goldens are read by Roborazzi at compare time but are not part
// of any source set, so Gradle did not see them as an input: editing a golden
// left :app:testDebugUnitTest UP-TO-DATE and `verifyRoborazziDebug` reported a
// green gate over a changed reference image. Declaring the directory makes a
// golden edit invalidate the task locally and invalidate the build-cache key on
// CI, so the regression gate always actually runs. This tightens the gate; it
// changes no tolerance.
//
// The same holds for PackageGraphTest (#202), which reads the main sources'
// `import` lines: an import that changes no bytecode would otherwise leave the
// task UP-TO-DATE over a new package cycle.
tasks.withType<Test>().configureEach {
    inputs.dir(layout.projectDirectory.dir("screenshots"))
        .withPropertyName("roborazziGoldens")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("src/main/java"))
        .withPropertyName("mainSourcesForPackageGraph")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    // The Reader authentication library (#100). Its manifest is the one place
    // android.permission.INTERNET is declared; manifest merging delivers it to
    // this app, and ReaderAccountManifestTest reads the merge back.
    implementation(project(":reader-auth"))
    // The account-library contract module (#112). It depends on :reader-auth;
    // :app is its host, exactly as it is :reader-auth's.
    implementation(project(":reader-library"))
    // The EPUB, content and RSVP timing engines (#201): Android-free, and they
    // import nothing from :app.
    implementation(project(":reader-engine"))
    // The Reader account pipeline and its one assembly call (#200); it brings
    // :reader-library and :reader-auth with it.
    implementation(project(":reader-account"))
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

    // The libraries' test fixtures (#199): the scripted doubles of their
    // host-facing interfaces the account tests and goldens run against, so
    // this app defines no fake of a library type.
    testImplementation(testFixtures(project(":reader-auth")))
    testImplementation(testFixtures(project(":reader-library")))
    testImplementation(testFixtures(project(":reader-account")))
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
    // The engine's EPUB and content fixtures (#201), shared by the JVM tests and
    // the on-device SAF and pipeline tests.
    testImplementation(testFixtures(project(":reader-engine")))
    androidTestImplementation(testFixtures(project(":reader-engine")))

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
