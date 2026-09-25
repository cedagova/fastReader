package com.cedagova.reader.account

import com.cedagova.reader.account.library.AccountBookCopies
import com.cedagova.reader.account.library.AccountCopyCatalog
import com.cedagova.reader.account.library.AccountCopyStore
import com.cedagova.reader.account.library.AccountDocumentCopyReferences
import com.cedagova.reader.account.library.AccountDownloads
import com.cedagova.reader.account.library.AccountImports
import com.cedagova.reader.account.library.AccountShelf
import com.cedagova.reader.account.library.DeviceBookIdentity
import com.cedagova.reader.account.library.DevicePublicationSources
import com.cedagova.reader.account.library.ResumeOfferRecords
import com.cedagova.reader.auth.ReaderAuthClient
import com.cedagova.reader.auth.ReaderAuthOperations
import com.cedagova.reader.library.ReaderLibraryClient
import com.cedagova.reader.library.downloads.AssetDownloadClient
import com.cedagova.reader.library.downloads.AssetDownloadGateway
import com.cedagova.reader.library.downloads.ReaderApiAssetDownloadGateway
import com.cedagova.reader.library.imports.PublicationImportEngine
import com.cedagova.reader.library.imports.PublicationImportGateway
import com.cedagova.reader.library.imports.PublicationTransferClient
import com.cedagova.reader.library.imports.ReaderApiPublicationImportGateway
import com.cedagova.reader.library.sync.AccountHostRecords
import com.cedagova.reader.library.sync.AccountSyncEngine
import com.cedagova.reader.library.sync.AccountSyncTrigger
import com.cedagova.reader.library.sync.FileAccountLibraryStores
import com.cedagova.reader.library.sync.ReaderApiLibraryGateway
import com.cedagova.reader.library.sync.ReaderLibraryGateway
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The four network seams the account pipeline calls through, all present or —
 * on a build with no account values — all absent (#200).
 *
 * Production is [over]: one [ReaderLibraryClient] over the one authenticated
 * client's reader-api, shared by the three library gateways, plus the two
 * deliberately session-less transports (publication upload and asset download)
 * that never hold a bearer. A host's tests pass the scripted doubles from the
 * libraries' test fixtures instead.
 */
public class ReaderAccountGateways(
    /** The sign-in and session operations; also the foreground session refresh. */
    public val auth: ReaderAuthOperations,
    /** The account library the sync engine reads and writes. */
    public val library: ReaderLibraryGateway,
    /** Add-to-account: admission, transfer and status. */
    public val imports: PublicationImportGateway,
    /** Download grants and the grant-signed fetch. */
    public val downloads: AssetDownloadGateway,
) {
    public companion object {

        /**
         * The production gateways over the one [ReaderAuthClient] a host owns.
         *
         * One library client, not one per gateway: it is a stateless typed
         * layer over [ReaderAuthClient.api], so sharing it changes no request.
         */
        public fun over(client: ReaderAuthClient): ReaderAccountGateways {
            val operations = ReaderLibraryClient(client.api)
            return ReaderAccountGateways(
                auth = client,
                library = ReaderApiLibraryGateway(operations),
                imports = ReaderApiPublicationImportGateway(
                    operations = operations,
                    engine = PublicationImportEngine(operations, PublicationTransferClient()),
                ),
                downloads = ReaderApiAssetDownloadGateway(operations, AssetDownloadClient()),
            )
        }
    }
}

/**
 * The Reader account libraries assembled for one host: the one call a host
 * makes (#200, A197-F003).
 *
 * A host builds one of these for its process, in its `Application.onCreate`,
 * and calls [onForeground] and [onBackground] from its process lifecycle
 * observer. Everything else — which screens read [account], [shelf],
 * [imports] and [downloads] — is the host's. FastReader's call site is
 * `FastReaderApplication.onCreate`.
 *
 * ## What it wires
 *
 * - [account], the sign-in surface's state model over [ReaderAccountGateways.auth];
 * - [sync], `:reader-library`'s account sync engine, reading who is signed in
 *   from [account] through [toAccountSession] — the session bridge — and
 *   storing its documents under `filesDir/account-library/`;
 * - [shelf], [imports], [copies] and [downloads] over [sync]'s state and
 *   single writer, with the verified copies under `filesDir/account-copies/`.
 *
 * The directories and file formats are exactly the ones FastReader used before
 * this module existed, so a host that adopts it migrates nothing.
 *
 * ## The host seams
 *
 * The device catalog ([AccountCopyCatalog], [DevicePublicationSources]), how a
 * device book is named by its content ([DeviceBookIdentity]), where an answered
 * resume offer is noted ([ResumeOfferRecords], built over [sync]'s host
 * records) and the undo window of an account removal are the host's.
 *
 * ## Network use
 *
 * With [gateways] null (a build with no account values) nothing here can make
 * a call and every state stays signed out or not configured. Otherwise the
 * only unprompted calls are the two [onForeground] makes, and both send
 * nothing while no session is stored.
 *
 * Built on and for one scope: every long-lived collector and job runs in
 * [scope], exactly as when a host wired them by hand.
 */
public class ReaderAccountGraph(
    gateways: ReaderAccountGateways?,
    /** The configuration keys a build lacks, shown by [ReaderAccountState.NotConfigured]. */
    missingValues: List<String>,
    /** The host's private files directory: the account documents and copies live under it. */
    filesDir: File,
    copyCatalog: AccountCopyCatalog,
    publicationSources: DevicePublicationSources,
    bookIdentity: DeviceBookIdentity,
    resumeOffers: (AccountHostRecords) -> ResumeOfferRecords,
    private val scope: CoroutineScope,
    /** How long an account removal can be taken back; FastReader passes its device shelf's window. */
    undoWindowMs: Long = AccountShelf.DEFAULT_UNDO_WINDOW_MS,
) {
    private val auth: ReaderAuthOperations? = gateways?.auth

    /** The account surface's state model, process-scoped. */
    public val account: ReaderAccountController = ReaderAccountController(
        gateway = gateways?.auth,
        missingValues = missingValues,
        scope = scope,
    )

    /**
     * The account library and its foreground-driven sync engine (#113). Signed
     * out — and with no gateways — it holds an empty state and calls nothing.
     */
    public val sync: AccountSyncEngine = AccountSyncEngine(
        gateway = gateways?.library,
        stores = FileAccountLibraryStores(File(filesDir, FileAccountLibraryStores.DIRECTORY_NAME)),
        accountState = account.state.map { it.toAccountSession() },
        scope = scope,
    )

    /** The account library as a shelf uses it: [sync]'s state and operations, plus the removal's undo window. */
    public val shelf: AccountShelf = AccountShelf(
        actions = sync,
        resumeOffers = resumeOffers(sync),
        state = sync.state,
        scope = scope,
        undoWindowMs = undoWindowMs,
    )

    /** Adding a device book to the account: the consent gate and the transfers that survive a relaunch. */
    public val imports: AccountImports = AccountImports(
        gateway = gateways?.imports,
        records = sync,
        sources = publicationSources,
        identity = bookIdentity,
        // The account row for a finished import is the backend's to create;
        // this only asks for the pass that carries it here (AD-22, AD-23).
        onImportReady = { sync.requestSync(AccountSyncTrigger.OWN_WRITE) },
        accountState = sync.state,
        scope = scope,
    )

    /** The verified private copies of account books on this device (#118). */
    public val copies: AccountBookCopies = AccountBookCopies(
        gateway = gateways?.downloads,
        store = AccountCopyStore(File(filesDir, AccountCopyStore.DIRECTORY_NAME)),
        references = AccountDocumentCopyReferences(sync),
        catalog = copyCatalog,
    )

    /** Downloading an account book onto this device and freeing it again (#119). */
    public val downloads: AccountDownloads = AccountDownloads(
        copies = copies,
        accountState = sync.state,
        scope = scope,
    )

    private var copiesSwept = false

    /**
     * The host's process came to the foreground. Call it from the host's
     * process lifecycle `onStart`, on the main thread.
     *
     * In order: the one start-up sweep of the copies (first foreground only),
     * `:reader-auth`'s session refresh (its host requirement 5: it refreshes a
     * stored session inside the margin and sends nothing without one), a sync
     * pass, and the imports' foreground hook (status reads resume, stored
     * imports continue).
     */
    public fun onForeground() {
        sweepCopiesOnce()
        refreshSessionOnForeground()
        sync.requestSync(AccountSyncTrigger.FOREGROUND)
        imports.onForeground()
    }

    /**
     * The host's process left the foreground. Status reads stop with it;
     * nothing is cancelled — the import records are on disk and the next
     * [onForeground] picks them up where this left them.
     */
    public fun onBackground() {
        imports.onBackground()
    }

    /**
     * The one account-copy sweep this process runs (#118).
     *
     * A download interrupted by process death left a partial file and no
     * catalog row, and this is where it goes. It happens on the *first*
     * foreground rather than at construction, for the same reason every other
     * start-of-session job does: nothing touches the disk before the app is in
     * front of the reader (AD-21).
     *
     * Once, and only once. A download is process-scoped and outlives the screen
     * that started it, so sweeping on every foreground would delete the
     * temporary file of a transfer still running behind a locked screen.
     */
    private fun sweepCopiesOnce() {
        if (copiesSwept) return
        copiesSwept = true
        scope.launch { copies.reconcile() }
    }

    /**
     * `:reader-auth`'s host requirement 5: the module refreshes a stored
     * session inside the margin when the app returns to the foreground, and
     * nothing else runs it. `onForeground()` never throws a reader-auth
     * failure (#159); the outcome is already in the session state [account]
     * renders.
     */
    private fun refreshSessionOnForeground() {
        val client = auth ?: return
        scope.launch { client.onForeground() }
    }
}
