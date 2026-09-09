package com.cedagova.fastreader.library.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.library.Catalog
import com.cedagova.fastreader.library.IngestionState
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * REQ-205 for the library: that the width is actually spent, asserted as geometry
 * rather than eyeballed off a golden.
 *
 * "Two columns" is exactly "two book rows that start at the same y and different
 * x", and that is a claim about laid-out bounds, so it is made against the bounds.
 * The goldens then say what those columns look like.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LibraryWideLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w600dp-h960dp-xhdpi")
    fun atExactlySixHundredDpTwoBooksSitSideBySide() {
        render()
        val first = bounds("library_book_dubliners")
        val second = bounds("library_book_ficciones")
        assertEquals("same row", first.first, second.first)
        assertNotEquals("different column", first.second, second.second)
        composeRule.onNodeWithTag("library_wide_header").assertExists()
    }

    @Test
    @Config(qualifiers = "w599dp-h960dp-xhdpi")
    fun oneDpBelowTheBoundaryTheListIsStillOneColumn() {
        render()
        val first = bounds("library_book_dubliners")
        val second = bounds("library_book_ficciones")
        assertTrue("stacked", second.first > first.first)
        assertEquals("same column", first.second, second.second)
        composeRule.onNodeWithTag("library_wide_header").assertDoesNotExist()
    }

    /**
     * The font-scaled column minimum, at the size that exercises it: 600 dp holds
     * two 300 dp columns at the default size and only one at the largest, because
     * a column has to be wide enough for the title in it.
     */
    @Test
    @Config(qualifiers = "w600dp-h960dp-xhdpi")
    fun atTheLargestFontSizeTheBoundaryTabletFallsBackToOneWideColumn() {
        render(fontSize = com.cedagova.fastreader.settings.FontSize.EXTRA_LARGE)
        val first = bounds("library_book_dubliners")
        val second = bounds("library_book_ficciones")
        assertTrue("stacked", second.first > first.first)
        assertEquals("same column", first.second, second.second)
    }

    /** Returns (top, left) in whole pixels. */
    private fun bounds(tag: String): Pair<Int, Int> {
        val rect = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return rect.top.toInt() to rect.left.toInt()
    }

    private fun render(
        fontSize: com.cedagova.fastreader.settings.FontSize =
            com.cedagova.fastreader.settings.FontSize.MEDIUM,
    ) {
        val catalog = Catalog(
            books = listOf(
                LibraryFixtures.readable("dubliners", "Dubliners", "James Joyce", "dubliners.epub"),
                LibraryFixtures.readable("ficciones", "Ficciones", "Jorge Luis Borges", "ficciones.epub"),
            ),
        )
        composeRule.setContent {
            FastReaderTheme(fontSize = fontSize) {
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
