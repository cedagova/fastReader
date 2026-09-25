pluginManagement {
    // The shared build conventions (#205): every module applies one of
    // build-logic's plugins instead of repeating SDK, JVM, lint, test and
    // formatter settings.
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "fastReader"
include(":app")
// The reusable Reader auth library (#92, #93). It depends on nothing under
// :app; since #100 FastReader's :app is its host. See reader-auth/README.md.
include(":reader-auth")
// The Reader account-library client (#112). It depends on :reader-auth and on
// nothing under :app; see reader-library/README.md.
include(":reader-library")
// The EPUB, content and RSVP timing engines (#201): Kotlin/JVM, Android-free,
// no dependency on :app. See reader-engine/README.md.
include(":reader-engine")
// The Reader account pipeline (#200): session state, verified copies, downloads,
// imports, the shelf and the one assembly call. It depends on :reader-library
// and on nothing under :app; see reader-account/README.md.
include(":reader-account")
