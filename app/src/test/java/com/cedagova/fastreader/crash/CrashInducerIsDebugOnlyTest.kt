package com.cedagova.fastreader.crash

import com.cedagova.fastreader.app.repositoryRoot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crash button ships to nobody.
 *
 * `CrashInducerActivity` exists so the handler, the report and the offer can be
 * exercised on a real device. It also throws on sight, and it is exported so
 * `adb shell am start` can reach it — which is harmless in a debug build and
 * would be a crash button on a reader's phone in a release one.
 *
 * Being in `src/debug` is what makes that impossible: neither the class nor its
 * manifest entry is compiled into or merged for the release variant. That is a
 * property of the layout on disk, so it is asserted about the layout on disk —
 * the only cheap check that stays true when someone later moves a file "so the
 * IDE can see it".
 */
class CrashInducerIsDebugOnlyTest {

    private val inducer = "java/com/cedagova/fastreader/debug/CrashInducerActivity.kt"

    @Test
    fun `the inducer is in the debug source set`() {
        assertTrue(
            "the debug source set no longer holds the inducer",
            sourceSet("debug").resolve(inducer).isFile,
        )
    }

    @Test
    fun `no copy of it is in the shipped source set`() {
        assertFalse(
            "the crash button would ship: ${sourceSet("main").resolve(inducer)}",
            sourceSet("main").resolve(inducer).isFile,
        )
    }

    /**
     * Not just the file: a reference from shipped code would drag the class into
     * the release variant, or fail to compile it. Either way the intent is wrong.
     */
    @Test
    fun `nothing in the shipped source set mentions it`() {
        val mentions = sourceSet("main").walkTopDown()
            .filter { it.isFile }
            .filter { it.readText().contains("CrashInducer") }
            .map { it.relativeTo(repositoryRoot()).path }
            .toList()

        assertEquals("shipped files naming the crash inducer", emptyList<String>(), mentions)
    }

    /** The entry that makes it startable is in the debug manifest and only there. */
    @Test
    fun `only the debug manifest declares it`() {
        assertTrue(
            "the debug manifest does not declare the inducer",
            manifest("debug").contains(".debug.CrashInducerActivity"),
        )
        assertFalse(
            "the shipped manifest declares the inducer",
            manifest("main").contains("CrashInducer"),
        )
    }

    private fun sourceSet(name: String): File =
        File(repositoryRoot(), "app/src/$name").also {
            check(it.isDirectory) { "app/src/$name is missing" }
        }

    private fun manifest(name: String): String =
        sourceSet(name).resolve("AndroidManifest.xml").readText()
}
