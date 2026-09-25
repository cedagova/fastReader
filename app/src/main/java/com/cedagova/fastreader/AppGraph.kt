package com.cedagova.fastreader

import android.content.Context
import com.cedagova.fastreader.account.ReaderAccountConfiguration
import com.cedagova.fastreader.account.library.AccountResumeOffers
import com.cedagova.fastreader.account.library.CatalogBookIdentity
import com.cedagova.fastreader.account.library.DeviceBookSources
import com.cedagova.fastreader.account.library.LibraryAccountCopyCatalog
import com.cedagova.fastreader.crash.CrashReportStore
import com.cedagova.fastreader.external.ExternalOpenController
import com.cedagova.fastreader.library.CatalogCodec
import com.cedagova.fastreader.library.CatalogIngestor
import com.cedagova.fastreader.library.DocumentGateway
import com.cedagova.fastreader.library.FileCatalogStore
import com.cedagova.fastreader.library.LibraryRepository
import com.cedagova.fastreader.library.ScanTrigger
import com.cedagova.fastreader.library.saf.SafDocumentGateway
import com.cedagova.fastreader.library.store.CoverStore
import com.cedagova.fastreader.settings.SharedPreferencesThemeMirror
import com.cedagova.fastreader.settings.ThemeMirror
import com.cedagova.reader.account.ReaderAccountGateways
import com.cedagova.reader.account.ReaderAccountGraph
import com.cedagova.reader.auth.ReaderAuthClient
import com.cedagova.reader.auth.ReaderAuthConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * The composition root: every process-scoped object the app uses, built once by
 * plain constructor calls (`docs/app-shell.md`).
 *
 * There is no DI framework: the app is small enough that one class of `val`s
 * costs less than a framework would, and reading this file top to bottom is
 * reading the wiring. [FastReaderApplication] builds it in `onCreate` and is the
 * only holder; everything else reaches it through [appGraph], and screens receive
 * from it only the objects they use.
 *
 * Built after crash reporting is installed, so a failure anywhere below is
 * itself reported (REQ-207) — which is why [crashReports] is handed in rather
 * than built here.
 */
class AppGraph(context: Context, val crashReports: CrashReportStore, scope: CoroutineScope) {

    private val applicationContext = context.applicationContext

    // --- Device library -------------------------------------------------------

    /**
     * The one-key theme copy the launch path reads before the catalog can answer
     * (AD-10). The repository writes it on every theme change; `MainActivity`
     * reads it before `super.onCreate`.
     */
    val themeMirror: ThemeMirror = SharedPreferencesThemeMirror(applicationContext)

    private val gateway: DocumentGateway = SafDocumentGateway(applicationContext)

    /** Cover thumbnails, read by the library screen. */
    val covers = CoverStore(File(applicationContext.filesDir, "covers"))

    /** The device catalog: books, folders, positions and settings (AD-3). */
    val repository = LibraryRepository(
        store = FileCatalogStore(
            file = File(File(applicationContext.filesDir, "catalog"), "catalog.json"),
            codec = CatalogCodec(),
        ),
        ingestor = CatalogIngestor(gateway, covers),
        gateway = gateway,
        covers = covers,
        scope = scope,
        ioDispatcher = Dispatchers.IO,
        themeMirror = themeMirror,
    )

    /**
     * The one book handed over from outside the app, if any (REQ-103).
     *
     * Lives here, on the process-scoped graph, because that is exactly the
     * lifetime a session-only open is allowed: it survives a rotation and it does
     * not survive process death, after which the reader is back in the library
     * with no row for that book and its position kept (AD-9).
     */
    val external = ExternalOpenController(
        repository = repository,
        gateway = gateway,
        scope = scope,
        ioDispatcher = Dispatchers.IO,
    )

    // --- Reader account (#100, #200) -----------------------------------------

    /** The three stage values as built in; blank when the build had none. */
    val readerAccountConfig: ReaderAuthConfig = ReaderAccountConfiguration.fromBuild()

    /**
     * The one `:reader-auth` client for the process (`reader-auth/CONTRACT.md`,
     * "Host requirements"), or `null` when the build is not configured — in
     * which case nothing is ever called. With no stored session it opens no
     * connection.
     */
    val readerAuth: ReaderAuthClient? =
        if (readerAccountConfig.isConfigured) ReaderAuthClient.create(applicationContext, readerAccountConfig) else null

    /**
     * The Reader account libraries, assembled by `:reader-account`'s one call
     * (#200) over FastReader's catalog, book identity, resume-offer note and
     * undo window: the account surface, the account library and its sync
     * engine, the shelf, imports, copies and downloads.
     *
     * Signed out — and on a build with no stage values — it holds an empty state
     * and calls nothing.
     */
    val readerAccount = ReaderAccountGraph(
        gateways = readerAuth?.let(ReaderAccountGateways::over),
        missingValues = ReaderAccountConfiguration.missingValues(readerAccountConfig),
        filesDir = applicationContext.filesDir,
        copyCatalog = LibraryAccountCopyCatalog(repository),
        publicationSources = DeviceBookSources(gateway) { id -> repository.catalog.value.book(id) },
        bookIdentity = CatalogBookIdentity,
        resumeOffers = ::AccountResumeOffers,
        scope = scope,
        // One "immediate confirmation" in this app: the device shelf's window.
        undoWindowMs = LibraryRepository.DEFAULT_UNDO_WINDOW_MS,
    )

    // --- Process lifecycle ----------------------------------------------------

    /**
     * The app came to the foreground. The account's hooks — the copy sweep
     * (first start only), the session refresh, a sync pass and the imports'
     * hook, in that order — and then the library rescan of added folders
     * (REQ-002). These are the app's whole unprompted work: no timer, no
     * receiver, no scheduled job (AD-21), and signed out the account half sends
     * nothing.
     */
    fun onForeground() {
        readerAccount.onForeground()
        repository.requestRescan(ScanTrigger.APP_OPEN)
    }

    /** The app left the foreground: status reads stop; nothing is cancelled. */
    fun onBackground() {
        readerAccount.onBackground()
    }
}
