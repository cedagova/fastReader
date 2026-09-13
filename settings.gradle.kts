pluginManagement {
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
// The standalone Reader auth library and its proving-ground host app (#92).
// Neither depends on :app and :app depends on neither; they share only the
// toolchain. See reader-auth/README.md.
include(":reader-auth")
include(":reader-auth-host")
