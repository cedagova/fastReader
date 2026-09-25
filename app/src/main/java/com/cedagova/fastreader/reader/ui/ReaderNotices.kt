package com.cedagova.fastreader.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cedagova.fastreader.R
import com.cedagova.fastreader.reader.ResumeOffer
import com.cedagova.fastreader.ui.components.Banner
import com.cedagova.fastreader.ui.components.BannerTone
import com.cedagova.fastreader.ui.theme.Sizes
import com.cedagova.fastreader.ui.theme.Spacing

/**
 * The book was opened from another app and is not in the library (REQ-103).
 *
 * One line and two buttons, in the register of every other explanation in the
 * app: it says the one thing that is kept — the reading position — because that
 * is the whole of what the privacy statement promises for this path (REQ-107),
 * and it offers the way to keep the book itself.
 *
 * Stacked rather than a single row: at the largest font scale on a 720p phone a
 * sentence and two labels side by side either clip or squeeze the sentence into a
 * column of single words. The buttons keep 48 dp of height at every scale
 * (REQ-301), and both carry their own label for TalkBack — the sentence above
 * them is read as ordinary text, so neither button has to repeat it.
 *
 * A banner and not a dialog, for the same reason as the persistence
 * problem banner: nothing here should stop someone reading. It sits
 * above the reading surface rather than inside it, so the stream keeps its fixed
 * size and static background (REQ-062, REQ-302, AD-6), and it goes with the rest
 * of the chrome in focused mode.
 */
@Composable
internal fun ExternalOpenNotice(onAddToLibrary: () -> Unit, onDismiss: () -> Unit) {
    Banner(tone = BannerTone.Notice, modifier = Modifier.testTag("reader_external_notice")) {
        Column(modifier = Modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Small)) {
            Text(
                text = stringResource(R.string.reader_external_notice),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                TextButton(
                    onClick = onAddToLibrary,
                    modifier = Modifier
                        .defaultMinSize(minHeight = Sizes.TouchTarget)
                        .testTag("reader_external_add"),
                ) {
                    Text(text = stringResource(R.string.reader_external_add))
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .defaultMinSize(minHeight = Sizes.TouchTarget)
                        .testTag("reader_external_dismiss"),
                ) {
                    Text(text = stringResource(R.string.reader_external_dismiss))
                }
            }
        }
    }
}

/**
 * REQ-202: the one-time offer to start past a book's cover and title pages.
 *
 * A banner in the same slot and the same shape as [ExternalOpenNotice], and for
 * the same reasons: it must not stop anyone reading, it must not sit inside the
 * reading surface where it would change the stream's fixed size or static
 * background (REQ-062, REQ-302, AD-6), and it goes with the chrome in focused
 * mode.
 *
 * Both buttons answer the question, which is why the second one says what it
 * does rather than "Dismiss": staying on the cover is a choice about where to
 * start reading, not the closing of a message. Either way the offer is recorded
 * as made and this book never shows it again.
 *
 * ## Accessibility (REQ-301)
 *
 * The skip button names the chapter it goes to, so a reader who cannot see the
 * sentence above it still learns where the tap lands. That label is as long as
 * the book's chapter title, which is why the buttons sit in a [FlowRow]: on a
 * 360 dp screen at a large font scale a plain `Row` gives the second button no
 * width at all, wraps its label one character to a line, and pushes the way to
 * decline off the screen — the compact golden beside this one was recorded
 * against exactly that failure. Both buttons clear [Sizes.TouchTarget].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FrontMatterOfferNotice(chapterTitle: String, onSkip: () -> Unit, onDismiss: () -> Unit) {
    Banner(tone = BannerTone.Notice, modifier = Modifier.testTag("reader_front_matter_offer")) {
        Column(modifier = Modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Small)) {
            Text(
                text = stringResource(R.string.reader_front_matter_notice),
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier
                        .defaultMinSize(minHeight = Sizes.TouchTarget)
                        .testTag("reader_front_matter_skip"),
                ) {
                    Text(text = stringResource(R.string.reader_front_matter_skip, chapterTitle))
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .defaultMinSize(minHeight = Sizes.TouchTarget)
                        .testTag("reader_front_matter_stay"),
                ) {
                    Text(text = stringResource(R.string.reader_front_matter_stay))
                }
            }
        }
    }
}

/**
 * REQ-511: the offer to pick up where another device left off.
 *
 * The third banner in this slot, drawn in the same shape as
 * [FrontMatterOfferNotice] and [ExternalOpenNotice] for the same three reasons:
 * it must not stop anyone reading, it must not sit inside the reading surface
 * where it would change the stream's fixed size or static background (REQ-062,
 * REQ-302, AD-6), and it goes with the chrome in focused mode. The stream keeps
 * running underneath it — this is a question, not a modal.
 *
 * ## Two sentences, one condition
 *
 * When the other client named a section this parse has, the offer names that
 * chapter and the percent. When it did not — a different edition, a renamed
 * spine — the offer names the percent alone, because the tap lands by fraction
 * and naming a chapter it will not land in would be worse than naming none.
 * That is the whole of `chapterTitle == null`.
 *
 * ## Accessibility (REQ-301)
 *
 * The accepting button names the destination rather than only the verb, for the
 * reason the front-matter offer's does: "Resume" alone leaves a reader using
 * TalkBack no way to know where the tap goes. That makes its label as long as a
 * chapter title, so the buttons sit in a [FlowRow] — on a 360 dp screen at a
 * large font scale a plain `Row` gives the second button no width at all and
 * pushes the way to decline off the screen. Both clear [Sizes.TouchTarget].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ResumeOfferNotice(offer: ResumeOffer, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Banner(tone = BannerTone.Notice, modifier = Modifier.testTag("reader_resume_offer")) {
        Column(modifier = Modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Small)) {
            Text(
                text = offer.chapterTitle
                    ?.let { stringResource(R.string.reader_resume_offer, it, offer.percent) }
                    ?: stringResource(R.string.reader_resume_offer_percent_only, offer.percent),
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                TextButton(
                    onClick = onAccept,
                    modifier = Modifier
                        .defaultMinSize(minHeight = Sizes.TouchTarget)
                        .testTag("reader_resume_accept"),
                ) {
                    Text(
                        text = offer.chapterTitle
                            ?.let { stringResource(R.string.reader_resume_accept, it) }
                            ?: stringResource(R.string.reader_resume_accept_percent, offer.percent),
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .defaultMinSize(minHeight = Sizes.TouchTarget)
                        .testTag("reader_resume_stay"),
                ) {
                    Text(text = stringResource(R.string.reader_resume_stay))
                }
            }
        }
    }
}
