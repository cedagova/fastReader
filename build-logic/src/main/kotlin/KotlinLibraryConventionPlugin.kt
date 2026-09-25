import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * `conventions.kotlin.library`: an Android-free Kotlin/JVM library module (#201)
 * with the shared settings — the catalog's JVM target, the formatter, and the
 * same deliberate public surface as the Android libraries (explicit-API mode and
 * a committed ABI dump checked by `check`).
 *
 * Plain JVM on purpose: the Android SDK is not on its compile classpath, so an
 * `android.*` import in such a module is a compile error, not a review finding.
 */
class KotlinLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("java-library")
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            val jvmTarget = libs.version("jvmTarget")

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.toVersion(jvmTarget)
                targetCompatibility = JavaVersion.toVersion(jvmTarget)
            }
            extensions.configure<KotlinJvmProjectExtension> {
                compilerOptions {
                    this.jvmTarget.set(JvmTarget.fromTarget(jvmTarget))
                }
            }

            // The repository's unit-test entry point is `testDebugUnitTest` (CI,
            // the verification loop, the copy check's `test` entries). A JVM
            // module's tests are its `test` task, so the name is aliased to it
            // and `./gradlew testDebugUnitTest` still runs every module's tests.
            tasks.register("testDebugUnitTest") {
                group = "verification"
                description = "Runs this JVM module's unit tests (alias of `test`)."
                dependsOn("test")
            }

            configureJvmLibraryApiSurface()
            configureKotlinFormatting(
                kotlinSources = listOf("src/**/*.kt"),
                gradleScripts = listOf("*.gradle.kts"),
            )
        }
    }
}
