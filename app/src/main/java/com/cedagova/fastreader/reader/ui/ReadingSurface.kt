package com.cedagova.fastreader.reader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.cedagova.fastreader.R
import com.cedagova.fastreader.reader.ReaderMode
import com.cedagova.fastreader.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * The reading area: one tap target covering everything above the controls, so
 * "tap to pause" (REQ-014) does not require aiming at a button. It resumes too,
 * except at the end of the book, where play would have nothing to show.
 *
 * ## The two gestures (REQ-030)
 *
 * A **tap** plays or pauses. A **long press** hides or restores the chrome. They
 * are the same target on purpose — focused mode leaves nothing else to aim at —
 * and they cannot collide, because a long press is not a tap: Compose's
 * `combinedClickable` fires exactly one of them. Neither is a swipe, so neither
 * competes with the system's edge-swipe navigation, which a reader holding the
 * phone one-handed will trigger by accident.
 *
 * Both are announced. `onClickLabel` and `onLongClickLabel` land on the node that
 * carries the actions, so TalkBack offers "Pause" and "Hide the controls" on the
 * reading surface rather than leaving focused mode undiscoverable without sight.
 *
 * ## The third gesture, only while the chrome is hidden (REQ-108)
 *
 * With the chrome hidden the speed slider is gone, so the surface takes a
 * **vertical drag**: up faster, down slower, one 25 WPM step per
 * [SpeedStepDistance]. It is added only when [chromeHidden] — focused mode, or a
 * running stream — because the slider is the speed control everywhere else and a
 * drag on a surface with the slider under it would only be a second way to do
 * the same thing.
 *
 * It cannot collide with the two gestures above. A drag consumes the pointer past
 * touch slop, which cancels `combinedClickable`'s press, and the drag node sits
 * *inside* the clickable in the modifier chain, so it sees each pointer event
 * first. A press that never moves is still a tap or a long press.
 *
 * Over the paused paragraph, whose own vertical scroll is nested inside this, the
 * scroll wins where there is anything to scroll — the nearer meaning of a drag on
 * a paragraph the reader is reading. The word itself, which is where the thumb
 * goes in a running stream, always changes speed.
 *
 * A screen reader cannot perform a drag: TalkBack takes the swipes for its own
 * navigation. So the same two steps are also custom accessibility actions on this
 * node, "Increase reading speed" and "Decrease reading speed" (REQ-301), which is
 * both the accessible control and the place the gesture is named for a reader who
 * never sees the hint.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReadingSurface(
    state: ReaderUiState.Reading,
    onTogglePlay: () -> Unit,
    onToggleFocused: () -> Unit,
    /** What the long press toggles; the label names it. */
    focused: Boolean,
    /** Nothing but this surface is on the page, so the speed drag lives here. */
    chromeHidden: Boolean,
    speedNotice: String?,
    onSpeedStep: (Int) -> Unit,
    word: @Composable (ReaderWord, Modifier) -> Unit,
    modifier: Modifier,
) {
    val tappable = state.mode != ReaderMode.FINISHED
    // At the end of the book a tap does nothing, so it must not announce that it
    // will play: in focused mode the surface is still long-clickable, and a click
    // label on a node whose click is a no-op is a lie to a screen reader.
    val label = if (tappable) {
        stringResource(if (state.isStopped) R.string.reader_play else R.string.reader_pause)
    } else {
        null
    }
    val focusLabel = stringResource(if (focused) R.string.reader_show_controls else R.string.reader_hide_controls)
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // No content description: the word and, when stopped, the paragraph
                // under it are what a screen reader should read here. The click labels
                // still name the actions, so the tap target announces them without
                // hiding the text it covers.
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    // No ripple. The default indication tints this entire surface
                    // for as long as a pointer is down, and the speed drag put that
                    // on screen *during a running stream* — a whole-page brightness
                    // change on the one surface REQ-062/REQ-302 promise is static
                    // apart from glyphs, and an animation on a screen AD-6 says has
                    // none. Tap and long press lose nothing: each already answers
                    // with the state change itself, the paragraph appearing or the
                    // chrome going.
                    indication = null,
                    enabled = tappable || focused,
                    onClickLabel = label,
                    onLongClickLabel = focusLabel,
                    onLongClick = onToggleFocused,
                    onClick = { if (tappable) onTogglePlay() },
                )
                // After the clickable, never before: the inner node sees each
                // pointer event first, so a real drag is consumed here and the
                // press above it is cancelled instead of also firing.
                .then(if (chromeHidden) Modifier.speedGesture(onSpeedStep) else Modifier)
                .padding(horizontal = Spacing.XLarge)
                .testTag(if (chromeHidden) "reader_surface_focused" else "reader_surface"),
        ) {
            when (state.mode) {
                // The word keeps the same place whether the stream is running or
                // stopped, so pausing reveals the paragraph underneath instead of
                // moving the word the reader is looking at. Whether the paragraph
                // is there is the presenter's call, not the mode's: a reader who
                // chose to keep it on screen has it while playing too.
                ReaderMode.PLAYING, ReaderMode.PAUSED -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f).testTag("reader_word"),
                        contentAlignment = Alignment.Center,
                    ) {
                        word(state.word, Modifier.fillMaxSize())
                    }
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                        if (state.context != null) ParagraphContext(state.context)
                    }
                }

                // A stop screen has no word to keep in place, so it uses the whole
                // surface.
                ReaderMode.CHAPTER_PAUSE -> FullSurface { ChapterPause(state) }

                ReaderMode.FINISHED -> FullSurface { Finished(state) }
            }
        }
        if (speedNotice != null) {
            SpeedNotice(speedNotice, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * The focused-mode speed drag and its screen-reader equivalent, on one node.
 *
 * A `Modifier` extension rather than inline chain so the surface's own layout
 * stays readable and so the two halves of REQ-108's control — the gesture and the
 * custom actions that stand in for it under TalkBack — cannot drift apart.
 */
@Composable
private fun Modifier.speedGesture(onSpeedStep: (Int) -> Unit): Modifier {
    val faster = stringResource(R.string.reader_speed_faster)
    val slower = stringResource(R.string.reader_speed_slower)
    val stepPixels = with(LocalDensity.current) { SpeedStepDistance.toPx() }
    val drag = remember(stepPixels) { SpeedDrag(stepPixels) }
    // The callback is read through a state holder rather than being a key of the
    // block below. This screen recomposes on every streamed word — sixteen times a
    // second at the 1000 WPM ceiling — and `pointerInput` restarts its block, and
    // so cancels a gesture in progress, whenever a key changes by identity. Today
    // the caller's lambda happens to be memoized and the drag survives; a single
    // unstable capture added to it later would silently break dragging at speed
    // and nowhere else. This makes that impossible rather than lucky.
    val step by rememberUpdatedState(onSpeedStep)
    return this
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction(faster) {
                    step(1)
                    true
                },
                CustomAccessibilityAction(slower) {
                    step(-1)
                    true
                },
            )
        }
        .pointerInput(drag) {
            detectVerticalDragGestures(
                onDragStart = { drag.begin() },
                onVerticalDrag = { _, deltaY ->
                    val steps = drag.drag(deltaY)
                    if (steps != 0) step(steps)
                },
            )
        }
}

/**
 * REQ-108's readout, and the hint that names the gesture: one line of text over
 * the bottom of the focused surface, gone again within two seconds.
 *
 * Text and nothing else — no card, no scrim, no animation. Its background is the
 * page's own background, so it occludes the paused paragraph where it overlaps it
 * without putting a second brightness on the screen (REQ-062/REQ-302). Being an
 * overlay, it does not exist in the surface's layout at all: the word does not
 * move when it appears or when it goes.
 *
 * A live region, because the reader who most needs the readout is the one who
 * reached the speed change through the custom accessibility actions and cannot
 * see the line it left behind.
 */
@Composable
private fun SpeedNotice(text: String, modifier: Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .padding(horizontal = Spacing.XXLarge, vertical = 32.dp)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = Spacing.Medium, vertical = Spacing.XSmall)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("reader_speed_notice"),
    )
}

@Composable
private fun ColumnScope.FullSurface(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { content() }
}

/**
 * The paused view (REQ-010): the paragraph the reader stopped in, with the current
 * word marked, so the thread can be picked back up before playing again. With
 * "Always show paragraph" on it is also the running view, and the mark moves
 * from word to word as the stream goes.
 *
 * The paragraph is rebuilt from each token's own text and the exact separator that
 * followed it, so it reads as the book sets it — `—¿Quién teme a la máquina?
 * —preguntó ella—.`, not the bare word list increment 002 showed here. That is
 * the whole reason [com.cedagova.reader.engine.content.WordToken] carries its
 * punctuation: a paused reader is reading prose, and prose without its
 * punctuation is materially harder to pick a thread up from.
 */
@Composable
private fun ParagraphContext(context: ReaderContext) {
    val highlight = MaterialTheme.colorScheme.primary
    val ellipsis = stringResource(R.string.reader_context_continues)
    // Where the marked word starts in the text below, so the layout can say
    // which line it landed on.
    var currentStart = 0
    val paragraph = buildAnnotatedString {
        if (context.truncatedStart) append("$ellipsis ")
        context.words.forEachIndexed { offset, entry ->
            if (offset == context.currentOffset) {
                currentStart = length
                withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.Bold)) {
                    append(entry.text)
                }
            } else {
                append(entry.text)
            }
            append(entry.gapAfter)
        }
        if (context.truncatedEnd) append(" $ellipsis")
    }

    // Landscape and large font scales can leave less room than the paragraph
    // needs. The column scrolls, and it scrolls itself to the section of lines
    // holding the marked word, so the mark never sits below the fold: whichever
    // way the reader got here — pausing deep in a long paragraph, or the stream
    // carrying the mark down the shown lines — the word is on screen (REQ-010).
    val scrollState = rememberScrollState()
    var viewportHeight by remember { mutableIntStateOf(0) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    LaunchedEffect(layout, viewportHeight, currentStart) {
        val lines = layout ?: return@LaunchedEffect
        // A layout of the previous text: the fresh one re-runs this. Keying on
        // the text alone would scroll to a line measured on words no longer shown.
        if (lines.layoutInput.text != paragraph) return@LaunchedEffect
        if (viewportHeight <= 0 || lines.lineCount == 0) return@LaunchedEffect
        val current = lines.getLineForOffset(currentStart)
        val first =
            sectionStartLine(
                lines.lineCount,
                viewportHeight.toFloat(),
                current,
                lines::getLineTop,
                lines::getLineBottom,
            )
        scrollState.scrollTo(lines.getLineTop(first).roundToInt())
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Outside the scroll: the size here is the viewport, not the text.
            .onSizeChanged { viewportHeight = it.height }
            .verticalScroll(scrollState)
            .testTag("reader_paused"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = paragraph,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Start,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth().testTag("reader_context"),
        )
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
        Spacer(Modifier.height(Spacing.Medium))
        Text(
            text = state.chapterTitle,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.XLarge))
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
        Spacer(Modifier.height(Spacing.Medium))
        Text(
            text = state.bookTitle,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Large))
        Text(
            text = stringResource(R.string.reader_finished_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
