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
    var query by rememberSaveable { mutableStateOf("") }
    var foldersOpen by rememberSaveable { mutableStateOf(false) }
    val state = remember(catalog, ingestion, query, resumeBlocked, undoableRemoval) {
        buildLibraryUiState(catalog, ingestion, query, resumeBlocked, undoableRemoval)
    }
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
        onRefresh = { repository.requestRescan(ScanTrigger.MANUAL_REFRESH) },
        onRemove = { repository.requestRemoveBook(it.id) },
        onUndoRemove = { repository.requestUndoRemoveBook() },
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
        modifier = modifier,
    )
}
