package com.cedagova.fastreader.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.settings.FontSize
import com.cedagova.fastreader.settings.PivotColor
import com.cedagova.fastreader.settings.ReaderSettings
import com.cedagova.fastreader.settings.ThemeChoice
import com.cedagova.fastreader.timing.PauseStrength
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The settings surface's UI regression gate (REQ-020, REQ-022, REQ-023, REQ-061).
 *
 * These images are the primary proof of the claims that are *about* what is drawn:
 * that the live preview renders the cue settings above it, that the surface opens
 * with a centred word and the fixed focus letter off (#32), that turning the
 * highlight off leaves a plain word and takes its palette away with it, that
 * turning the fixed focus letter on moves the previewed word off centre, that a
 * colour from the palette is really applied, that the rhythm readout states what
 * the chosen pause strength does, and that the visual-only statement is on the
 * page in plain language rather than behind something.
 *
 * The preview is held on one token here ([PREVIEW_HELD_TOKEN]); in the app it
 * streams. A still frame is what a golden can be, and the streaming itself is
 * proven on the emulator, where a moving word is something that can actually be
 * watched.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TALL_PHONE)
class SettingsScreenScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The screen as a reader first meets it: every default, the preview showing
     * the cues the app ships with, and the REQ-061 statement at the end.
     */
    @Test
    fun theWholeSurfaceAtItsDefaults() {
        capture("settings_default", ReaderSettings.DEFAULTS)
    }

    @Test
    fun theSameSurfaceOnADarkPage() {
        capture("settings_dark", ReaderSettings.DEFAULTS, darkTheme = true)
    }

    /**
     * REQ-020's "disabling the cue shows plain centered words", seen in the
     * preview, and with it the palette gone — a colour control that colours
     * nothing would be a lie about what the setting does.
     *
     * Pause strength is `off` in the same image, so the rhythm readout states
     * REQ-011's uniform-duration acceptance in milliseconds.
     */
    @Test
    fun turningTheCuesOffLeavesAPlainWordAndTakesThePaletteAway() {
        capture(
            "settings_cues_off",
            ReaderSettings.DEFAULTS.copy(
                highlightEnabled = false,
                guideMarksEnabled = false,
                pauseStrength = PauseStrength.OFF,
            ),
        )
    }

    /**
     * #32's opt-in state, in the surface that owns it: both cue switches on, and
     * the previewed word shifted off centre with the caret under it. Against
     * `settings_default` this is the whole of what the toggle changes, and it is
     * the live-preview half of the acceptance — the preview reflects *both*
     * toggles, not just the highlight.
     */
    @Test
    fun turningTheFixedFocusLetterOnMovesThePreviewedWordOffCentre() {
        capture(
            "settings_focus_alignment_on",
            ReaderSettings.DEFAULTS.copy(focusAlignmentEnabled = true),
        )
    }

    /**
     * REQ-020's "color change applies immediately": a palette entry other than the
     * default, applied to the highlighted letter in the preview, with the guide
     * marks off so the word is the only thing the change can be read from.
     */
    @Test
    fun aColourFromThePaletteIsAppliedToTheHighlightedLetter() {
        capture(
            "settings_highlight_crimson",
            ReaderSettings.DEFAULTS.copy(
                pivotColor = PivotColor.CRIMSON,
                guideMarksEnabled = false,
                pauseStrength = PauseStrength.STRONG,
            ),
        )
    }

    /**
     * REQ-022's font size, applied to the settings screen by the same theme that
     * applies it to the reader and the library — including to the previewed word,
     * which is what tells a reader what they are choosing before they leave.
     */
    @Test
    @Config(sdk = [35], qualifiers = TALLER_PHONE)
    fun theLargestTextSizeAppliesToThisScreenToo() {
        capture(
            "settings_font_extra_large",
            ReaderSettings.DEFAULTS.copy(fontSize = FontSize.EXTRA_LARGE, theme = ThemeChoice.DARK),
            darkTheme = true,
        )
    }

    /**
     * REQ-060's layout half, at the largest text this app can be asked for: the
     * 720p phone in the AVD matrix, the device font scale at 2.0 *and* the app's
     * own size at its largest. The option rows have to wrap rather than clip, the
     * preview has to grow with the text rather than draw over the page, and the
     * combined scale has to stop at the app bar's ceiling — without the theme's
     * cap this renders at 3.0 and the title loses its descenders.
     */
    @Test
    @Config(sdk = [35], qualifiers = COMPACT_PHONE)
    fun theLargestTextThisAppCanBeAskedForStillFitsACrampedScreen() {
        capture(
            "settings_compact_large_font",
            ReaderSettings.DEFAULTS.copy(fontSize = FontSize.EXTRA_LARGE),
            fontScale = 2f,
        )
    }

    private fun capture(
        name: String,
        settings: ReaderSettings,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            ScaledFonts(fontScale) {
                FastReaderTheme(darkTheme = darkTheme, fontSize = settings.fontSize) {
                    SettingsScreen(
                        settings = settings,
                        onSettingsChange = {},
                        onReset = {},
                        onBack = {},
                        heldPreviewToken = PREVIEW_HELD_TOKEN,
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("screenshots/$name.png")
    }

    /** The device's own font scale, which the app's [FontSize] multiplies rather than replaces. */
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

/**
 * The reference phone's width on a window tall enough to hold the whole settings
 * surface at once.
 *
 * The screen scrolls on a real phone, and a golden of its first viewport would
 * leave the REQ-061 statement and the reset control — both of which these images
 * exist to prove — outside every capture. The emulator pass shows what the real
 * viewport looks like and that the rest is reachable by scrolling.
 */
private const val TALL_PHONE = "w411dp-h1400dp-xxhdpi"

/** The same window, tall enough to hold the whole surface at the largest text size. */
private const val TALLER_PHONE = "w411dp-h1800dp-xxhdpi"

/** 720p, 2 GB phone, matching the `Phone_Low_API33` AVD used for cramped layouts. */
private const val COMPACT_PHONE = "w360dp-h640dp-xhdpi"
