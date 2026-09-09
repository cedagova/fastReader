package com.cedagova.fastreader.reader.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.reader.ReaderFixtures
import com.cedagova.fastreader.reader.ReaderSession
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * REQ-108's wiring: the drag reaches the callback, only in focused mode, and a
 * screen reader that cannot drag has the same two steps as named actions
 * (REQ-301).
 *
 * The goldens prove what the readout looks like and the emulator run proves the
 * gesture works under a real finger at speed. Neither proves *routing* — that the
 * surface's third gesture exists exactly where focused mode is and nowhere else,
 * and that it did not quietly replace the tap and the long press it shares a node
 * with. That is what this file is for, and it is the layer where a regression in
 * it would otherwise only show up on a device.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class ReaderSpeedGestureTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val book = ReaderFixtures.englishNovel
    private val playing = ReaderBookView("The Quiet Machine", book)
        .present(ReaderSession(book).jumpTo(12).play())

    private val steps = mutableListOf<Int>()
    private var taps = 0
    private var focusToggles = 0

    @Test
    fun `dragging up the focused surface asks for a faster speed`() {
        show(focused = true)

        composeRule.onNodeWithTag("reader_surface_focused").performTouchInput { swipeUp() }

        assertTrue("steps were $steps", steps.sum() > 0)
    }

    @Test
    fun `dragging down the focused surface asks for a slower speed`() {
        show(focused = true)

        composeRule.onNodeWithTag("reader_surface_focused").performTouchInput { swipeDown() }

        assertTrue("steps were $steps", steps.sum() < 0)
    }

    /** A drag is not a tap and not a long press; the two v1 gestures are untouched. */
    @Test
    fun `a drag does not also play, pause, or leave focused mode`() {
        show(focused = true)

        composeRule.onNodeWithTag("reader_surface_focused").performTouchInput { swipeUp() }

        assertEquals(0, taps)
        assertEquals(0, focusToggles)
    }

    /** Out of scope for this issue: with the chrome up, the slider is the speed control. */
    @Test
    fun `dragging the unfocused surface changes nothing`() {
        show(focused = false)

        composeRule.onNodeWithTag("reader_surface").performTouchInput { swipeUp() }

        assertEquals(emptyList<Int>(), steps)
    }

    @Test
    fun `a screen reader gets the same two steps as named actions`() {
        show(focused = true)

        val actions = composeRule.onNodeWithTag("reader_surface_focused")
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]

        assertEquals(listOf("Increase reading speed", "Decrease reading speed"), actions.map { it.label })

        actions.first { it.label == "Increase reading speed" }.action?.invoke()
        actions.first { it.label == "Decrease reading speed" }.action?.invoke()

        assertEquals(listOf(1, -1), steps)
    }

    /** Nothing offers a speed action where the slider already is. */
    @Test
    fun `the unfocused surface has no speed actions`() {
        show(focused = false)

        val actions = composeRule.onNodeWithTag("reader_surface")
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.CustomActions)

        assertNull(actions)
    }

    @Test
    fun `the readout is the text the caller passes and nothing else`() {
        show(focused = true, notice = "500 WPM")

        composeRule.onNodeWithTag("reader_speed_notice").assertIsDisplayed()
        composeRule.onNodeWithText("500 WPM").assertIsDisplayed()
    }

    private fun show(focused: Boolean, notice: String? = null) {
        composeRule.setContent {
            FastReaderTheme {
                ReaderScreen(
                    state = playing,
                    onBack = {},
                    onTogglePlay = { taps += 1 },
                    onWpmChange = {},
                    onBackSentence = {},
                    onForwardSentence = {},
                    onBackParagraph = {},
                    onForwardParagraph = {},
                    onScrub = {},
                    onChapterSelected = {},
                    focused = focused,
                    onToggleFocused = { focusToggles += 1 },
                    speedNotice = notice,
                    onSpeedStep = { steps += it },
                )
            }
        }
    }
}
