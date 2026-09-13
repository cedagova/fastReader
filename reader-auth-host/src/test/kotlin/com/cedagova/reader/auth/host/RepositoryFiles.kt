package com.cedagova.reader.auth.host

import java.io.File

/**
 * The checked-out repository root, found from wherever Gradle runs the JVM
 * tests. The tests here assert on tracked source files (manifests and rule
 * files) rather than on resources, so they walk up to the directory holding
 * `settings.gradle.kts` — a file every Gradle root has, so this helper does not
 * tie the host to anything FastReader-specific.
 */
internal fun repositoryRoot(): File {
    var candidate: File? = File("").absoluteFile
    while (candidate != null) {
        if (File(candidate, "settings.gradle.kts").isFile) return candidate
        candidate = candidate.parentFile
    }
    error("no settings.gradle.kts above ${File("").absoluteFile}")
}

internal fun repositoryFile(path: String): File =
    File(repositoryRoot(), path).also {
        check(it.isFile) { "$path is missing from ${repositoryRoot()}" }
    }
