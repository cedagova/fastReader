package com.cedagova.fastreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cedagova.fastreader.R
import com.cedagova.fastreader.content.BundledSample

/** Android's accessibility minimum for an interactive control (REQ-301/REQ-060). */
private val TouchTarget = 48.dp

/**
 * The offer to read one of the texts shipped inside the app (REQ-109).
 *
 * One component, two places, because the copy is the same promise in both: the
 * empty library — where it is the only thing a stranger can read — and the
 * settings screen, where it stays reachable once they have books of their own.
 * Whether to show it is the caller's decision; what it says is not.
 *
 * It carries no heading of its own so each surface can use its own (the library's
 * page heading, the settings screen's section heading) while both draw the words
 * from `R.string.sample_title`.
 *
 * ## Accessibility (REQ-301)
 *
 * The buttons are labelled with each language's own name for itself, so the label
 * is right whatever the interface language is. That label is two words at most,
 * which is too little for a screen reader, so each button's spoken description
 * names the language *and* the text ("Read the sample in Español: Una palabra a
 * la vez"). Every button clears [TouchTarget], and they are laid out in a
 * [FlowRow] so they wrap instead of clipping at the largest font scale.
 *
 * ## Static luminance (REQ-302)
 *
 * Ordinary buttons and ordinary text. Nothing animates, flashes or pulses here,
 * and the sample itself is plain prose for the same reason.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SampleOffer(
    onOpenSample: (BundledSample) -> Unit,
    modifier: Modifier = Modifier,
    samples: List<BundledSample> = rememberSampleOrder(),
) {
    Column(
        modifier = modifier.fillMaxWidth().testTag("sample_offer"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.sample_intro),
            style = MaterialTheme.typography.bodyLarge,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            samples.forEach { sample ->
                val description = stringResource(
                    R.string.sample_open_description,
                    sample.endonym,
                    sample.title,
                )
                OutlinedButton(
                    onClick = { onOpenSample(sample) },
                    modifier = Modifier
                        .defaultMinSize(minHeight = TouchTarget)
                        .semantics { contentDescription = description }
                        .testTag("sample_open_${sample.languageTag}"),
                ) {
                    // The visible label is already inside the description above;
                    // left as its own node TalkBack would read the language twice.
                    Text(text = sample.endonym, modifier = Modifier.clearAndSetSemantics {})
                }
            }
        }
        Text(
            text = stringResource(R.string.sample_source),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The samples in the order this device should be offered them: a sample in the
 * device's own language first (REQ-109).
 *
 * Read from the composition's configuration rather than [java.util.Locale]'s
 * process default, so it follows the same value the rest of the resource system
 * follows — which is also what lets a Roborazzi render prove the Spanish
 * ordering by declaring an `es` qualifier rather than mutating global state.
 */
@Composable
fun rememberSampleOrder(): List<BundledSample> {
    val language = LocalConfiguration.current.locales[0].language
    return remember(language) { BundledSample.offeredFor(language) }
}
