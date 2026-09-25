import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).orElseThrow { IllegalStateException("gradle/libs.versions.toml has no version '$alias'") }
        .requiredVersion

/**
 * The settings every Android module shares: SDK levels, the Java/Kotlin JVM
 * target, lint gates, unit-test settings and the formatter. A module's own
 * build file keeps only what is genuinely its own (namespace, dependencies,
 * signing, build config fields).
 */
internal fun Project.configureAndroidModule(android: CommonExtension) {
    val jvmTarget = libs.version("jvmTarget")

    android.apply {
        compileSdk = libs.version("compileSdk").toInt()

        defaultConfig.apply {
            minSdk = libs.version("minSdk").toInt()
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compileOptions.apply {
            sourceCompatibility = JavaVersion.toVersion(jvmTarget)
            targetCompatibility = JavaVersion.toVersion(jvmTarget)
        }

        testOptions.unitTests.apply {
            // Robolectric tests read Android resources and merged manifests
            // back (the goldens, ReaderAccountManifestTest, the auth store
            // under the real no-backup directory), so resources are packaged
            // for every module's unit tests.
            isIncludeAndroidResources = true
        }

        // The missing-translation gate (AD-15, REQ-206), applied to every
        // module so a library can never regress on a rule the app enforces.
        //
        // Android resolves a string that `values-es` does not define by
        // falling back to the default resource, so a Spanish device shows an
        // English sentence and nothing reports it. `MissingTranslation` is
        // exactly that report, and `ExtraTranslation` is its mirror — a
        // Spanish string whose English original was renamed or deleted.
        //
        // They are declared here rather than left at their defaults so that
        // the gate is a property of this repository and survives a lint
        // baseline, a severity default changing between AGP versions, or a
        // future `lint.xml`. `lint` runs on every push and pull request
        // (.github/workflows/checks.yml), and `abortOnError` makes a finding
        // a red run rather than a warning somebody reads later.
        //
        // The escape hatch, when a string genuinely must not be translated, is
        // `translatable="false"` on that string in `values/strings.xml` — a
        // visible, reviewable edit next to the string itself. Loosening this
        // block is not.
        lint.apply {
            error += listOf("MissingTranslation", "ExtraTranslation", "MissingQuantity")
            abortOnError = true
            warningsAsErrors = false
        }
    }

    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            this.jvmTarget.set(JvmTarget.fromTarget(jvmTarget))
        }
    }

    configureKotlinFormatting(
        kotlinSources = listOf("src/**/*.kt"),
        gradleScripts = listOf("*.gradle.kts"),
    )
}
