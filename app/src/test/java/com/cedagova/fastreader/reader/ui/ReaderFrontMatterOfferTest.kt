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
 * REQ-301 for the offer this leaf adds, and the half of REQ-202 an image cannot
 * carry.
 *
 * The golden proves the banner looks right. This proves it *works* for someone
 * who cannot see it — a button can render perfectly and still reach the
 * accessibility tree unlabelled, or two dp under the touch minimum — and that the
 * skip control names its destination rather than only its verb. "Skip" alone
 * would leave a reader using TalkBack no way to know what they are skipping to;
 * that claim survives a re-recorded image, so it is asserted here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class ReaderFrontMatterOfferTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val book = ReaderFixtures.englishNovel
    private val state = ReaderBookView("The Long Approach", book).present(ReaderSession(book))

    @Test
    fun `the offer says what the book starts with and where the skip goes`() {
        showOffer("Chapter One: The Approach")

        val text = allNodes().map { it.label() }
        assertTrue(
            "the offer must say why it is there, got $text",
            text.any { it.contains("starts with cover and title pages") },
        )
        val actions = actionableNodes().map { it.label() }
        assertTrue(
            "the skip control must name its destination, got $actions",
            actions.contains("Skip to Chapter One: The Approach"),
        )
        assertTrue(
            "declining must be an option in its own words, got $actions",
            actions.contains("Start at the beginning"),
        )
    }

    @Test
    fun `both of the offer's controls clear the forty-eight dp touch minimum`() {
        showOffer("Chapter One: The Approach")

        val minimum = with(composeRule.density) { 48.dp.toPx() }
        val short = actionableNodes()
            .filter { it.label().startsWith("Skip to ") || it.label() == "Start at the beginning" }
            .filter { it.boundsInRoot.height < minimum - 1f }

        assertEquals(
            "controls shorter than 48 dp: " + short.map { "${it.label()} @ ${it.boundsInRoot}" },
            0,
            short.size,
        )
    }

    /** A book that opens on its first chapter must never be told it starts with a cover. */
    @Test
    fun `a book with no front matter shows no offer at all`() {
        showOffer(null)

        val text = allNodes().map { it.label() }
        assertTrue(
            "an ordinary book must carry no offer, got $text",
            text.none { it.contains("starts with cover and title pages") },
        )
    }

    private fun showOffer(chapterTitle: String?) {
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
                    frontMatterOffer = chapterTitle,
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
