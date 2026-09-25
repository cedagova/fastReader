// The throwaway host app of scripts/library-copy-check.sh (#207): a minified
// release build over the copied libraries.
//
// The keep rules the serialization runtime embeds in its jar are ignored on
// purpose: they keep every @Serializable class of every package, so with them a
// module's own rules could be missing and nothing would show it. (The script
// also empties the copied consumer rules of every module that keeps types it
// does not own — today :reader-auth — for the same reason.)
plugins {
    // The copied app convention, as a Reader client applies it.
    id("conventions.android.application")
    // For the isolation canary (canary/IsolationCanary.kt) only.
    alias(libs.plugins.kotlin.serialization)
}

fun listed(name: String): List<String> = rootProject.file(name).readLines().map(String::trim).filter(String::isNotEmpty)

android {
    namespace = "example.librarycopycheck"

    defaultConfig {
        applicationId = "example.librarycopycheck"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "host-rules.pro")
            optimization {
                keepRules {
                    ignoreFrom("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm")
                }
            }
        }
    }
}

// Every copied module, as the host's app depends on them, and the test
// fixtures the copy set lists, as the host's unit tests depend on them (#199).
dependencies {
    listed("copied-modules.txt").forEach { implementation(project(":$it")) }
    listed("fixture-modules.txt").forEach { testImplementation(testFixtures(project(":$it"))) }
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
