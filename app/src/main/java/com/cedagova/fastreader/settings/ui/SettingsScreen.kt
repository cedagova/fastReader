package com.cedagova.fastreader.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cedagova.fastreader.R
import com.cedagova.fastreader.reader.ui.resolve
import com.cedagova.fastreader.settings.AppVersion
import com.cedagova.fastreader.settings.FontSize
import com.cedagova.fastreader.settings.PivotColor
import com.cedagova.fastreader.settings.ReaderSettings
import com.cedagova.fastreader.settings.ThemeChoice
import com.cedagova.fastreader.ui.components.BackButton
import com.cedagova.fastreader.ui.components.ChoiceRow
import com.cedagova.fastreader.ui.components.ProblemBanner
import com.cedagova.fastreader.ui.components.SectionHeading
import com.cedagova.fastreader.ui.components.SwitchRow
import com.cedagova.fastreader.ui.theme.Sizes
import com.cedagova.fastreader.ui.theme.Spacing
import com.cedagova.reader.account.ReaderAccountSummary
import com.cedagova.reader.engine.timing.PauseStrength

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
 * Six groups and nothing else: theme, the two sizes and whether the paragraph
 * stays on screen while the stream runs (REQ-022), the cue set
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
 * ## About (#46, #100)
 *
 * The last section is not a set of choices: it states which build this is
 * (REQ-106), hands the releases page to a browser, opens the Reader account
 * surface (#100, REQ-401) — the one row here that leads to a further screen —
 * and says in plain sentences what stays on the device and what leaves it
 * (REQ-107). The account row sits directly above that statement because the
 * statement is about it. It is stateless in the same way the rest of the
 * screen is: the version arrives as a value, whether the hand-off found a
 * browser arrives as a flag, and the account's summary arrives as a value, so
 * every state is reachable from a golden.
 *
 * ## REQ-060
 *
 * The choice rows are [selectableGroup]s, so TalkBack announces "2 of 4" and
 * swipes between the options of one setting instead of walking a flat list of
 * chips. Every control clears [Sizes.TouchTarget], the chip rows wrap rather than clip
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
    /** What the Reader account row says (#100): not configured, signed out, or signed in as whom. */
    readerAccount: ReaderAccountSummary,
    /** Opens the Reader account surface over this screen (#100). */
    onOpenReaderAccount: () -> Unit,
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
                    BackButton(stringResource(R.string.settings_back), onBack, tag = "settings_back")
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
            persistenceFailure?.let {
                ProblemBanner(
                    title = stringResource(R.string.settings_problem_title),
                    message = it,
                    modifier = Modifier.testTag("settings_problem"),
                )
            }

            Column(modifier = Modifier.padding(horizontal = Spacing.Large)) {
                Spacer(Modifier.height(Spacing.Small))
                SettingsPreview(
                    cues = settings.cues,
                    pauseStrength = settings.pauseStrength,
                    heldTokenIndex = heldPreviewToken,
                )
                Spacer(Modifier.height(Spacing.Small))
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
                // Appearance rather than rhythm: it changes what is on the page
                // while the stream runs, not when anything is shown or for how
                // long. Off by default — one word on a static page is the
                // reading surface, and the paragraph is opt-in company for it.
                SwitchRow(
                    label = stringResource(R.string.settings_paragraph_always_shown),
                    summary = stringResource(R.string.settings_paragraph_always_shown_summary),
                    checked = settings.paragraphAlwaysShown,
                    onCheckedChange = { onSettingsChange(settings.copy(paragraphAlwaysShown = it)) },
                    tag = "settings_paragraph_always_shown",
                )
                // The two readouts are numbers about the session, not the page.
                // A reader who finds them a pull on the eye turns them off and
                // keeps the bar, which is also the scrub control, and the
                // chapter row, which is the way to the picker.
                SwitchRow(
                    label = stringResource(R.string.settings_progress_shown),
                    summary = stringResource(R.string.settings_progress_shown_summary),
                    checked = settings.progressShown,
                    onCheckedChange = { onSettingsChange(settings.copy(progressShown = it)) },
                    tag = "settings_progress_shown",
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

                Spacer(Modifier.height(Spacing.Large))
                OutlinedButton(
                    onClick = onReset,
                    enabled = !settings.isDefault,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = Sizes.TouchTarget)
                        .testTag("settings_reset"),
                ) {
                    Text(stringResource(R.string.settings_reset))
                }

                AboutSection(
                    version = version,
                    onCheckForUpdates = onCheckForUpdates,
                    updateHandoffUnavailable = updateHandoffUnavailable,
                    readerAccount = readerAccount,
                    onOpenReaderAccount = onOpenReaderAccount,
                )
                Spacer(Modifier.height(Spacing.XXLarge))
            }
        }
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
    Spacer(Modifier.height(Spacing.Medium))
    Text(
        text = stringResource(R.string.settings_highlight_color),
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(Spacing.XSmall))
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup().testTag("settings_highlight_color"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
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
                    .size(Sizes.TouchTarget)
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
                        .size(if (isSelected) SelectedSwatchSize else SwatchSize)
                        .clip(CircleShape)
                        .background(entry.resolve())
                        .border(width = if (isSelected) 3.dp else 1.dp, color = ring, shape = CircleShape),
                )
            }
        }
    }
}

/** A palette swatch, drawn inside a [Sizes.TouchTarget]-sized selectable box. */
private val SwatchSize = 24.dp

/** The chosen swatch grows, so the choice reads without relying on its ring colour alone. */
private val SelectedSwatchSize = 28.dp

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
