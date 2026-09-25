package com.cedagova.fastreader.library.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.cedagova.fastreader.R
import com.cedagova.fastreader.ui.theme.Sizes
import com.cedagova.fastreader.ui.theme.Spacing
import com.cedagova.reader.account.library.BookDownloadState
import com.cedagova.reader.account.library.BookImportState
import com.cedagova.reader.account.library.DownloadProblem
import com.cedagova.reader.account.library.ImportProblem
import com.cedagova.reader.account.library.ImportsOff
import com.cedagova.reader.account.library.PublicationSourceProblem
import com.cedagova.reader.library.model.PublicationFailureCategory
import com.cedagova.reader.library.model.ReaderCapabilityReason
import com.cedagova.reader.library.sync.wireName
import kotlin.math.roundToInt

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
internal fun AddToAccountSlot(
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
                    .defaultMinSize(minHeight = Sizes.TouchTarget)
                    .testTag("library_account_add_${book.id}")
                    .semantics { contentDescription = label },
                contentPadding = PaddingValues(horizontal = Spacing.Medium, vertical = Spacing.Small),
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
internal fun AccountDownloadSlot(
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
                    .defaultMinSize(minHeight = Sizes.TouchTarget)
                    .testTag("library_account_download_${book.id}")
                    .semantics { contentDescription = label },
                contentPadding = PaddingValues(horizontal = Spacing.Medium, vertical = Spacing.Small),
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

/** One button beside an import note. */
private data class ImportAction(val label: String, val description: String?, val tag: String, val onClick: () -> Unit)

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
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.XSmall).testTag(tag)) {
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
                modifier = Modifier.padding(top = Spacing.XXSmall),
            )
        }
        if (progress != null) {
            Spacer(Modifier.height(Spacing.XSmall))
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
                    .defaultMinSize(minHeight = Sizes.TouchTarget)
                    .testTag(action.tag)
                    .then(
                        if (described != null) {
                            Modifier.semantics { contentDescription = described }
                        } else {
                            Modifier
                        },
                    ),
                contentPadding = PaddingValues(horizontal = Spacing.Medium, vertical = Spacing.Small),
            ) {
                Text(action.label)
            }
        }
    }
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
        PublicationFailureCategory.TOO_LARGE -> {
            // Locals: the state is :reader-account's (#200), so its properties
            // do not smart-cast across the module boundary.
            val size = sizeBytes
            val cap = maxSourceBytes
            if (size != null && cap != null) {
                stringResource(
                    R.string.library_account_refused_too_large,
                    humanSize(size),
                    humanSize(cap),
                )
            } else {
                stringResource(R.string.library_account_refused_other)
            }
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
internal fun humanSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** The progress value that means "working, with no measure of how far". */
private const val INDETERMINATE = -1f

private const val PERCENT = 100

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
