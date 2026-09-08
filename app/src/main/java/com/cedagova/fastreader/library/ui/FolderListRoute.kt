package com.cedagova.fastreader.library.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.cedagova.fastreader.library.LibraryGraph

/**
 * The folder list wired to the real catalog (REQ-104).
 *
 * The counts come straight from the live catalog, so a rescan that finds new
 * books while this screen is open changes what the confirmation would say before
 * the reader confirms it — the number is never a stale snapshot of a folder.
 *
 * Which folder is being confirmed is remembered by id rather than by value: a
 * rescan can replace the item while the dialog is up, and the dialog has to keep
 * showing the folder's *current* count, not the one it opened with.
 */
@Composable
fun FolderListRoute(
    graph: LibraryGraph,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = graph.repository
    val catalog by repository.catalog.collectAsState()
    var confirmingId by rememberSaveable { mutableStateOf<String?>(null) }

    val folders = remember(catalog) { buildFolderItems(catalog) }
    // A folder removed elsewhere, or gone after a rescan, takes its dialog with it.
    val confirming = folders.firstOrNull { it.id == confirmingId }

    BackHandler(onBack = onBack)

    FolderListScreen(
        state = FolderListUiState(folders = folders, confirming = confirming),
        onBack = onBack,
        onRemoveRequest = { confirmingId = it.id },
        onRemoveConfirm = { folder ->
            confirmingId = null
            repository.requestRemoveFolder(folder.id)
        },
        onRemoveCancel = { confirmingId = null },
        modifier = modifier,
    )
}
