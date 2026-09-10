package com.cedagova.fastreader.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The handler's one hard rule: it may write a report, and it must not stop the
 * crash.
 *
 * A default handler that returns normally leaves the process alive with its main
 * thread dead — a window on screen that answers nothing. That is invisible in a
 * screenshot and obvious in a delegation test, which is why this is asserted
 * here and only *confirmed* on the device, where the induced crash has to show
 * up under `AndroidRuntime:E`.
 */
class CrashReportWriterTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val facts = CrashFacts("1.1.0", 3, "Pixel 7", "16", 36)

    private class RecordingHandler : Thread.UncaughtExceptionHandler {
        var thread: Thread? = null
        var error: Throwable? = null
        override fun uncaughtException(t: Thread, e: Throwable) {
            thread = t
            error = e
        }
    }

    private fun store(): CrashReportStore = CrashReportStore(temporary.root.resolve("crash"))

    @Test
    fun `the crash is handed on to the handler that was installed before`() {
        val platform = RecordingHandler()
        val error = IllegalStateException("boom")
        val thread = Thread.currentThread()

        CrashReportWriter(store(), facts, platform).uncaughtException(thread, error)

        assertSame("the crash was swallowed instead of being delivered", error, platform.error)
        assertSame(thread, platform.thread)
    }

    @Test
    fun `the report is written before the crash is handed on`() {
        val store = store()
        val platform = RecordingHandler()

        CrashReportWriter(store, facts, platform).uncaughtException(
            Thread.currentThread(),
            IllegalStateException("boom"),
        )

        val report = store.read() ?: error("the handler wrote no report")
        assertTrue(report, report.startsWith(CRASH_REPORT_HEADING))
        assertTrue(report, report.contains("Thrown: java.lang.IllegalStateException"))
    }

    /**
     * The report is the optional half. A store that cannot write must not become
     * the reason a crash is never delivered.
     */
    @Test
    fun `a report that cannot be written still lets the crash through`() {
        val blocked = temporary.newFile("not-a-directory")
        val platform = RecordingHandler()
        val error = IllegalStateException("boom")

        CrashReportWriter(CrashReportStore(blocked.resolve("crash")), facts, platform)
            .uncaughtException(Thread.currentThread(), error)

        assertSame(error, platform.error)
    }

    /** Nothing else in the app may see the redaction bypassed. */
    @Test
    fun `the stored report carries no message from the crash`() {
        val store = store()

        CrashReportWriter(store, facts, RecordingHandler()).uncaughtException(
            Thread.currentThread(),
            IllegalStateException("could not read Rayuela - Julio Cortazar.epub"),
        )

        val report = store.read().orEmpty()
        assertEquals(report, false, report.contains("Rayuela"))
        assertEquals(report, false, report.contains(".epub"))
    }

    @Test
    fun `nothing is stored when no crash has happened`() {
        assertNull(store().read())
    }
}
