package com.cedagova.fastreader.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cedagova.fastreader.R
import com.cedagova.fastreader.reader.ui.resolve
import com.cedagova.fastreader.settings.FontSize
import com.cedagova.fastreader.settings.PivotColor
import com.cedagova.fastreader.settings.ReaderSettings
import com.cedagova.fastreader.settings.ThemeChoice
import com.cedagova.fastreader.timing.PauseStrength

/** Android's accessibility minimum for an interactive control (REQ-060). */
private val TouchTarget = 48.dp

/**
 * The settings surface (LEAF302): the bounded set of choices the definition
 * allows, each one shown working in the live preview above it.
 *
 * Stateless like the library and reader screens — every state it can show is
 * reachable from a [ReaderSettings] value, which is what lets the Roborazzi
 * goldens be the regression gate for it. [SettingsRoute] supplies the stored
 * value and writes changes back.
 *
 * ## Bounded, exactly as defined
 *
 * Five groups and nothing else: theme (REQ-022), text size (REQ-022), the pivot
 * cue and its palette (REQ-020), guide marks (REQ-021), and pause strength
 * (REQ-011). There is no free-form colour picker, no point-size field, and no
 * per-multiplier timing panel. Every control is a choice from a small fixed set,
 * which is also why they are all radio-style chips or switches rather than
 * sliders.
 *
 * ## REQ-060
 *
 * The choice rows are [selectableGroup]s, so TalkBack announces "2 of 4" and
 * swipes between the options of one setting instead of walking a flat list of
 * chips. Every control clears [TouchTarget], the chip rows wrap rather than clip
 * when the text is large, and the whole page scrolls, so nothing is unreachable
 * at the largest font size on the smallest screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Why a change did not stick, or null when the store is accepting writes. */
    persistenceFailure: String? = null,
    /** Holds the preview on one token so a golden captures a deterministic frame. */
    heldPreviewToken: Int? = null,
) {
    Scaffold(
        modifier = modifier.fillMaxSize().testTag("settings_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    val back = stringResource(R.string.settings_back)
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(TouchTarget)
                            .semantics { contentDescription = back }
                            .testTag("settings_back"),
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            persistenceFailure?.let { PersistenceFailureBanner(it) }

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(8.dp))
                SettingsPreview(
                    cues = settings.cues,
                    pauseStrength = settings.pauseStrength,
                    heldTokenIndex = heldPreviewToken,
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()

                SectionHeading(stringResource(R.string.settings_section_appearance))
                ChoiceRow(
                    label = stringResource(R.string.settings_theme),
                    options = ThemeChoice.entries,
                    selected = settings.theme,
                    optionLabel = { stringResource(it.labelRes()) },
                    onSelect = { onSettingsChange(settings.copy(theme = it)) },
                    tag = "settings_theme",
                )
                ChoiceRow(
                    label = stringResource(R.string.settings_font_size),
                    options = FontSize.entries,
                    selected = settings.fontSize,
                    optionLabel = { stringResource(it.labelRes()) },
                    onSelect = { onSettingsChange(settings.copy(fontSize = it)) },
                    tag = "settings_font_size",
                )

                SectionHeading(stringResource(R.string.settings_section_cues))
                SwitchRow(
                    label = stringResource(R.string.settings_pivot),
                    summary = stringResource(R.string.settings_pivot_summary),
                    checked = settings.pivotEnabled,
                    onCheckedChange = { onSettingsChange(settings.copy(pivotEnabled = it)) },
                    tag = "settings_pivot",
                )
                // The palette only means anything while the letter it colours is
                // being drawn, so it goes with the cue rather than staying on
                // screen as a control that does nothing (REQ-020).
                if (settings.pivotEnabled) {
                    PivotColorRow(
                        selected = settings.pivotColor,
                        onSelect = { onSettingsChange(settings.copy(pivotColor = it)) },
                    )
                }
                SwitchRow(
                    label = stringResource(R.string.settings_guide_marks),
                    summary = stringResource(R.string.settings_guide_marks_summary),
                    checked = settings.guideMarksEnabled,
                    onCheckedChange = { onSettingsChange(settings.copy(guideMarksEnabled = it)) },
                    tag = "settings_guide_marks",
                )

                SectionHeading(stringResource(R.string.settings_section_rhythm))
                ChoiceRow(
                    label = stringResource(R.string.settings_pause_strength),
                    summary = stringResource(R.string.settings_pause_summary),
                    options = PauseStrength.entries,
                    selected = settings.pauseStrength,
                    optionLabel = { stringResource(it.labelRes()) },
                    onSelect = { onSettingsChange(settings.copy(pauseStrength = it)) },
                    tag = "settings_pause_strength",
                )

                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onReset,
                    enabled = !settings.isDefault,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = TouchTarget)
                        .testTag("settings_reset"),
                ) {
                    Text(stringResource(R.string.settings_reset))
                }

                SectionHeading(stringResource(R.string.settings_section_about))
                VisualOnlyStatement()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
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

/**
 * The store is refusing writes, so a change the reader just made is not being
 * kept. Same treatment as the library's and the reader's: a banner, never a
 * dialog, and never silence.
 */
@Composable
private fun PersistenceFailureBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().testTag("settings_problem"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.settings_problem_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(4.dp))
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
private fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    tag: String,
    summary: String? = null,
) {
    Spacer(Modifier.height(12.dp))
    Text(text = label, style = MaterialTheme.typography.bodyLarge)
    if (summary != null) {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(4.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup().testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
 * matter here: a chip's default height is below [TouchTarget], and its internal
 * semantics role is the library's to choose, whereas these options are radio
 * buttons inside a [selectableGroup] and must announce themselves as such.
 */
@Composable
private fun OptionChip(text: String, selected: Boolean, onClick: () -> Unit) {
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
            .heightIn(min = TouchTarget)
            .clip(RoundedCornerShape(percent = 50))
            .background(container)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/**
 * The bounded pivot palette (REQ-020), as five swatches drawn in the colours they
 * actually produce on the page in front of the reader.
 *
 * A name alone would not do: each entry resolves differently in the light and dark
 * themes, so the swatch is the honest label. The name still travels to TalkBack,
 * which cannot see a circle.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PivotColorRow(selected: PivotColor, onSelect: (PivotColor) -> Unit) {
    Spacer(Modifier.height(12.dp))
    Text(text = stringResource(R.string.settings_pivot_color), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(4.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup().testTag("settings_pivot_color"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PivotColor.entries.forEach { entry ->
            val name = stringResource(entry.labelRes())
            val isSelected = entry == selected
            val ring = if (isSelected) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
            Box(
                modifier = Modifier
                    .size(TouchTarget)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(entry) },
                    )
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(if (isSelected) 28.dp else 24.dp)
                        .clip(CircleShape)
                        .background(entry.resolve())
                        .border(width = if (isSelected) 3.dp else 1.dp, color = ring, shape = CircleShape),
                )
            }
        }
    }
}

/**
 * One on/off setting.
 *
 * The whole row toggles, and [toggleable] sits on the row rather than on the
 * switch so the touch target is the row's full width and TalkBack focuses one node
 * that names the setting and its state — not a label it cannot act on next to a
 * switch that does not say what it controls.
 */
@Composable
private fun SwitchRow(
    label: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
) {
    val state = stringResource(if (checked) R.string.settings_on else R.string.settings_off)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 8.dp)
            // No contentDescription: the label and its summary are real text
            // inside this merged node, and a description would replace both. Only
            // the state needs saying, because the switch itself is silent below.
            .semantics { stateDescription = state }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        // The row owns the action and the announcement; the switch is the picture
        // of the state, so it is taken out of the accessibility tree entirely.
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

private fun ThemeChoice.labelRes(): Int = when (this) {
    ThemeChoice.LIGHT -> R.string.settings_theme_light
    ThemeChoice.DARK -> R.string.settings_theme_dark
    ThemeChoice.SYSTEM -> R.string.settings_theme_system
}

private fun FontSize.labelRes(): Int = when (this) {
    FontSize.SMALL -> R.string.settings_font_small
    FontSize.MEDIUM -> R.string.settings_font_medium
    FontSize.LARGE -> R.string.settings_font_large
    FontSize.EXTRA_LARGE -> R.string.settings_font_extra_large
}

private fun PauseStrength.labelRes(): Int = when (this) {
    PauseStrength.OFF -> R.string.settings_pause_off
    PauseStrength.SUBTLE -> R.string.settings_pause_subtle
    PauseStrength.NORMAL -> R.string.settings_pause_normal
    PauseStrength.STRONG -> R.string.settings_pause_strong
}

private fun PivotColor.labelRes(): Int = when (this) {
    PivotColor.ACCENT -> R.string.settings_color_accent
    PivotColor.CRIMSON -> R.string.settings_color_crimson
    PivotColor.AMBER -> R.string.settings_color_amber
    PivotColor.TEAL -> R.string.settings_color_teal
    PivotColor.VIOLET -> R.string.settings_color_violet
}
