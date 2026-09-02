package com.cedagova.fastreader.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedagova.fastreader.R
import com.cedagova.fastreader.reader.ReaderMode
import com.cedagova.fastreader.timing.RsvpTiming
import kotlin.math.roundToInt

/** Android's accessibility minimum for an interactive control (REQ-060). */
private val TouchTarget = 48.dp

private val WordSize = 36.sp

/**
 * Landscape leaves the reading area about a fifth of the height portrait does, and
 * a word drawn at [WordSize] there is simply cut in half. Below this much room the
 * word is drawn at [CompactWordSize] instead. Real shrink-to-fit is LEAF301's; this
 * is the minimum that keeps every orientation readable.
 */
private val CompactWordArea = 96.dp

private val CompactWordSize = 24.sp

/**
 * The reader surface (LEAF203): the paused context view, the word stream, in-book
 * navigation, the chapter pause, progress and time remaining, and the end state.
 *
 * Stateless, like the library screen: every state it can show is reachable from a
 * [ReaderUiState] value, which is what lets the Roborazzi goldens be the UI
 * regression gate and what lets states that are tedious to reach on a device —
 * the end of a book, a mid-book content gap — be rendered directly.
 * [ReaderRoute] supplies the real session-backed state.
 *
 * ## AD-6 — static-luminance presentation (REQ-062)
 *
 * The word stream is an instantaneous text swap and nothing else. This screen
 * therefore contains no animation API at all: no `AnimatedContent`, no
 * `Crossfade`, no `animate*AsState`, and the progress bar is drawn by hand
 * ([ProgressBar]) rather than with the Material indicator, whose progress
 * variant animates. The reading surface keeps a fixed size and a fixed background
 * in every mode, and the transport controls are disabled rather than hidden while
 * the stream runs, so starting and stopping never reflows the screen. What changes
 * between two frames of a running stream is glyphs, plus the odd percent or
 * minute; nothing alternates in brightness at any speed.
 *
 * ## The presentation seam
 *
 * [word] draws one streamed token. It is a slot, not a hard-coded `Text`, because
 * LEAF301 replaces exactly this — pivot-letter alignment, the coloured pivot,
 * guide marks, shrink-to-fit — while playback semantics, navigation and this
 * layout stay where they are. Its default, [PlainWord], is the plain centred word
 * the plan specifies for this increment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    state: ReaderUiState,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onWpmChange: (Int) -> Unit,
    onBackSentence: () -> Unit,
    onForwardSentence: () -> Unit,
    onBackParagraph: () -> Unit,
    onForwardParagraph: () -> Unit,
    onScrub: (Float) -> Unit,
    onChapterSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    word: @Composable (ReaderWord, Modifier) -> Unit = { token, wordModifier -> PlainWord(token, wordModifier) },
) {
    var chapterPickerOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize().testTag("reader_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.bookTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    val back = stringResource(R.string.reader_back)
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(TouchTarget)
                            .semantics { contentDescription = back }
                            .testTag("reader_back"),
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        // Scaffold's inset padding is what keeps the transport controls clear of
        // the gesture navigation bar; the app draws edge to edge.
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (state) {
                is ReaderUiState.Opening -> OpeningBook(state, Modifier.weight(1f))
                is ReaderUiState.Unavailable -> Unavailable(state, Modifier.weight(1f))
                is ReaderUiState.Reading -> {
                    ReadingSurface(
                        state = state,
                        onTogglePlay = onTogglePlay,
                        word = word,
                        modifier = Modifier.weight(1f),
                    )
                    ReaderControls(
                        state = state,
                        onTogglePlay = onTogglePlay,
                        onWpmChange = onWpmChange,
                        onBackSentence = onBackSentence,
                        onForwardSentence = onForwardSentence,
                        onBackParagraph = onBackParagraph,
                        onForwardParagraph = onForwardParagraph,
                        onScrub = onScrub,
                        onOpenChapters = { chapterPickerOpen = true },
                    )
                }
            }
        }
    }

    if (chapterPickerOpen && state is ReaderUiState.Reading) {
        ChapterPicker(
            chapters = state.chapters,
            currentPosition = state.chapterNumber - 1,
            onDismiss = { chapterPickerOpen = false },
            onSelect = {
                chapterPickerOpen = false
                onChapterSelected(it)
            },
        )
    }
}

/**
 * The book-open loading state. LEAF201 parses off the main thread and reports one
 * step per spine item, so this is determinate as soon as the spine is known.
 */
@Composable
private fun OpeningBook(state: ReaderUiState.Opening, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .testTag("reader_opening"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.reader_opening, state.bookTitle),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        val fraction = state.fraction
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.reader_percent, (fraction * 100).roundToInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Unavailable(state: ReaderUiState.Unavailable, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .testTag("reader_unavailable"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.reader_unavailable_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The reading area: one tap target covering everything above the controls, so
 * "tap to pause" (REQ-014) does not require aiming at a button. It resumes too,
 * except at the end of the book, where play would have nothing to show.
 */
@Composable
private fun ReadingSurface(
    state: ReaderUiState.Reading,
    onTogglePlay: () -> Unit,
    word: @Composable (ReaderWord, Modifier) -> Unit,
    modifier: Modifier,
) {
    val label = stringResource(if (state.isStopped) R.string.reader_play else R.string.reader_pause)
    val tappable = state.mode != ReaderMode.FINISHED
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            // No content description: the word and, when stopped, the paragraph
            // under it are what a screen reader should read here. `onClickLabel`
            // still names the action, so the tap target announces "Pause" or
            // "Play" without hiding the text it covers.
            .then(
                if (tappable) {
                    Modifier.clickable(onClickLabel = label, onClick = onTogglePlay)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 20.dp)
            .testTag("reader_surface"),
    ) {
        when (state.mode) {
            // The word keeps the same place whether the stream is running or
            // stopped, so pausing reveals the paragraph underneath instead of
            // moving the word the reader is looking at.
            ReaderMode.PLAYING, ReaderMode.PAUSED -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("reader_word"),
                    contentAlignment = Alignment.Center,
                ) {
                    word(state.word, Modifier.fillMaxSize())
                }
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                    if (state.mode == ReaderMode.PAUSED) PausedContext(state)
                }
            }

            // A stop screen has no word to keep in place, so it uses the whole
            // surface.
            ReaderMode.CHAPTER_PAUSE -> FullSurface { ChapterPause(state) }
            ReaderMode.FINISHED -> FullSurface { Finished(state) }
        }
    }
}

@Composable
private fun ColumnScope.FullSurface(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { content() }
}

/**
 * The default word presentation for this increment: a plain centred word on the
 * page background (the plan reserves pivot alignment and cues for LEAF301).
 *
 * A skip marker is set apart in italics and the muted colour, because
 * `[image skipped]` is the app talking, not the book (REQ-015).
 *
 * Fitting the word to the space is the presenter's job, not the layout's — which
 * is why the size is decided here, inside the slot LEAF301 replaces, rather than
 * by a reserved height the reader screen imposes on every presentation.
 */
@Composable
fun PlainWord(token: ReaderWord, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = token.text,
            textAlign = TextAlign.Center,
            fontSize = if (maxHeight < CompactWordArea) CompactWordSize else WordSize,
            // An unusually long word wraps rather than being cut off. It is rare
            // enough that two lines is the whole treatment here; shrink-to-fit
            // arrives with LEAF301.
            maxLines = 2,
            fontStyle = if (token.isSkipMarker) FontStyle.Italic else FontStyle.Normal,
            fontWeight = if (token.isHeading) FontWeight.Bold else FontWeight.Normal,
            color = if (token.isSkipMarker) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onBackground
            },
        )
    }
}

/**
 * The paused view (REQ-010): the paragraph the reader stopped in, with the current
 * word marked, so the thread can be picked back up before playing again.
 */
@Composable
private fun PausedContext(state: ReaderUiState.Reading) {
    val context = state.context
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Landscape and large font scales can leave less room than the
            // paragraph needs; scrolling is better than losing the end of it.
            .verticalScroll(rememberScrollState())
            .testTag("reader_paused"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (context != null) {
            val highlight = MaterialTheme.colorScheme.primary
            val ellipsis = stringResource(R.string.reader_context_continues)
            val paragraph = buildAnnotatedString {
                if (context.truncatedStart) append("$ellipsis ")
                context.words.forEachIndexed { offset, text ->
                    if (offset > 0) append(" ")
                    if (offset == context.currentOffset) {
                        withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.Bold)) { append(text) }
                    } else {
                        append(text)
                    }
                }
                if (context.truncatedEnd) append(" $ellipsis")
            }
            Text(
                text = paragraph,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Start,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().testTag("reader_context"),
            )
        }
    }
}

/** REQ-015: crossing a chapter end stops the stream on a screen naming the new chapter. */
@Composable
private fun ChapterPause(state: ReaderUiState.Reading) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("reader_chapter_pause"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.reader_chapter_position, state.chapterNumber, state.chapterCount),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = state.chapterTitle,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.reader_chapter_pause_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** REQ-018: the end of a book is an explicit state, not a stream that quietly stops. */
@Composable
private fun Finished(state: ReaderUiState.Reading) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("reader_finished"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.reader_finished_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = state.bookTitle,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.reader_finished_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ReaderControls(
    state: ReaderUiState.Reading,
    onTogglePlay: () -> Unit,
    onWpmChange: (Int) -> Unit,
    onBackSentence: () -> Unit,
    onForwardSentence: () -> Unit,
    onBackParagraph: () -> Unit,
    onForwardParagraph: () -> Unit,
    onScrub: (Float) -> Unit,
    onOpenChapters: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        ChapterRow(state = state, onOpenChapters = onOpenChapters)
        ProgressRow(state)
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

/** The chapter title doubles as the chapter picker's entry point (REQ-014). */
@Composable
private fun ChapterRow(state: ReaderUiState.Reading, onOpenChapters: () -> Unit) {
    val position = stringResource(R.string.reader_chapter_position, state.chapterNumber, state.chapterCount)
    val label = stringResource(R.string.reader_chapters_of, state.chapterTitle, position)
    TextButton(
        onClick = onOpenChapters,
        enabled = state.canNavigate && state.chapters.isNotEmpty(),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = TouchTarget)
            .semantics { contentDescription = label }
            .testTag("reader_chapters"),
    ) {
        Text(
            text = state.chapterTitle.ifBlank { stringResource(R.string.reader_chapters) },
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = position,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** REQ-017: progress percent and time remaining at the current speed. */
@Composable
private fun ProgressRow(state: ReaderUiState.Reading) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("reader_progress"),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.reader_progress, state.progressPercent),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = remainingLabel(state.remainingMillis),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            .height(4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (filled > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(filled)
                    .height(4.dp)
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
        modifier = Modifier.fillMaxWidth().height(TouchTarget).testTag("reader_position"),
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
            .size(TouchTarget)
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
    Canvas(modifier = Modifier.size(24.dp).clearAndSetSemantics {}) {
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

/** REQ-012: speed is adjustable at any time, including mid-stream, and never stops playback. */
@Composable
private fun SpeedControl(state: ReaderUiState.Reading, onWpmChange: (Int) -> Unit) {
    val label = stringResource(R.string.reader_speed_label)
    val speed = stringResource(R.string.reader_speed, state.wpm)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Slider(
            value = state.wpm.toFloat(),
            onValueChange = { onWpmChange((it / SPEED_STEP).roundToInt() * SPEED_STEP) },
            valueRange = RsvpTiming.MIN_WPM.toFloat()..RsvpTiming.MAX_WPM.toFloat(),
            modifier = Modifier
                .weight(1f)
                .testTag("reader_speed")
                .semantics {
                    contentDescription = label
                    stateDescription = speed
                },
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = speed,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
    if (state.showSpeedHint) {
        Text(
            text = stringResource(R.string.reader_speed_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag("reader_speed_hint"),
        )
    }
}

@Composable
private fun ChapterPicker(
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
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = TouchTarget),
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

/**
 * Speed lands on round 25 WPM steps. The slider itself stays continuous rather
 * than using Material's `steps`, whose tick marks would draw 36 dots across a
 * control the reader is only ever asked to read one number off.
 */
private const val SPEED_STEP = 25
