package com.cedagova.fastreader.library.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.cedagova.fastreader.R
import com.cedagova.fastreader.settings.LibraryOrder
import com.cedagova.fastreader.ui.LayoutWidth
import com.cedagova.fastreader.ui.WideLayoutMinWidth
import com.cedagova.fastreader.ui.WidthAware
import com.cedagova.fastreader.ui.components.ConfirmDialog
import com.cedagova.fastreader.ui.components.ProblemBanner
import com.cedagova.fastreader.ui.components.UndoBar
import com.cedagova.reader.account.library.BookImportState

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
    /** Opens the added-folder list (REQ-104). */
    onOpenFolders: () -> Unit = {},
    /** Takes back the removal the undo snackbar is offering (REQ-105). */
    onUndoRemove: () -> Unit = {},
    /** Removes the book from the Reader account, on every device (REQ-508). */
    onRemoveFromAccount: (LibraryBookItem) -> Unit = {},
    /** Takes back the account removal, as the contract's one immediate Undo (REQ-508). */
    onUndoAccountRemove: () -> Unit = {},
    /**
     * Asks what it would take to add this device book to the account (REQ-505).
     *
     * It does **not** add it. It reads the deployment's policy and leads to the
     * consent question or to the refusal that policy already implies; nothing
     * about the book leaves the device on this callback.
     */
    onAddToAccount: (LibraryBookItem) -> Unit = {},
    /** The owner answered yes: the one callback here that can send a book's bytes. */
    onConfirmAddToAccount: (LibraryBookItem) -> Unit = {},
    /** Stops an add that is already under way; the device book is untouched. */
    onCancelAddToAccount: (LibraryBookItem) -> Unit = {},
    /** Declines the question, or puts away a refusal that has been read. Sends nothing. */
    onDismissAddToAccount: (LibraryBookItem) -> Unit = {},
    /** Puts away an account notice that is a verdict about the past, not a live state. */
    onDismissAccountNotice: () -> Unit = {},
    /**
     * Fetches an account book's bytes and opens it (REQ-510).
     *
     * It is a *download*, not an open: nothing opens until the bytes have been
     * placed and their SHA-256 has matched the account's identity for the book.
     */
    onDownloadAccountBook: (LibraryBookItem) -> Unit = {},
    /** Stops a download under way. Nothing was placed, so the row is untouched. */
    onCancelDownload: (LibraryBookItem) -> Unit = {},
    /** Puts away a download refusal that has been read. Downloads nothing. */
    onDismissDownload: (LibraryBookItem) -> Unit = {},
    /** Frees this device's downloaded copy; the account keeps the book (D2). */
    onRemoveAccountCopy: (LibraryBookItem) -> Unit = {},
    /** Stores a new library order (REQ-203). The list re-sorts from the stored value. */
    onOrderChange: (LibraryOrder) -> Unit = {},
    coverLoader: CoverLoader = CoverLoader.None,
) {
    // The book whose removal is waiting on a yes, by id so it survives rotation.
    // Removal was one tap and an undo bar; a confirmation first is what the
    // reader asked for, and the undo stays as the second chance after it.
    var confirmingRemoval by rememberSaveable { mutableStateOf<String?>(null) }
    val bookToRemove = confirmingRemoval?.let { id -> state.books.firstOrNull { it.id == id } }
    if (bookToRemove != null) {
        RemoveBookDialog(
            book = bookToRemove,
            onConfirm = {
                confirmingRemoval = null
                onRemove(bookToRemove)
            },
            onDismiss = { confirmingRemoval = null },
        )
    }
    // The same shape for the account removal, and deliberately a *second* piece
    // of state: the two removals mean different things, and a reader who has
    // said yes to one must never have said yes to the other.
    var confirmingAccountRemoval by rememberSaveable { mutableStateOf<String?>(null) }
    val bookToRemoveFromAccount = confirmingAccountRemoval?.let { id -> state.books.firstOrNull { it.id == id } }
    if (bookToRemoveFromAccount != null) {
        RemoveFromAccountDialog(
            book = bookToRemoveFromAccount,
            onConfirm = {
                confirmingAccountRemoval = null
                onRemoveFromAccount(bookToRemoveFromAccount)
            },
            onDismiss = { confirmingAccountRemoval = null },
        )
    }
    // And a third, for the third removal this shelf can offer. Freeing a
    // downloaded copy is neither of the other two — the book stays in the
    // account and the row stays on the shelf — so a yes to it must never be
    // mistaken for a yes to either.
    var confirmingCopyRemoval by rememberSaveable { mutableStateOf<String?>(null) }
    val bookToFree = confirmingCopyRemoval?.let { id -> state.books.firstOrNull { it.id == id } }
    if (bookToFree?.accountCopy != null) {
        RemoveAccountCopyDialog(
            book = bookToFree,
            copy = bookToFree.accountCopy,
            onConfirm = {
                confirmingCopyRemoval = null
                onRemoveAccountCopy(bookToFree)
            },
            onDismiss = { confirmingCopyRemoval = null },
        )
    }
    // The consent question is driven by the flow's own state rather than by a
    // tap this screen remembers, and deliberately so: it is the *only* gate in
    // front of a book's bytes, so it must be the same value the code that sends
    // them is looking at. A remembered id could say yes to a question the flow
    // had already moved past.
    val bookAwaitingConsent = state.books.firstOrNull { it.addToAccount?.state is BookImportState.Consent }
    val consent = bookAwaitingConsent?.addToAccount?.state as? BookImportState.Consent
    if (bookAwaitingConsent != null && consent != null) {
        AddToAccountDialog(
            book = bookAwaitingConsent,
            consent = consent,
            onConfirm = { onConfirmAddToAccount(bookAwaitingConsent) },
            onDismiss = { onDismissAddToAccount(bookAwaitingConsent) },
        )
    }
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
            bottomBar = {
                // Both offers can be on screen in principle, so both are laid out
                // rather than one hiding the other: each names its own book and its
                // own Undo, and a reader must never press the wrong one.
                Column {
                    state.accountUndoNotice?.let { AccountUndoBar(it, onUndoAccountRemove) }
                    state.undoNotice?.let { notice ->
                        // The sentence says what happened to the *file* as well as to
                        // the row, because "removed" is exactly the word a reader
                        // would fear meant deleted (REQ-105).
                        UndoBar(
                            message = stringResource(R.string.library_undo_removed, notice.title),
                            actionLabel = stringResource(R.string.library_undo),
                            actionDescription = stringResource(R.string.library_undo_label, notice.title),
                            onUndo = onUndoRemove,
                            tag = "library_undo",
                        )
                    }
                }
            },
        ) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                state.failureMessage?.let {
                    ProblemBanner(
                        title = stringResource(R.string.library_problem_title),
                        message = it,
                        modifier = Modifier.testTag("library_problem"),
                    )
                }
                state.accountNotice?.let { AccountNoticeBanner(it, onDismissAccountNotice) }
                state.resumeNotice?.let { ResumeNoticeBanner(it, onDismissResumeNotice) }
                state.scan?.let { ScanBanner(it) }
                when (state.content) {
                    LibraryContent.EMPTY_LIBRARY -> EmptyLibrary(
                        onAddBooks = onAddBooks,
                        onAddFolder = onAddFolder,
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
                                onRemove = { confirmingRemoval = it.id },
                                onRemoveFromAccount = { confirmingAccountRemoval = it.id },
                                onRemoveAccountCopy = { confirmingCopyRemoval = it.id },
                                onAddToAccount = onAddToAccount,
                                onCancelAddToAccount = onCancelAddToAccount,
                                onDismissAddToAccount = onDismissAddToAccount,
                                onDownloadAccountBook = onDownloadAccountBook,
                                onCancelDownload = onCancelDownload,
                                onDismissDownload = onDismissDownload,
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
 * The question before a book leaves the library. It names the book, says what
 * removal does and does not do, and its action says "book" rather than a bare
 * "Remove", the way [FolderListScreen]'s confirmation says "folder".
 */
@Composable
private fun RemoveBookDialog(book: LibraryBookItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.library_remove_title, book.title),
        confirmLabel = stringResource(R.string.library_remove_confirm),
        dismissLabel = stringResource(R.string.library_remove_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        tag = "library_remove_dialog",
    ) {
        Text(
            text = stringResource(R.string.library_remove_kept),
            style = MaterialTheme.typography.bodyMedium,
        )
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
