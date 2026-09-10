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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cedagova.fastreader.R
import com.cedagova.fastreader.reader.ui.resolve
import com.cedagova.fastreader.settings.AppVersion
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
 * Six groups and nothing else: theme and the two sizes (REQ-022), the cue set
 * — letter highlight and its palette, fixed focus letter, guide marks (REQ-020,
 * REQ-021) — pause strength (REQ-011), and the chapter-boundary pause (REQ-201).
 * There is no free-form colour picker, no point-size field, and no per-multiplier
 * timing panel. Every control is a choice from a small fixed set, which is also
 * why they are all radio-style chips or switches rather than sliders.
 *
 * ## The two cue toggles (#32)
 *
 * "Highlight letter" and "Fixed focus letter" used to be one switch. They are
 * separate choices — one colours a letter, the other moves the word off centre —
 * and only the first is on by default. Their copy says what each one does to the
 * page in front of the reader; the internal words for the mechanism ("pivot",
 * "recognition point") stay in the code and out of the UI.
 *
 * ## About (#46)
 *
 * The last section is not a set of choices: it states which build this is
 * (REQ-106), offers the one outbound action in the app — handing the releases
 * page to a browser — and says in plain sentences what stays on the device and
 * what leaves it (REQ-107). It is stateless in the same way the rest of the
 * screen is: the version arrives as a value, and whether the hand-off found a
 * browser arrives as a flag, so both states are reachable from a golden.
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
    /** The installed package's version, shown in About (REQ-106). */
    version: AppVersion,
    /** Hands the releases page to the reader's browser (REQ-106). */
    onCheckForUpdates: () -> Unit,
    modifier: Modifier = Modifier,
    /** Why a change did not stick, or null when the store is accepting writes. */
    persistenceFailure: String? = null,
    /** The last hand-off found no app able to open a web link (REQ-106 edge). */
    updateHandoffUnavailable: Boolean = false,
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
                // Two sizes, deliberately: the word is the reading surface and the
                // size that suits it has nothing to do with the size that suits a
                // menu. The word's row comes first because it is the one a reader
                // came here to change.
                ChoiceRow(
                    label = stringResource(R.string.settings_word_size),
                    summary = stringResource(R.string.settings_word_size_summary),
                    options = FontSize.entries,
                    selected = settings.wordSize,
                    optionLabel = { stringResource(it.labelRes()) },
                    onSelect = { onSettingsChange(settings.copy(wordSize = it)) },
                    tag = "settings_word_size",
                )
                ChoiceRow(
                    label = stringResource(R.string.settings_font_size),
                    summary = stringResource(R.string.settings_font_size_summary),
                    options = FontSize.entries,
                    selected = settings.fontSize,
                    optionLabel = { stringResource(it.labelRes()) },
                    onSelect = { onSettingsChange(settings.copy(fontSize = it)) },
                    tag = "settings_font_size",
                )

                SectionHeading(stringResource(R.string.settings_section_cues))
                SwitchRow(
                    label = stringResource(R.string.settings_highlight),
                    summary = stringResource(R.string.settings_highlight_summary),
                    checked = settings.highlightEnabled,
                    onCheckedChange = { onSettingsChange(settings.copy(highlightEnabled = it)) },
                    tag = "settings_highlight",
                )
                // The palette only means anything while the letter it colours is
                // being drawn, so it goes with the cue rather than staying on
                // screen as a control that does nothing (REQ-020).
                if (settings.highlightEnabled) {
                    PivotColorRow(
                        selected = settings.pivotColor,
                        onSelect = { onSettingsChange(settings.copy(pivotColor = it)) },
                    )
                }
                SwitchRow(
                    label = stringResource(R.string.settings_focus_alignment),
                    summary = stringResource(R.string.settings_focus_alignment_summary),
                    checked = settings.focusAlignmentEnabled,
                    onCheckedChange = { onSettingsChange(settings.copy(focusAlignmentEnabled = it)) },
                    tag = "settings_focus_alignment",
                )
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

                // Its own section, not a fourth cue and not a fifth pause
                // strength: the cue switches change what a word looks like and
                // pause strength changes how long one is shown, while this decides
                // whether the stream stops at all (REQ-201, D4).
                SectionHeading(stringResource(R.string.settings_section_chapters))
                SwitchRow(
                    label = stringResource(R.string.settings_chapter_pause),
                    summary = stringResource(R.string.settings_chapter_pause_summary),
                    checked = settings.chapterPauseEnabled,
                    onCheckedChange = { onSettingsChange(settings.copy(chapterPauseEnabled = it)) },
                    tag = "settings_chapter_pause",
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
                VersionRow(version)
                CheckForUpdatesRow(
                    onCheckForUpdates = onCheckForUpdates,
                    handoffUnavailable = updateHandoffUnavailable,
                )
                PrivacyStatement()
                VisualOnlyStatement()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
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
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
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
 * FastReader holds no network permission, so it cannot check anything itself; the
 * action opens the releases page in the reader's browser. The summary says that
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
    Spacer(Modifier.height(12.dp))
    Text(
        text = summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clearAndSetSemantics {},
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onCheckForUpdates,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = TouchTarget)
            .semantics { contentDescription = "$label. $summary" }
            .testTag("settings_check_updates"),
    ) {
        Text(text = label, modifier = Modifier.clearAndSetSemantics {})
    }
    if (handoffUnavailable) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_check_updates_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().testTag("settings_check_updates_unavailable"),
        )
    }
}

/**
 * REQ-107: what leaves this device and what is kept on it, in four sentences that
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
    Spacer(Modifier.height(20.dp))
    Text(
        text = stringResource(R.string.settings_privacy_label),
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.settings_privacy),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("settings_privacy"),
    )
    Spacer(Modifier.height(20.dp))
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
 *
 * The label is declared on the selectable node and cleared from the `Text` beneath
 * it, so exactly one description reaches the accessibility tree per option rather
 * than a drawn label that happens to be readable. It is the convention the
 * reader's controls already use, and `SettingsAccessibilityTest` holds every
 * control on this screen to it.
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
            .semantics { contentDescription = text }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
 * The bounded highlight palette (REQ-020), as five swatches drawn in the colours
 * they actually produce on the page in front of the reader.
 *
 * A name alone would not do: each entry resolves differently in the light and dark
 * themes, so the swatch is the honest label. The name still travels to TalkBack,
 * which cannot see a circle.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PivotColorRow(selected: PivotColor, onSelect: (PivotColor) -> Unit) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.settings_highlight_color),
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(4.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup().testTag("settings_highlight_color"),
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
 *
 * The name *and* the summary go into that node's description, and both `Text`s are
 * cleared from the tree, so the row is announced once and in a fixed order. The
 * summary is the only place a setting's effect is explained, so it belongs in what
 * a reader who cannot see it hears, and it should not arrive as a second focus
 * stop after the name.
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
    val description = "$label. $summary"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .semantics {
                contentDescription = description
                stateDescription = state
            }
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 8.dp)
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
