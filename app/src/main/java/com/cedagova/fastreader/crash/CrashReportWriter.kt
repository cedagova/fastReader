package com.cedagova.fastreader.crash

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import kotlin.system.exitProcess

/**
 * The uncaught-exception handler: writes the report, then lets the crash finish
 * killing the process (REQ-207, AD-14).
 *
 * ## It must not swallow the crash
 *
 * This is the part that is easy to get wrong and impossible to notice
 * afterwards. A default handler that returns normally leaves the thread dead and
 * the process alive — a main thread gone, a window still on screen, and an app
 * that answers nothing. So the handler always ends by handing the same throwable
 * to the handler that was installed before it, which on Android is the
 * platform's own: it writes the `AndroidRuntime` log line and kills the process.
 * That log line is also the proof — an induced crash that does not appear under
 * `adb logcat -s AndroidRuntime:E` means this delegation was lost.
 *
 * When there is no previous handler — no Android runtime under the test, a
 * stripped host — the process is killed here instead, so "the process still
 * terminates" does not depend on who happened to be installed first.
 *
 * ## Why the facts are captured up front
 *
 * [CrashFacts] is read once, at install time, and held. Asking the package
 * manager for the version *during* a crash is a binder call on a process that is
 * already failing, and reading `Build` is free; taking both early means the
 * handler does nothing but format a string and rename a file.
 */
class CrashReportWriter(
    private val store: CrashReportStore,
    private val facts: CrashFacts,
    private val delegate: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            store.write(crashReportText(facts, error))
        } catch (reportFailure: Throwable) {
            // Broad on purpose. Rendering or storing the report is the optional
            // half of this handler; the mandatory half is below, and an
            // OutOfMemoryError raised while describing a crash must not stop the
            // crash itself from being delivered.
        }
        val previous = delegate
        if (previous != null) {
            previous.uncaughtException(thread, error)
            return
        }
        Process.killProcess(Process.myPid())
        exitProcess(CRASH_EXIT_CODE)
    }

    private companion object {
        /** What `RuntimeInit` uses for the same case, so the shell sees no news. */
        const val CRASH_EXIT_CODE = 10
    }
}

/**
 * Installs the handler for this process and answers the store the offer reads.
 *
 * Called from `FastReaderApplication.onCreate`, which is the earliest point the
 * app runs any code of its own: a crash before this — in the runtime's own
 * start-up — is not something an app can report on.
 */
fun installCrashReporting(context: Context): CrashReportStore {
    val store = CrashReportStore(File(context.filesDir, "crash"))
    val writer = CrashReportWriter(
        store = store,
        facts = crashFactsFor(context),
        delegate = Thread.getDefaultUncaughtExceptionHandler(),
    )
    Thread.setDefaultUncaughtExceptionHandler(writer)
    return store
}

/**
 * The four facts a report carries, read from the installed package and the
 * device rather than from `BuildConfig`.
 *
 * Same reasoning as the settings version row (`AppVersion`): the number that
 * matters is the one belonging to the APK actually running, which is what makes
 * "a report from an older version is still offered with its own version string"
 * true rather than hopeful.
 */
private fun crashFactsFor(context: Context): CrashFacts {
    val version = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        info.versionName.orEmpty() to code
    }.getOrDefault("" to 0L)
    return CrashFacts(
        versionName = version.first,
        versionCode = version.second,
        deviceModel = Build.MODEL.orEmpty(),
        androidRelease = Build.VERSION.RELEASE.orEmpty(),
        androidSdk = Build.VERSION.SDK_INT,
    )
}
