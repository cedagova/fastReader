package com.cedagova.fastreader.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.content.ContentFailureReason
import com.cedagova.fastreader.reader.ReaderFixtures
import com.cedagova.fastreader.reader.ReaderSession
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

    private fun playingAt(index: Int) = view.present(ReaderSession(book).jumpTo(index).play())

    private fun pausedAt(index: Int) = view.present(ReaderSession(book).jumpTo(index))

    private fun capture(
        name: String,
        state: ReaderUiState,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            ScaledFonts(fontScale) {
                FastReaderTheme(darkTheme = darkTheme) {
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

/** 1080p reference phone, matching the `Phone_Mid_API36` AVD used for the emulator pass. */
private const val REFERENCE_PHONE = "w411dp-h914dp-xxhdpi"

/** 720p, 2 GB phone, matching the `Phone_Low_API33` AVD used for cramped layouts. */
private const val COMPACT_PHONE = "w360dp-h640dp-xhdpi"

/** The reference phone turned on its side: the shortest reading area the app has to fit. */
private const val LANDSCAPE_PHONE = "w914dp-h411dp-land-xxhdpi"
