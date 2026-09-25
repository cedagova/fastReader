package com.cedagova.fastreader

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.cedagova.fastreader.crash.installCrashReporting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Application entry point: the host of the composition root, [AppGraph].
 *
 * It does three things, in this order, and nothing else:
 *
 * 1. Installs crash reporting (REQ-207) before anything else this app does: an
 *    uncaught exception from here on writes a redacted report and still takes
 *    the process down, and the file it leaves is what the next launch offers to
 *    share.
 * 2. Builds the one [AppGraph] for the process.
 * 3. Forwards the process's foreground and background to it. The foreground
 *    rescans added folders, so a book copied into one shows up without the
 *    reader doing anything (REQ-002), and runs the Reader account's foreground
 *    hooks — the fifth host obligation of `reader-auth/CONTRACT.md` among them.
 *    Those are the app's whole unprompted work: it runs no timer, registers no
 *    receiver and schedules no work (AD-21).
 */
class FastReaderApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob())

    /** The composition root; read it through [appGraph]. */
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so that a failure in any of the wiring below is itself reported.
        val crashReports = installCrashReporting(this)
        graph = AppGraph(this, crashReports, applicationScope)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = graph.onForeground()

                override fun onStop(owner: LifecycleOwner) = graph.onBackground()
            },
        )
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}

/**
 * The process's [AppGraph], from anything that has a [Context].
 *
 * The one place the application object is cast (A197-F005): an activity asks for
 * the graph, never for the application type.
 */
val Context.appGraph: AppGraph get() = (applicationContext as FastReaderApplication).graph
