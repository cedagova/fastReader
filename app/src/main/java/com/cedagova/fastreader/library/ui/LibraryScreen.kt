package com.cedagova.fastreader.library.ui

import android.text.format.Formatter
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cedagova.fastreader.R
import com.cedagova.fastreader.account.library.BookDownloadState
import com.cedagova.fastreader.account.library.BookImportState
import com.cedagova.fastreader.account.library.DownloadProblem
import com.cedagova.fastreader.account.library.ImportProblem
import com.cedagova.fastreader.account.library.ImportsOff
import com.cedagova.fastreader.account.library.PublicationSourceProblem
import com.cedagova.reader.library.sync.wireName
import com.cedagova.fastreader.library.BookStatus
import com.cedagova.fastreader.library.ResumeBlockedReason
import com.cedagova.fastreader.library.ScanTrigger
import com.cedagova.fastreader.settings.LibraryOrder
import com.cedagova.fastreader.ui.LayoutWidth
import com.cedagova.fastreader.ui.WideLayoutMinWidth
import com.cedagova.fastreader.ui.WidthAware
import com.cedagova.reader.library.model.PublicationFailureCategory
import com.cedagova.reader.library.model.ReaderCapabilityReason
import kotlin.math.roundToInt

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
            onConfirm = { confirmingRemoval = null; onRemove(bookToRemove) },
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
            onConfirm = { confirmingAccountRemoval = null; onRemoveFromAccount(bookToRemoveFromAccount) },
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
            onConfirm = { confirmingCopyRemoval = null; onRemoveAccountCopy(bookToFree) },
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
                state.undoNotice?.let { UndoBar(it, onUndoRemove) }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            state.failureMessage?.let { FailureBanner(it) }
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
 * What the shelf says about the account library (REQ-501, the states table).
 *
 * One banner for every account state that is not "settled and online", because
 * the definition's rule is that none of them is ever silent: bootstrapping,
 * offline with the queue named, a capability the backend has not made
 * available, a session it no longer accepts, and a change it refused outright.
 * Signed out there is no notice at all — that is D4's v1.6.0 shelf — and the
 * one exception, a session that went away, is the one the table says shows its
 * reason once.
 *
 * It is a polite live region so TalkBack announces the change rather than
 * leaving a reader who is not looking at the screen to discover it (REQ-060).
 * The backend's own code and request id are shown under the sentence: the
 * sentence is for the reader and the code is what finds a server log.
 */
@Composable
private fun AccountNoticeBanner(notice: AccountNotice, onDismiss: () -> Unit) {
    val body = when (notice.kind) {
        AccountNoticeKind.BOOTSTRAPPING -> stringResource(R.string.library_account_bootstrapping)
        AccountNoticeKind.OFFLINE -> stringResource(R.string.library_account_offline)
        AccountNoticeKind.UNAVAILABLE -> stringResource(R.string.library_account_unavailable)
        AccountNoticeKind.SESSION_GONE -> stringResource(R.string.library_account_session_gone)
        AccountNoticeKind.REJECTED -> stringResource(R.string.library_account_rejected)
        AccountNoticeKind.PROBLEM -> stringResource(R.string.library_account_problem)
    }
    val queued = if (notice.kind == AccountNoticeKind.OFFLINE && notice.queued > 0) {
        pluralStringResource(R.plurals.library_account_queued, notice.queued, notice.queued)
    } else {
        null
    }
    val detail = listOfNotNull(
        notice.code?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.library_account_code, it) },
        notice.requestId?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.library_account_request, it) },
    ).joinToString(" \u00b7 ")
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("library_account_notice")
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.library_account_notice_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = body, style = MaterialTheme.typography.bodyMedium)
                queued?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (notice.dismissible) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .defaultMinSize(minWidth = TouchTarget, minHeight = TouchTarget)
                        .testTag("library_account_notice_dismiss"),
                ) {
                    Text(stringResource(R.string.library_resume_blocked_dismiss))
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
        }
    }
}

/**
 * The account removal the reader can still take back (REQ-508).
 *
 * The same shape as the library's own undo bar, and deliberately a different
 * sentence: this removal reaches every device the account is signed in on, and
 * it left this device's file and this device's place in the book alone. That
 * distinction is the whole reason the two removals are separate controls.
 */
@Composable
private fun AccountUndoBar(notice: AccountUndoNotice, onUndo: () -> Unit) {
    val undoLabel = stringResource(R.string.library_account_undo_label, notice.title)
    Snackbar(
        modifier = Modifier.padding(12.dp).testTag("library_account_undo"),
        action = {
            TextButton(
                onClick = onUndo,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.inversePrimary,
                ),
                modifier = Modifier
                    .defaultMinSize(minWidth = TouchTarget, minHeight = TouchTarget)
                    .testTag("library_account_undo_action")
                    .semantics { contentDescription = undoLabel },
            ) {
                Text(stringResource(R.string.library_undo))
            }
        },
    ) {
        Text(stringResource(R.string.library_account_undo_removed, notice.title))
    }
}

/**
 * The question before a book leaves the account (REQ-508).
 *
 * It says the thing a reader would otherwise have to find out afterwards: this
 * removal is not local. The book leaves the Reader account on every device
 * signed in to it — and the file on this device, and the reader's place in it,
 * are not touched.
 */
@Composable
private fun RemoveFromAccountDialog(book: LibraryBookItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("library_account_remove_dialog"),
        title = {
            Text(
                text = stringResource(R.string.library_account_remove_title, book.title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Text(
                text = stringResource(
                    if (book.isAccountOnly) {
                        R.string.library_account_remove_body_account_only
                    } else {
                        R.string.library_account_remove_body
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier
                    .defaultMinSize(minWidth = TouchTarget, minHeight = TouchTarget)
                    .testTag("library_account_remove_dialog_confirm"),
            ) {
                Text(stringResource(R.string.library_account_remove_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .defaultMinSize(minWidth = TouchTarget, minHeight = TouchTarget)
                    .testTag("library_account_remove_dialog_cancel"),
            ) {
                Text(stringResource(R.string.library_remove_cancel))
            }
        },
    )
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

/** The screen a stranger meets first: how to get their own books in. */
@Composable
private fun EmptyLibrary(
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
    onRemoveFromAccount: (LibraryBookItem) -> Unit,
    onRemoveAccountCopy: (LibraryBookItem) -> Unit,
    onAddToAccount: (LibraryBookItem) -> Unit,
    onCancelAddToAccount: (LibraryBookItem) -> Unit,
    onDismissAddToAccount: (LibraryBookItem) -> Unit,
    onDownloadAccountBook: (LibraryBookItem) -> Unit,
    onCancelDownload: (LibraryBookItem) -> Unit,
    onDismissDownload: (LibraryBookItem) -> Unit,
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
                    onRemoveFromAccount = { onRemoveFromAccount(book) },
                    onRemoveAccountCopy = { onRemoveAccountCopy(book) },
                    onAddToAccount = { onAddToAccount(book) },
                    onCancelAddToAccount = { onCancelAddToAccount(book) },
                    onDismissAddToAccount = { onDismissAddToAccount(book) },
                    onDownloadAccountBook = { onDownloadAccountBook(book) },
                    onCancelDownload = { onCancelDownload(book) },
                    onDismissDownload = { onDismissDownload(book) },
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
                    onRemoveFromAccount = { onRemoveFromAccount(book) },
                    onRemoveAccountCopy = { onRemoveAccountCopy(book) },
                    onAddToAccount = { onAddToAccount(book) },
                    onCancelAddToAccount = { onCancelAddToAccount(book) },
                    onDismissAddToAccount = { onDismissAddToAccount(book) },
                    onDownloadAccountBook = { onDownloadAccountBook(book) },
                    onCancelDownload = { onCancelDownload(book) },
                    onDismissDownload = { onDismissDownload(book) },
                    onGrantAccess = { onGrantAccess(book) },
                    onOpen = { onOpen(book) },
                    coverLoader = coverLoader,
                )
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
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("library_remove_dialog"),
        title = {
            Text(
                text = stringResource(R.string.library_remove_title, book.title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Text(
                text = stringResource(R.string.library_remove_kept),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier
                    .defaultMinSize(minWidth = TouchTarget, minHeight = TouchTarget)
                    .testTag("library_remove_dialog_confirm"),
            ) {
                Text(stringResource(R.string.library_remove_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .defaultMinSize(minWidth = TouchTarget, minHeight = TouchTarget)
                    .testTag("library_remove_dialog_cancel"),
            ) {
                Text(stringResource(R.string.library_remove_cancel))
            }
        },
    )
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
    onRemoveFromAccount: () -> Unit,
    onRemoveAccountCopy: () -> Unit,
    onAddToAccount: () -> Unit,
    onCancelAddToAccount: () -> Unit,
    onDismissAddToAccount: () -> Unit,
    onDownloadAccountBook: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismissDownload: () -> Unit,
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
                if (book.canOpen) {
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
            // A new slot under the status line, beside the re-grant button that
            // was already there: removing a book from the account is a different
            // act from removing its row here, so it is a different control that
            // says which one it is (REQ-508).
            if (book.account != null) {
                val accountRemoveLabel = stringResource(R.string.library_account_remove_label, book.title)
                TextButton(
                    onClick = onRemoveFromAccount,
                    modifier = Modifier
                        .defaultMinSize(minHeight = TouchTarget)
                        .testTag("library_account_remove_${book.id}")
                        .semantics { contentDescription = accountRemoveLabel },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.library_account_remove))
                }
            }
            // The other half of the account pair, and the one that puts bytes on
            // the wire: adding this device's book to the account (REQ-505). It
            // is present only on a row that could actually be added, so every
            // signed-out row and every account row look exactly as they did.
            book.addToAccount?.let { add ->
                AddToAccountSlot(
                    book = book,
                    add = add,
                    onAdd = onAddToAccount,
                    onCancel = onCancelAddToAccount,
                    onDismiss = onDismissAddToAccount,
                )
            }
            // The other direction (REQ-510): bringing an account book's bytes
            // here. Present on an account-only row and nowhere else.
            if (book.isAccountOnly) {
                AccountDownloadSlot(
                    book = book,
                    onDownload = onDownloadAccountBook,
                    onCancel = onCancelDownload,
                    onDismiss = onDismissDownload,
                )
            }
            // And freeing those bytes again (D2). Read off the catalog's own
            // `ACCOUNT_COPY` source, so it is still here after sign-out — which
            // is exactly what "a downloaded copy is an ordinary device book,
            // openable and removable" has to mean on screen (D4).
            if (book.accountCopy != null) {
                val freeLabel = stringResource(R.string.library_account_copy_remove_label, book.title)
                TextButton(
                    onClick = onRemoveAccountCopy,
                    modifier = Modifier
                        .defaultMinSize(minHeight = TouchTarget)
                        .testTag("library_account_copy_remove_${book.id}")
                        .semantics { contentDescription = freeLabel },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.library_account_copy_remove))
                }
            }
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
        // Removing the *row* is removing this device's copy from this device's
        // library, and an account-only row has no copy here to remove: its only
        // removal is the account one above. A downloaded copy has one too, and
        // it is the one above that frees the bytes — offering this as well
        // would let a reader drop the row and leave the file behind, which is
        // the one way "removable" could be untrue. Every other device row keeps
        // this button exactly as it was.
        if (!book.isAccountOnly && book.accountCopy == null) {
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
}

/**
 * Everything the add-to-account flow puts under a device book's status line
 * (REQ-505, REQ-506, REQ-507).
 *
 * One slot rather than several, because the states are exclusive: the action,
 * the reason the action is not there, the wait while the policy is read, the
 * transfer with its Cancel, and the verdict with the backend's own words. The
 * consent question itself is not here — it is a dialog over the whole screen,
 * for the same reason the two removals are.
 *
 * The sentences sit inside the row's own merged semantics, so TalkBack reads
 * them as part of the book — "Rayuela, …, Sending to your Reader account, 42%"
 * — rather than as a stray line a reader would have to hunt for. The buttons
 * stay separately focusable, because a clickable is its own merge boundary, and
 * each of them names the book it belongs to (REQ-060).
 */
@Composable
private fun AddToAccountSlot(
    book: LibraryBookItem,
    add: AddToAccount,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val off = add.off
    if (off != null) {
        ImportNote(
            text = stringResource(off.sentence()),
            // The capability's own typed reason, as the sync notice quotes its
            // own (#139). A missing or duplicated entry has no reason to quote.
            code = off.reason?.takeIf { it != ReaderCapabilityReason.UNKNOWN }?.wireName(),
            requestId = off.requestId,
            tag = "library_account_add_off_${book.id}",
        )
        return
    }
    when (val state = add.state) {
        null -> {
            val label = stringResource(R.string.library_account_add_label, book.title)
            TextButton(
                onClick = onAdd,
                modifier = Modifier
                    .defaultMinSize(minHeight = TouchTarget)
                    .testTag("library_account_add_${book.id}")
                    .semantics { contentDescription = label },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.library_account_add))
            }
        }

        // Reading the policy. Said rather than left blank, because the tap has
        // to look like it did something — and because what it did is exactly
        // this and nothing about the book has been sent.
        BookImportState.Checking -> ImportNote(
            text = stringResource(R.string.library_account_add_checking),
            tag = "library_account_add_checking_${book.id}",
            progress = INDETERMINATE,
        )

        // The dialog has the question; the row stays quiet behind it.
        is BookImportState.Consent -> Unit

        is BookImportState.Sending -> ImportNote(
            text = stringResource(
                R.string.library_account_add_sending,
                (state.fraction * PERCENT).roundToInt().coerceIn(0, PERCENT),
            ),
            tag = "library_account_add_sending_${book.id}",
            progress = state.fraction,
            action = ImportAction(
                label = stringResource(R.string.library_account_add_cancel),
                description = stringResource(R.string.library_account_add_cancel_label, book.title),
                tag = "library_account_add_cancel_${book.id}",
                onClick = onCancel,
            ),
        )

        BookImportState.Finishing -> ImportNote(
            text = stringResource(R.string.library_account_add_finishing),
            tag = "library_account_add_finishing_${book.id}",
            progress = INDETERMINATE,
            action = ImportAction(
                label = stringResource(R.string.library_account_add_cancel),
                description = stringResource(R.string.library_account_add_cancel_label, book.title),
                tag = "library_account_add_cancel_${book.id}",
                onClick = onCancel,
            ),
        )

        is BookImportState.Refused -> ImportNote(
            // Two sentences, always: what the backend said, and the thing the
            // reader actually wants to know — that the book on this phone is
            // exactly as it was (REQ-507).
            text = state.message() + " " + stringResource(R.string.library_account_add_kept),
            code = state.code,
            requestId = state.requestId,
            tag = "library_account_add_refused_${book.id}",
            error = true,
            action = if (state.retryable) {
                ImportAction(
                    label = stringResource(R.string.library_account_add_retry),
                    description = stringResource(R.string.library_account_add_retry_label, book.title),
                    tag = "library_account_add_retry_${book.id}",
                    onClick = onAdd,
                )
            } else {
                ImportAction(
                    label = stringResource(R.string.library_resume_blocked_dismiss),
                    description = null,
                    tag = "library_account_add_dismiss_${book.id}",
                    onClick = onDismiss,
                )
            },
        )
    }
}

/**
 * Everything the download flow puts under an account-only book's status line
 * (REQ-510, the definition's "Open account book not on device" and "Download
 * grant unavailable / storage full").
 *
 * Three exclusive states and no fourth: the offer, the transfer with its
 * Cancel, and the refusal with its reason. There is deliberately nothing for
 * "done" — a copy that has landed has become a device row with a cover, a
 * position and a **Remove downloaded copy** action, so this slot is gone by
 * then rather than showing a stale success.
 *
 * Every refusal ends with the same second sentence: the book is still in the
 * Reader account. That is the definition's "the book stays listed" said to the
 * reader rather than only held by the data, and it is what makes each of these
 * a statement about one attempt instead of about their library.
 */
@Composable
private fun AccountDownloadSlot(
    book: LibraryBookItem,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (val state = book.download) {
        null -> {
            val label = stringResource(R.string.library_account_download_label, book.title)
            TextButton(
                onClick = onDownload,
                modifier = Modifier
                    .defaultMinSize(minHeight = TouchTarget)
                    .testTag("library_account_download_${book.id}")
                    .semantics { contentDescription = label },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.library_account_download))
            }
        }

        is BookDownloadState.Downloading -> {
            val fraction = state.fraction
            ImportNote(
                text = if (fraction == null) {
                    stringResource(R.string.library_account_downloading_unmeasured)
                } else {
                    stringResource(
                        R.string.library_account_downloading,
                        (fraction * PERCENT).roundToInt().coerceIn(0, PERCENT),
                    )
                },
                tag = "library_account_downloading_${book.id}",
                progress = fraction ?: INDETERMINATE,
                action = ImportAction(
                    label = stringResource(R.string.library_account_download_cancel),
                    description = stringResource(R.string.library_account_download_cancel_label, book.title),
                    tag = "library_account_download_cancel_${book.id}",
                    onClick = onCancel,
                ),
            )
        }

        is BookDownloadState.Refused -> ImportNote(
            text = state.message() + " " + stringResource(R.string.library_account_download_kept),
            code = state.code,
            requestId = state.requestId,
            tag = "library_account_download_refused_${book.id}",
            error = true,
            action = if (state.retryable) {
                ImportAction(
                    label = stringResource(R.string.library_account_download_retry),
                    description = stringResource(R.string.library_account_download_retry_label, book.title),
                    tag = "library_account_download_retry_${book.id}",
                    onClick = onDownload,
                )
            } else {
                ImportAction(
                    label = stringResource(R.string.library_resume_blocked_dismiss),
                    description = null,
                    tag = "library_account_download_dismiss_${book.id}",
                    onClick = onDismiss,
                )
            },
        )
    }
}

/**
 * The refusal, in the reader's language, from the one closed set of reasons a
 * download can fail for.
 *
 * Each sentence names the thing that actually went wrong — the asset, this
 * device, the network, the backend, or the book — because a reader's next move
 * is different for each, and "something went wrong" would leave them without
 * one.
 */
@Composable
private fun BookDownloadState.Refused.message(): String = stringResource(
    when (problem) {
        DownloadProblem.TAMPERED -> R.string.library_account_download_tampered
        DownloadProblem.NO_STORAGE -> R.string.library_account_download_no_storage
        DownloadProblem.OFFLINE -> R.string.library_account_download_offline
        DownloadProblem.REFUSED -> R.string.library_account_download_refused
        DownloadProblem.FAILED -> R.string.library_account_download_failed
        DownloadProblem.REDIRECTED -> R.string.library_account_download_redirected
        DownloadProblem.UNREADABLE -> R.string.library_account_download_unreadable
        DownloadProblem.UNAVAILABLE -> R.string.library_account_download_unavailable
    },
)

/**
 * The question before a downloaded copy's bytes are freed (D2).
 *
 * It is the third removal this shelf offers and the only one that costs bytes,
 * so it says the three things that distinguish it from the other two: how much
 * is freed, that the **account** keeps the book and it can be fetched again,
 * and that the reader's place in it survives. The size is the file's own —
 * taken from the catalog source the copy wrote — not a number the account
 * reported.
 */
@Composable
private fun RemoveAccountCopyDialog(
    book: LibraryBookItem,
    copy: AccountCopyRow,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("library_account_copy_remove_dialog"),
        title = {
            Text(
                text = stringResource(R.string.library_account_copy_remove_title, book.title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Text(
                text = if (copy.sizeBytes > 0) {
                    stringResource(R.string.library_account_copy_remove_body, humanSize(copy.sizeBytes))
                } else {
                    stringResource(R.string.library_account_copy_remove_body_unmeasured)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier
                    .defaultMinSize(minWidth = TouchTarget, minHeight = TouchTarget)
                    .testTag("library_account_copy_remove_dialog_confirm"),
            ) {
                Text(stringResource(R.string.library_account_copy_remove_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .defaultMinSize(minWidth = TouchTarget, minHeight = TouchTarget)
                    .testTag("library_account_copy_remove_dialog_cancel"),
            ) {
                Text(stringResource(R.string.library_remove_cancel))
            }
        },
    )
}

/** One button beside an import note. */
private data class ImportAction(
    val label: String,
    val description: String?,
    val tag: String,
    val onClick: () -> Unit,
)

/**
 * A sentence under a book's status line, with the backend's own code and
 * request id when there are any, an optional progress bar, and at most one
 * action.
 *
 * [progress] is [INDETERMINATE] for a wait with no measure and a fraction for
 * one with — the same two shapes `ScanBanner` already uses for the folder scan,
 * so the shelf has one visual language for "something is happening".
 */
@Composable
private fun ImportNote(
    text: String,
    tag: String,
    code: String? = null,
    requestId: String? = null,
    progress: Float? = null,
    error: Boolean = false,
    action: ImportAction? = null,
) {
    val detail = listOfNotNull(
        code?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.library_account_code, it) },
        requestId?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.library_account_request, it) },
    ).joinToString(" · ")
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag(tag)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (progress != null) {
            Spacer(Modifier.height(4.dp))
            if (progress == INDETERMINATE) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (action != null) {
            val described = action.description
            TextButton(
                onClick = action.onClick,
                modifier = Modifier
                    .defaultMinSize(minHeight = TouchTarget)
                    .testTag(action.tag)
                    .then(
                        if (described != null) {
                            Modifier.semantics { contentDescription = described }
                        } else {
                            Modifier
                        },
                    ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(action.label)
            }
        }
    }
}

/**
 * The question before a book's bytes leave this device (REQ-505).
 *
 * It says the three things a reader cannot find out afterwards: that the
 * **file itself** goes to the Reader account, how much of it that is, and that
 * it is **kept** there. It also says what the removal question says in the
 * other direction — the copy here and the reader's place in it are untouched —
 * and, last, that nothing has gone yet, because until this dialog is confirmed
 * nothing has.
 *
 * The cap under it is the policy's own number for this file's format, read on
 * this attempt (REQ-506). No constant in this app knows what it is.
 */
@Composable
private fun AddToAccountDialog(
    book: LibraryBookItem,
    consent: BookImportState.Consent,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("library_account_add_dialog"),
        title = {
            Text(
                text = stringResource(R.string.library_account_add_title, book.title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        R.string.library_account_add_body,
                        humanSize(consent.sizeBytes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.library_account_add_limit,
                        humanSize(consent.maxSourceBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier
                    .defaultMinSize(minWidth = TouchTarget, minHeight = TouchTarget)
                    .testTag("library_account_add_dialog_confirm"),
            ) {
                Text(stringResource(R.string.library_account_add_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .defaultMinSize(minWidth = TouchTarget, minHeight = TouchTarget)
                    .testTag("library_account_add_dialog_cancel"),
            ) {
                Text(stringResource(R.string.library_remove_cancel))
            }
        },
    )
}

/**
 * The refusal, in the reader's language, from the backend's own category.
 *
 * Every branch is one member of `PublicationFailureCategory` or one local
 * reason there was nothing to send. This app classifies nothing: it translates
 * a category into a sentence, and where the category is about size it quotes
 * the policy's cap rather than a number of its own (REQ-507).
 */
@Composable
private fun BookImportState.Refused.message(): String = when (val reason = problem) {
    ImportProblem.NeedsConnection -> stringResource(R.string.library_account_refused_offline)

    is ImportProblem.SourceUnavailable -> stringResource(
        when (reason.problem) {
            PublicationSourceProblem.UNREACHABLE -> R.string.library_account_refused_unreachable
            PublicationSourceProblem.SIZE_UNKNOWN -> R.string.library_account_refused_size_unknown
        },
    )

    is ImportProblem.Api -> stringResource(R.string.library_account_refused_other)

    is ImportProblem.Category -> when (reason.category) {
        PublicationFailureCategory.TOO_LARGE ->
            if (sizeBytes != null && maxSourceBytes != null) {
                stringResource(
                    R.string.library_account_refused_too_large,
                    humanSize(sizeBytes),
                    humanSize(maxSourceBytes),
                )
            } else {
                stringResource(R.string.library_account_refused_other)
            }

        PublicationFailureCategory.UNSUPPORTED -> stringResource(R.string.library_account_refused_unsupported)
        PublicationFailureCategory.PROTECTED -> stringResource(R.string.library_account_refused_protected)
        PublicationFailureCategory.UNSAFE -> stringResource(R.string.library_account_refused_unsafe)
        PublicationFailureCategory.MALFORMED -> stringResource(R.string.library_account_refused_malformed)
        PublicationFailureCategory.UPLOAD -> stringResource(R.string.library_account_refused_upload)
        PublicationFailureCategory.CONVERSION -> stringResource(R.string.library_account_refused_conversion)
        PublicationFailureCategory.CANCELLED -> stringResource(R.string.library_account_refused_cancelled)
        PublicationFailureCategory.UNKNOWN -> stringResource(R.string.library_account_refused_other)
    }
}

/**
 * A byte count as the platform writes it in the reader's own language.
 *
 * The platform's formatter rather than a hand-rolled one: it is the same "12
 * MB" a Spanish device writes as "12 MB" and a locale with another decimal
 * separator writes its own way, and getting that wrong in the one sentence
 * that says how much of the reader's data is about to move would be a poor
 * place to save a dependency.
 */
@Composable
private fun humanSize(bytes: Long): String =
    Formatter.formatShortFileSize(LocalContext.current, bytes)

/** The progress value that means "working, with no measure of how far". */
private const val INDETERMINATE = -1f

private const val PERCENT = 100

@Composable
private fun LibraryBookItem.statusLine(): String = when {
    // The account has this book and this device does not. Said plainly; what
    // to do about it is the control under this line (REQ-510).
    isAccountOnly -> stringResource(R.string.library_account_not_on_device)
    else -> deviceStatusLine()
}

@Composable
private fun LibraryBookItem.deviceStatusLine(): String = when (status) {
    // One line, two numbers, and only when the second one says something: the
    // account's place is shown beside this device's while it is ahead of it
    // (REQ-511). Which number is *the* position is not in question — the
    // device's is, until the reader answers the offer in the reader.
    BookStatus.READABLE -> accountPercentAhead
        ?.let { stringResource(R.string.library_progress_account_ahead, progressPercent, it) }
        ?: stringResource(R.string.library_progress, progressPercent)
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

/**
 * The sentence for an add that is not on offer, by the capability's typed
 * reason (#139). Each says what the reader can expect rather than naming the
 * mechanism; the reason itself is quoted as the code underneath.
 */
private fun ImportsOff.sentence(): Int = when (reason) {
    ReaderCapabilityReason.QUOTA_EXHAUSTED -> R.string.library_account_add_off_busy
    ReaderCapabilityReason.CLIENT_VERSION_INVALID,
    ReaderCapabilityReason.CLIENT_VERSION_MISSING,
    ReaderCapabilityReason.CLIENT_VERSION_TOO_NEW,
    ReaderCapabilityReason.CLIENT_VERSION_TOO_OLD,
    -> R.string.library_account_add_off_version
    ReaderCapabilityReason.ACTOR_DEPENDENCY_UNAVAILABLE -> R.string.library_account_add_off_later
    // `enabled: false`, not enabled, not configured, and a missing or
    // duplicated entry: the deployment is not taking books.
    else -> R.string.library_account_add_off
}
