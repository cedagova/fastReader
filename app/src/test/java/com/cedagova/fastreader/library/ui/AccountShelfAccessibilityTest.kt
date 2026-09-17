package com.cedagova.fastreader.library.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.account.library.AccountLibraryState
import com.cedagova.fastreader.account.library.AccountSyncError
import com.cedagova.fastreader.account.library.AccountSyncPhase
import com.cedagova.fastreader.library.IngestionState
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * REQ-060 / REQ-301 for everything the account shelf adds (LEAF703).
 *
 * A tree assertion rather than a list of per-control checks, for the reason the
 * removal sweep already gives: a control can render perfectly and still reach
 * the accessibility tree as an actionable node with nothing to announce. This
 * shelf's trap is the exact one — every account row carries a control reading
 * "Remove from account", and three of them in a row would tell a screen reader
 * nothing about which book each one is for.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h1400dp-xxhdpi")
class AccountShelfAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `each account removal names the book it would remove`() {
        showShelf()

        val labels = accountControls().map { it.label() }

        listOf("Ficciones", "Dubliners").forEach { title ->
            assertTrue(
                "no control announces removing \"$title\" from the account, only $labels",
                labels.contains("Remove $title from your Reader account"),
            )
        }
    }

    @Test
    fun `every account-shelf control a screen reader can act on says what it is`() {
        showShelf()

        val unlabelled = accountControls().filter { it.label().isBlank() }

        assertEquals(
            "actionable nodes with no label, at ${unlabelled.map { it.boundsInRoot }}",
            emptyList<String>(),
            unlabelled.map { it.config.toString() },
        )
    }

    @Test
    fun `every account-shelf control is at least forty-eight density-independent pixels tall`() {
        showShelf()

        assertEveryControlIsTallEnough()
    }

    @Test
    fun `the account removal confirmation is labelled and reachable`() {
        var removed = 0
        showShelf(onRemoveFromAccount = { removed += 1 })

        composeRule.onNodeWithTag(
            "library_account_remove_${LibraryAccountFixtures.FICCIONES_ID}",
        ).performClick()

        assertEquals("nothing leaves the account before the yes", 0, removed)
        val controls = accountControls().filter { it.testTag().startsWith("library_account_remove_dialog") }
        assertEquals(listOf("Cancel", "Remove from account"), controls.map { it.label() }.sorted())
        assertEveryControlIsTallEnough()

        composeRule.onNodeWithTag("library_account_remove_dialog_confirm").performClick()

        assertEquals(1, removed)
    }

    /**
     * "Undo" alone is what a badly labelled bar announces, and this one refers
     * to a row that has already left the shelf.
     */
    @Test
    fun `the account undo offer names the book it would bring back`() {
        showShelf(accountUndo = AccountUndoNotice("acc-1", "Dubliners"))

        val undo = accountControls().filter { it.testTag().startsWith("library_account_undo") }

        assertEquals("the bar must offer exactly one control", 1, undo.size)
        assertEquals("Undo removing Dubliners from your Reader account", undo.single().label())
        assertEveryControlIsTallEnough()
    }

    /**
     * The shelf's state changes with no interaction of the reader's — a sync
     * finishing, a session going away — so the banner is announced rather than
     * left to be discovered by someone not looking at the screen.
     */
    @Test
    fun `a change of account state is announced`() {
        showShelf(
            account = AccountLibraryState(
                phase = AccountSyncPhase.SIGNED_OUT,
                lastError = AccountSyncError.SessionGone("session_revoked", "req-1"),
            ),
        )

        val banner = composeRule.allNodes().single { it.testTag() == "library_account_notice" }

        assertNotNull(
            "the account banner has to be a live region to be announced",
            banner.config.getOrElseNullable(SemanticsProperties.LiveRegion) { null },
        )
        val spoken = banner.spokenText()
        assertTrue("the reason has to be readable, got \"$spoken\"", spoken.contains("session_revoked"))
    }

    private fun showShelf(
        account: AccountLibraryState = LibraryAccountFixtures.signedIn(
            LibraryAccountFixtures.ficcionesInAccount(),
            LibraryAccountFixtures.dublinersInAccountOnly(),
        ),
        accountUndo: AccountUndoNotice? = null,
        onRemoveFromAccount: (LibraryBookItem) -> Unit = {},
    ) {
        composeRule.setContent {
            FastReaderTheme {
                LibraryScreen(
                    state = buildLibraryUiState(
                        catalog = LibraryAccountFixtures.deviceCatalog(),
                        ingestion = IngestionState.Idle,
                        query = "",
                        account = account,
                        accountUndo = accountUndo,
                    ),
                    onQueryChange = {},
                    onAddBooks = {},
                    onAddFolder = {},
                    onRefresh = {},
                    onRemove = {},
                    onGrantAccess = {},
                    onOpen = {},
                    onRemoveFromAccount = onRemoveFromAccount,
                )
            }
        }
    }

    /**
     * Only the controls this leaf adds.
     *
     * The rows behind them, the top bar and the header are LEAF102's surface,
     * already swept by its own tests, and one of them — the 40 dp app-bar icon
     * pair of v1.6.0 — would make every assertion here a report about code this
     * leaf does not touch.
     */
    private fun accountControls() =
        composeRule.actionableNodes().filter { it.testTag().startsWith("library_account_") }

    /** What a screen reader reads out for a node whose text is in its children. */
    private fun androidx.compose.ui.semantics.SemanticsNode.spokenText(): String {
        val out = StringBuilder()
        fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
            out.append(node.label()).append(' ')
            node.children.forEach(::walk)
        }
        walk(this)
        return out.toString()
    }

    private fun assertEveryControlIsTallEnough() {
        val minimum = with(composeRule.density) { 48.dp.toPx() }
        val short = accountControls().filter { it.boundsInRoot.height < minimum - 1f }

        assertEquals(
            "controls shorter than 48 dp: " + short.map { "${it.label()} @ ${it.boundsInRoot}" },
            emptyList<String>(),
            short.map { it.label() },
        )
    }
}
