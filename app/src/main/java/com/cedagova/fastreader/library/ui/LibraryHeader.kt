package com.cedagova.fastreader.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cedagova.fastreader.R
import com.cedagova.fastreader.settings.LibraryOrder
import com.cedagova.fastreader.ui.theme.Sizes
import com.cedagova.fastreader.ui.theme.Spacing

/**
 * The two secondary affordances that sit between the header and the list: how
 * the list is ordered (REQ-203) and the way into the added folders (REQ-104).
 *
 * They share one [FlowRow] rather than taking a row each. The library header is
 * already three rows deep before the list starts, and on a landscape phone the
 * whole window is 411 dp tall (REQ-205), so a row that only ever holds one short
 * control is height the books should have. Side by side when they fit, stacked
 * when they do not — which is what a large font scale plus a plural folder count
 * eventually forces, and stacking is the outcome that keeps REQ-301's "nothing
 * clipped" true without a second breakpoint.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ListControls(
    order: LibraryOrder,
    onOrderChange: (LibraryOrder) -> Unit,
    folderCount: Int,
    onOpenFolders: () -> Unit,
    horizontalPadding: Dp = Spacing.Large,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
        verticalArrangement = Arrangement.spacedBy(Spacing.XSmall),
    ) {
        OrderControl(order = order, onOrderChange = onOrderChange)
        FoldersEntry(count = folderCount, onOpenFolders = onOpenFolders)
    }
}

/**
 * REQ-203's order control: the current order, and a menu of the three.
 *
 * A menu rather than three buttons or a segmented row. Three labels laid out
 * across a 360 dp phone at the largest font size is the shape that clips, and
 * the reader is choosing one of a small set they rarely change — the case a
 * menu is for. The button says which order is on, so the answer to "how is this
 * sorted?" is on screen without opening anything.
 *
 * The menu's own state is [rememberSaveable] because the choice it writes goes
 * through the repository: the menu must survive the recomposition its own tap
 * causes, and a configuration change while it is open should not silently close
 * it.
 */
@Composable
private fun OrderControl(order: LibraryOrder, onOrderChange: (LibraryOrder) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val current = order.label()
    // One label for the whole control, so TalkBack says what the button does and
    // which order is on rather than reading "Sort colon Recently read".
    val label = stringResource(R.string.library_order_label, current)
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier
                .defaultMinSize(minHeight = Sizes.TouchTarget)
                .testTag("library_order")
                .semantics { contentDescription = label },
            contentPadding = PaddingValues(horizontal = Spacing.Medium, vertical = Spacing.Small),
        ) {
            Text(stringResource(R.string.library_order_button, current))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag("library_order_menu"),
        ) {
            LibraryOrder.entries.forEach { choice ->
                val chosen = choice == order
                DropdownMenuItem(
                    text = { Text(choice.label()) },
                    onClick = {
                        expanded = false
                        onOrderChange(choice)
                    },
                    // A tick on the current order, and the same fact in the
                    // semantics tree so TalkBack announces it as selected rather
                    // than describing an icon.
                    leadingIcon = {
                        if (chosen) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        } else {
                            Spacer(Modifier.size(Sizes.Icon))
                        }
                    },
                    modifier = Modifier
                        .testTag("library_order_${choice.name.lowercase()}")
                        .semantics { selected = chosen },
                )
            }
        }
    }
}

@Composable
private fun LibraryOrder.label(): String = stringResource(
    when (this) {
        LibraryOrder.TITLE -> R.string.library_order_title
        LibraryOrder.RECENTLY_READ -> R.string.library_order_recently_read
        LibraryOrder.RECENTLY_ADDED -> R.string.library_order_recently_added
    },
)

/**
 * The way into the added-folder list (REQ-104).
 *
 * Only shown once a folder exists: with none, the list would be a dead end, and
 * "Add folder" is already on the screen right above it.
 */
@Composable
private fun FoldersEntry(count: Int, onOpenFolders: () -> Unit) {
    if (count == 0) return
    TextButton(
        onClick = onOpenFolders,
        modifier = Modifier
            .defaultMinSize(minHeight = Sizes.TouchTarget)
            .testTag("library_open_folders"),
        contentPadding = PaddingValues(horizontal = Spacing.Medium, vertical = Spacing.Small),
    ) {
        Text(pluralStringResource(R.plurals.library_folders_open_count, count, count))
    }
}

/** The screen a stranger meets first: how to get their own books in. */
@Composable
internal fun EmptyLibrary(
    onAddBooks: () -> Unit,
    onAddFolder: () -> Unit,
    folderCount: Int,
    onOpenFolders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        // A line of prose stops being readable long before it stops fitting, so
        // on a wide screen this column takes a measure rather than the window.
        // At every phone width it is already narrower than this and the cap does
        // nothing (REQ-205 asks the *list* to use the width, not the paragraphs).
        modifier = modifier
            .widthIn(max = ReadableTextWidth)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.XXLarge, vertical = Spacing.XXLarge)
            .testTag("library_empty"),
        verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
    ) {
        Text(
            text = stringResource(R.string.library_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = stringResource(R.string.library_empty_intro), style = MaterialTheme.typography.bodyLarge)
        Bullet(stringResource(R.string.library_empty_pick))
        Bullet(stringResource(R.string.library_empty_folder))
        Spacer(Modifier.height(Spacing.XSmall))
        AddActions(onAddBooks = onAddBooks, onAddFolder = onAddFolder, horizontalPadding = 0.dp)
        // An added folder with nothing readable in it still has to be reachable,
        // or the only way to take it back out would be to add a book first.
        FoldersEntry(count = folderCount, onOpenFolders = onOpenFolders)
        Text(
            text = stringResource(R.string.library_empty_in_place),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text(text = "•", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clearAndSetSemantics {})
        Spacer(Modifier.width(Spacing.Small))
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun AddActions(
    onAddBooks: () -> Unit,
    onAddFolder: () -> Unit,
    horizontalPadding: Dp = Spacing.Large,
    /** Half the row each, which is right when the row is theirs alone. */
    weighted: Boolean = true,
) {
    Row(
        modifier = Modifier
            .then(if (weighted) Modifier.fillMaxWidth() else Modifier)
            .padding(horizontal = horizontalPadding, vertical = Spacing.Small),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
    ) {
        val share = if (weighted) Modifier.weight(1f) else Modifier
        OutlinedButton(
            onClick = onAddBooks,
            modifier = share.defaultMinSize(minHeight = Sizes.TouchTarget).testTag("library_add_books"),
        ) {
            Text(stringResource(R.string.library_add_books))
        }
        OutlinedButton(
            onClick = onAddFolder,
            modifier = share.defaultMinSize(minHeight = Sizes.TouchTarget).testTag("library_add_folder"),
        ) {
            Text(stringResource(R.string.library_add_folder))
        }
    }
}

@Composable
internal fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.library_search_label)) },
        placeholder = { Text(stringResource(R.string.library_search_placeholder)) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.library_search_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Large, vertical = Spacing.Small)
            .testTag("library_search"),
    )
}

@Composable
internal fun NoSearchResults(query: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Large, vertical = Spacing.XXLarge)
            .testTag("library_no_results"),
        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
    ) {
        Text(
            text = stringResource(R.string.library_no_results_title, query),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.library_no_results_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A comfortable measure for a column of prose; the empty library is the only one here. */
private val ReadableTextWidth = 560.dp
