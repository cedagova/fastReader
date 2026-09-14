package com.cedagova.fastreader

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.cedagova.fastreader.account.LibraryReaderAccountGateway
import com.cedagova.fastreader.account.ReaderApiLibraryGateway
import com.cedagova.fastreader.account.ReaderLibraryGateway
import com.cedagova.fastreader.account.ReaderAccountConfiguration
import com.cedagova.fastreader.account.ReaderAccountController
import com.cedagova.fastreader.crash.CrashReportStore
import com.cedagova.fastreader.crash.installCrashReporting
import com.cedagova.fastreader.library.LibraryGraph
import com.cedagova.fastreader.library.ScanTrigger
import com.cedagova.reader.auth.ReaderAuthClient
import com.cedagova.reader.library.ReaderLibraryClient
import com.cedagova.reader.auth.ReaderAuthConfig
import com.cedagova.reader.auth.ReaderAuthException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
 *
 * ## The Reader account (#100)
 *
 * Since #100 this object is also the host of `:reader-auth`
 * (`reader-auth/CONTRACT.md`, "Host requirements"): it owns the one
 * [ReaderAuthClient] for the process — absent when the build carries no stage
 * values — and calls `onForeground()` from the same foreground observer the
 * library rescan uses, which is the contract's fifth host obligation. That
 * call is the app's only unprompted network use, and only when a session is
 * stored and inside the refresh margin; a signed-out device sends nothing.
 * The app runs no timer. Everything the account surface does goes through
 * [readerAccount], which reaches the client through the app-owned gateway.
 */
class FastReaderApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob())

    lateinit var library: LibraryGraph
        private set

    /** The report from the last crash, if the last run ended in one (REQ-207). */
    lateinit var crashReports: CrashReportStore
        private set

    /** The three stage values as built in; blank when the build had none. */
    val readerAccountConfig: ReaderAuthConfig = ReaderAccountConfiguration.fromBuild()

    /**
     * The one library client, or `null` when the build is not configured — in
     * which case nothing is ever called. `onCreate` builds it while wiring the
     * account controller, so a configured build constructs it on every cold
     * start; the foreground hook below needs it on every start anyway, and
     * with no stored session it opens no connection.
     */
    val readerAuth: ReaderAuthClient? by lazy {
        if (readerAccountConfig.isConfigured) ReaderAuthClient.create(this, readerAccountConfig) else null
    }

    /**
     * The account-library seam (#112), or `null` on an unconfigured build for
     * the same reason [readerAuth] is: there is no client to call through.
     *
     * Nothing reads it yet — the account store and sync engine that will
     * (LEAF702) are the next increment's work. It is wired here so the
     * production path is the one the tests' fake stands in for, rather than
     * being assembled for the first time by whoever needs it.
     */
    val readerLibrary: ReaderLibraryGateway? by lazy {
        readerAuth?.let { ReaderApiLibraryGateway(ReaderLibraryClient(it.api)) }
    }

    /** The account surface's state model, process-scoped like the library graph. */
    lateinit var readerAccount: ReaderAccountController
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so that a failure in any of the wiring below is itself reported.
        crashReports = installCrashReporting(this)
        library = LibraryGraph(this, applicationScope)
        readerAccount = ReaderAccountController(
            gateway = readerAuth?.let(::LibraryReaderAccountGateway),
            missingValues = ReaderAccountConfiguration.missingValues(readerAccountConfig),
            scope = applicationScope,
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    library.repository.requestRescan(ScanTrigger.APP_OPEN)
                    refreshReaderSessionOnForeground()
                }
            },
        )
    }

    /**
     * Host requirement 5: the module refreshes a stored session inside the
     * margin when the app returns to the foreground, and nothing else runs
     * it. Its failures are the library's closed set and already reflected in
     * the session state the account surface renders, so none is rethrown.
     */
    private fun refreshReaderSessionOnForeground() {
        val client = readerAuth ?: return
        applicationScope.launch {
            try {
                client.onForeground()
            } catch (e: ReaderAuthException) {
                // The session state already says what happened.
            }
        }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
