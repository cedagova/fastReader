package com.cedagova.fastreader.reader.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.reader.ReaderFixtures
import com.cedagova.fastreader.reader.ReaderSession
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The WPM readout is the door off the slider's 25-step grid: pressing it swaps
 * in a number field, and only a whole number inside the timing range reaches
 * `onWpmChange` — exactly as typed, never rounded.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class ReaderSpeedEntryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val book = ReaderFixtures.englishNovel
    private val playing = ReaderBookView("The Quiet Machine", book)
        .present(ReaderSession(book).jumpTo(12).play())

    private val speeds = mutableListOf<Int>()

    @Test
    fun `an off-grid speed is set exactly as typed`() {
        show()

        composeRule.onNodeWithTag("reader_speed_readout").performClick()
        settle()
        composeRule.onNodeWithTag("reader_speed_entry").assertIsDisplayed()
        composeRule.onNodeWithTag("reader_speed_entry").performTextClearance()
        composeRule.onNodeWithTag("reader_speed_entry").performTextInput("333")
        settle()
        composeRule.onNodeWithTag("reader_speed_entry").performImeAction()
        settle()

        assertEquals(listOf(333), speeds)
        composeRule.onNodeWithTag("reader_speed_entry").assertDoesNotExist()
        composeRule.onNodeWithTag("reader_speed_readout").assertIsDisplayed()
    }

    @Test
    fun `a speed outside the range is never sent`() {
        show()

        composeRule.onNodeWithTag("reader_speed_readout").performClick()
        settle()
        composeRule.onNodeWithTag("reader_speed_entry").performTextClearance()
        composeRule.onNodeWithTag("reader_speed_entry").performTextInput("5000")
        settle()
        composeRule.onNodeWithTag("reader_speed_entry").performImeAction()
        settle()

        assertEquals(emptyList<Int>(), speeds)
        composeRule.onNodeWithTag("reader_speed_entry").assertIsDisplayed()
    }

    /**
     * The field's cursor blinks for as long as it has focus, so with the clock on
     * auto-advance `waitForIdle` never returns. The clock is paused and stepped by
     * hand instead — see [settle].
     */
    private fun show() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            FastReaderTheme {
                ReaderScreen(
                    state = playing,
                    onBack = {},
                    onTogglePlay = {},
                    onWpmChange = { speeds += it },
                    onBackSentence = {},
                    onForwardSentence = {},
                    onBackParagraph = {},
                    onForwardParagraph = {},
                    onScrub = {},
                    onChapterSelected = {},
                    focused = false,
                    onToggleFocused = {},
                    speedNotice = null,
                    onSpeedStep = {},
                )
            }
        }
        settle()
    }

    /**
     * With the clock paused a state change is only composed on the next sync, and
     * what that composes only gets bounds on a later frame — so: sync, frames, sync.
     */
    private fun settle() {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
    }
}
