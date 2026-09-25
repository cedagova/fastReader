package com.cedagova.fastreader.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.cedagova.fastreader.R
import com.cedagova.fastreader.ui.components.ConfirmDialog
import com.cedagova.fastreader.ui.components.UndoBar
import com.cedagova.fastreader.ui.theme.Spacing
import com.cedagova.reader.account.library.BookImportState

/*
 * The account half of the library screen (A197-F006): the shelf's account
 * notice, the account undo bar, and the three questions the account rows can
 * ask. The row slots they belong to are in `LibraryAccountSlots.kt`; the screen
 * itself only decides when each is shown.
 */

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
internal fun AccountNoticeBanner(notice: AccountNotice, onDismiss: () -> Unit) {
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
    DismissibleNotice(
        title = stringResource(R.string.library_account_notice_title),
        onDismiss = onDismiss.takeIf { notice.dismissible },
        dismissTag = "library_account_notice_dismiss",
        modifier = Modifier
            .testTag("library_account_notice")
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
        queued?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = Spacing.XSmall),
            )
        }
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = Spacing.XSmall),
            )
        }
    }
}

/**
 * The account removal the reader can still take back (REQ-508).
 *
 * The same bar as the library's own undo, and deliberately a different
 * sentence: this removal reaches every device the account is signed in on, and
 * it left this device's file and this device's place in the book alone. That
 * distinction is the whole reason the two removals are separate controls.
 */
@Composable
internal fun AccountUndoBar(notice: AccountUndoNotice, onUndo: () -> Unit) {
    UndoBar(
        message = stringResource(R.string.library_account_undo_removed, notice.title),
        actionLabel = stringResource(R.string.library_undo),
        actionDescription = stringResource(R.string.library_account_undo_label, notice.title),
        onUndo = onUndo,
        tag = "library_account_undo",
    )
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
internal fun RemoveFromAccountDialog(book: LibraryBookItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.library_account_remove_title, book.title),
        confirmLabel = stringResource(R.string.library_account_remove_confirm),
        dismissLabel = stringResource(R.string.library_remove_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        tag = "library_account_remove_dialog",
    ) {
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
    }
}

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
internal fun RemoveAccountCopyDialog(
    book: LibraryBookItem,
    copy: AccountCopyRow,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.library_account_copy_remove_title, book.title),
        confirmLabel = stringResource(R.string.library_account_copy_remove_confirm),
        dismissLabel = stringResource(R.string.library_remove_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        tag = "library_account_copy_remove_dialog",
    ) {
        Text(
            text = if (copy.sizeBytes > 0) {
                stringResource(R.string.library_account_copy_remove_body, humanSize(copy.sizeBytes))
            } else {
                stringResource(R.string.library_account_copy_remove_body_unmeasured)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
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
internal fun AddToAccountDialog(
    book: LibraryBookItem,
    consent: BookImportState.Consent,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.library_account_add_title, book.title),
        confirmLabel = stringResource(R.string.library_account_add_confirm),
        dismissLabel = stringResource(R.string.library_remove_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        tag = "library_account_add_dialog",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
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
    }
}
