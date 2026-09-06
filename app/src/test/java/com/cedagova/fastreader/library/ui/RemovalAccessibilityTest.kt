package com.cedagova.fastreader.library.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.library.Catalog
import com.cedagova.fastreader.library.FolderStatus
import com.cedagova.fastreader.library.IngestionState
import com.cedagova.fastreader.library.RemovedBook
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * REQ-301 for everything this leaf adds: the folder list, the removal
 * confirmation, and the library's undo offer.
 *
 * Like the settings sweep this mirrors, it is a *tree* assertion rather than a
 * list of per-control checks, because the failure it guards against is invisible
 * in a golden: a control can render perfectly and still reach the accessibility
 * tree as an actionable node with nothing to announce. Three "Remove folder"
 * buttons in a row are exactly that trap — identical labels that tell a screen
 * reader nothing about which folder each one removes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h1400dp-xxhdpi")
class RemovalAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `every folder-list control a screen reader can act on says what it is`() {
        showFolderList()

        assertNoUnlabelledControl()
    }

    @Test
    fun `each remove button names the folder it would remove`() {
        showFolderList()

        val labels = composeRule.actionableNodes().map { it.label() }

        listOf("Novels", "Old backup", "SD card books").forEach { name ->
            assertTrue(
                "no control announces removing \"$name\", only $labels",
                labels.contains("Remove $name"),
            )
        }
    }

    @Test
    fun `every folder-list control is at least forty-eight density-independent pixels tall`() {
        showFolderList()

        assertEveryControlIsTallEnough()
    }

    @Test
    fun `the removal confirmation is labelled and reachable`() {
        showFolderList(confirming = true)

        assertNoUnlabelledControl()
        assertEveryControlIsTallEnough()
        val labels = composeRule.actionableNodes().map { it.label() }
        assertTrue("the confirmation needs a way out, got $labels", labels.contains("Cancel"))
        assertTrue("the confirmation needs its action, got $labels", labels.contains("Remove folder"))
    }

    /**
     * "Undo" alone is what a badly labelled banner announces. This one has to say
     * which book comes back, because the row it refers to is already gone.
     */
    @Test
    fun `the undo offer names the book it would bring back`() {
        composeRule.setContent {
            FastReaderTheme {
                LibraryScreen(
                    state = buildLibraryUiState(
                        Catalog(books = listOf(LibraryFixtures.readable("a", "Ficciones"))),
                        IngestionState.Idle,
                        query = "",
                        undoableRemoval = RemovedBook("rayuela", "Rayuela"),
                    ),
                    onQueryChange = {},
                    onAddBooks = {},
                    onAddFolder = {},
                    onRefresh = {},
                    onRemove = {},
                    onGrantAccess = {},
                    onOpen = {},
                )
            }
        }

        // Scoped to the banner: the surrounding library rows are LEAF102's
        // surface and their semantics are unchanged by this leaf.
        val undo = composeRule.actionableNodes().filter { it.testTag().startsWith("library_undo") }
        val minimum = with(composeRule.density) { 48.dp.toPx() }

        assertEquals("the banner must offer exactly one control", 1, undo.size)
        assertEquals("Undo removing Rayuela", undo.single().label())
        assertTrue(
            "the undo control is ${undo.single().boundsInRoot.height}px tall",
            undo.single().boundsInRoot.height >= minimum - 1f,
        )
    }

    private fun showFolderList(confirming: Boolean = false) {
        val folders = listOf(
            LibraryFolderItem("tree://novels", "Novels", FolderStatus.AVAILABLE, 3, 2),
            LibraryFolderItem("tree://moved", "Old backup", FolderStatus.MISSING, 1, 1),
            LibraryFolderItem("tree://sd", "SD card books", FolderStatus.PERMISSION_LOST, 12, 12),
        )
        composeRule.setContent {
            FastReaderTheme {
                FolderListScreen(
                    state = FolderListUiState(folders, confirming = folders.first().takeIf { confirming }),
                    onBack = {},
                    onRemoveRequest = {},
                    onRemoveConfirm = {},
                    onRemoveCancel = {},
                )
            }
        }
    }

    private fun assertNoUnlabelledControl() {
        val unlabelled = composeRule.actionableNodes().filter { it.label().isBlank() }

        assertEquals(
            "actionable nodes with no label, at ${unlabelled.map { it.boundsInRoot }}",
            emptyList<String>(),
            unlabelled.map { it.config.toString() },
        )
    }

    private fun assertEveryControlIsTallEnough() {
        val minimum = with(composeRule.density) { 48.dp.toPx() }
        val short = composeRule.actionableNodes().filter { it.boundsInRoot.height < minimum - 1f }

        assertEquals(
            "controls shorter than 48 dp: " + short.map { "${it.label()} @ ${it.boundsInRoot}" },
            0,
            short.size,
        )
    }
}

/**
 * Every root, not just the first: a confirmation is its own window, so the
 * dialog's controls are invisible to a sweep that only walks the screen behind it.
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

private fun SemanticsNode.testTag(): String =
    config.getOrElseNullable(SemanticsProperties.TestTag) { null }.orEmpty()

private fun SemanticsNode.label(): String {
    val described = config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }
    if (!described.isNullOrEmpty()) return described.joinToString(" ").trim()
    val text = config.getOrElseNullable(SemanticsProperties.Text) { null }
    return text?.joinToString(" ") { it.text }?.trim().orEmpty()
}
