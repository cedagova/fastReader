package com.cedagova.fastreader.library.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cedagova.fastreader.R
import com.cedagova.fastreader.content.BundledSample
import com.cedagova.fastreader.library.BookStatus
import com.cedagova.fastreader.library.ResumeBlockedReason
import com.cedagova.fastreader.library.ScanTrigger
import com.cedagova.fastreader.settings.LibraryOrder
import com.cedagova.fastreader.ui.SampleOffer
import com.cedagova.fastreader.ui.LayoutWidth
import com.cedagova.fastreader.ui.WideLayoutMinWidth
import com.cedagova.fastreader.ui.WidthAware
import com.cedagova.fastreader.ui.rememberSampleOrder

/** Smallest comfortable touch target; Android's accessibility minimum is 48dp (REQ-060). */
private val TouchTarget = 48.dp

/**
 * The library surface (LEAF102): empty-state guidance, the book list with covers
 * and progress, live search, the scan loading state, and a distinct
 * plain-language rendering for every book state LEAF101 can persist.
 *
 * Stateless on purpose — every state it can show is reachable from a
 * [LibraryUiState] value, which is what lets the Roborazzi renders be the UI
 * regression gate. [LibraryRoute] supplies the real repository-backed state.
 *
 * ## Two layouts, one screen (REQ-205)
 *
 * At [com.cedagova.fastreader.ui.WideLayoutMinWidth] and above the screen spends
 * the width it has in two places, and nowhere else changes:
 *
 * - **the header is one row**, search beside the two add buttons instead of above
 *   them, whenever [headerFitsOneRow]. That is width used, and it is also ~72 dp
 *   of height given back to the book list — which is what landscape, where the
 *   whole window is 411 dp tall, actually needs.
 * - **the list is a grid** of [bookColumnMinWidth]-wide columns instead of one
 *   full-width column, so a 10" tablet shows three or four books across rather
 *   than one book and a lot of nothing.
 *
 * The column minimum is font-scaled, which is the whole of how the grid keeps
 * REQ-205's "no clipped text at the largest font size": a book row's cover,
 * remove button and padding are a fixed 152 dp, and only the title and author
 * grow with the font, so the width a column needs is
 * `152 dp + 148 dp x fontScale`. At the largest size a 600 dp tablet therefore
 * gets one *wide* row rather than two cramped ones — the grid adds a column when
 * there is room for a whole one and not before.
 *
 * Below the breakpoint every one of these composes exactly as it did, which is why
 * the phone-portrait goldens are unchanged bytes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onQueryChange: (String) -> Unit,
    onAddBooks: () -> Unit,
    onAddFolder: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: (LibraryBookItem) -> Unit,
    onGrantAccess: (LibraryBookItem) -> Unit,
    onOpen: (LibraryBookItem) -> Unit,
    modifier: Modifier = Modifier,
    onDismissResumeNotice: () -> Unit = {},
    /** Opens the settings screen (LEAF302). */
    onOpenSettings: () -> Unit = {},
    /**
     * Opens one of the texts shipped inside the app (REQ-109). Offered only while
     * the library is empty: once there are real books, the sample would be
     * clutter in front of them and lives in Settings instead.
     */
    onOpenSample: (BundledSample) -> Unit = {},
    /** The samples, in the order this device should see them (Spanish first on a Spanish device). */
    samples: List<BundledSample> = rememberSampleOrder(),
    /** Opens the added-folder list (REQ-104). */
    onOpenFolders: () -> Unit = {},
    /** Takes back the removal the undo snackbar is offering (REQ-105). */
    onUndoRemove: () -> Unit = {},
    /** Stores a new library order (REQ-203). The list re-sorts from the stored value. */
    onOrderChange: (LibraryOrder) -> Unit = {},
    coverLoader: CoverLoader = CoverLoader.None,
) {
    WidthAware(modifier.fillMaxSize()) { layout ->
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("library_screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    IconButton(onClick = onRefresh, modifier = Modifier.testTag("library_refresh")) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.library_refresh))
                    }
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("library_settings")) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_open))
                    }
                },
            )
        },
        bottomBar = { state.undoNotice?.let { UndoBar(it, onUndoRemove) } },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            state.failureMessage?.let { FailureBanner(it) }
            state.resumeNotice?.let { ResumeNoticeBanner(it, onDismissResumeNotice) }
            state.scan?.let { ScanBanner(it) }
            when (state.content) {
                LibraryContent.EMPTY_LIBRARY -> EmptyLibrary(
                    onAddBooks = onAddBooks,
                    onAddFolder = onAddFolder,
                    onOpenSample = onOpenSample,
                    samples = samples,
                    folderCount = state.folders.size,
                    onOpenFolders = onOpenFolders,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )

                LibraryContent.NO_SEARCH_RESULTS,
                LibraryContent.BOOKS,
                -> {
                    if (headerFitsOneRow(layout)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().testTag("library_wide_header"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SearchField(
                                query = state.query,
                                onQueryChange = onQueryChange,
                                modifier = Modifier.weight(1f),
                            )
                            // Natural width, not a weight: at the largest font
                            // size a half-and-half split squeezes "Add folder"
                            // onto two lines, and the search field is the one of
                            // the three that can give room away.
                            AddActions(
                                onAddBooks = onAddBooks,
                                onAddFolder = onAddFolder,
                                weighted = false,
                            )
                        }
                    } else {
                        SearchField(query = state.query, onQueryChange = onQueryChange)
                        AddActions(onAddBooks = onAddBooks, onAddFolder = onAddFolder)
                    }
                    ListControls(
                        order = state.order,
                        onOrderChange = onOrderChange,
                        folderCount = state.folders.size,
                        onOpenFolders = onOpenFolders,
                    )
                    if (state.content == LibraryContent.NO_SEARCH_RESULTS) {
                        NoSearchResults(state.query)
                    } else {
                        BookList(
                            books = state.books,
                            onRemove = onRemove,
                            onGrantAccess = onGrantAccess,
                            onOpen = onOpen,
                            coverLoader = coverLoader,
                            wide = layout.wide,
                        )
                    }
                }
            }
        }
    }
    }
}

/**
 * Why the app opened here instead of in the book being read (REQ-009).
 *
 * The book's own row already carries its state, but a reader who expected to
 * land back in their book should not have to find the row and infer what
 * happened, so the reason is said once at the top and dismissed when read.
 */
@Composable
private fun ResumeNoticeBanner(notice: ResumeNotice, onDismiss: () -> Unit) {
    val title = notice.title ?: stringResource(R.string.library_resume_blocked_unnamed)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().testTag("library_resume_notice"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.library_resume_blocked_title, title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        when (notice.reason) {
                            ResumeBlockedReason.MISSING -> R.string.library_resume_blocked_missing
                            ResumeBlockedReason.PERMISSION_LOST -> R.string.library_resume_blocked_permission_lost
                            ResumeBlockedReason.UNREADABLE -> R.string.library_resume_blocked_unreadable
                            ResumeBlockedReason.REMOVED -> R.string.library_resume_blocked_removed
                            ResumeBlockedReason.NOT_IN_LIBRARY -> R.string.library_resume_blocked_gone
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minWidth = TouchTarget, minHeight = TouchTarget)
                    .testTag("library_resume_notice_dismiss"),
            ) {
                Text(stringResource(R.string.library_resume_blocked_dismiss))
            }
        }
    }
}

/**
 * The removal the reader can still take back (REQ-105).
 *
 * Anchored to the bottom of the screen rather than stacked with the banners at
 * the top: a book removed from the end of a long list would put a top banner
 * off-screen, and an offer that expires in eight seconds is worth nothing if the
 * reader has to scroll to find it.
 *
 * It is a plain [Snackbar] driven by [LibraryUiState] rather than a
 * `SnackbarHostState`, so it stays as testable as every other library state:
 * the goldens prove the copy and the control instead of a timing-dependent
 * overlay, and the window itself is the repository's to keep. The sentence says
 * what happened to the *file* as well as to the row, because "removed" is
 * exactly the word a reader would fear meant deleted.
 */
@Composable
private fun UndoBar(notice: UndoNotice, onUndo: () -> Unit) {
    val undoLabel = stringResource(R.string.library_undo_label, notice.title)
    Snackbar(
        modifier = Modifier.padding(12.dp).testTag("library_undo"),
        action = {
            TextButton(
                onClick = onUndo,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.inversePrimary,
                ),
                modifier = Modifier
                    .defaultMinSize(minWidth = TouchTarget, minHeight = TouchTarget)
                    .testTag("library_undo_action")
                    .semantics { contentDescription = undoLabel },
            ) {
                Text(stringResource(R.string.library_undo))
            }
        },
    ) {
        Text(stringResource(R.string.library_undo_removed, notice.title))
    }
}

/**
 * The way into the added-folder list (REQ-104).
 *
 * Only shown once a folder exists: with none, the list would be a dead end, and
 * "Add folder" is already on the screen right above it.
 */
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
private fun ListControls(
    order: LibraryOrder,
    onOrderChange: (LibraryOrder) -> Unit,
    folderCount: Int,
    onOpenFolders: () -> Unit,
    horizontalPadding: Dp = 16.dp,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
                .defaultMinSize(minHeight = TouchTarget)
                .testTag("library_order")
                .semantics { contentDescription = label },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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
                            Spacer(Modifier.size(24.dp))
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

@Composable
private fun FoldersEntry(count: Int, onOpenFolders: () -> Unit) {
    if (count == 0) return
    TextButton(
        onClick = onOpenFolders,
        modifier = Modifier
            .defaultMinSize(minHeight = TouchTarget)
            .testTag("library_open_folders"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(pluralStringResource(R.plurals.library_folders_open_count, count, count))
    }
}

@Composable
private fun FailureBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().testTag("library_problem"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.library_problem_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ScanBanner(scan: LibraryScan) {
    val label = stringResource(
        when (scan.trigger) {
            ScanTrigger.APP_OPEN -> R.string.library_scan_app_open
            ScanTrigger.MANUAL_REFRESH -> R.string.library_scan_manual_refresh
            ScanTrigger.ADD_BOOKS -> R.string.library_scan_add_books
            ScanTrigger.ADD_FOLDER -> R.string.library_scan_add_folder
        },
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("library_scanning"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            val detail = listOfNotNull(
                if (scan.hasCounts) stringResource(R.string.library_scan_counts, scan.processed, scan.total) else null,
                scan.currentName,
            ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val fraction = scan.fraction
            Spacer(Modifier.height(8.dp))
            if (fraction == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * The screen a stranger meets first: how to get their own books in, and — because
 * they have none yet and an empty screen is a dead end — something to read right
 * now (REQ-109).
 *
 * The sample comes after the two ways to add books, not before them. Adding a
 * book is what the app is for; the sample is what to do while you have not.
 */
@Composable
private fun EmptyLibrary(
    onAddBooks: () -> Unit,
    onAddFolder: () -> Unit,
    onOpenSample: (BundledSample) -> Unit,
    samples: List<BundledSample>,
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
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .testTag("library_empty"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
        Spacer(Modifier.height(4.dp))
        AddActions(onAddBooks = onAddBooks, onAddFolder = onAddFolder, horizontalPadding = 0.dp)
        // An added folder with nothing readable in it still has to be reachable,
        // or the only way to take it back out would be to add a book first.
        FoldersEntry(count = folderCount, onOpenFolders = onOpenFolders)
        Text(
            text = stringResource(R.string.library_empty_in_place),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.sample_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        SampleOffer(onOpenSample = onOpenSample, samples = samples)
    }
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text(text = "•", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clearAndSetSemantics {})
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AddActions(
    onAddBooks: () -> Unit,
    onAddFolder: () -> Unit,
    horizontalPadding: Dp = 16.dp,
    /** Half the row each, which is right when the row is theirs alone. */
    weighted: Boolean = true,
) {
    Row(
        modifier = Modifier
            .then(if (weighted) Modifier.fillMaxWidth() else Modifier)
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val share = if (weighted) Modifier.weight(1f) else Modifier
        OutlinedButton(
            onClick = onAddBooks,
            modifier = share.defaultMinSize(minHeight = TouchTarget).testTag("library_add_books"),
        ) {
            Text(stringResource(R.string.library_add_books))
        }
        OutlinedButton(
            onClick = onAddFolder,
            modifier = share.defaultMinSize(minHeight = TouchTarget).testTag("library_add_folder"),
        ) {
            Text(stringResource(R.string.library_add_folder))
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
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
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("library_search"),
    )
}

@Composable
private fun NoSearchResults(query: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .testTag("library_no_results"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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

@Composable
private fun BookList(
    books: List<LibraryBookItem>,
    onRemove: (LibraryBookItem) -> Unit,
    onGrantAccess: (LibraryBookItem) -> Unit,
    onOpen: (LibraryBookItem) -> Unit,
    coverLoader: CoverLoader,
    wide: Boolean = false,
) {
    if (!wide) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("library_list"),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(items = books, key = { it.id }) { book ->
                BookRow(
                    book = book,
                    onRemove = { onRemove(book) },
                    onGrantAccess = { onGrantAccess(book) },
                    onOpen = { onOpen(book) },
                    coverLoader = coverLoader,
                )
                HorizontalDivider()
            }
        }
        return
    }
    LazyVerticalGrid(
        // Adaptive, not a column count: the same rule then covers a 600 dp tablet,
        // a phone on its side and a 10" tablet, and it is the rule that keeps the
        // largest font size legible instead of a second breakpoint that would have
        // to be kept in step with it.
        columns = GridCells.Adaptive(minSize = bookColumnMinWidth()),
        modifier = Modifier.fillMaxSize().testTag("library_list"),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        gridItems(items = books, key = { it.id }) { book ->
            // No `fillMaxHeight`/`weight` here, however much the dividers would
            // like to line up across a row: a lazy grid measures its items with an
            // unbounded height, where `fillMaxHeight` does nothing and a weight
            // resolves against infinity — which is how the first draft of this
            // rendered four books at zero height and an empty screen.
            Column {
                // The divider goes *above* the row here, where the list puts it
                // below. Every cell in a grid row starts at the same y and they
                // end at different ones, so a leading rule is the only one that
                // draws as an unbroken line between rows instead of a staircase.
                HorizontalDivider()
                BookRow(
                    book = book,
                    onRemove = { onRemove(book) },
                    onGrantAccess = { onGrantAccess(book) },
                    onOpen = { onOpen(book) },
                    coverLoader = coverLoader,
                )
            }
        }
    }
}

/**
 * Whether search and the two add buttons fit on one row at the current font scale.
 *
 * The same shape of rule as [bookColumnMinWidth] and for the same reason: the
 * buttons take the width their labels need, so the width the row needs grows with
 * the font. At 600 dp and the largest font size the two buttons alone want 317 dp
 * and the search field is left folding its own label in half — so the boundary
 * tablet keeps the stacked header at that size, and a phone on its side (914 dp),
 * where the row genuinely fits, does not.
 */
@Composable
private fun headerFitsOneRow(layout: LayoutWidth): Boolean =
    layout.wide && layout.available >= WideLayoutMinWidth * LocalDensity.current.fontScale

/**
 * The narrowest a book column may be, at the current font scale.
 *
 * A book row spends [BookRowFixedWidth] on things that do not grow with the font —
 * the 48 dp cover, the 48 dp remove button, the gaps between them and the row's own
 * padding — and everything else on the title, author and status line, which do.
 * So the minimum is a fixed part plus a scaled one, and the grid drops to fewer,
 * wider columns exactly when the text would otherwise start losing its ends
 * (REQ-205, REQ-301).
 */
@Composable
private fun bookColumnMinWidth(): Dp =
    BookRowFixedWidth + BookRowTextWidth * LocalDensity.current.fontScale

/** 48 dp cover + 16 dp gap + 8 dp gap + 48 dp remove button + 2 x 16 dp padding. */
private val BookRowFixedWidth = 152.dp

/** Room for roughly a dozen characters of title per line at the default size. */
private val BookRowTextWidth = 148.dp

/** A comfortable measure for a column of prose; the empty library is the only one here. */
private val ReadableTextWidth = 560.dp

@Composable
private fun BookRow(
    book: LibraryBookItem,
    onRemove: () -> Unit,
    onGrantAccess: () -> Unit,
    onOpen: () -> Unit,
    coverLoader: CoverLoader,
    modifier: Modifier = Modifier,
) {
    val author = book.author ?: stringResource(R.string.library_unknown_author)
    val statusLine = book.statusLine()
    val openLabel = stringResource(R.string.library_open, book.title)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp)
            // A readable book opens in the reader; the rest already explain in
            // their status line why there is nothing to open.
            .then(
                if (book.isReadable) {
                    Modifier.clickable(onClickLabel = openLabel, onClick = onOpen)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("library_book_${book.id}"),
        verticalAlignment = Alignment.Top,
    ) {
        Cover(book = book, coverLoader = coverLoader)
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                // One TalkBack stop for the book; the actions stay separately focusable.
                .semantics(mergeDescendants = true) {},
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = author,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = if (book.isReadable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 4.dp),
            )
            if (book.status == BookStatus.PERMISSION_LOST) {
                TextButton(
                    onClick = onGrantAccess,
                    modifier = Modifier
                        .defaultMinSize(minHeight = TouchTarget)
                        .testTag("library_grant_${book.id}"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.library_grant_access))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(TouchTarget).testTag("library_remove_${book.id}"),
        ) {
            Icon(
                imageVector = Icons.Filled.Clear,
                contentDescription = stringResource(R.string.library_remove, book.title),
            )
        }
    }
}

@Composable
private fun LibraryBookItem.statusLine(): String = when (status) {
    BookStatus.READABLE -> stringResource(R.string.library_progress, progressPercent)
    BookStatus.CORRUPT -> stringResource(R.string.library_state_corrupt)
    BookStatus.DRM_PROTECTED -> stringResource(R.string.library_state_drm)
    BookStatus.MISSING -> stringResource(R.string.library_state_missing)
    BookStatus.PERMISSION_LOST -> stringResource(R.string.library_state_permission_lost)
}

@Composable
private fun Cover(book: LibraryBookItem, coverLoader: CoverLoader) {
    val image: ImageBitmap? by produceState<ImageBitmap?>(null, book.id, book.hasCover, coverLoader) {
        value = if (book.hasCover) coverLoader.load(book.id) else null
    }
    val shape = RoundedCornerShape(4.dp)
    val bitmap = image
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 48.dp, height = 64.dp)
                .clip(shape)
                // Decorative, like the placeholder: otherwise TalkBack says the
                // title twice, and only for the books that happen to have art.
                .clearAndSetSemantics {},
        )
    } else {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 64.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                // Decorative: the title next to it already says which book this is.
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = book.coverInitial,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
