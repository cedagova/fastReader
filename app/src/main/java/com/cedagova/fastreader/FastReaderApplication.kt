package com.cedagova.fastreader

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.cedagova.fastreader.account.ReaderAccountConfiguration
import com.cedagova.fastreader.account.library.AccountResumeOffers
import com.cedagova.fastreader.account.library.CatalogBookIdentity
import com.cedagova.fastreader.account.library.DeviceBookSources
import com.cedagova.fastreader.account.library.LibraryAccountCopyCatalog
import com.cedagova.fastreader.crash.CrashReportStore
import com.cedagova.fastreader.crash.installCrashReporting
import com.cedagova.fastreader.library.LibraryGraph
import com.cedagova.fastreader.library.LibraryRepository
import com.cedagova.fastreader.library.ScanTrigger
import com.cedagova.reader.account.ReaderAccountController
import com.cedagova.reader.account.ReaderAccountGateways
import com.cedagova.reader.account.ReaderAccountGraph
import com.cedagova.reader.account.library.AccountDownloads
import com.cedagova.reader.account.library.AccountImports
import com.cedagova.reader.account.library.AccountShelf
import com.cedagova.reader.auth.ReaderAuthClient
import com.cedagova.reader.auth.ReaderAuthConfig
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
 *
 * ## The Reader account (#100)
 *
 * Since #100 this object is also the host of `:reader-auth`
 * (`reader-auth/CONTRACT.md`, "Host requirements"): it owns the one
 * [ReaderAuthClient] for the process — absent when the build carries no stage
 * values — and calls `onForeground()` from the same foreground observer the
 * library rescan uses, which is the contract's fifth host obligation. That
 * call happens only when a session is stored and inside the refresh margin.
 * Everything the account surface does goes through [readerAccount], which
 * reaches the client through the library-owned `ReaderAuthOperations` (#199).
 *
 * Since #200 the whole account pipeline is `:reader-account`'s, assembled by
 * one call — the [account] graph in [onCreate] — over FastReader's catalog,
 * book identity, resume-offer note and undo window.
 *
 * ## The account library (#113)
 *
 * The same foreground observer triggers [account]'s sync engine, which syncs the
 * signed-in account's library (AD-21). Those two calls are the app's whole
 * unprompted network use, they happen only while a session is stored, and a
 * signed-out device still sends nothing. The app runs no timer, registers no
 * receiver and schedules no work.
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
     * which case nothing is ever called. `onCreate` builds it while assembling
     * [account], so a configured build constructs it on every cold start; the
     * foreground hook needs it on every start anyway, and with no stored
     * session it opens no connection.
     */
    val readerAuth: ReaderAuthClient? by lazy {
        if (readerAccountConfig.isConfigured) ReaderAuthClient.create(this, readerAccountConfig) else null
    }

    /**
     * The Reader account libraries, assembled once for this process (#200):
     * the account surface, the account library and its sync engine, the
     * shelf, imports, copies and downloads, and their foreground hooks.
     *
     * Process-scoped like the library graph, and driven by the same foreground
     * observer: there is no scheduler, no receiver and no new permission
     * behind it (AD-21). Signed out — and on a build with no stage values — it
     * holds an empty state and calls nothing.
     */
    lateinit var account: ReaderAccountGraph
        private set

    /** The account surface's state model, process-scoped like the library graph. */
    val readerAccount: ReaderAccountController get() = account.account

    /** The account library as the shelf uses it (#114), with the removal's undo window. */
    val accountShelf: AccountShelf get() = account.shelf

    /** Adding a device book to the account (#117): the consent gate and the transfers that survive a relaunch. */
    val accountImports: AccountImports get() = account.imports

    /** Downloading an account book onto this device and freeing it again (#119). */
    val accountDownloads: AccountDownloads get() = account.downloads

    override fun onCreate() {
        super.onCreate()
        // First, so that a failure in any of the wiring below is itself reported.
        crashReports = installCrashReporting(this)
        library = LibraryGraph(this, applicationScope)
        // The one call that assembles the Reader account libraries (#200).
        account = ReaderAccountGraph(
            gateways = readerAuth?.let(ReaderAccountGateways::over),
            missingValues = ReaderAccountConfiguration.missingValues(readerAccountConfig),
            filesDir = filesDir,
            copyCatalog = LibraryAccountCopyCatalog(library.repository),
            publicationSources = DeviceBookSources(library.gateway) { id -> library.repository.catalog.value.book(id) },
            bookIdentity = CatalogBookIdentity,
            resumeOffers = ::AccountResumeOffers,
            scope = applicationScope,
            // One "immediate confirmation" in this app: the device shelf's window.
            undoWindowMs = LibraryRepository.DEFAULT_UNDO_WINDOW_MS,
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    // The copy sweep (first start only), the session refresh,
                    // a sync pass and the imports' hook, in that order.
                    account.onForeground()
                    library.repository.requestRescan(ScanTrigger.APP_OPEN)
                }

                override fun onStop(owner: LifecycleOwner) {
                    // Status reads stop with the app; nothing is cancelled.
                    account.onBackground()
                }
            },
        )
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
