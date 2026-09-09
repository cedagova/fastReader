package com.cedagova.fastreader.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import java.io.FileNotFoundException

/**
 * A crash on demand, so the report and the offer can be exercised on a real
 * device (REQ-207's validation).
 *
 * Debug builds only — it lives in `src/debug`, so neither the class nor its
 * manifest entry exists in a release APK. `CrashInducerIsDebugOnlyTest` holds
 * that: a copy that ever appeared under `src/main` would ship a crash button to
 * readers.
 *
 * ```
 * adb shell am start -n com.cedagova.fastreader/com.cedagova.fastreader.debug.CrashInducerActivity
 * ```
 *
 * ## The exception is chosen, not arbitrary
 *
 * Its message and its cause both spell out a book: a title, an author and the
 * full path of a file in the reader's Downloads folder. That is the leak this
 * feature exists to prevent, and it is the ordinary case rather than a contrived
 * one — `FileNotFoundException`'s message *is* a path, every time the platform
 * raises it.
 *
 * So an induced crash is not just "does the offer appear". The report it leaves
 * is the evidence that none of those words reach the text the reader would
 * share, on the device, in the real handler — not only in
 * `CrashReportTest`'s fixtures.
 */
class CrashInducerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        throw IllegalStateException(
            "induced crash: could not read $PRETEND_BOOK",
            FileNotFoundException("$PRETEND_BOOK (No such file or directory)"),
        )
    }
}

/**
 * A book that does not exist, named the way a real one would be. Every part of
 * it — the folder, the title, the author, the extension — must be absent from
 * the stored report.
 */
private const val PRETEND_BOOK =
    "/storage/emulated/0/Download/Rayuela - Julio Cortazar.epub"
