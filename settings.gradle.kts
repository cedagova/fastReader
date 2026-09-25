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
