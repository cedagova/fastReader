package com.cedagova.fastreader.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cedagova.fastreader.R
import com.cedagova.fastreader.reader.ReaderMode
import com.cedagova.fastreader.ui.theme.Sizes
import com.cedagova.fastreader.ui.theme.Spacing
import com.cedagova.reader.engine.timing.RsvpTiming
import kotlin.math.roundToInt

@Composable
internal fun ReaderControls(
    state: ReaderUiState.Reading,
    onTogglePlay: () -> Unit,
    onWpmChange: (Int) -> Unit,
    onBackSentence: () -> Unit,
    onForwardSentence: () -> Unit,
    onBackParagraph: () -> Unit,
    onForwardParagraph: () -> Unit,
    onScrub: (Float) -> Unit,
    onOpenChapters: () -> Unit,
    progressShown: Boolean,
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
) {
    Column(
        modifier = modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Small).testTag("reader_controls"),
        verticalArrangement = verticalArrangement,
    ) {
        ChapterRow(state = state, onOpenChapters = onOpenChapters)
        if (progressShown) ProgressRow(state)
        PositionControl(state = state, onScrub = onScrub)
        Transport(
            state = state,
            onTogglePlay = onTogglePlay,
            onBackSentence = onBackSentence,
            onForwardSentence = onForwardSentence,
            onBackParagraph = onBackParagraph,
            onForwardParagraph = onForwardParagraph,
        )
        SpeedControl(state = state, onWpmChange = onWpmChange)
    }
}

/**
 * The chapter title doubles as the chapter picker's entry point (REQ-014).
 *
 * The title takes two lines rather than one, and ellipsises rather than clipping.
 * On a phone it never needs either — "Chapter One: The Arrival" fits a 379 dp row
 * at every font size the app allows — but REQ-205's control column is 280 dp at
 * the 600 dp boundary, where the same title at the largest font size lost "The
 * Arrival" off the end with no ellipsis to say so. Wrapping costs the narrow
 * layout nothing: a title that already fits one line is laid out identically, which
 * is why no phone golden moved when this changed.
 */
@Composable
private fun ChapterRow(state: ReaderUiState.Reading, onOpenChapters: () -> Unit) {
    val position = stringResource(R.string.reader_chapter_position, state.chapterNumber, state.chapterCount)
    val label = stringResource(R.string.reader_chapters_of, state.chapterTitle, position)
    TextButton(
        onClick = onOpenChapters,
        enabled = state.canNavigate && state.chapters.isNotEmpty(),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.TouchTarget)
            .semantics { contentDescription = label }
            .testTag("reader_chapters"),
    ) {
        Text(
            text = state.chapterTitle.ifBlank { stringResource(R.string.reader_chapters) },
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(Spacing.Small))
        Text(
            text = position,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * REQ-017: progress percent and time remaining at the current speed.
 *
 * The two labels each take half the row rather than being pushed apart by
 * `SpaceBetween`, which at a 2.0 system font scale let them meet with no gap and
 * render as "54% readUnder a minute left" (REQ-060). Halves cannot collide: the
 * longer label wraps inside its own half instead.
 */
@Composable
private fun ProgressRow(state: ReaderUiState.Reading) {
    Row(modifier = Modifier.fillMaxWidth().testTag("reader_progress")) {
        Text(
            text = stringResource(R.string.reader_progress, state.progressPercent),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Spacing.Small))
        Text(
            text = remainingLabel(state.remainingMillis),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A progress bar drawn by hand.
 *
 * Material's indicator animates when its progress changes, which at 1000 WPM
 * would put a continuously moving, brightening element on a screen AD-6 promises
 * is static apart from glyphs. Two boxes cannot animate.
 */
@Composable
private fun ProgressBar(fraction: Float) {
    val filled = fraction.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.XSmall)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (filled > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(filled)
                    .height(Spacing.XSmall)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * Position in the book: a scrubber while the stream is stopped (REQ-014), and a
 * plain bar while it runs.
 *
 * The swap happens only when playback starts or stops, never between two words,
 * and the slot keeps a fixed height so neither transition reflows the screen. It
 * is also the cheaper of the two to draw, which matters at the 1000 WPM ceiling
 * where the whole control column is laid out again on every word.
 */
@Composable
private fun PositionControl(state: ReaderUiState.Reading, onScrub: (Float) -> Unit) {
    val label = stringResource(R.string.reader_scrub)
    val position = stringResource(R.string.reader_progress, state.progressPercent)
    Box(
        modifier = Modifier.fillMaxWidth().height(Sizes.TouchTarget).testTag("reader_position"),
        contentAlignment = Alignment.Center,
    ) {
        if (state.canNavigate) {
            Slider(
                value = state.progressFraction,
                onValueChange = onScrub,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reader_scrub")
                    .semantics {
                        contentDescription = label
                        stateDescription = position
                    },
            )
        } else {
            ProgressBar(state.progressFraction)
        }
    }
}

@Composable
private fun Transport(
    state: ReaderUiState.Reading,
    onTogglePlay: () -> Unit,
    onBackSentence: () -> Unit,
    onForwardSentence: () -> Unit,
    onBackParagraph: () -> Unit,
    onForwardParagraph: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("reader_transport"),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(
            description = stringResource(R.string.reader_back_paragraph),
            enabled = state.canNavigate,
            chevrons = 2,
            forward = false,
            onClick = onBackParagraph,
            tag = "reader_back_paragraph",
        )
        StepButton(
            description = stringResource(R.string.reader_back_sentence),
            enabled = state.canNavigate,
            chevrons = 1,
            forward = false,
            onClick = onBackSentence,
            tag = "reader_back_sentence",
        )
        PlayPauseButton(state = state, onTogglePlay = onTogglePlay)
        StepButton(
            description = stringResource(R.string.reader_forward_sentence),
            enabled = state.canNavigate,
            chevrons = 1,
            forward = true,
            onClick = onForwardSentence,
            tag = "reader_forward_sentence",
        )
        StepButton(
            description = stringResource(R.string.reader_forward_paragraph),
            enabled = state.canNavigate,
            chevrons = 2,
            forward = true,
            onClick = onForwardParagraph,
            tag = "reader_forward_paragraph",
        )
    }
}

/**
 * One navigation step. Sentence and paragraph differ by how many chevrons are
 * drawn, which keeps every icon inside the core Material set instead of pulling in
 * the extended icon library for four glyphs.
 *
 * The label goes on the button rather than on an icon inside it. A `Modifier`
 * label lands on the same node that carries the click action, so an accessibility
 * sweep of this screen shows every focusable control naming itself — which the
 * default arrangement, with the description on a child of the clickable node,
 * does not.
 */
@Composable
private fun StepButton(
    description: String,
    enabled: Boolean,
    chevrons: Int,
    forward: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(Sizes.TouchTarget)
            .semantics { contentDescription = description }
            .testTag(tag),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-14).dp)) {
            repeat(chevrons) {
                Icon(
                    imageVector = if (forward) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowLeft,
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun PlayPauseButton(state: ReaderUiState.Reading, onTogglePlay: () -> Unit) {
    val playing = state.mode == ReaderMode.PLAYING
    val enabled = state.mode != ReaderMode.FINISHED
    val description = stringResource(if (playing) R.string.reader_pause else R.string.reader_play)
    val container = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, onClick = onTogglePlay)
            .semantics { contentDescription = description }
            .testTag("reader_play_pause"),
        contentAlignment = Alignment.Center,
    ) {
        if (playing) {
            PauseGlyph(content)
        } else {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** Two bars. The core Material icon set has `PlayArrow` but no pause, and one shape is not worth 30 MB of extended icons. */
@Composable
private fun PauseGlyph(color: Color) {
    Canvas(modifier = Modifier.size(Sizes.Icon).clearAndSetSemantics {}) {
        val barWidth = size.width * 0.28f
        val gap = size.width * 0.16f
        val left = (size.width - (2 * barWidth + gap)) / 2f
        drawRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(left, 0f),
            size = androidx.compose.ui.geometry.Size(barWidth, size.height),
        )
        drawRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(left + barWidth + gap, 0f),
            size = androidx.compose.ui.geometry.Size(barWidth, size.height),
        )
    }
}

/**
 * The slider's ceiling, below the range's own [RsvpTiming.MAX_WPM].
 *
 * The track is a phone width minus the readout, and over the full 100–1000
 * range each 25 WPM step was a few pixels of thumb travel — too fine for a
 * thumb to stop on the step meant rather than the one beside it. Halving the
 * span doubles the travel per step. Speeds past 600 stay reachable: the typed
 * entry and the focused-mode drag still take the whole range, and a speed
 * above the slider's end simply shows the thumb at the end.
 */
private const val SLIDER_MAX_WPM: Int = 600

/** REQ-012: speed is adjustable at any time, including mid-stream, and never stops playback. */
@Composable
private fun SpeedControl(state: ReaderUiState.Reading, onWpmChange: (Int) -> Unit) {
    val label = stringResource(R.string.reader_speed_label)
    val speed = stringResource(R.string.reader_speed, state.wpm)
    val editSpeed = stringResource(R.string.reader_speed_edit)
    var editing by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Slider(
            value = state.wpm.toFloat(),
            onValueChange = { onWpmChange((it / SPEED_STEP_WPM).roundToInt() * SPEED_STEP_WPM) },
            valueRange = RsvpTiming.MIN_WPM.toFloat()..SLIDER_MAX_WPM.toFloat(),
            modifier = Modifier
                .weight(1f)
                .testTag("reader_speed")
                .semantics {
                    contentDescription = label
                    stateDescription = speed
                },
        )
        Spacer(Modifier.width(Spacing.Medium))
        if (editing) {
            SpeedEntry(
                wpm = state.wpm,
                onDone = { typed ->
                    editing = false
                    typed?.let(onWpmChange)
                },
            )
        } else {
            Text(
                text = speed,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = { editing = true })
                    .testTag("reader_speed_readout")
                    .clearAndSetSemantics { contentDescription = editSpeed },
            )
        }
    }
}

/**
 * The slider lands on 25 WPM steps; the readout it sits beside is the way off
 * the grid. Pressed, it becomes this field, which hands back any whole number in
 * [RsvpTiming.MIN_WPM]..[RsvpTiming.MAX_WPM] exactly as typed. Done commits, and
 * the field commits or reverts on its own when focus leaves it or the keyboard
 * is dismissed, so there is no second control to reach for.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedEntry(wpm: Int, onDone: (Int?) -> Unit) {
    // The cursor starts after the last digit: the reader came here to change the
    // number, so a backspace or a typed digit must act on its end, not its front.
    var field by remember {
        val initial = wpm.toString()
        mutableStateOf(TextFieldValue(initial, selection = TextRange(initial.length)))
    }
    val parsed = field.text.toIntOrNull()?.takeIf { it in RsvpTiming.MIN_WPM..RsvpTiming.MAX_WPM }
    val focusRequester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val finish = { value: Int? ->
        if (!finished) {
            finished = true
            onDone(value)
        }
    }
    OutlinedTextField(
        value = field,
        onValueChange = { field = it.copy(text = it.text.filter(Char::isDigit).take(4)) },
        isError = parsed == null,
        singleLine = true,
        textStyle = MaterialTheme.typography.labelLarge,
        suffix = { Text(stringResource(R.string.reader_speed_unit)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { parsed?.let(finish) }),
        modifier = Modifier
            .width(SpeedEntryWidth)
            .focusRequester(focusRequester)
            .onFocusChanged { focus ->
                if (focus.isFocused) {
                    hadFocus = true
                } else if (hadFocus) {
                    finish(parsed)
                }
            }
            .testTag("reader_speed_entry"),
    )
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    // Back, or the keyboard's own dismiss, hides the IME without moving focus; the
    // field must not sit there editing over a keyboard that is gone.
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            imeWasVisible = true
        } else if (imeWasVisible) {
            finish(parsed)
        }
    }
}

private val SpeedEntryWidth = 132.dp

@Composable
internal fun ChapterPicker(
    chapters: List<ChapterEntry>,
    currentPosition: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_chapters)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp).testTag("reader_chapter_list")) {
                itemsIndexed(items = chapters, key = { _, chapter -> chapter.chapterIndex }) { position, chapter ->
                    TextButton(
                        onClick = { onSelect(chapter.chapterIndex) },
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.TouchTarget),
                    ) {
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (position == currentPosition) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_close)) }
        },
    )
}

/**
 * Time remaining, rounded the way a reader reads it. The estimate is worth minutes,
 * not seconds, and a seconds display would also be a second element changing on
 * every word.
 */
@Composable
private fun remainingLabel(remainingMillis: Long): String {
    val totalMinutes = (remainingMillis / 60_000L).toInt()
    return when {
        remainingMillis <= 0L -> stringResource(R.string.reader_remaining_none)
        totalMinutes <= 0 -> stringResource(R.string.reader_remaining_under_minute)
        totalMinutes < 60 -> stringResource(R.string.reader_remaining_minutes, totalMinutes)
        else -> stringResource(R.string.reader_remaining_hours, totalMinutes / 60, totalMinutes % 60)
    }
}

/*
 * Speed lands on round 25 WPM steps, which [SPEED_STEP_WPM] holds for the slider
 * and the focused-mode gesture alike. The slider itself stays continuous rather
 * than using Material's `steps`, whose tick marks would draw 36 dots across a
 * control the reader is only ever asked to read one number off.
 */
