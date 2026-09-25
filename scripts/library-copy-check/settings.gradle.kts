// The throwaway host project scripts/library-copy-check.sh assembles (#207).
//
// It stands in for the Reader client: the copy set from
// docs/library-consumption.md is copied beside this file exactly as a host
// would copy it, and nothing else of fastReader is present. The script writes
// the copied module names into copied-modules.txt.
pluginManagement {
    // The copied shared build conventions.
    includeBuild("build-logic")
    repositories {
        google()
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

rootProject.name = "library-copy-check"
file("copied-modules.txt").readLines().map(String::trim).filter(String::isNotEmpty).forEach { include(":$it") }
include(":consumer")
