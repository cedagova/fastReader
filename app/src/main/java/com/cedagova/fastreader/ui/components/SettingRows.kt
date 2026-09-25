package com.cedagova.fastreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import com.cedagova.fastreader.R
import com.cedagova.fastreader.ui.theme.Sizes
import com.cedagova.fastreader.ui.theme.Spacing

/*
 * The rows a settings-style page is built from: a section heading, a bounded
 * choice, and an on/off setting as a switch or a checkbox (A197-F006).
 *
 * They share one accessibility convention, and it is why they live here rather
 * than on each screen: the row owns the action and the one description TalkBack
 * reads, and the drawn text under it is cleared from the tree, so every row is
 * announced once, in a fixed order, and names its own state.
 * `SettingsAccessibilityTest` and `ReaderAccountAccessibilityTest` hold the
 * screens that use them to it.
 */

/** The start of a group of settings; a heading for TalkBack's heading navigation. */
@Composable
fun SectionHeading(text: String) {
    Spacer(Modifier.height(Spacing.XLarge))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(Spacing.XSmall))
}

/**
 * One bounded choice, as a wrapping row of radio-style chips.
 *
 * [FlowRow] rather than a fixed row because at the largest text size four labels
 * do not fit across a 360 dp phone; wrapping keeps every option reachable instead
 * of clipping the last one. The group carries [selectableGroup] so TalkBack treats
 * the chips as one setting with n options rather than as n unrelated buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    tag: String,
    summary: String? = null,
) {
    Spacer(Modifier.height(Spacing.Medium))
    Text(text = label, style = MaterialTheme.typography.bodyLarge)
    if (summary != null) {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(Spacing.XSmall))
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup().testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
    ) {
        options.forEach { option ->
            OptionChip(
                text = optionLabel(option),
                selected = option == selected,
                onClick = { onSelect(option) },
            )
        }
    }
}

/**
 * One option of a bounded choice.
 *
 * Built from a `Box` rather than a Material `FilterChip` for two reasons that both
 * matter here: a chip's default height is below [Sizes.TouchTarget], and its
 * internal semantics role is the library's to choose, whereas these options are
 * radio buttons inside a [selectableGroup] and must announce themselves as such.
 *
 * The label is declared on the selectable node and cleared from the `Text` beneath
 * it, so exactly one description reaches the accessibility tree per option rather
 * than a drawn label that happens to be readable.
 */
@Composable
fun OptionChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .heightIn(min = Sizes.TouchTarget)
            .clip(RoundedCornerShape(percent = 50))
            .background(container)
            .semantics { contentDescription = text }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.Large, vertical = Spacing.Small),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * One on/off setting.
 *
 * The whole row toggles, and [toggleable] sits on the row rather than on the
 * switch so the touch target is the row's full width and TalkBack focuses one node
 * that names the setting and its state — not a label it cannot act on next to a
 * switch that does not say what it controls.
 *
 * The name *and* the summary go into that node's description, and both `Text`s are
 * cleared from the tree, so the row is announced once and in a fixed order. The
 * summary is the only place a setting's effect is explained, so it belongs in what
 * a reader who cannot see it hears, and it should not arrive as a second focus
 * stop after the name.
 */
@Composable
fun SwitchRow(label: String, summary: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, tag: String) {
    val state = stringResource(if (checked) R.string.settings_on else R.string.settings_off)
    val description = "$label. $summary"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.TouchTarget)
            .semantics {
                contentDescription = description
                stateDescription = state
            }
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = Spacing.Small)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).clearAndSetSemantics {}) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Spacing.Medium))
        // The row owns the action and the announcement; the switch is the picture
        // of the state, so it is taken out of the accessibility tree entirely.
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * A yes/no choice that belongs to a form rather than to the app's settings — the
 * sign-up choice on the account screen. The whole row toggles and is announced
 * once with its state, the same treatment [SwitchRow] gets.
 */
@Composable
fun CheckboxRow(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit, tag: String) {
    val state = stringResource(if (checked) R.string.settings_on else R.string.settings_off)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.TouchTarget)
            .semantics {
                contentDescription = label
                stateDescription = state
            }
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Spacer(Modifier.width(Spacing.Small))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clearAndSetSemantics {})
    }
}
