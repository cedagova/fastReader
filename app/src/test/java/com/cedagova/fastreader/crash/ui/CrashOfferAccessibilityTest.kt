package com.cedagova.fastreader.crash.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * REQ-301 for the crash-report offer: every control it adds is labelled, big
 * enough to hit, and there is a way out of the dialog.
 *
 * A golden cannot catch what this catches. A button can render perfectly and
 * still reach a screen reader as an actionable node with nothing to announce,
 * and this is a dialog whose two answers do opposite and irreversible things —
 * "button" and "button" would be an unusable way to be asked.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class CrashOfferAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun showOffer(shareUnavailable: Boolean = false) {
        composeRule.setContent {
            FastReaderTheme {
                CrashReportOffer(
                    onShare = {},
                    onDelete = {},
                    shareUnavailable = shareUnavailable,
                )
            }
        }
    }

    @Test
    fun `every control the offer adds says what it is`() {
        showOffer()

        val unlabelled = composeRule.actionableNodes().filter { it.label().isBlank() }

        assertEquals(
            "actionable nodes with no label, at ${unlabelled.map { it.boundsInRoot }}",
            emptyList<String>(),
            unlabelled.map { it.config.toString() },
        )
    }

    /** Not "OK" and "Cancel": each answer names what it does to the report. */
    @Test
    fun `both answers name the report and which way they go`() {
        showOffer()

        val labels = composeRule.actionableNodes().map { it.label() }

        assertTrue("no control offers the share, only $labels", labels.contains("Share report"))
        assertTrue("no control offers the refusal, only $labels", labels.contains("Delete report"))
    }

    @Test
    fun `every control is at least forty-eight density-independent pixels tall`() {
        showOffer()

        val minimum = with(composeRule.density) { 48.dp.toPx() }
        val short = composeRule.actionableNodes().filter { it.boundsInRoot.height < minimum - 1f }

        assertEquals(
            "controls shorter than 48 dp: " + short.map { "${it.label()} @ ${it.boundsInRoot}" },
            0,
            short.size,
        )
    }

    /** The fallback line is text a screen reader has to reach, not a red flash. */
    @Test
    fun `a device that cannot accept the text says so in words`() {
        showOffer(shareUnavailable = true)

        val texts = composeRule.allNodes().flatMap { node ->
            node.config.getOrElseNullable(SemanticsProperties.Text) { null }
                ?.map { it.text }
                .orEmpty()
        }

        assertTrue(
            "the offer does not say why nothing happened, only $texts",
            texts.any { it.startsWith("No app on this device can accept the report") },
        )
    }
}

/**
 * Every root, not just the first: the offer is its own window, so a sweep that
 * only walks the screen behind it finds none of its controls.
 */
private fun ComposeContentTestRule.actionableNodes(): List<SemanticsNode> =
    allNodes().filter { it.config.contains(SemanticsActions.OnClick) }

private fun ComposeContentTestRule.allNodes(): List<SemanticsNode> {
    val out = mutableListOf<SemanticsNode>()
    fun walk(node: SemanticsNode) {
        out += node
        node.children.forEach(::walk)
    }
    onAllNodes(isRoot()).fetchSemanticsNodes().forEach(::walk)
    return out
}

private fun SemanticsNode.label(): String {
    val described = config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }
    if (!described.isNullOrEmpty()) return described.joinToString(" ").trim()
    val text = config.getOrElseNullable(SemanticsProperties.Text) { null }
    return text?.joinToString(" ") { it.text }?.trim().orEmpty()
}
