package com.cedagova.reader.library

import com.cedagova.reader.auth.testing.CAPABILITIES
import com.cedagova.reader.auth.testing.REFRESH_GRANT
import com.cedagova.reader.library.testing.ReaderLibraryHarness
import java.io.File

// This module's own test support. The shared pieces — the one mock server,
// the settable clock and waiter, the documents it answers with and a signed-in
// client over it (ReaderLibraryHarness) — are the test fixtures of this module
// and of :reader-auth (packages com.cedagova.reader.library.testing and
// com.cedagova.reader.auth.testing, #199). There is deliberately no fake of the
// reader-api client: every test drives a REAL ReaderAuthClient over the mock
// server, because the point is that this module's operations inherit
// :reader-auth's behaviour rather than restate it.

// The routes these tests use, beside the fixtures' CAPABILITIES and REFRESH_GRANT.
const val LIBRARY = "GET /v1/reader/library"
const val PROGRESS = "GET /v1/reader/progress"
const val MUTATIONS = "POST /v1/reader/sync/mutations"
const val DELTAS = "GET /v1/reader/sync/deltas"

/** The repository root, for the test that reads the committed contract document. */
fun repositoryRoot(): File {
    var candidate: File? = File("").absoluteFile
    while (candidate != null) {
        if (File(candidate, "settings.gradle.kts").isFile) return candidate
        candidate = candidate.parentFile
    }
    error("no settings.gradle.kts above ${File("").absoluteFile}")
}
