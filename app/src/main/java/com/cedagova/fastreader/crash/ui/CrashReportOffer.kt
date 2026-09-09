package com.cedagova.fastreader.crash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cedagova.fastreader.R

/** Android's accessibility minimum for an interactive control (REQ-060, REQ-301). */
private val TouchTarget = 48.dp

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
    AlertDialog(
        onDismissRequest = onDelete,
        modifier = modifier.testTag("crash_offer"),
        title = {
            Text(
                text = stringResource(R.string.crash_offer_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        },
        confirmButton = {
            TextButton(
                onClick = onShare,
                modifier = Modifier
                    .defaultMinSize(minWidth = TouchTarget, minHeight = TouchTarget)
                    .testTag("crash_offer_share"),
            ) {
                Text(stringResource(R.string.crash_offer_share))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDelete,
                modifier = Modifier
                    .defaultMinSize(minWidth = TouchTarget, minHeight = TouchTarget)
                    .testTag("crash_offer_delete"),
            ) {
                Text(stringResource(R.string.crash_offer_delete))
            }
        },
    )
}
