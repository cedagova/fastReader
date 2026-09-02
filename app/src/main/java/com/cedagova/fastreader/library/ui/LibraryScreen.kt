package com.cedagova.fastreader.library.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cedagova.fastreader.R
import com.cedagova.fastreader.library.BookStatus
import com.cedagova.fastreader.library.ScanTrigger

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
    coverLoader: CoverLoader = CoverLoader.None,
) {
    Scaffold(
        modifier = modifier.fillMaxSize().testTag("library_screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    IconButton(onClick = onRefresh, modifier = Modifier.testTag("library_refresh")) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.library_refresh))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            state.failureMessage?.let { FailureBanner(it) }
            state.scan?.let { ScanBanner(it) }
            when (state.content) {
                LibraryContent.EMPTY_LIBRARY -> EmptyLibrary(
                    onAddBooks = onAddBooks,
                    onAddFolder = onAddFolder,
                )

                LibraryContent.NO_SEARCH_RESULTS,
                LibraryContent.BOOKS,
                -> {
                    SearchField(query = state.query, onQueryChange = onQueryChange)
                    AddActions(onAddBooks = onAddBooks, onAddFolder = onAddFolder)
                    if (state.content == LibraryContent.NO_SEARCH_RESULTS) {
                        NoSearchResults(state.query)
                    } else {
                        BookList(
                            books = state.books,
                            onRemove = onRemove,
                            onGrantAccess = onGrantAccess,
                            onOpen = onOpen,
                            coverLoader = coverLoader,
                        )
                    }
                }
            }
        }
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

@Composable
private fun EmptyLibrary(onAddBooks: () -> Unit, onAddFolder: () -> Unit) {
    Column(
        modifier = Modifier
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
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AddActions(
    onAddBooks: () -> Unit,
    onAddFolder: () -> Unit,
    horizontalPadding: Dp = 16.dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onAddBooks,
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = TouchTarget).testTag("library_add_books"),
        ) {
            Text(stringResource(R.string.library_add_books))
        }
        OutlinedButton(
            onClick = onAddFolder,
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = TouchTarget).testTag("library_add_folder"),
        ) {
            Text(stringResource(R.string.library_add_folder))
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
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
        modifier = Modifier
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
) {
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
}

@Composable
private fun BookRow(
    book: LibraryBookItem,
    onRemove: () -> Unit,
    onGrantAccess: () -> Unit,
    onOpen: () -> Unit,
    coverLoader: CoverLoader,
) {
    val author = book.author ?: stringResource(R.string.library_unknown_author)
    val statusLine = book.statusLine()
    val openLabel = stringResource(R.string.library_open, book.title)
    Row(
        modifier = Modifier
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
