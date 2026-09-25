package com.cedagova.fastreader.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.cedagova.fastreader.R
import com.cedagova.fastreader.settings.AppVersion
import com.cedagova.fastreader.ui.components.SectionHeading
import com.cedagova.fastreader.ui.theme.Sizes
import com.cedagova.fastreader.ui.theme.Spacing
import com.cedagova.reader.account.ReaderAccountSummary

/**
 * The settings screen's last section (#46, #100): not a set of choices but what
 * this build is, where updates come from, the way into the Reader account, and
 * what stays on the device.
 */
@Composable
internal fun AboutSection(
    version: AppVersion,
    onCheckForUpdates: () -> Unit,
    updateHandoffUnavailable: Boolean,
    readerAccount: ReaderAccountSummary,
    onOpenReaderAccount: () -> Unit,
) {
    SectionHeading(stringResource(R.string.settings_section_about))
    VersionRow(version)
    CheckForUpdatesRow(
        onCheckForUpdates = onCheckForUpdates,
        handoffUnavailable = updateHandoffUnavailable,
    )
    ReaderAccountRow(summary = readerAccount, onOpen = onOpenReaderAccount)
    PrivacyStatement()
    VisualOnlyStatement()
}

/**
 * REQ-106's first half: which FastReader this is.
 *
 * The value comes from the installed package (see [AppVersion]), so the row is a
 * statement about the artifact on the device rather than about the sources it was
 * built from. Name and build number are announced as one node — "Version 1.0.1,
 * build 2" — because a screen reader stopping separately on the word "Version"
 * and on "1.0.1 (build 2)" tells a reader less than the sentence does.
 */
@Composable
private fun VersionRow(version: AppVersion) {
    val description =
        stringResource(R.string.settings_version_description, version.name, version.code)
    Spacer(Modifier.height(Spacing.XSmall))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.TouchTarget)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("settings_version"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_version_label),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        // Both halves are weighted, so the widest text this app can be asked for
        // wraps the value instead of taking the whole row and squeezing the
        // label out of existence (REQ-301, and the golden at 360 dp / scale 2).
        Text(
            text = stringResource(R.string.settings_version_value, version.name, version.code),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * REQ-106's second half: a hand-off, and copy that says so before it happens.
 *
 * FastReader never checks for updates itself; the action opens the releases
 * page in the reader's browser. The summary says that
 * in the button's own announcement rather than only next to it, so a reader using
 * TalkBack learns where the tap goes before taking it — the same treatment the cue
 * switches give their summaries.
 *
 * When nothing on the device can open a web link the tap fails loud: the address
 * is shown so it is still usable, rather than the app swallowing the failure or
 * crashing on the uncaught `ActivityNotFoundException`.
 */
@Composable
private fun CheckForUpdatesRow(onCheckForUpdates: () -> Unit, handoffUnavailable: Boolean) {
    val label = stringResource(R.string.settings_check_updates)
    val summary = stringResource(R.string.settings_check_updates_summary)
    Spacer(Modifier.height(Spacing.Medium))
    Text(
        text = summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clearAndSetSemantics {},
    )
    Spacer(Modifier.height(Spacing.Small))
    OutlinedButton(
        onClick = onCheckForUpdates,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.TouchTarget)
            .semantics { contentDescription = "$label. $summary" }
            .testTag("settings_check_updates"),
    ) {
        Text(text = label, modifier = Modifier.clearAndSetSemantics {})
    }
    if (handoffUnavailable) {
        Spacer(Modifier.height(Spacing.Small))
        Text(
            text = stringResource(R.string.settings_check_updates_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().testTag("settings_check_updates_unavailable"),
        )
    }
}

/**
 * The way into the Reader account surface (#100, REQ-401), one tap from here.
 *
 * The row states the account's condition as a value — not configured, signed
 * out, or signed in as whom — so a reader knows before tapping whether there
 * is anything to sign out of, and the summary says what the account is for
 * and that reading needs none. One node for TalkBack: the label, the state
 * and the summary in that order, and the chevron is only a picture.
 */
@Composable
private fun ReaderAccountRow(summary: ReaderAccountSummary, onOpen: () -> Unit) {
    val label = stringResource(R.string.settings_reader_account)
    val value = when (summary) {
        ReaderAccountSummary.Loading -> stringResource(R.string.settings_reader_account_loading)
        ReaderAccountSummary.NotConfigured -> stringResource(R.string.settings_reader_account_not_configured)
        ReaderAccountSummary.SignedOut -> stringResource(R.string.settings_reader_account_signed_out)
        is ReaderAccountSummary.SignedIn -> stringResource(R.string.settings_reader_account_signed_in, summary.email)
    }
    val explanation = stringResource(R.string.settings_reader_account_summary)
    Spacer(Modifier.height(Spacing.Medium))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.TouchTarget)
            .semantics(mergeDescendants = true) { contentDescription = "$label. $value. $explanation" }
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(vertical = Spacing.Small)
            .testTag("settings_reader_account"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).clearAndSetSemantics {}) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings_reader_account_value"),
            )
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Spacing.Medium))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * REQ-107: what leaves this device and what is kept on it, in sentences that
 * each map to a manifest declaration or to something the app is observed doing.
 *
 * It sits in About next to the version and the update hand-off because those are
 * the two things it is about — there is no separate privacy screen to bury it in,
 * and the same wording is the release notes' privacy paragraph
 * (`docs/privacy-statement.md`, which `PrivacyStatementTest` holds to this
 * string).
 */
@Composable
private fun PrivacyStatement() {
    Spacer(Modifier.height(Spacing.XLarge))
    Text(
        text = stringResource(R.string.settings_privacy_label),
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(Spacing.XSmall))
    Text(
        text = stringResource(R.string.settings_privacy),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("settings_privacy"),
    )
    Spacer(Modifier.height(Spacing.XLarge))
}

/**
 * REQ-061: the word stream is visual-only, said plainly and where a reader will
 * meet it, rather than left to be discovered.
 *
 * It is a paragraph of ordinary text in the About section — not a dismissible
 * notice and not a footnote — because the requirement is that the limitation is
 * "stated plainly in-app … not hidden". It also says what *does* work with a
 * screen reader, so it reads as a boundary of the design rather than an apology.
 */
@Composable
private fun VisualOnlyStatement() {
    Text(
        text = stringResource(R.string.settings_visual_only),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("settings_visual_only"),
    )
}
