package com.cedagova.fastreader.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.cedagova.fastreader.R
import com.cedagova.fastreader.library.FolderStatus
import com.cedagova.fastreader.ui.components.BackButton
import com.cedagova.fastreader.ui.components.ConfirmDialog
import com.cedagova.fastreader.ui.theme.Sizes
import com.cedagova.fastreader.ui.theme.Spacing

/** Everything the folder list renders, so a golden can drive every state directly. */
data class FolderListUiState(
    val folders: List<LibraryFolderItem>,
    /** The folder whose removal is being confirmed, or null when nothing is asked. */
    val confirming: LibraryFolderItem? = null,
)

/**
 * The added-folder list (REQ-104): every folder the reader added, what state it
 * is in, how much of the library it accounts for, and a way to remove it.
 *
 * Stateless like the library and settings screens — every state it can show is
 * reachable from a [FolderListUiState] value, which is what lets the Roborazzi
 * goldens be its regression gate. [FolderListRoute] supplies the real state.
 *
 * ## Why the confirmation names a number
 *
 * "Remove folder" is the one library action whose blast radius is not visible on
 * screen: the folder row says nothing about which books came from it. So the
 * confirmation names how many books would actually leave — the ones this folder
 * *alone* provides — and says in the same breath that no file is deleted and no
 * place is lost. A book that was also picked directly, or that also lives in
 * another added folder, is not in that count and does not leave.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    state: FolderListUiState,
    onBack: () -> Unit,
    onRemoveRequest: (LibraryFolderItem) -> Unit,
    onRemoveConfirm: (LibraryFolderItem) -> Unit,
    onRemoveCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize().testTag("folders_screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.folders_title)) },
                navigationIcon = {
                    BackButton(stringResource(R.string.folders_back), onBack, tag = "folders_back")
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (state.folders.isEmpty()) {
                Text(
                    text = stringResource(R.string.folders_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(
                        horizontal = Spacing.Large,
                        vertical = Spacing.XXLarge,
                    ).testTag("folders_empty"),
                )
            } else {
                Text(
                    text = stringResource(R.string.folders_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Medium),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("folders_list"),
                    contentPadding = PaddingValues(bottom = Spacing.XXLarge),
                ) {
                    items(items = state.folders, key = { it.id }) { folder ->
                        FolderRow(folder = folder, onRemove = { onRemoveRequest(folder) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    state.confirming?.let { folder ->
        RemoveFolderDialog(
            folder = folder,
            onConfirm = { onRemoveConfirm(folder) },
            onDismiss = onRemoveCancel,
        )
    }
}

@Composable
private fun FolderRow(folder: LibraryFolderItem, onRemove: () -> Unit) {
    // "Remove folder" alone is ambiguous once a screen reader is walking a list
    // of them, so each button announces which folder it removes.
    val removeLabel = stringResource(R.string.folders_remove, folder.displayName)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.ListRowMinHeight)
            .padding(horizontal = Spacing.Large, vertical = Spacing.Medium)
            .testTag("folders_row_${folder.id}"),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                // One TalkBack stop for the folder; its remove action stays separate.
                .semantics(mergeDescendants = true) {},
        ) {
            Text(
                text = folder.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(R.plurals.folders_book_count, folder.bookCount, folder.bookCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.XSmall),
            )
            folder.problem()?.let { problem ->
                Text(
                    text = problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.XSmall),
                )
            }
        }
        Spacer(Modifier.width(Spacing.Small))
        TextButton(
            onClick = onRemove,
            modifier = Modifier
                .defaultMinSize(minWidth = Sizes.TouchTarget, minHeight = Sizes.TouchTarget)
                .testTag("folders_remove_${folder.id}")
                .semantics { contentDescription = removeLabel },
        ) {
            Text(stringResource(R.string.folders_remove_confirm))
        }
    }
}

@Composable
private fun RemoveFolderDialog(folder: LibraryFolderItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val count = folder.removedBookCount
    ConfirmDialog(
        title = stringResource(R.string.folders_remove_title, folder.displayName),
        confirmLabel = stringResource(R.string.folders_remove_confirm),
        dismissLabel = stringResource(R.string.folders_remove_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        tag = "folders_remove_dialog",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
            Text(
                text = if (count == 0) {
                    stringResource(R.string.folders_remove_none)
                } else {
                    pluralStringResource(R.plurals.folders_remove_count, count, count)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.folders_remove_kept),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The problem sentence for a folder that is not currently readable, or null. */
@Composable
private fun LibraryFolderItem.problem(): String? = when (status) {
    FolderStatus.AVAILABLE -> null
    FolderStatus.MISSING -> stringResource(R.string.folders_status_missing)
    FolderStatus.PERMISSION_LOST -> stringResource(R.string.folders_status_permission_lost)
}
