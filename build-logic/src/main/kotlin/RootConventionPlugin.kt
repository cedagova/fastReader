import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePlugin
import org.gradle.kotlin.dsl.apply

/**
 * `conventions.root`: the root project's share of the gates. The modules format
 * their own sources; this covers the build scripts no module owns and the
 * convention code in build-logic itself, and gives the root a `check` task so
 * `./gradlew check` runs it.
 */
class RootConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply<BasePlugin>()
            configureKotlinFormatting(
                kotlinSources = listOf("build-logic/src/**/*.kt"),
                gradleScripts = listOf("*.gradle.kts", "build-logic/*.gradle.kts"),
            )
        }
    }
}
