import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessPlugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

/**
 * The formatter and style check: ktlint, run through Spotless.
 *
 * `spotlessCheck` joins the project's `check` task, so `./gradlew check`
 * fails on an unformatted file or a ktlint rule violation; `./gradlew
 * spotlessApply` rewrites the files in place. The rules and their few
 * repository choices live in the root `.editorconfig`, which editors read too.
 */
internal fun Project.configureKotlinFormatting(
    kotlinSources: List<String>,
    gradleScripts: List<String>,
) {
    apply<SpotlessPlugin>()
    val ktlintVersion = libs.version("ktlint")
    val editorConfig = rootProject.file(".editorconfig")

    extensions.configure<SpotlessExtension> {
        kotlin {
            target(kotlinSources)
            ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
        }
        kotlinGradle {
            target(gradleScripts)
            ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
        }
    }
}
