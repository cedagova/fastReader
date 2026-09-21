package com.cedagova.fastreader.library.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import com.cedagova.fastreader.account.library.AccountImports
import com.cedagova.fastreader.account.library.AccountShelf
import com.cedagova.fastreader.library.LibraryGraph
import com.cedagova.fastreader.library.ResumeBlocked
import com.cedagova.fastreader.library.ScanTrigger
import com.cedagova.fastreader.library.saf.SafDocumentGateway

/**
 * The library screen wired to the real catalog: repository state in, document
 * picks out. Everything visual lives in [LibraryScreen], which stays stateless so
 * the Roborazzi renders can drive every state directly.
 */
@Composable
fun LibraryRoute(
    graph: LibraryGraph,
    /** The signed-in account's library and its operations (#114). */
    account: AccountShelf,
    /** Adding a device book to that account, with its consent gate (#117). */
    imports: AccountImports,
    onOpenBook: (String) -> Unit,
    modifier: Modifier = Modifier,
    resumeBlocked: ResumeBlocked? = null,
    onDismissResumeNotice: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val repository = graph.repository
    val catalog by repository.catalog.collectAsState()
    val ingestion by repository.ingestion.collectAsState()
    val undoableRemoval by repository.undoableRemoval.collectAsState()
    val accountLibrary by account.state.collectAsState()
    val accountRemoval by account.undo.collectAsState()
    val accountImports by imports.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var foldersOpen by rememberSaveable { mutableStateOf(false) }
    // Which account notice the reader has put away, by the notice's own key, so
    // dismissing "your session went away" cannot also hide the different thing
    // that happens next. A primitive, so it survives process death as cheaply
    // as it survives rotation.
    var dismissedAccountNotice by rememberSaveable { mutableStateOf<String?>(null) }
    val accountUndo = accountRemoval?.let { AccountUndoNotice(it.bookId, it.title) }
    val built = remember(
        catalog,
        ingestion,
        query,
        resumeBlocked,
        undoableRemoval,
        accountLibrary,
        accountUndo,
        accountImports,
    ) {
        buildLibraryUiState(
            catalog = catalog,
            ingestion = ingestion,
            query = query,
            resumeBlocked = resumeBlocked,
            undoableRemoval = undoableRemoval,
            account = accountLibrary,
            accountUndo = accountUndo,
            imports = accountImports,
        )
    }
    val state = if (built.accountNotice?.key == dismissedAccountNotice) built.copy(accountNotice = null) else built

    // The one status the shelf can see the backend does not yet have: a book
    // read to its last word here is finished (REQ-018), and finishing records
    // `finished` for the account. Derived from the rows that are on screen, so
    // it is the same fact the row's 100% states, and it settles after one
    // admission because the backend's canonical payload comes back as
    // `finished`.
    val finished = remember(built.books) { finishedAccountBooks(built.books) }
    LaunchedEffect(finished) { finished.forEach(account::recordFinished) }
    val coverLoader = remember(graph) { CoverStoreLoader(graph.covers) }

    val pickBooks = rememberLauncherForActivityResult(PickPersistableDocuments()) { uris ->
        if (uris.isNotEmpty()) repository.requestAddPickedBooks(uris.map(Uri::toString))
    }
    val pickFolder = rememberLauncherForActivityResult(PickPersistableDocumentTree()) { treeUri ->
        treeUri?.let { repository.requestAddFolder(it.toString()) }
    }

    // Shows the stored catalog even when the app-open rescan is skipped as too recent.
    LaunchedEffect(repository) { repository.load() }

    // A query outlives the books it filtered: remove the last book and the field
    // is hidden with its text intact, so the next book added would land straight
    // into a stale filter. An empty library has nothing to search.
    val libraryIsEmpty = catalog.books.isEmpty()
    LaunchedEffect(libraryIsEmpty) { if (libraryIsEmpty) query = "" }

    // The folder list sits over the library rather than beside it in a navigation
    // graph, for the same reason settings do: it is one place the reader steps
    // into and back out of, and the library behind it keeps its search and scroll.
    if (foldersOpen) {
        FolderListRoute(graph = graph, onBack = { foldersOpen = false }, modifier = modifier)
        return
    }

    LibraryScreen(
        state = state,
        onQueryChange = { query = it },
        onAddBooks = { pickBooks.launch(SafDocumentGateway.PICKER_MIME_TYPES) },
        onAddFolder = { pickFolder.launch(null) },
        // One refresh control, both libraries: the reader asked for the shelf to
        // be up to date, and half of it being the account's is not their problem.
        onRefresh = {
            repository.requestRescan(ScanTrigger.MANUAL_REFRESH)
            account.refresh()
        },
        onRemove = { repository.requestRemoveBook(it.id) },
        onUndoRemove = { repository.requestUndoRemoveBook() },
        onRemoveFromAccount = { book ->
            book.account?.let { account.removeFromAccount(it.bookId, book.title) }
        },
        onUndoAccountRemove = { account.undoRemove() },
        // Four callbacks for one flow, because the step that can send bytes has
        // to be its own: `requestAdd` asks the backend what it accepts, and only
        // `confirmAdd` — the owner's answer to the question `requestAdd` leads
        // to — can put this book on the wire (REQ-505).
        onAddToAccount = { imports.requestAdd(it.id) },
        onConfirmAddToAccount = { imports.confirmAdd(it.id) },
        onCancelAddToAccount = { imports.cancelAdd(it.id) },
        onDismissAddToAccount = { imports.dismiss(it.id) },
        onDismissAccountNotice = { dismissedAccountNotice = state.accountNotice?.key },
        onOpenFolders = { foldersOpen = true },
        onOpen = { onOpenBook(it.id) },
        onGrantAccess = { book ->
            // Re-granting a folder re-adds it at the same tree URI, which restores
            // every book it holds; a directly picked file has to be picked again.
            val tree = book.regrantTreeUri
            if (tree != null) pickFolder.launch(tree.toUri()) else pickBooks.launch(SafDocumentGateway.PICKER_MIME_TYPES)
        },
        coverLoader = coverLoader,
        onDismissResumeNotice = onDismissResumeNotice,
        onOpenSettings = onOpenSettings,
        onOrderChange = { repository.requestUpdateSettings { settings -> settings.copy(libraryOrder = it) } },
        modifier = modifier,
    )
}
