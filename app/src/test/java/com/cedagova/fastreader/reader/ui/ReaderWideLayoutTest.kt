package com.cedagova.fastreader.reader.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.reader.ReaderFixtures
import com.cedagova.fastreader.reader.ReaderSession
import com.cedagova.fastreader.settings.CueSettings
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Which reader layout each width composes, and what focused mode does to that
 * choice (REQ-205, REQ-030).
 *
 * The goldens are what prove the wide layout *looks* right; these prove it is the
 * one being chosen, at the boundary itself and one dp under it, without a golden
 * per case. The interesting one is the last pair: focused mode has no control
 * column, so the wide branch must not be taken at any width, or a tablet would
 * draw a divider down an otherwise empty page.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ReaderWideLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w600dp-h960dp-xhdpi")
    fun atExactlySixHundredDpTheControlsAreBesideTheStream() {
        render()
        composeRule.onNodeWithTag("reader_controls_beside").assertExists()
        composeRule.onNodeWithTag("reader_controls").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w599dp-h960dp-xhdpi")
    fun oneDpBelowTheBoundaryTheControlsAreStillUnderTheStream() {
        render()
        composeRule.onNodeWithTag("reader_controls_beside").assertDoesNotExist()
        composeRule.onNodeWithTag("reader_controls").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
    fun aPhoneOnItsSideAlsoPutsTheControlsBesideTheStream() {
        render()
        composeRule.onNodeWithTag("reader_controls_beside").assertExists()
    }

    /**
     * REQ-030 outranks REQ-205: with the chrome hidden there is no control column
     * to place, so a tablet composes the same lone stream a phone does — and the
     * surface still carries the focused-mode speed gesture's node (REQ-108).
     */
    @Test
    @Config(qualifiers = "w600dp-h960dp-xhdpi")
    fun focusedModeIsNotSplitAtTabletWidth() {
        render(focused = true)
        composeRule.onNodeWithTag("reader_controls_beside").assertDoesNotExist()
        composeRule.onNodeWithTag("reader_controls").assertDoesNotExist()
        composeRule.onNodeWithTag("reader_surface_focused").assertIsDisplayed()
    }

    private fun render(focused: Boolean = false) {
        val book = ReaderFixtures.englishNovel
        // Paused: a running stream has no control column to place at any width.
        val state = ReaderBookView("The Quiet Machine", book).present(ReaderSession(book).jumpTo(12))
        composeRule.setContent {
            FastReaderTheme {
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
                    cues = CueSettings.DEFAULTS,
                    focused = focused,
                )
            }
        }
    }
}
