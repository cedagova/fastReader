package com.cedagova.fastreader.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.cedagova.fastreader.ui.theme.Spacing

/** Which of the app's three banner colourings a [Banner] takes. */
enum class BannerTone {
    /** Something is wrong and the reader should know: the store refusing writes. */
    Problem,

    /** A sentence for the reader to read and usually answer: an offer, a notice. */
    Notice,

    /** Work under way that the reader is waiting on: a folder scan. */
    Progress,
}

/**
 * A full-width strip across the top of a screen's content, in one of the
 * [BannerTone] colourings.
 *
 * Every banner in the app is one of these, so a screen chooses what the banner
 * *says* and never how it is coloured. A banner is never a dialog: nothing on it
 * stops the reader doing what they came to do.
 */
@Composable
fun Banner(tone: BannerTone, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = when (tone) {
            BannerTone.Problem -> colors.errorContainer
            BannerTone.Notice -> colors.secondaryContainer
            BannerTone.Progress -> colors.surfaceVariant
        },
        contentColor = when (tone) {
            BannerTone.Problem -> colors.onErrorContainer
            BannerTone.Notice -> colors.onSecondaryContainer
            BannerTone.Progress -> colors.onSurfaceVariant
        },
        modifier = Modifier.fillMaxWidth().then(modifier),
        content = content,
    )
}

/**
 * The store is refusing writes, so what the reader just did is not being kept.
 *
 * One banner for the library, the reader and settings (A197-F006): a title that
 * names the problem and the store's own sentence under it. A banner and never a
 * dialog, and never silence — nothing here should stop someone reading, and a
 * reader who is losing their place must be told. The title is semi-bold on every
 * screen (plan AD-6); the library's copy had drifted to regular weight.
 */
@Composable
fun ProblemBanner(title: String, message: String, modifier: Modifier = Modifier) {
    Banner(tone = BannerTone.Problem, modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Medium)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
