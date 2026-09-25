// The one place every module's shared build settings live (A197-F008, #205).
//
// This is an included build, not buildSrc: it is wired in through the root
// settings' pluginManagement, so editing a convention recompiles this build only
// and a module opts in by applying a plugin id instead of copying settings. It
// reads the root version catalog, so SDK levels, the JVM target and tool
// versions are declared once, in gradle/libs.versions.toml.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
