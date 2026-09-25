plugins {
    `kotlin-dsl`
}

// compileOnly on purpose: the root build puts the Android, Kotlin and Spotless
// Gradle plugins on the build classpath (root build.gradle.kts, `apply false`)
// at the catalog versions, and these conventions run against those same
// classes. Nothing here can pull a second plugin version onto the classpath.
dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.spotless.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "conventions.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "conventions.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("root") {
            id = "conventions.root"
            implementationClass = "RootConventionPlugin"
        }
    }
}
