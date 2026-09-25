package com.cedagova.fastreader.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cedagova.fastreader.R
import com.cedagova.fastreader.reader.ReaderMode
import com.cedagova.fastreader.reader.ResumeOffer
import com.cedagova.fastreader.settings.CueSettings
import com.cedagova.fastreader.ui.LayoutWidth
import com.cedagova.fastreader.ui.WidthAware
import com.cedagova.fastreader.ui.components.BackButton
import com.cedagova.fastreader.ui.components.ProblemBanner
import com.cedagova.fastreader.ui.theme.Sizes
import com.cedagova.fastreader.ui.theme.Spacing
import kotlin.math.roundToInt

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
 * variant animates. The reading surface keeps a fixed background in every mode,
 * and while the stream runs nothing but the surface is on the page at all. What
 * changes between two frames of a running stream is glyphs; nothing alternates
 * in brightness at any speed.
 *
 * ## The chrome goes while the stream runs
 *
 * A running stream is the word and, if the reader asked for it, the paragraph —
 * and nothing else: the top bar and the entire control column are not composed
 * while the mode is [ReaderMode.PLAYING], so the page is the background and the
 * reading. Pausing brings all of it back. The controls used to stay on screen
 * disabled; a dimmed transport is still a transport in the corner of the eye,
 * which is the one thing a stream should not have.
 *
 * ## The presentation seam
 *
 * [word] draws one streamed token. It is a slot, not a hard-coded `Text`, so the
 * cue layer — pivot-letter alignment, the coloured pivot, guide marks,
 * shrink-to-fit — is replaceable without playback semantics, navigation or this
 * layout moving. Its default is [CueWord], which draws the cues in [cues].
 *
 * ## Focused mode (REQ-030)
 *
 * [focused] hides the same chrome while the stream is *paused*: with it set, the
 * top bar and the control column stay away in every mode, leaving the stream and
 * its cues alone on the page. It is a parameter rather than session state because
 * it is a property of this screen, not of the book — which also lets the goldens
 * render it directly.
 *
 * ## Speed without the controls (REQ-108)
 *
 * Focused mode hides the speed slider, so it adds a third gesture in its place: a
 * vertical drag on the reading surface, one 25 WPM step per
 * [SpeedStepDistance], up for faster. [SpeedGesture] documents why that gesture
 * and no other, and holds the arithmetic.
 *
 * [speedNotice] is the text-only, self-dismissing line the drag leaves behind —
 * the new speed, or the one-line hint that names the gesture when focused mode is
 * entered. It is a parameter for the same reason [focused] is: the timer that
 * clears it belongs to [ReaderRoute], and passing the text in lets a golden render
 * the readout without one.
 *
 * It is an *overlay*, drawn in a [Box] over the surface rather than in the
 * column with it, so appearing and disappearing cannot move the word (AD-6): the
 * stream keeps the same size and the same background whether the notice is there
 * or not. Nothing about it animates, and it never touches the stream's own
 * luminance (REQ-062).
 *
 * ## Two layouts, one screen (REQ-205)
 *
 * At [com.cedagova.fastreader.ui.WideLayoutMinWidth] and above — every tablet, and
 * every phone turned on its side — the control column moves from *under* the
 * stream to *beside* it, and nothing else changes: the same [ReaderControls] with
 * the same controls, labels and touch targets, and the same [ReadingSurface] with
 * the same tap, long press and drag. Below that width the screen is composed
 * exactly as it was, which is why the phone-portrait goldens are unchanged bytes.
 *
 * The wide branch is skipped whenever the chrome is hidden: focused mode has no
 * control column to place, so it is the same full-bleed stream at every width
 * (REQ-030), and the speed gesture it carries is unaffected by the breakpoint.
 *
 * Landscape is where the reading area is shortest, and moving the controls out of
 * the column is what gives it back: the stream gets the whole height instead of
 * roughly half of it. The control column takes [readerControlsWidth] of the width
 * and scrolls inside itself, so a large font scale lengthens it rather than
 * cutting the speed slider off the bottom.
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
    cues: CueSettings = CueSettings(),
    /** REQ-017's readouts, the percent read and the time left. Off hides only them: the bar and chapter row stay. */
    progressShown: Boolean = true,
    /** REQ-030: chrome hidden, stream and cues left alone. */
    focused: Boolean = false,
    onToggleFocused: () -> Unit = {},
    /**
     * REQ-108: the static, self-dismissing line the focused surface shows — the
     * speed the gesture just set, or the hint that names the gesture. Null when
     * there is nothing to say, which is nearly always.
     */
    speedNotice: String? = null,
    /** REQ-108: whole 25 WPM steps from the focused-mode drag; positive is faster. */
    onSpeedStep: (Int) -> Unit = {},
    /** Opens the settings screen (LEAF302). Hidden with the rest of the chrome in focused mode. */
    onOpenSettings: () -> Unit = {},
    /**
     * REQ-103: this book was handed over by another app and has no library row,
     * so the screen says what will and will not be kept.
     */
    externalNotice: Boolean = false,
    onAddToLibrary: () -> Unit = {},
    onDismissExternalNotice: () -> Unit = {},
    /**
     * REQ-202: this book opens on front matter and has not been offered the skip
     * before, so the screen offers it once. The title is the chapter the skip
     * lands in; null means no offer.
     */
    frontMatterOffer: String? = null,
    onSkipFrontMatter: () -> Unit = {},
    onDismissFrontMatterOffer: () -> Unit = {},
    /**
     * REQ-511: the account holds a place another device left in this book, ahead
     * of where this reader is, and it has not been answered. Null means no offer.
     *
     * The whole value rather than its text, because the offer has two sentences
     * and which one it is depends on whether the section could be honoured —
     * choosing between them is presentation, so it belongs here rather than in
     * the route. The screen reads [ResumeOffer.chapterTitle] and
     * [ResumeOffer.percent] and nothing else.
     */
    resumeOffer: ResumeOffer? = null,
    onAcceptResumeOffer: () -> Unit = {},
    onDismissResumeOffer: () -> Unit = {},
    word: @Composable (ReaderWord, Modifier) -> Unit = { token, wordModifier ->
        CueWord(token, cues, wordModifier)
    },
) {
    var chapterPickerOpen by remember { mutableStateOf(false) }

    // The chrome goes while the stream runs, and in focused mode whether or not it
    // runs. Only for the reader proper: a book that is still opening or cannot be
    // opened has no stream to hide it for, and hiding the way back to the library
    // there would strand the reader on a dead screen.
    val chromeHidden = state is ReaderUiState.Reading && (focused || state.mode == ReaderMode.PLAYING)

    WidthAware(modifier.fillMaxSize()) { layout ->
        Scaffold(
            modifier = Modifier.fillMaxSize().testTag("reader_screen"),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (!chromeHidden) {
                    TopAppBar(
                        title = {
                            Text(
                                text = state.bookTitle,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                            )
                        },
                        navigationIcon = {
                            BackButton(stringResource(R.string.reader_back), onBack, tag = "reader_back")
                        },
                        actions = {
                            // Cues and pause strength are things a reader judges while
                            // actually reading, so settings are one tap from the book
                            // rather than only from the library (REQ-023).
                            val settings = stringResource(R.string.settings_open)
                            IconButton(
                                onClick = onOpenSettings,
                                modifier = Modifier
                                    .size(Sizes.TouchTarget)
                                    .semantics { contentDescription = settings }
                                    .testTag("reader_settings"),
                            ) {
                                Icon(imageVector = Icons.Filled.Settings, contentDescription = null)
                            }
                        },
                    )
                }
            },
        ) { innerPadding ->
            // Scaffold's inset padding is what keeps the transport controls clear of
            // the gesture navigation bar; the app draws edge to edge.
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                when (state) {
                    is ReaderUiState.Opening -> OpeningBook(state, Modifier.weight(1f))

                    is ReaderUiState.Unavailable -> Unavailable(state, Modifier.weight(1f))

                    is ReaderUiState.Reading -> {
                        if (!chromeHidden) {
                            // The store is refusing writes, so the reader's place is not
                            // being kept. A banner and not a dialog: nothing here should
                            // stop someone reading, and it sits above the reading surface,
                            // outside it, so the stream keeps its fixed size and static
                            // background (REQ-062, AD-6).
                            state.persistenceFailure?.let {
                                ProblemBanner(
                                    title = stringResource(R.string.reader_persistence_problem_title),
                                    message = it,
                                    modifier = Modifier.testTag("reader_persistence_problem"),
                                )
                            }
                            if (externalNotice) {
                                ExternalOpenNotice(
                                    onAddToLibrary = onAddToLibrary,
                                    onDismiss = onDismissExternalNotice,
                                )
                            }
                            frontMatterOffer?.let { chapterTitle ->
                                FrontMatterOfferNotice(
                                    chapterTitle = chapterTitle,
                                    onSkip = onSkipFrontMatter,
                                    onDismiss = onDismissFrontMatterOffer,
                                )
                            }
                            resumeOffer?.let { offer ->
                                ResumeOfferNotice(
                                    offer = offer,
                                    onAccept = onAcceptResumeOffer,
                                    onDismiss = onDismissResumeOffer,
                                )
                            }
                        }
                        // The two slots the layouts share. Hoisted so the wide branch
                        // cannot drift from the narrow one: there is one argument list
                        // for the stream and one for the controls, and the branches
                        // differ only in where they put them.
                        val surface: @Composable (Modifier) -> Unit = { slot ->
                            ReadingSurface(
                                state = state,
                                onTogglePlay = onTogglePlay,
                                onToggleFocused = onToggleFocused,
                                focused = focused,
                                chromeHidden = chromeHidden,
                                speedNotice = speedNotice,
                                onSpeedStep = onSpeedStep,
                                word = word,
                                modifier = slot,
                            )
                        }
                        val controls: @Composable (Modifier, Arrangement.Vertical) -> Unit = { slot, arrangement ->
                            ReaderControls(
                                state = state,
                                onTogglePlay = onTogglePlay,
                                onWpmChange = onWpmChange,
                                onBackSentence = onBackSentence,
                                onForwardSentence = onForwardSentence,
                                onBackParagraph = onBackParagraph,
                                onForwardParagraph = onForwardParagraph,
                                onScrub = onScrub,
                                progressShown = progressShown,
                                onOpenChapters = { chapterPickerOpen = true },
                                modifier = slot,
                                verticalArrangement = arrangement,
                            )
                        }
                        if (layout.wide && !chromeHidden) {
                            WideReadingLayout(layout, surface, controls, Modifier.weight(1f))
                        } else {
                            surface(Modifier.weight(1f))
                            if (!chromeHidden) controls(Modifier.fillMaxWidth(), Arrangement.Top)
                        }
                    }
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
 * REQ-205: the stream and its controls side by side.
 *
 * The stream takes the width that is left rather than a share of its own, so the
 * reading area grows with the screen while the controls stay the size a hand can
 * work — a 5 mm-per-step slider on a 10" tablet would be worse, not better.
 *
 * The column is [androidx.compose.foundation.verticalScroll]ed and centred: at the
 * largest font size in landscape the five stacked controls can be taller than the
 * 411 dp the reading area has, and scrolling is the difference between a long
 * column and a speed slider that is not on the screen at all (REQ-301).
 *
 * The divider is the one thing here that is not already on the narrow screen. It
 * is a hairline of `outlineVariant` and carries no semantics, so it adds nothing
 * for TalkBack to read and nothing that changes brightness (REQ-302).
 */
@Composable
private fun ColumnScope.WideReadingLayout(
    layout: LayoutWidth,
    surface: @Composable (Modifier) -> Unit,
    controls: @Composable (Modifier, Arrangement.Vertical) -> Unit,
    modifier: Modifier,
) {
    Row(modifier = modifier.fillMaxWidth().testTag("reader_controls_beside")) {
        surface(Modifier.weight(1f).fillMaxHeight())
        VerticalDivider()
        controls(
            Modifier
                .width(readerControlsWidth(layout.available))
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            Arrangement.Center,
        )
    }
}

/**
 * How wide the control column is, from how wide the window is.
 *
 * A fraction with both ends pinned, because neither a fraction nor a fixed width
 * survives the whole range on its own:
 *
 * - at the 600 dp boundary the fraction alone would leave 252 dp, and the
 *   transport row's five 48 dp targets plus its padding need 272 dp — so
 *   [MinControlsWidth] is the width that keeps REQ-301's targets whole on the
 *   narrowest wide screen there is;
 * - on a 10" tablet the fraction alone would spend 538 dp on a slider, so
 *   [MaxControlsWidth] hands the rest back to the stream.
 */
private fun readerControlsWidth(available: Dp): Dp =
    (available * CONTROLS_WIDTH_FRACTION).coerceIn(MinControlsWidth, MaxControlsWidth)

private const val CONTROLS_WIDTH_FRACTION = 0.42f

/** Five 48 dp targets, their arrangement, and the column's own 16 dp padding. */
private val MinControlsWidth = 280.dp

private val MaxControlsWidth = 420.dp

/**
 * The book-open loading state. LEAF201 parses off the main thread and reports one
 * step per spine item, so this is determinate as soon as the spine is known.
 */
@Composable
private fun OpeningBook(state: ReaderUiState.Opening, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.XXLarge)
            .testTag("reader_opening"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.reader_opening, state.bookTitle),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Large))
        val fraction = state.fraction
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Spacing.Small))
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
            .padding(horizontal = Spacing.XXLarge)
            .testTag("reader_unavailable"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.reader_unavailable_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.Small))
        Text(
            text = stringResource(state.reason.messageRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
