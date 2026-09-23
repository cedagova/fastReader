package com.cedagova.fastreader

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.cedagova.fastreader.account.LibraryReaderAccountGateway
import com.cedagova.fastreader.account.AssetDownloadGateway
import com.cedagova.fastreader.account.PublicationImportGateway
import com.cedagova.fastreader.account.ReaderApiAssetDownloadGateway
import com.cedagova.reader.library.sync.ReaderApiLibraryGateway
import com.cedagova.fastreader.account.ReaderApiPublicationImportGateway
import com.cedagova.reader.library.sync.ReaderLibraryGateway
import com.cedagova.fastreader.account.ReaderAccountConfiguration
import com.cedagova.fastreader.account.ReaderAccountController
import com.cedagova.fastreader.account.library.AccountBookCopies
import com.cedagova.fastreader.account.library.AccountDocumentCopyReferences
import com.cedagova.fastreader.account.library.AccountResumeOffers
import com.cedagova.fastreader.account.toAccountSession
import com.cedagova.fastreader.account.library.AccountCopyStore
import com.cedagova.fastreader.account.library.AccountDownloads
import com.cedagova.fastreader.account.library.AccountImports
import com.cedagova.fastreader.account.library.AccountShelf
import com.cedagova.reader.library.sync.AccountSyncEngine
import com.cedagova.reader.library.sync.AccountSyncTrigger
import com.cedagova.fastreader.account.library.DeviceBookSources
import com.cedagova.reader.library.sync.FileAccountLibraryStores
import com.cedagova.fastreader.crash.CrashReportStore
import com.cedagova.fastreader.crash.installCrashReporting
import com.cedagova.fastreader.library.LibraryGraph
import com.cedagova.fastreader.library.ScanTrigger
import com.cedagova.reader.auth.ReaderAuthClient
import com.cedagova.reader.library.ReaderLibraryClient
import com.cedagova.reader.library.downloads.AssetDownloadClient
import com.cedagova.reader.library.imports.PublicationImportEngine
import com.cedagova.reader.library.imports.PublicationTransferClient
import com.cedagova.reader.auth.ReaderAuthConfig
import com.cedagova.reader.auth.ReaderAuthException
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
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
 * call happens only when a session is stored and inside the refresh margin.
 * Everything the account surface does goes through [readerAccount], which
 * reaches the client through the app-owned gateway.
 *
 * ## The account library (#113)
 *
 * The same foreground observer triggers [accountLibrary], which syncs the
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
     * [accountLibrary] is its one consumer.
     */
    val readerLibrary: ReaderLibraryGateway? by lazy {
        readerAuth?.let { ReaderApiLibraryGateway(ReaderLibraryClient(it.api)) }
    }

    /**
     * The publication-import seam (#117), or `null` on an unconfigured build.
     *
     * The transfer client beside it is a second, deliberately session-less
     * transport: publication bytes go straight to the storage provider under
     * the signed grant an admission hands out, never through reader-api and
     * never with the bearer (see `PublicationTransferClient`). That is why it
     * is built here rather than taken from [readerAuth].
     */
    val readerImports: PublicationImportGateway? by lazy {
        readerAuth?.let { auth ->
            val operations = ReaderLibraryClient(auth.api)
            ReaderApiPublicationImportGateway(
                operations = operations,
                engine = PublicationImportEngine(operations, PublicationTransferClient()),
            )
        }
    }

    /**
     * The asset-download seam (#118), or `null` on an unconfigured build.
     *
     * The transport beside it is the third, deliberately session-less client
     * in this app and the exact counterpart of the publication transfer's:
     * book bytes come from the storage provider under the signed grant
     * reader-api issues, never through reader-api and never with the bearer
     * (see `AssetDownloadClient`). That is why it is built here rather than
     * taken from [readerAuth].
     */
    val readerDownloads: AssetDownloadGateway? by lazy {
        readerAuth?.let { auth ->
            ReaderApiAssetDownloadGateway(ReaderLibraryClient(auth.api), AssetDownloadClient())
        }
    }

    /** The account surface's state model, process-scoped like the library graph. */
    lateinit var readerAccount: ReaderAccountController
        private set

    /**
     * The account library and its foreground-driven sync engine (#113).
     *
     * Process-scoped like the library graph, and driven by the same foreground
     * observer: there is no scheduler, no receiver and no new permission behind
     * it (AD-21). Signed out — and on a build with no stage values — it holds
     * an empty state and calls nothing.
     */
    lateinit var accountLibrary: AccountSyncEngine
        private set

    /**
     * The account library as the shelf uses it (#114): [accountLibrary]'s state
     * and operations, plus the window in which a removal can still be taken
     * back. Process-scoped, because the window outlives a rotation.
     */
    lateinit var accountShelf: AccountShelf
        private set

    /**
     * Adding a device book to the account (#117): the consent gate and the
     * transfers that survive a relaunch.
     *
     * Process-scoped because a transfer is: it must not be tied to the screen
     * that started it, and the record it leaves behind is what a relaunch
     * resumes. Driven by the same foreground observer as everything else —
     * status reads happen while the app is in front of the reader and stop
     * when it is not (AD-21).
     */
    lateinit var accountImports: AccountImports
        private set

    /**
     * The verified private copies of account books on this device (#118).
     *
     * Process-scoped like everything else here, and for the same reason a
     * transfer is: a download must not be tied to the screen that started it.
     * LEAF812 is what offers one; this is what performs, verifies, places and
     * frees it.
     */
    lateinit var accountCopies: AccountBookCopies
        private set

    /**
     * Downloading an account book onto this device and freeing it again (#119).
     *
     * Process-scoped for the same reason [accountImports] is: a download must
     * outlive the screen that started it, and a reader who leaves the shelf
     * mid-transfer should come back to a finished book rather than to nothing.
     */
    lateinit var accountDownloads: AccountDownloads
        private set

    private var accountCopiesSwept = false

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
        accountLibrary = AccountSyncEngine(
            gateway = readerLibrary,
            stores = FileAccountLibraryStores(File(filesDir, FileAccountLibraryStores.DIRECTORY_NAME)),
            accountState = readerAccount.state.map { it.toAccountSession() },
            scope = applicationScope,
        )
        accountShelf = AccountShelf(
            actions = accountLibrary,
            resumeOffers = AccountResumeOffers(accountLibrary),
            state = accountLibrary.state,
            scope = applicationScope,
        )
        accountImports = AccountImports(
            gateway = readerImports,
            records = accountLibrary,
            sources = DeviceBookSources(library.gateway),
            bookForId = { id -> library.repository.catalog.value.book(id) },
            // The account row for a finished import is the backend's to create;
            // this only asks for the pass that carries it here (AD-22, AD-23).
            onImportReady = { accountLibrary.requestSync(AccountSyncTrigger.OWN_WRITE) },
            accountState = accountLibrary.state,
            scope = applicationScope,
        )
        accountCopies = AccountBookCopies(
            gateway = readerDownloads,
            store = AccountCopyStore(File(filesDir, AccountCopyStore.DIRECTORY_NAME)),
            references = AccountDocumentCopyReferences(accountLibrary),
            library = library.repository,
        )
        accountDownloads = AccountDownloads(
            copies = accountCopies,
            accountState = accountLibrary.state,
            scope = applicationScope,
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    sweepAccountCopiesOnce()
                    library.repository.requestRescan(ScanTrigger.APP_OPEN)
                    refreshReaderSessionOnForeground()
                    accountLibrary.requestSync(AccountSyncTrigger.FOREGROUND)
                    accountImports.onForeground()
                }

                override fun onStop(owner: LifecycleOwner) {
                    // Status reads stop with the app. Nothing is cancelled: the
                    // import records are on disk and the next foreground pass
                    // picks them up where this one left them.
                    accountImports.onBackground()
                }
            },
        )
    }

    /**
     * The one account-copy sweep this process runs (#118).
     *
     * A download interrupted by app death left a partial file and no catalog
     * row, and this is where it goes. It happens on the *first* foreground
     * rather than in `onCreate`, beside the library rescan, for the same reason
     * every other start-of-session job does: nothing here touches the disk
     * before the app is actually in front of the reader (AD-21).
     *
     * Once, and only once. A download is process-scoped and outlives the screen
     * that started it, so sweeping on every foreground would delete the
     * temporary file of a transfer still running behind a locked screen.
     */
    private fun sweepAccountCopiesOnce() {
        if (accountCopiesSwept) return
        accountCopiesSwept = true
        applicationScope.launch { accountCopies.reconcile() }
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
