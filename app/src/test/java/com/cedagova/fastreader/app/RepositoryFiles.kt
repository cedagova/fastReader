package com.cedagova.fastreader.app

import java.io.File

/**
 * The checked-out repository root, found from wherever Gradle happens to run the
 * JVM tests.
 *
 * The tests in this package assert that things outside the source set — the
 * release version, the release-notes copy — agree with what the app ships, so
 * they have to read tracked files rather than resources. Walking up to the
 * directory holding `version.properties` keeps that independent of the working
 * directory, which differs between a Gradle run and an IDE one.
 */
internal fun repositoryRoot(): File {
    var candidate: File? = File("").absoluteFile
    while (candidate != null) {
        if (File(candidate, "version.properties").isFile) return candidate
        candidate = candidate.parentFile
    }
    error("no version.properties above ${File("").absoluteFile}")
}

internal fun repositoryFile(path: String): File =
    File(repositoryRoot(), path).also {
        check(it.isFile) { "$path is missing from ${repositoryRoot()}" }
    }
