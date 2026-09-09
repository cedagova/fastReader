package com.cedagova.fastreader

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.cedagova.fastreader.crash.CrashReportStore
import com.cedagova.fastreader.crash.installCrashReporting
import com.cedagova.fastreader.library.LibraryGraph
import com.cedagova.fastreader.library.ScanTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Application entry point.
 *
 * Owns the library graph and rescans added folders every time the app comes to
 * the foreground, so a book copied into an added folder shows up without the
 * reader doing anything (REQ-002).
 *
 * It is also where crash reporting is installed (REQ-207), before anything else
 * this app does: an uncaught exception from here on writes a redacted report and
 * still takes the process down, and the file it leaves is what the next launch
 * offers to share.
 */
class FastReaderApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob())

    lateinit var library: LibraryGraph
        private set

    /** The report from the last crash, if the last run ended in one (REQ-207). */
    lateinit var crashReports: CrashReportStore
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so that a failure in any of the wiring below is itself reported.
        crashReports = installCrashReporting(this)
        library = LibraryGraph(this, applicationScope)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    library.repository.requestRescan(ScanTrigger.APP_OPEN)
                }
            },
        )
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
