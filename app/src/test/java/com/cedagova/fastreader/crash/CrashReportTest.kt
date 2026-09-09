package com.cedagova.fastreader.crash

import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REQ-207's privacy line, asserted directly: what the reader would share carries
 * the four facts and nothing that belongs to them.
 *
 * The tests below are deliberately different in kind, because either one alone
 * is a false comfort:
 *
 * - the **corpus** test proves that specific secrets fed in through every route
 *   a throwable offers — message, cause, suppressed, deep cause — do not come
 *   out. It can only ever prove it for the secrets someone thought of.
 * - the **shape** test proves that *no* line of the report can be anything but
 *   one of a handful of fixed forms. That covers the secret nobody thought of,
 *   which is the one that would actually ship.
 */
class CrashReportTest {

    private val facts = CrashFacts(
        versionName = "1.1.0",
        versionCode = 3,
        deviceModel = "Pixel 7",
        androidRelease = "16",
        androidSdk = 36,
    )

    /**
     * The private words, planted in every place a `Throwable` can carry text.
     *
     * The path is the real-world case: the platform builds
     * `FileNotFoundException`'s message out of the path itself, so this is not a
     * hypothetical developer mistake but the default behaviour of the library
     * this app reads files with.
     */
    private val secrets = listOf(
        "Rayuela",
        "Julio Cortazar",
        "Rayuela - Julio Cortazar.epub",
        "/storage/emulated/0/Download",
        "content://com.android.providers.downloads",
        "algunos burgueses portenos",
    )

    private fun crashCarryingEverySecret(): Throwable {
        val deepest = IllegalArgumentException(
            "no spine item for chapter 3 of Rayuela by Julio Cortazar",
        )
        val missingFile = FileNotFoundException(
            "/storage/emulated/0/Download/Rayuela - Julio Cortazar.epub " +
                "(No such file or directory)",
        ).apply { initCause(deepest) }
        return IllegalStateException(
            "could not open content://com.android.providers.downloads/document/42 " +
                "while showing \"algunos burgueses portenos\"",
            missingFile,
        ).apply {
            addSuppressed(
                IllegalStateException("closing Rayuela - Julio Cortazar.epub failed"),
            )
        }
    }

    @Test
    fun `no book text, title, file name or path survives into the report`() {
        val report = crashReportText(facts, crashCarryingEverySecret())

        secrets.forEach { secret ->
            assertFalse(
                "the report still contains \"$secret\":\n$report",
                report.contains(secret, ignoreCase = true),
            )
        }
    }

    /**
     * A path needs a separator. Neither survives [safeField] or [safeSymbol], so
     * this holds for text nobody anticipated as well as for the corpus above.
     */
    @Test
    fun `the report contains no path separator of any kind`() {
        val report = crashReportText(facts, crashCarryingEverySecret())

        assertFalse("a forward slash reached the report:\n$report", report.contains('/'))
        assertFalse("a backslash reached the report:\n$report", report.contains('\\'))
    }

    /** Every line, including the ones a future change adds. */
    @Test
    fun `every line of the report is one of the shapes the renderer can emit`() {
        val report = crashReportText(facts, crashCarryingEverySecret())

        val unexpected = report.lines()
            .filterNot { line -> ALLOWED_LINES.any { it.matches(line) } }

        assertEquals(
            "lines the report should not be able to produce",
            emptyList<String>(),
            unexpected,
        )
    }

    @Test
    fun `the report names the version, the device and the Android version`() {
        val report = crashReportText(facts, RuntimeException())

        assertTrue(report, report.startsWith("$CRASH_REPORT_HEADING\n"))
        assertTrue(report, report.contains("\nVersion: 1.1.0 (3)\n"))
        assertTrue(report, report.contains("\nDevice: Pixel 7\n"))
        assertTrue(report, report.contains("\nAndroid: 16 (API 36)\n"))
    }

    /** The half of a stack trace that is kept: types and call sites. */
    @Test
    fun `the report names the exception type and where it was thrown`() {
        val report = crashReportText(facts, thrownHere())

        assertTrue(report, report.contains("Thrown: java.lang.IllegalStateException"))
        assertTrue(
            report,
            report.contains("    at com.cedagova.fastreader.crash.CrashReportTest.thrownHere:"),
        )
    }

    @Test
    fun `every exception in the chain is reported, and each says how it got there`() {
        val report = crashReportText(facts, crashCarryingEverySecret())

        assertTrue(report, report.contains("Thrown: java.lang.IllegalStateException"))
        assertTrue(report, report.contains("Suppressed by: java.lang.IllegalStateException"))
        assertTrue(report, report.contains("Caused by: java.io.FileNotFoundException"))
        assertTrue(report, report.contains("Caused by: java.lang.IllegalArgumentException"))
    }

    /** `initCause` accepts a loop, and a handler that follows one never returns. */
    @Test
    fun `a cause that points back at its own exception still terminates`() {
        val outer = RuntimeException("outer")
        val inner = RuntimeException("inner")
        outer.initCause(inner)
        inner.initCause(outer)

        val report = crashReportText(facts, outer)

        val declarations = Regex("^(Thrown|Caused by): ", RegexOption.MULTILINE)
        assertEquals(report, 2, declarations.findAll(report).count())
    }

    /** A runaway trace must not become a megabyte the reader is asked to share. */
    @Test
    fun `a very deep stack is truncated and says how much it left out`() {
        val error = RuntimeException("deep").apply {
            stackTrace = Array(500) { StackTraceElement("Deep", "call", "Deep.kt", it + 1) }
        }

        val report = crashReportText(facts, error)

        assertTrue(report, report.contains("    ... 460 more frames"))
    }

    /** A vendor string is arbitrary bytes; it may not smuggle a shape past the test. */
    @Test
    fun `an unprintable or path-shaped device model is reduced to safe characters`() {
        assertEquals("Pixel 7", safeField("Pixel  7"))
        assertEquals("etc passwd", safeField("/etc/passwd"))
        assertEquals("unknown", safeField("   "))
    }

    /** Every lambda on a modern runtime carries a load address after a slash. */
    @Test
    fun `a hidden-class frame loses the address the runtime gave it`() {
        assertEquals(
            "com.cedagova.fastreader.Foo\$\$Lambda",
            safeSymbol("com.cedagova.fastreader.Foo\$\$Lambda/0x00007f9a1c0d3200"),
        )
    }

    private fun thrownHere(): Throwable = IllegalStateException("thrown for the test")

    private companion object {
        /**
         * Every form `crashReportText` is able to produce, and nothing else. A new
         * kind of line has to be added here deliberately, which is the moment to
         * ask what it could carry.
         */
        val ALLOWED_LINES = listOf(
            Regex("^" + Regex.escape(CRASH_REPORT_HEADING) + "$"),
            Regex("^Version: [A-Za-z0-9 ()._+-]+ \\(\\d+\\)$"),
            Regex("^Device: [A-Za-z0-9 ()._+-]+$"),
            Regex("^Android: [A-Za-z0-9 ()._+-]+ \\(API \\d+\\)$"),
            Regex("^" + Regex.escape(MESSAGE_NOTE) + "$"),
            Regex("^$"),
            Regex("^(Thrown|Caused by|Suppressed by): [A-Za-z0-9_$.<>?-]+$"),
            Regex("^    at [A-Za-z0-9_$.<>?-]+(:(\\d+|native))?$"),
            Regex("^    \\.\\.\\. \\d+ more frames$"),
        )
    }
}
