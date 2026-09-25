import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.named
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * A reusable library's public surface is deliberate and checked (#198, A197-F001).
 *
 * - Explicit-API mode: every declaration states `public` or `internal`, and a
 *   public one states its type, so nothing becomes contract by default.
 * - The Kotlin Gradle plugin's ABI validation keeps the surface in a committed
 *   dump, `api/<module>.api`, taken from the release variant's classes.
 *   `checkKotlinAbi` compares the compiled surface with that file and joins
 *   the module's `check` task, so an unintended public change fails `./gradlew
 *   check` and CI. After a deliberate change, `./gradlew updateKotlinAbi`
 *   rewrites the dump and the diff shows the new surface in review.
 */
@OptIn(ExperimentalAbiValidation::class)
internal fun Project.configureLibraryApiSurface() {
    extensions.configure<KotlinAndroidProjectExtension> {
        explicitApi()
        abiValidation {
            // The committed dump lives beside the module's build file.
            referenceDumpDir.set(layout.projectDirectory.dir("api"))
        }
        // The plugin registers the check but does not attach it to `check`.
        val checkAbi = abiValidation.checkTaskProvider
        tasks.named("check") { dependsOn(checkAbi) }
    }
    feedReleaseClassesToAbiDump()
}

/**
 * Kotlin 2.4.20's ABI validation looks for the Android target's `release`
 * compilation before AGP's built-in Kotlin has created it, so its dump task
 * gets no classes: it would compare an empty surface with an empty file and
 * pass on any change. This hands the dump task the release classes the way
 * the plugin itself does for a single-target JVM module (one JVM input, no
 * subdirectory), with the compile task as a dependency.
 *
 * The task type and its input type are internal to the plugin, so they are
 * reached reflectively. If a later plugin moves them, this throws at
 * configuration and the build stops — it cannot silently check nothing. When
 * the plugin finds the compilation itself, delete this function.
 */
private fun Project.feedReleaseClassesToAbiDump() {
    tasks.matching { it.name == ABI_DUMP_TASK }.configureEach {
        val compileRelease = project.tasks.named<KotlinCompile>("compileReleaseKotlin")
        val classes = project.files(compileRelease.flatMap { it.destinationDirectory })
        val targetInfo = javaClass.classLoader.loadClass(JVM_TARGET_INFO)
            .getConstructor(String::class.java, org.gradle.api.file.FileCollection::class.java)
            .newInstance("", classes)

        @Suppress("UNCHECKED_CAST")
        val jvm = javaClass.getMethod("getJvm").invoke(this) as ListProperty<Any>
        jvm.add(targetInfo)
        dependsOn(compileRelease)
    }
}

private const val ABI_DUMP_TASK = "internalDumpKotlinAbi"
private const val JVM_TARGET_INFO = "org.jetbrains.kotlin.gradle.tasks.abi.KotlinAbiDumpTaskImpl\$JvmTargetInfo"
