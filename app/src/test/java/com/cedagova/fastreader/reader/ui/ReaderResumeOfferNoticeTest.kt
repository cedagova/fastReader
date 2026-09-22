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
import com.cedagova.fastreader.reader.ResumeOffer
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * REQ-301 for the resume offer, and the half of REQ-511 an image cannot carry.
 *
 * The golden proves the banner looks right. This proves it *works* for someone who
 * cannot see it, and — the part that would survive a re-recorded image — that the
 * two sentences are chosen by whether the section could be honoured, not by
 * whether the record happened to carry a chapter title.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class ReaderResumeOfferNoticeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val book = ReaderFixtures.englishNovel
    private val state = ReaderBookView("The Long Approach", book).present(ReaderSession(book))

    @Test
    fun `the offer names the chapter and the percent, and so does the way to take it`() {
        showOffer(offer(chapterTitle = "Chapter Four: The Signal", percent = 77))

        val text = allNodes().map { it.label() }
        assertTrue(
            "the offer must say where the place came from, got $text",
            text.any {
                it.contains("Resume where you left off on another device") &&
                    it.contains("Chapter Four: The Signal") &&
                    it.contains("77%")
            },
        )
        val actions = actionableNodes().map { it.label() }
        assertTrue(
            "taking it must name the destination, got $actions",
            actions.contains("Resume at Chapter Four: The Signal"),
        )
        assertTrue(
            "declining must be an option in its own words, got $actions",
            actions.contains("Stay where I am"),
        )
    }

    /**
     * #121's edge case: a section this parse does not have. The offer names the
     * percentage alone, and — this is the part worth asserting — the destination
     * the accepting button names is the percentage too, so no control promises a
     * chapter the tap will not land in.
     */
    @Test
    fun `an offer with no usable section names the percent alone`() {
        showOffer(offer(chapterTitle = null, percent = 60))

        val text = allNodes().map { it.label() }
        assertTrue(
            "got $text",
            text.any { it == "Resume where you left off on another device: 60%" },
        )
        val actions = actionableNodes().map { it.label() }
        assertTrue("got $actions", actions.contains("Resume at 60%"))
        assertTrue(
            "no control may name a chapter here, got $actions",
            actions.none { it.startsWith("Resume at Chapter") },
        )
    }

    @Test
    fun `both of the offer's controls clear the forty-eight dp touch minimum`() {
        showOffer(offer(chapterTitle = "Chapter Four: The Signal", percent = 77))

        val minimum = with(composeRule.density) { 48.dp.toPx() }
        val short = actionableNodes()
            .filter { it.label().startsWith("Resume at ") || it.label() == "Stay where I am" }
            .filter { it.boundsInRoot.height < minimum - 1f }

        assertEquals(
            "controls shorter than 48 dp: " + short.map { "${it.label()} @ ${it.boundsInRoot}" },
            0,
            short.size,
        )
    }

    /** A book the account holds no place in must never be told one was left. */
    @Test
    fun `no offer means nothing on the screen about another device`() {
        showOffer(null)

        val text = allNodes().map { it.label() }
        assertTrue(
            "an ordinary open must carry no offer, got $text",
            text.none { it.contains("another device") },
        )
    }

    private fun offer(chapterTitle: String?, percent: Int) = ResumeOffer(
        accountBookId = "acct-1",
        changeKey = "4:2026-09-20T10:00:00Z",
        targetTokenIndex = 24,
        chapterTitle = chapterTitle,
        percent = percent,
    )

    private fun showOffer(offer: ResumeOffer?) {
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
                    resumeOffer = offer,
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
