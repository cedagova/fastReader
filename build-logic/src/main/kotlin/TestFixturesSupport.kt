import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * The experimental AGP switch that compiles Kotlin sources in an Android
 * library's `src/testFixtures` (#199). Without it AGP builds the fixtures as
 * Java only, and the failure surfaces much later as unresolved Kotlin
 * references in the host's tests.
 */
internal const val TEST_FIXTURES_KOTLIN_FLAG: String = "android.experimental.enableTestFixturesKotlinSupport"

/**
 * Fails the configuration, naming the fix, when an Android library enables test
 * fixtures but the build has not switched on [TEST_FIXTURES_KOTLIN_FLAG]: a host
 * that copied the libraries without their `gradle.properties` line, or an AGP
 * upgrade that stopped honouring the switch (then move the fixtures to a
 * dedicated module; docs/architecture.md).
 */
internal fun Project.requireTestFixturesKotlinSupport(android: LibraryExtension) {
    if (!android.testFixtures.enable) return
    val value = providers.gradleProperty(TEST_FIXTURES_KOTLIN_FLAG).orNull
    if (value != "true") {
        throw GradleException(
            "$path enables test fixtures with Kotlin sources, but $TEST_FIXTURES_KOTLIN_FLAG is " +
                "${value ?: "unset"}. Add `$TEST_FIXTURES_KOTLIN_FLAG=true` to gradle.properties " +
                "(docs/library-consumption.md). If this AGP no longer supports the switch, move the " +
                "fixtures into a dedicated module instead (docs/architecture.md).",
        )
    }
}
