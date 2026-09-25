package example.librarycopycheck

import com.cedagova.reader.account.ReaderAccountGateways
import com.cedagova.reader.account.ReaderAccountGraph
import com.cedagova.reader.account.library.AccountCopyCatalog
import com.cedagova.reader.account.library.DeviceBookIdentity
import com.cedagova.reader.account.library.DevicePublicationSources
import com.cedagova.reader.account.library.ResumeOfferRecords
import com.cedagova.reader.auth.ReaderAuthClient
import com.cedagova.reader.library.ReaderLibraryClient
import com.cedagova.reader.library.ReaderLibraryOperations
import com.cedagova.reader.library.sync.AccountHostRecords
import java.io.File
import kotlinx.coroutines.CoroutineScope

/**
 * The host side of the copy check. It compiles against the copied libraries'
 * public surface the way a Reader client does, reaching :reader-auth's types
 * through :reader-library's `api` dependency.
 */
fun libraryOperations(auth: ReaderAuthClient): ReaderLibraryOperations = ReaderLibraryClient(auth.api)

/**
 * The one call a host makes to assemble the account libraries (#200), over its
 * own implementations of :reader-account's four host seams.
 */
fun accountGraph(
    auth: ReaderAuthClient?,
    filesDir: File,
    catalog: AccountCopyCatalog,
    books: DevicePublicationSources,
    identity: DeviceBookIdentity,
    resumeOffers: (AccountHostRecords) -> ResumeOfferRecords,
    scope: CoroutineScope,
): ReaderAccountGraph = ReaderAccountGraph(
    gateways = auth?.let(ReaderAccountGateways::over),
    missingValues = emptyList(),
    filesDir = filesDir,
    copyCatalog = catalog,
    publicationSources = books,
    bookIdentity = identity,
    resumeOffers = resumeOffers,
    scope = scope,
)
