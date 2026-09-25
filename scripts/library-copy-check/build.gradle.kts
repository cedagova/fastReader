// The host's root build puts the Gradle plugins the copied modules and
// build-logic compile against on the build classpath, at the catalog versions
// (build-logic compiles against them compileOnly). A host adds these aliases
// to its own root build file; `com.android.application` comes from the same
// Android Gradle plugin artifact as `com.android.library`.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless) apply false
}
