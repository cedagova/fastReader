package com.cedagova.fastreader.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-shell rules of `docs/app-shell.md` that a reviewer would otherwise
 * have to remember: collection is lifecycle-aware and only the composition root
 * casts the application object (A197-F005); no UI file outgrows a readable size
 * and the touch target is declared once (A197-F006); `catalog.json` keeps one
 * writer (A197-F007).
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
    fun `no ui file exceeds the size bound`() {
        val offenders = sources
            .filter { "/ui/" in it.invariantSeparatorsPath }
            .map { it to it.readLines().size }
            .filter { (_, lines) -> lines > MAX_UI_FILE_LINES }
            .map { (file, lines) -> "${file.name}: $lines lines" }
        assertEquals(
            "UI files over $MAX_UI_FILE_LINES lines; split them by section (docs/app-shell.md)",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * `catalog.json` has one writer (#204): a second `CatalogDocument` over the
     * same file would save its own copy of the document over the first one's.
     */
    @Test
    fun `only the device library builds the catalog writer`() {
        val builders = sources.filter { file -> CATALOG_WRITER.containsMatchIn(file.readText()) }
        assertEquals(listOf("DeviceLibrary.kt"), builders.map { it.name })
    }

    @Test
    fun `the touch target is declared once`() {
        val declarations = sources.filter { file -> file.readLines().any { TOUCH_TARGET.containsMatchIn(it) } }
        assertEquals(listOf("Dimens.kt"), declarations.map { it.name })
    }

    @Test
    fun `the scan sees the sources`() {
        assertTrue("found only ${sources.size} files", sources.size >= 50)
    }

    private companion object {
        /** `collectAsState(` but not `collectAsStateWithLifecycle(`. */
        val COLLECT_AS_STATE = Regex("""\bcollectAsState\s*\(""")

        /** A constructor call of the one `catalog.json` writer. */
        val CATALOG_WRITER = Regex("""\bCatalogDocument\s*\(""")

        /** Plan AD-7 (#211): no Kotlin file under `app/src/main/java/**/ui/` is longer. */
        const val MAX_UI_FILE_LINES = 600

        /** A declaration of the 48 dp minimum, under any visibility. */
        val TOUCH_TARGET = Regex("""\bval TouchTarget\b""")
    }
}
