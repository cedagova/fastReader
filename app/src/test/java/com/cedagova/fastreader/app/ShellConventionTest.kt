package com.cedagova.fastreader.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two app-shell rules of `docs/app-shell.md` that a reviewer would otherwise
 * have to remember (A197-F005): collection is lifecycle-aware, and only the
 * composition root casts the application object.
 *
 * The third rule, "Routes never receive the graph", needs no test of its own: a
 * feature package importing `AppGraph` is a cycle with the root package, which
 * `PackageGraphTest` already refuses.
 */
class ShellConventionTest {

    private val sources: List<File> = File(repositoryRoot(), "app/src/main/java")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    @Test
    fun `no screen collects a flow without its lifecycle`() {
        val offenders = sources
            .filter { file -> file.readLines().any { COLLECT_AS_STATE.containsMatchIn(it) } }
            .map { it.name }
        assertEquals("collectAsState() in :app; use collectAsStateWithLifecycle()", emptyList<String>(), offenders)
    }

    @Test
    fun `only the composition root casts the application`() {
        val offenders = sources.filter { file -> "as FastReaderApplication" in file.readText() }
        assertEquals(listOf("FastReaderApplication.kt"), offenders.map { it.name })
    }

    @Test
    fun `the scan sees the sources`() {
        assertTrue("found only ${sources.size} files", sources.size >= 50)
    }

    private companion object {
        /** `collectAsState(` but not `collectAsStateWithLifecycle(`. */
        val COLLECT_AS_STATE = Regex("""\bcollectAsState\s*\(""")
    }
}
