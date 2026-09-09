package com.cedagova.fastreader.reader.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.reader.ReaderFixtures
import com.cedagova.fastreader.reader.ReaderSession
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * REQ-301 for the one surface this leaf adds, and REQ-107's copy claim with it.
 *
 * The golden proves the notice looks right; this proves it *works* for someone who
 * cannot see it. Those are different failures: a button can render perfectly and
 * still reach the accessibility tree as an unlabelled node, or sit two dp under
 * the touch minimum, and no image would show either.
 *
 * The copy assertion belongs here rather than in a golden for the same reason:
 * REQ-107 says the privacy statement's sentences map to observed behaviour, and
 * this sentence's behaviour is that nothing but the position is kept. A reworded
 * notice that stopped saying so would still diff clean against a re-recorded
 * image.
 */
@RunWith(AndroidJUnit4::class)

@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class ReaderExternalNoticeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val book = ReaderFixtures.englishNovel
    private val state = ReaderBookView("The Quiet Machine", book).present(ReaderSession(book).jumpTo(12))

    @Test
    fun `the notice says the book is not kept and offers the way to keep it`() {
        showNotice(true)

        val text = allNodes().map { it.label() }
        assertTrue(
            "the notice must say where the book came from, got $text",
            text.any { it.startsWith("Opened from another app.") },
        )
        assertTrue(
            "the notice must say the position is the only thing kept, got $text",
            text.any { it.contains("Only your reading position will be remembered") },
        )
        val actions = actionableNodes().map { it.label() }
        assertTrue("no way to add the book, got $actions", actions.contains("Add to library"))
        assertTrue("no way to dismiss the notice, got $actions", actions.contains("Dismiss"))
    }

    @Test
    fun `both of the notice's controls clear the forty-eight dp touch minimum`() {
        showNotice(true)

        val minimum = with(composeRule.density) { 48.dp.toPx() }
        val short = actionableNodes()
            .filter { it.label() == "Add to library" || it.label() == "Dismiss" }
            .filter { it.boundsInRoot.height < minimum - 1f }

        assertEquals(
            "controls shorter than 48 dp: " + short.map { "${it.label()} @ ${it.boundsInRoot}" },
            0,
            short.size,
        )
    }

    /** A library book must never be told it is not in the library. */
    @Test
    fun `an ordinary book shows no notice at all`() {
        showNotice(false)

        val text = allNodes().map { it.label() }
        assertTrue(
            "a library book must not carry the external notice, got $text",
            text.none { it.startsWith("Opened from another app") },
        )
    }

    private fun showNotice(externalNotice: Boolean) {
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
                    externalNotice = externalNotice,
                )
            }
        }
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
