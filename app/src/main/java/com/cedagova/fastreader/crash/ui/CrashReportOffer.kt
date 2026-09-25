package com.cedagova.fastreader.crash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.cedagova.fastreader.R
import com.cedagova.fastreader.ui.components.ConfirmDialog
import com.cedagova.fastreader.ui.theme.Spacing

/**
 * The offer to share the report from the last crash (REQ-207).
 *
 * Stateless, so a golden and the accessibility sweep can drive it directly. What
 * the two buttons mean is the whole of this surface:
 *
 * - **Share report** hands the text to the system share sheet. Nothing has left
 *   the device at the moment it is tapped — the reader still has to choose an
 *   app, and may back out of the sheet.
 * - **Delete report** discards it. So does dismissing the dialog with Back or a
 *   tap outside, because "declining discards it" has to hold however the reader
 *   declines; a dialog that could be tapped away and come back next launch is
 *   not offered once.
 *
 * The body says both of those in the reader's words, and the second paragraph
 * lists what the report holds and what it cannot hold, so the decision is not
 * taken on trust. That paragraph is held to what the renderer actually does by
 * `CrashOfferCopyTest`.
 */
@Composable
fun CrashReportOffer(
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /** Set when no app on this device can accept the report as text. */
    shareUnavailable: Boolean = false,
) {
    ConfirmDialog(
        title = stringResource(R.string.crash_offer_title),
        confirmLabel = stringResource(R.string.crash_offer_share),
        dismissLabel = stringResource(R.string.crash_offer_delete),
        onConfirm = onShare,
        onDismiss = onDelete,
        tag = "crash_offer",
        modifier = modifier,
        confirmTag = "crash_offer_share",
        dismissTag = "crash_offer_delete",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
            Text(
                text = stringResource(R.string.crash_offer_body),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.crash_offer_contents),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (shareUnavailable) {
                Text(
                    text = stringResource(R.string.crash_offer_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("crash_offer_unavailable"),
                )
            }
        }
    }
}
