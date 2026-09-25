package com.cedagova.fastreader.library.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.cedagova.fastreader.R
import com.cedagova.fastreader.library.ResumeBlockedReason
import com.cedagova.fastreader.library.ScanTrigger
import com.cedagova.fastreader.ui.components.Banner
import com.cedagova.fastreader.ui.components.BannerTone
import com.cedagova.fastreader.ui.theme.Sizes
import com.cedagova.fastreader.ui.theme.Spacing

/**
 * Why the app opened here instead of in the book being read (REQ-009).
 *
 * The book's own row already carries its state, but a reader who expected to
 * land back in their book should not have to find the row and infer what
 * happened, so the reason is said once at the top and dismissed when read.
 */
@Composable
internal fun ResumeNoticeBanner(notice: ResumeNotice, onDismiss: () -> Unit) {
    val title = notice.title ?: stringResource(R.string.library_resume_blocked_unnamed)
    DismissibleNotice(
        title = stringResource(R.string.library_resume_blocked_title, title),
        onDismiss = onDismiss,
        dismissTag = "library_resume_notice_dismiss",
        modifier = Modifier.testTag("library_resume_notice"),
    ) {
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
}

/**
 * The shelf's notice shape: a semi-bold title, the sentences under it, and a
 * button that puts the notice away — or, for a notice that stays until its
 * state does ([onDismiss] null), the same room left empty so the text keeps its
 * measure. The resume notice and the account notice are both this.
 */
@Composable
internal fun DismissibleNotice(
    title: String,
    onDismiss: (() -> Unit)?,
    dismissTag: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Banner(tone = BannerTone.Notice, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = Spacing.Large, top = Spacing.Medium, bottom = Spacing.Medium),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                content()
            }
            if (onDismiss != null) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .defaultMinSize(minWidth = Sizes.TouchTarget, minHeight = Sizes.TouchTarget)
                        .testTag(dismissTag),
                ) {
                    Text(stringResource(R.string.library_resume_blocked_dismiss))
                }
            } else {
                Spacer(Modifier.width(Spacing.Large))
            }
        }
    }
}

/** The folder scan under way: what started it, how far it is, and which file it is on. */
@Composable
internal fun ScanBanner(scan: LibraryScan) {
    val label = stringResource(
        when (scan.trigger) {
            ScanTrigger.APP_OPEN -> R.string.library_scan_app_open
            ScanTrigger.MANUAL_REFRESH -> R.string.library_scan_manual_refresh
            ScanTrigger.ADD_BOOKS -> R.string.library_scan_add_books
            ScanTrigger.ADD_FOLDER -> R.string.library_scan_add_folder
        },
    )
    Banner(tone = BannerTone.Progress, modifier = Modifier.testTag("library_scanning")) {
        Column(modifier = Modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Medium)) {
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
                    modifier = Modifier.padding(top = Spacing.XSmall),
                )
            }
            val fraction = scan.fraction
            Spacer(Modifier.height(Spacing.Small))
            if (fraction == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
