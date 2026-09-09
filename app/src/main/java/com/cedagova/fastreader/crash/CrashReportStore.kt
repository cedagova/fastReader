package com.cedagova.fastreader.crash

import java.io.File

/**
 * The one crash report waiting to be offered, in the app's private storage
 * (AD-14).
 *
 * ## One slot, not a log
 *
 * A reader is offered the last crash, once, and then the file is gone. Keeping a
 * history would mean keeping crash reports on the device indefinitely for a
 * feature whose whole promise is that nothing is retained without being asked —
 * so a second crash overwrites the first rather than joining it.
 *
 * ## Where it lives, and what that buys
 *
 * Under `filesDir`, which is the app's private directory: no other app can read
 * it, and it is already excluded from both halves of Android's data extraction
 * by `allowBackup="false"` and `res/xml/data_extraction_rules.xml` (AD-11), so
 * the report cannot travel to a new phone either. That exclusion is
 * whole-domain, so this file needed no rule of its own.
 *
 * ## Never throws
 *
 * [write] runs inside the uncaught-exception handler, where an exception would
 * replace the crash being reported with a crash in the reporter. [read] runs at
 * launch, where a partial or unreadable file must cost the reader nothing. Both
 * answer with a value instead. A crash while writing therefore loses the report,
 * never the app's data — the file is written beside its destination and moved
 * into place, so a half-written report is never offered.
 */
class CrashReportStore(private val directory: File) {

    private val report = File(directory, REPORT_FILE)
    private val partial = File(directory, PARTIAL_FILE)

    /** Stores [text] as the pending report. Answers whether it landed. */
    fun write(text: String): Boolean = try {
        directory.mkdirs()
        partial.writeText(text)
        report.delete()
        partial.renameTo(report).also { moved -> if (!moved) partial.delete() }
    } catch (failure: Throwable) {
        // Deliberately broad, and deliberately silent: the caller is a process
        // that is already dying, and there is nowhere left to report to.
        false
    }

    /** The pending report, or null when there is none to offer. */
    fun read(): String? = try {
        if (!report.isFile || report.length() > MAX_BYTES) null
        else report.readText().ifBlank { null }
    } catch (failure: Throwable) {
        null
    }

    /** Discards the pending report. After this there is nothing left to offer. */
    fun clear() {
        runCatching { report.delete() }
        runCatching { partial.delete() }
    }

    private companion object {
        const val REPORT_FILE = "report.txt"
        const val PARTIAL_FILE = "report.txt.partial"

        /**
         * A rendered report is a few kilobytes; anything past this is damage, and
         * reading it into memory at launch would be the second bug.
         */
        const val MAX_BYTES = 256L * 1024L
    }
}
