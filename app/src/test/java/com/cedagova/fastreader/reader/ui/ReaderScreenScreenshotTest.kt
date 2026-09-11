package com.cedagova.fastreader.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.content.ContentFailureReason
import com.cedagova.fastreader.content.WordToken
import com.cedagova.fastreader.reader.ReaderFixtures
import com.cedagova.fastreader.reader.ReaderSession
import com.cedagova.fastreader.settings.CueSettings
import com.cedagova.fastreader.settings.FontSize
import com.cedagova.fastreader.settings.ReaderSettings
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The reader's UI regression gate: one committed golden per state the screen can
 * reach. `recordRoborazziDebug` refreshes them, `verifyRoborazziDebug` diffs.
 *
 * These renders are the primary proof that each state has its own layout and copy
 * — the paused paragraph with the word marked, the streamed word, the chapter
 * pause, the end of the book, a skip marker — and that the layout
 * survives a cramped screen at a large font scale. What they cannot prove is
 * anything about time: playback smoothness, the screen staying awake, and the
 * foreground-loss pause are all measured on the emulator, because a still image
 * of a correct frame says nothing about the frame after it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = REFERENCE_PHONE)
class ReaderScreenScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val book = ReaderFixtures.englishNovel
    private val view = ReaderBookView(BOOK_TITLE, book)

    @Test
    fun openingALargeBookShowsDeterminateProgress() {
        capture("reader_opening", ReaderUiState.Opening(BOOK_TITLE, fraction = 0.4f))
    }

    @Test
    fun aBookThatCannotBeReadExplainsWhyInPlainLanguage() {
        capture(
            "reader_unavailable",
            ReaderUiState.Unavailable(BOOK_TITLE, ContentFailureReason.CORRUPT_ARCHIVE),
        )
    }

    @Test
    fun theStreamShowsOneWordOnAStaticPage() {
        capture("reader_playing", playingAt(12))
    }

    @Test
    fun pausingShowsTheSurroundingParagraphWithTheWordMarked() {
        capture("reader_paused", pausedAt(12))
    }

    /**
     * "Always show paragraph" on: the stream runs with the paragraph under the
     * word, the mark on the word being shown, and no navigation — the transport
     * is still the running one.
     */
    @Test
    fun theParagraphStaysUnderTheRunningWordWhenAlwaysShown() {
        capture("reader_playing_paragraph", view.present(ReaderSession(book).jumpTo(12).play(), paragraphAlwaysShown = true))
    }

    // --- REQ-206, the Spanish interface --------------------------------------

    /**
     * REQ-206 on the reader: with the device in Spanish every label and readout
     * around the stream comes from `values-es`, and nothing falls back to
     * English.
     *
     * Paused rather than playing, because paused is the state that has every
     * string on screen at once — the chapter name, the progress, the time left,
     * the speed readout in its Spanish unit, and the six transport labels that
     * only TalkBack ever reads aloud. The book's own words are the fixture's and
     * stay as they are: translating the interface never touches a book.
     */
    @Test
    @Config(qualifiers = "+es")
    fun theReaderIsSpanishOnASpanishDevice() {
        capture("reader_spanish", pausedAt(12))
    }

    /**
     * REQ-206 on the notice a book handed over by another app carries. Its
     * sentence is the promise that nothing but the reading position is kept, so
     * it is the one notice whose Spanish has to be exactly as narrow as the
     * English.
     */
    @Test
    @Config(qualifiers = "+es")
    fun theExternalNoticeIsSpanishOnASpanishDevice() {
        capture("reader_external_notice_spanish", pausedAt(12), externalNotice = true)
    }

    /**
     * REQ-206 on #51's front-matter offer: two buttons and a sentence, where the
     * Spanish labels are the longer pair and the chapter title is the book's own.
     */
    @Test
    @Config(qualifiers = "+es")
    fun theFrontMatterOfferIsSpanishOnASpanishDevice() {
        capture(
            "reader_front_matter_offer_spanish",
            pausedAt(0),
            frontMatterOffer = "Chapter One: The Approach",
        )
    }

    @Test
    fun theSameParagraphInDarkTheme() {
        capture("reader_paused_dark", pausedAt(12), darkTheme = true)
    }

    @Test
    fun anImageInTheBookStreamsASkipMarker() {
        capture("reader_skip_marker", playingAt(17))
    }

    @Test
    fun crossingAChapterEndPausesOnATitledScreen() {
        capture("reader_chapter_pause", view.present(ReaderSession(book).jumpTo(4).play().advance()))
    }

    @Test
    fun finishingTheBookIsItsOwnState() {
        capture("reader_end", view.present(ReaderSession(book).jumpTo(40).play().advance()))
    }

    /**
     * The definition's persistence guardrail: a write failure is visible where the
     * reader is, and does not stop them reading.
     */
    @Test
    fun aStoreThatCannotSaveSaysSoWithoutStoppingTheReader() {
        capture(
            "reader_persistence_problem",
            pausedAt(12).copy(persistenceFailure = "There is no space left on the device."),
        )
    }

    /**
     * REQ-103's session-only state, and the one image REQ-107's copy claim rests
     * on: the reader is told, on the reading surface, that this book is not in
     * their library and that only their place in it is kept.
     */
    @Test
    fun aBookOpenedFromAnotherAppSaysWhatIsAndIsNotKept() {
        capture("reader_external_notice", pausedAt(12), externalNotice = true)
    }

    /**
     * The same notice where it is most likely to break: the smallest phone in the
     * matrix at a large system font scale, with the sentence and both buttons
     * still whole above a reading surface that still has room for the paragraph
     * (REQ-301).
     */
    @Test
    @Config(sdk = [35], qualifiers = COMPACT_PHONE)
    fun theNoticeFitsACrampedScreenAtALargeFontScale() {
        capture("reader_external_notice_compact", pausedAt(12), externalNotice = true, fontScale = 1.3f)
    }

    /**
     * REQ-202: a book that opens on its cover, with the one-time offer to start
     * at the first chapter.
     *
     * Captured on the book's very first token, which is the only position the
     * offer is ever shown at, and with the paused context view under it — so the
     * image shows the offer sitting *above* the reading surface rather than
     * inside it, which is what keeps the stream's fixed size and static
     * background intact (REQ-062, REQ-302, AD-6).
     */
    @Test
    fun aBookThatOpensOnItsCoverOffersTheFirstChapter() {
        capture("reader_front_matter_offer", pausedAt(0), frontMatterOffer = "Chapter One: The Approach")
    }

    /**
     * The same offer where it has to survive: 360 dp of width at a large font
     * scale, where a destination-naming label is the thing that wraps.
     */
    @Test
    @Config(sdk = [35], qualifiers = COMPACT_PHONE)
    fun theOfferFitsACrampedScreenAtALargeFontScale() {
        capture(
            "reader_front_matter_offer_compact",
            pausedAt(0),
            frontMatterOffer = "Chapter One: The Approach",
            fontScale = 1.3f,
        )
    }

    // --- The cue matrix ------------------------------------------------------
    //
    // One golden per cue combination the reader can be looking at, because a cue
    // is a *rendering*: whether the recognition letter is coloured and where the
    // word sits are claims only an image can settle. Playback is identical in all
    // of them — the same session, the same word — so any difference between two of
    // these images is the cue layer and nothing else.

    /**
     * #32's acceptance: **Fixed focus letter** on reproduces increment 003's
     * rendering exactly. Same golden, same bytes, from the two split flags that
     * replaced the one `pivotEnabled`.
     */
    @Test
    fun theFixedFocusLetterHoldsTheWordOnAColouredLetter() {
        capture("reader_cue_pivot", playingAt(12), cues = CueSettings.FOCUS_ALIGNED_ONLY)
    }

    /** REQ-020, highlight off: "disabling the cue shows plain centered words". */
    @Test
    fun turningTheHighlightOffLeavesAPlainCentredWord() {
        capture("reader_cue_off", playingAt(12), cues = CueSettings.NO_CUES)
    }

    /**
     * #32's default: the word centred on the screen axis with its recognition
     * letter coloured and the marks under the centre column. This is what the app
     * ships with, so it is what every other reader golden here shows.
     */
    @Test
    fun theDefaultPresentationCentresTheWordAndColoursOneLetter() {
        capture("reader_cue_guide_marks", playingAt(12), cues = CueSettings.DEFAULTS)
    }

    @Test
    fun theSameCuesOnADarkPage() {
        capture("reader_cue_guide_marks_dark", playingAt(12), cues = CueSettings.DEFAULTS, darkTheme = true)
    }

    /**
     * The opt-in state with the marks on: the word off centre, the caret moved to
     * the column with it. Side by side with `reader_cue_guide_marks` this is the
     * whole of what the **Fixed focus letter** toggle does.
     */
    @Test
    fun theFixedFocusLetterMovesTheWordAndTheCaretTogether() {
        capture("reader_cue_focus_alignment", playingAt(12), cues = CueSettings.ALL_CUES)
    }

    /** REQ-030: nothing on screen but the word and the cues left enabled. */
    @Test
    fun focusedModeLeavesOnlyTheStream() {
        capture("reader_focused", playingAt(12), cues = CueSettings.DEFAULTS, focused = true)
    }

    /**
     * REQ-030's other half: "a tap pauses with context". Focused mode hides the
     * chrome, not the paused paragraph — the reader still has to be able to pick
     * the thread back up without leaving the mode.
     */
    @Test
    fun aTapInFocusedModeStillShowsTheParagraph() {
        capture("reader_focused_paused", pausedAt(12), cues = CueSettings.DEFAULTS, focused = true)
    }

    /**
     * Overflow at the shipped default: a real 19-letter word from the Spanish
     * fixture, on the smallest phone in the matrix, at twice the system font
     * scale. It has to shrink, not truncate and not wrap, and stay centred.
     */
    @Test
    @Config(sdk = [35], qualifiers = COMPACT_PHONE)
    fun aLongWordAtTwiceTheFontScaleShrinksToFit() {
        capture(
            "reader_cue_overflow",
            overflowState(),
            cues = CueSettings.DEFAULTS,
            fontScale = 2f,
        )
    }

    /**
     * The same word with the fixed focus letter on, which is the stricter of the
     * two fits: the part left of the recognition letter has to fit left of the
     * column and the part right of it right of the column, because the column
     * does not move. Byte-identical to increment 003's overflow golden.
     */
    @Test
    @Config(sdk = [35], qualifiers = COMPACT_PHONE)
    fun aLongWordStillFitsAroundTheFixedColumn() {
        capture(
            "reader_cue_overflow_aligned",
            overflowState(),
            cues = CueSettings.ALL_CUES,
            fontScale = 2f,
        )
    }

    private fun overflowState(): ReaderUiState {
        val spanish = ReaderFixtures.spanishNovel
        val longWord = spanish.tokens.first { it is WordToken && it.text == "extraordinariamente" }.index
        return ReaderBookView(SPANISH_TITLE, spanish)
            .present(ReaderSession(spanish).jumpTo(longWord).play())
    }

    /**
     * A paragraph longer than the token window (REQ-010), paused deep enough in
     * that the window is cut at both ends: the marked word sits in the middle of
     * the shown lines, not below them. Landscape leaves the fewest lines under
     * the word, so the mark is the first thing to fall below the fold there.
     */
    @Test
    @Config(sdk = [35], qualifiers = LANDSCAPE_PHONE)
    fun theShownLinesFollowTheWordDownALongParagraphInLandscape() {
        capture("reader_paused_long_paragraph_landscape", longParagraphAt(60))
    }

    /**
     * The shown lines follow the mark. With the stream running under "Always show
     * paragraph" on the cramped phone at a large font scale, the window is taller
     * than the area under the word, and the mark is past the lines that fit. The
     * view scrolls to the section of whole lines holding the word — cut from the
     * top of the window, so the reader sees the lines turn rather than creep — and
     * the mark is on screen.
     */
    @Test
    @Config(sdk = [35], qualifiers = COMPACT_PHONE)
    fun theShownLinesFollowTheRunningWordDownALongParagraphAtALargeFontScale() {
        capture("reader_playing_long_paragraph_compact_large_font", longParagraphAt(120, playing = true), fontScale = 1.3f)
    }

    private fun longParagraphAt(index: Int, playing: Boolean = false): ReaderUiState.Reading {
        val long = ReaderFixtures.longParagraph
        val session = ReaderSession(long).jumpTo(index).let { if (playing) it.play() else it }
        return ReaderBookView(LONG_PARAGRAPH_TITLE, long).present(session, paragraphAlwaysShown = playing)
    }

    /** Cramped 720p phone (`Phone_Low_API33`) at a large system font scale (REQ-060). */
    @Test
    @Config(sdk = [35], qualifiers = COMPACT_PHONE)
    fun theReaderSurvivesACrampedScreenAtALargeFontScale() {
        capture("reader_compact_large_font", pausedAt(12), fontScale = 1.3f)
    }

    /**
     * Landscape leaves the reading area a fraction of the height, which is where an
     * emulator pass caught the word being cut in half. This is that regression's
     * cheap gate.
     */
    @Test
    @Config(sdk = [35], qualifiers = LANDSCAPE_PHONE)
    fun theWordFitsTheShortReadingAreaInLandscape() {
        capture("reader_landscape", playingAt(12))
    }

    /**
     * REQ-022: the text-size setting reaches the reader — the streamed word, the
     * paused paragraph and every control together, not one of them alone. Same
     * session as `reader_paused`; only the setting differs.
     *
     * Both halves of that setting are applied the way [ReaderRoute] applies them:
     * the theme carries the chrome, and the cue value carries the word, because
     * Android's font-scale curve is flat at the word's size and would otherwise
     * leave it exactly as it was.
     */
    @Test
    fun theTextSizeSettingAppliesToTheReader() {
        val largest = ReaderSettings.DEFAULTS.copy(fontSize = FontSize.EXTRA_LARGE, wordSize = FontSize.EXTRA_LARGE)
        capture("reader_font_extra_large", pausedAt(12), cues = largest.cues, fontSize = largest.fontSize)
    }

    // --- REQ-108, the focused-mode speed gesture -----------------------------
    //
    // The gesture is a device claim; its *readout* is a rendering one. These two
    // settle the only questions an image can settle about it: that the line is
    // text and nothing else — no card, no scrim, no second brightness on a page
    // REQ-302 requires to be static — and that it sits clear of the word, which
    // must not move when it appears.

    /**
     * The state a drag leaves behind: the new speed, over an otherwise untouched
     * focused surface. Compare with `reader_focused`, which is the same session
     * and the same word with no notice — the only difference between the two
     * images is the line at the bottom.
     */
    @Test
    fun theSpeedGestureLeavesTheNewSpeedOnTheScreen() {
        capture(
            "reader_focused_speed_readout",
            view.present(ReaderSession(book).jumpTo(12).play().withWpm(500)),
            cues = CueSettings.DEFAULTS,
            focused = true,
            speedNotice = "500 WPM",
        )
    }

    /**
     * The same slot carrying the hint that names the gesture on entering focused
     * mode — the longest copy it ever holds, so this is also where its wrapping is
     * checked.
     */
    @Test
    fun enteringFocusedModeNamesTheGesture() {
        capture(
            "reader_focused_speed_hint",
            playingAt(12),
            cues = CueSettings.DEFAULTS,
            focused = true,
            speedNotice = "Drag up or down to change speed",
        )
    }

    /**
     * REQ-301's font-scale clause for the new copy, at its worst case: the longest
     * of the two lines, on the 720p phone (`Phone_Low_API33`), at a 2.0 system
     * font scale. The notice is the only new thing on the page that has text to
     * lose, and this is where it would lose it.
     */
    @Test
    @Config(sdk = [35], qualifiers = COMPACT_PHONE)
    fun theGestureHintSurvivesACrampedScreenAtTwiceTheFontScale() {
        capture(
            "reader_focused_speed_hint_compact_large_font",
            playingAt(12),
            fontScale = 2f,
            cues = CueSettings.DEFAULTS,
            focused = true,
            speedNotice = "Drag up or down to change speed",
        )
    }

    // --- REQ-205, controls beside the stream ---------------------------------
    //
    // The requirement's own words are "no control below the stream": that is a
    // claim about an image and nothing else, so these are the images. The three
    // widths are the boundary device, the same rule reached by turning a phone on
    // its side, and a 10" tablet, and the acceptance's largest font size is
    // applied the way [ReaderRoute] applies it — through the theme *and* the cue
    // value, because the word's own size does not move on Android's font curve.

    /**
     * The boundary: `Tablet_Low_API33` is exactly 600 dp wide, and this is what it
     * renders. Chapter, progress, scrubber, transport and speed are all to the
     * right of the stream; nothing is under it.
     */
    @Test
    @Config(sdk = [35], qualifiers = TABLET_BOUNDARY)
    fun atExactlySixHundredDpTheControlsAreBesideTheStream() {
        capture("reader_tablet", pausedAt(12))
    }

    /**
     * The same 600 dp at the largest font size — the narrowest control column the
     * app ever draws, carrying the largest text it ever puts in it. This is the
     * one image that settles both halves of the acceptance at once: no control
     * below the stream, and no label cut off inside the column.
     */
    @Test
    @Config(sdk = [35], qualifiers = TABLET_BOUNDARY)
    fun theTabletControlColumnIsWholeAtTheLargestFontSize() {
        largeFontCapture("reader_tablet_large_font", pausedAt(12))
    }

    /** The reference phone on its side at the largest font size. */
    @Test
    @Config(sdk = [35], qualifiers = LANDSCAPE_PHONE)
    fun theLandscapeControlColumnIsWholeAtTheLargestFontSize() {
        largeFontCapture("reader_landscape_large_font", pausedAt(12))
    }

    /** `Tablet_Mid_API36`: the control column stops growing, the stream does not. */
    @Test
    @Config(sdk = [35], qualifiers = TABLET_LARGE)
    fun aTenInchTabletSpendsItsExtraWidthOnTheStream() {
        largeFontCapture("reader_tablet_large", playingAt(12))
    }

    /**
     * REQ-030 is unchanged by REQ-205: focused mode has no control column, so the
     * wide branch is skipped and the tablet shows the same full-bleed stream a
     * phone does. Compare with `reader_tablet`, the same session at the same
     * width with the chrome on.
     */
    @Test
    @Config(sdk = [35], qualifiers = TABLET_BOUNDARY)
    fun focusedModeIsTheSameFullBleedStreamAtTabletWidth() {
        capture("reader_tablet_focused", playingAt(12), cues = CueSettings.DEFAULTS, focused = true)
    }

    private fun largeFontCapture(name: String, state: ReaderUiState) {
        val largest = ReaderSettings.DEFAULTS.copy(fontSize = FontSize.EXTRA_LARGE, wordSize = FontSize.EXTRA_LARGE)
        capture(name, state, cues = largest.cues, fontSize = largest.fontSize)
    }

    private fun playingAt(index: Int) = view.present(ReaderSession(book).jumpTo(index).play())

    private fun pausedAt(index: Int) = view.present(ReaderSession(book).jumpTo(index))

    private fun capture(
        name: String,
        state: ReaderUiState,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        cues: CueSettings = CueSettings(),
        focused: Boolean = false,
        fontSize: FontSize = FontSize.MEDIUM,
        externalNotice: Boolean = false,
        speedNotice: String? = null,
        frontMatterOffer: String? = null,
    ) {
        composeRule.setContent {
            ScaledFonts(fontScale) {
                FastReaderTheme(darkTheme = darkTheme, fontSize = fontSize) {
                    ReaderScreen(
                        state = state,
                        onBack = {},
                        onTogglePlay = {},
                        onWpmChange = {},
                        onBackSentence = {},
                        onForwardSentence = {},
                        onBackParagraph = {},
                        onForwardParagraph = {},
                        onScrub = {},
                        onChapterSelected = {},
                        cues = cues,
                        focused = focused,
                        externalNotice = externalNotice,
                        speedNotice = speedNotice,
                        frontMatterOffer = frontMatterOffer,
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Composable
    private fun ScaledFonts(fontScale: Float, content: @Composable () -> Unit) {
        if (fontScale == 1f) {
            content()
        } else {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                content = content,
            )
        }
    }
}

private const val BOOK_TITLE = "The Quiet Machine"

private const val SPANISH_TITLE = "¿Quién teme a la máquina?"

private const val LONG_PARAGRAPH_TITLE = "The Eleventh Day"

/** 1080p reference phone, matching the `Phone_Mid_API36` AVD used for the emulator pass. */
private const val REFERENCE_PHONE = "w411dp-h914dp-xxhdpi"

/** 720p, 2 GB phone, matching the `Phone_Low_API33` AVD used for cramped layouts. */
private const val COMPACT_PHONE = "w360dp-h640dp-xhdpi"

/** The reference phone turned on its side: the shortest reading area the app has to fit. */
private const val LANDSCAPE_PHONE = "w914dp-h411dp-land-xxhdpi"

/**
 * The `sw600dp` boundary itself, matching the `Tablet_Low_API33` AVD, which is
 * exactly 600 dp wide. A breakpoint that is wrong by one dp is wrong only here.
 */
private const val TABLET_BOUNDARY = "w600dp-h960dp-xhdpi"

/** 10" tablet at 2560 x 1600, matching the `Tablet_Mid_API36` AVD: 1280 x 800 dp. */
private const val TABLET_LARGE = "w1280dp-h800dp-land-xhdpi"
