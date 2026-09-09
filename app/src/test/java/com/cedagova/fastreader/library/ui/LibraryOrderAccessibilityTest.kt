package com.cedagova.fastreader.library.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.library.Catalog
import com.cedagova.fastreader.library.IngestionState
import com.cedagova.fastreader.settings.LibraryOrder
import com.cedagova.fastreader.settings.ReaderSettings
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * REQ-301 for the order control (REQ-203).
 *
 * The golden shows the menu; it cannot show what a screen reader is told. The
 * two failures this guards are both invisible in an image: a button that reads
 * out as "Sort colon Recently read" with no hint that it does anything, and a
 * menu whose current entry is marked only by a tick — a pixel a screen reader
 * never sees.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = REFERENCE_PHONE)
class LibraryOrderAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the order control says what it does and which order is on`() {
        showLibrary()

        val control = composeRule.actionableNodes().single { it.testTag() == "library_order" }

        assertEquals("Sort books. Currently Recently read.", control.label())
    }

    @Test
    fun `the order control clears the forty-eight dp target`() {
        showLibrary()

        val control = composeRule.actionableNodes().single { it.testTag() == "library_order" }
        val minimum = with(composeRule.density) { 48.dp.toPx() }

        assertTrue(
            "the order control is ${control.boundsInRoot.height}px tall",
            control.boundsInRoot.height >= minimum - 1f,
        )
    }

    /**
     * The menu is its own window, so this is also the check that the sweep
     * reaches it at all: three labelled entries, each big enough to hit, and the
     * current one announced as selected rather than drawn as a tick.
     */
    @Test
    fun `every order in the menu is labelled, reachable, and says whether it is on`() {
        showLibrary(LibraryOrder.RECENTLY_ADDED)
        composeRule.onNodeWithTag("library_order").performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()

        val entries = composeRule.actionableNodes().filter { it.testTag().startsWith("library_order_") }
        val minimum = with(composeRule.density) { 48.dp.toPx() }

        assertEquals(
            "the menu must offer exactly the three orders, got ${entries.map { it.label() }}",
            listOf("Title", "Recently read", "Recently added"),
            entries.map { it.label() },
        )
        val short = entries.filter { it.boundsInRoot.height < minimum - 1f }
        assertEquals("menu entries shorter than 48 dp: ${short.map { it.label() }}", 0, short.size)
        assertEquals(
            "exactly the chosen order is announced as selected",
            listOf("Recently added"),
            entries.filter { it.isSelected() }.map { it.label() },
        )
    }

    private fun showLibrary(order: LibraryOrder = LibraryOrder.RECENTLY_READ) {
        val catalog = Catalog(
            settings = ReaderSettings(libraryOrder = order),
            books = listOf(LibraryFixtures.readable("a", "Ficciones", "Jorge Luis Borges")),
        )
        composeRule.setContent {
            FastReaderTheme {
                LibraryScreen(
                    state = buildLibraryUiState(catalog, IngestionState.Idle, query = ""),
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
    }
}
