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
 * pause, the end of the book, the speed hint, a skip marker — and that the layout
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

    @Test
    fun aboveFourFiftyWordsPerMinuteTheHintIsVisibleAndPlaybackContinues() {
        capture("reader_speed_hint", view.present(ReaderSession(book).jumpTo(12).play().withWpm(500)))
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
        val largest = ReaderSettings.DEFAULTS.copy(fontSize = FontSize.EXTRA_LARGE)
        capture("reader_font_extra_large", pausedAt(12), cues = largest.cues, fontSize = largest.fontSize)
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

/** 1080p reference phone, matching the `Phone_Mid_API36` AVD used for the emulator pass. */
private const val REFERENCE_PHONE = "w411dp-h914dp-xxhdpi"

/** 720p, 2 GB phone, matching the `Phone_Low_API33` AVD used for cramped layouts. */
private const val COMPACT_PHONE = "w360dp-h640dp-xhdpi"

/** The reference phone turned on its side: the shortest reading area the app has to fit. */
private const val LANDSCAPE_PHONE = "w914dp-h411dp-land-xxhdpi"
