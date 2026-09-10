package com.cedagova.fastreader.crash

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The single slot the offer reads from, including the failures it has to absorb
 * rather than raise: it is written by a dying process and read at launch.
 */
class CrashReportStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun store(directory: File = temporary.root.resolve("crash")) =
        CrashReportStore(directory)

    @Test
    fun `a report that was written is the report that is read back`() {
        val store = store()

        assertTrue(store.write("a report"))

        assertEquals("a report", store.read())
    }

    @Test
    fun `there is nothing to offer before a crash`() {
        assertNull(store().read())
    }

    /** REQ-207's "not shown again": declining has to make the file go away. */
    @Test
    fun `clearing leaves nothing to offer, and clearing twice is harmless`() {
        val store = store()
        store.write("a report")

        store.clear()
        store.clear()

        assertNull(store.read())
        assertEquals(emptyList<String>(), temporary.root.resolve("crash").list()?.toList())
    }

    /** One slot: the crash a reader is asked about is the one that just happened. */
    @Test
    fun `a second crash replaces the first rather than joining it`() {
        val store = store()

        store.write("first")
        store.write("second")

        assertEquals("second", store.read())
    }

    /**
     * The report is moved into place, so a process killed mid-write leaves a
     * partial file that is never mistaken for one.
     */
    @Test
    fun `a half-written report is not left where the offer would find it`() {
        val directory = temporary.root.resolve("crash").apply { mkdirs() }
        directory.resolve("report.txt.partial").writeText("half a re")

        assertNull(store(directory).read())
    }

    /** The handler cannot afford a second exception; a write it cannot do fails quietly. */
    @Test
    fun `a directory that cannot be created fails without throwing`() {
        val blocked = temporary.newFile("not-a-directory")

        assertFalse(store(blocked.resolve("crash")).write("a report"))
    }

    /** Damage, not a report: reading it into memory at launch would be the second bug. */
    @Test
    fun `an implausibly large file is ignored rather than loaded`() {
        val directory = temporary.root.resolve("crash").apply { mkdirs() }
        directory.resolve("report.txt").writeText("x".repeat(300 * 1024))

        assertNull(store(directory).read())
    }

    @Test
    fun `an empty file is not an offer`() {
        val directory = temporary.root.resolve("crash").apply { mkdirs() }
        directory.resolve("report.txt").writeText("   \n")

        assertNull(store(directory).read())
    }
}
