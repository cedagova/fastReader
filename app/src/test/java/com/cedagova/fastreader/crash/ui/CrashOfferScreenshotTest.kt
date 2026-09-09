package com.cedagova.fastreader.crash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The offer's UI regression gate (REQ-207).
 *
 * The words are the feature here. What the reader is agreeing to — that nothing
 * has been sent yet, that deleting is final, and exactly which four things the
 * report holds — lives entirely in this dialog's copy, and a later edit that
 * softened one of those sentences would leave every other test green.
 *
 * The compact render is the one that can actually break: three paragraphs and
 * two long button labels on a 360 dp screen at a large font scale is where a
 * dialog clips or pushes its buttons off the bottom (REQ-301).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class CrashOfferScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theOfferSaysWhatIsInTheReportAndWhatEachAnswerDoes() {
        capture("crash_offer")
    }

    /** Cramped 720p phone (`Phone_Low_API33`) at a large system font scale. */
    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
    fun theOfferSurvivesACrampedScreenAtALargeFontScale() {
        capture("crash_offer_compact_large_font", fontScale = 1.3f)
    }

    private fun capture(name: String, fontScale: Float = 1f) {
        composeRule.setContent {
            ScaledFonts(fontScale) {
                FastReaderTheme {
                    CrashReportOffer(onShare = {}, onDelete = {})
                }
            }
        }
        // The offer is its own window, so the compose root is not unique; the
        // screen capture is the only one that contains the dialog.
        captureScreenRoboImage("screenshots/$name.png")
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
