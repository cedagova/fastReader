package com.cedagova.fastreader.settings.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.settings.ReaderSettings
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * REQ-060 for the settings surface: every control announces a sensible label and
 * meets Android's minimum touch target.
 *
 * This is a *tree* assertion rather than a list of per-control checks, because the
 * thing that goes wrong here is structural and invisible. A control can look
 * finished, render identically in every golden, and still reach the accessibility
 * tree as an actionable node with nothing to announce — which is what an
 * emulator `uiautomator` sweep of this screen was needed to establish about a
 * draft of it, and what no image could have shown.
 *
 * Walking the merged semantics tree — the same tree the accessibility service
 * reads — and requiring every actionable node to carry a label and a 48 dp target
 * makes the screen's REQ-060 claim one assertion that a control added later
 * cannot quietly escape.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h1400dp-xxhdpi")
class SettingsAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        composeRule.setContent {
            FastReaderTheme {
                SettingsScreen(
                    settings = ReaderSettings.DEFAULTS,
                    onSettingsChange = {},
                    onReset = {},
                    onBack = {},
                    heldPreviewToken = PREVIEW_HELD_TOKEN,
                )
            }
        }
    }

    @Test
    fun `every control a screen reader can act on says what it is`() {
        val unlabelled = actionableNodes().filter { it.label().isBlank() }

        assertEquals(
            "actionable nodes with no label, at ${unlabelled.map { it.boundsInRoot }}",
            emptyList<String>(),
            unlabelled.map { it.config.toString() },
        )
    }

    /** The controls themselves, so a rename of one is not silently a removal of it. */
    @Test
    fun `the labels are the settings a reader is choosing between`() {
        val labels = actionableNodes().map { it.label() }

        listOf(
            "Back",
            "Light", "Dark", "System",
            "Small", "Medium", "Large", "Largest",
            "Default", "Crimson", "Amber", "Teal", "Violet",
            "Off", "Subtle", "Normal", "Strong",
            "Reset to defaults",
        ).forEach { expected ->
            assertTrue("no control announces \"$expected\", only $labels", labels.contains(expected))
        }
        assertTrue(
            "the pivot switch should name what it toggles, got $labels",
            labels.any { it.startsWith("Pivot cue.") },
        )
        assertTrue(
            "the guide-marks switch should name what it toggles, got $labels",
            labels.any { it.startsWith("Guide marks.") },
        )
    }

    /** Android's accessibility minimum, in the dp this screen is laid out in. */
    @Test
    fun `every control is at least forty-eight density-independent pixels tall`() {
        val density = composeRule.density
        val minimum = with(density) { 48.dp.toPx() }
        val short = actionableNodes().filter { it.boundsInRoot.height < minimum - 1f }

        assertEquals(
            "controls shorter than 48 dp: " + short.map { "${it.label()} @ ${it.boundsInRoot}" },
            0,
            short.size,
        )
    }

    /** The live preview must not announce a word that changes four times a second. */
    @Test
    fun `the preview is one static label rather than the streaming word`() {
        val previewLabels = allNodes()
            .map { it.label() }
            .filter { it.startsWith("Preview of the word stream") }

        assertEquals(1, previewLabels.size)
        assertTrue(
            "the sample word must not be announced, got " + allNodes().map { it.label() },
            allNodes().none { it.label() == "appear" },
        )
    }

    private fun actionableNodes(): List<SemanticsNode> =
        allNodes().filter { it.config.contains(SemanticsActions.OnClick) }

    private fun allNodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
            node.children.forEach(::walk)
        }
        walk(composeRule.onRoot().fetchSemanticsNode())
        return out
    }

    private fun SemanticsNode.label(): String {
        val described = config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }
        if (!described.isNullOrEmpty()) return described.joinToString(" ").trim()
        val text = config.getOrElseNullable(SemanticsProperties.Text) { null }
        return text?.joinToString(" ") { it.text }?.trim().orEmpty()
    }
}
