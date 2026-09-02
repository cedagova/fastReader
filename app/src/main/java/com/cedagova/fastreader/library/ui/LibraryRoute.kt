package com.cedagova.fastreader.library.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.cedagova.fastreader.library.ScanTrigger
import com.cedagova.fastreader.library.saf.SafDocumentGateway

/**
 * The library screen wired to the real catalog: repository state in, document
 * picks out. Everything visual lives in [LibraryScreen], which stays stateless so
 * the Roborazzi renders can drive every state directly.
 */
@Composable
fun LibraryRoute(graph: LibraryGraph, modifier: Modifier = Modifier) {
    val repository = graph.repository
    val catalog by repository.catalog.collectAsState()
    val ingestion by repository.ingestion.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val state = remember(catalog, ingestion, query) { buildLibraryUiState(catalog, ingestion, query) }
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

    LibraryScreen(
        state = state,
        onQueryChange = { query = it },
        onAddBooks = { pickBooks.launch(SafDocumentGateway.PICKER_MIME_TYPES) },
        onAddFolder = { pickFolder.launch(null) },
        onRefresh = { repository.requestRescan(ScanTrigger.MANUAL_REFRESH) },
        onRemove = { repository.requestRemoveBook(it.id) },
        onGrantAccess = { book ->
            // Re-granting a folder re-adds it at the same tree URI, which restores
            // every book it holds; a directly picked file has to be picked again.
            val tree = book.regrantTreeUri
            if (tree != null) pickFolder.launch(tree.toUri()) else pickBooks.launch(SafDocumentGateway.PICKER_MIME_TYPES)
        },
        coverLoader = coverLoader,
        modifier = modifier,
    )
}

/**
 * The stock picker contracts do not ask for a persistable grant, so the read
 * permission would be gone the next time the app starts. Both contracts below add
 * that flag; the catalog then takes the long-lived grant (AD-1).
 */
private class PickPersistableDocuments : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).withPersistableRead()
}

private class PickPersistableDocumentTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).withPersistableRead()
}

private fun Intent.withPersistableRead(): Intent = addFlags(
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
)
