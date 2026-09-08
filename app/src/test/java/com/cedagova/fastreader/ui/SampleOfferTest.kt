package com.cedagova.fastreader.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.content.BundledSample
import com.cedagova.fastreader.library.BookStatus
import com.cedagova.fastreader.library.Catalog
import com.cedagova.fastreader.library.IngestionState
import com.cedagova.fastreader.library.ui.LibraryFixtures
import com.cedagova.fastreader.library.ui.LibraryScreen
import com.cedagova.fastreader.library.ui.buildLibraryUiState
import com.cedagova.fastreader.settings.ReaderSettings
import com.cedagova.fastreader.settings.ui.SettingsScreen
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Where the sample is offered, and where it must not be (REQ-109).
 *
 * The presence rule is a structural claim, not a visual one — "this is absent
 * once you have books" is a thing an image cannot show, because the image looks
 * exactly like the one without the feature. So it is asserted against the
 * semantics tree here, and the goldens are left to prove what the offer looks
 * like when it *is* there.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h1400dp-xxhdpi")
class SampleOfferTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val samples = listOf(BundledSample.ENGLISH, BundledSample.SPANISH)

    @Test
    fun `an empty library offers both samples`() {
        setLibrary(Catalog())

        composeRule.onNodeWithTag("sample_offer").performScrollTo().assertIsDisplayed()
        samples.forEach { sample ->
            composeRule.onNodeWithTag("sample_open_${sample.languageTag}").assertIsDisplayed()
        }
    }

    @Test
    fun `a library with books does not offer the sample`() {
        setLibrary(populated())

        composeRule.onAllNodesWithTag("sample_offer").assertCountEquals(0)
        samples.forEach { sample ->
            composeRule.onAllNodesWithTag("sample_open_${sample.languageTag}").assertCountEquals(0)
        }
    }

    /** The whole point of the settings entry: it survives the library filling up. */
    @Test
    fun `settings offers the sample`() {
        composeRule.setContent {
            FastReaderTheme {
                SettingsScreen(
                    settings = ReaderSettings.DEFAULTS,
                    onSettingsChange = {},
                    onReset = {},
                    onBack = {},
                    samples = samples,
                )
            }
        }

        composeRule.onNodeWithTag("sample_offer").performScrollTo().assertIsDisplayed()
    }

    /**
     * REQ-301: the visible label is one word, which tells a screen reader almost
     * nothing, so the spoken description names the language and the text.
     */
    @Test
    fun `each offer announces its language and its text`() {
        setLibrary(Catalog())

        composeRule
            .onNodeWithContentDescription("Read the sample in English: A Word at a Time")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Read the sample in Español: Una palabra a la vez")
            .assertIsDisplayed()
    }

    @Test
    fun `tapping an offer asks for that sample`() {
        val opened = mutableListOf<BundledSample>()
        setLibrary(Catalog(), onOpenSample = { opened += it })

        composeRule.onNodeWithTag("sample_open_es").performScrollTo().performClick()
        composeRule.onNodeWithTag("sample_open_en").performClick()

        assertEquals(listOf(BundledSample.SPANISH, BundledSample.ENGLISH), opened)
    }

    private fun setLibrary(catalog: Catalog, onOpenSample: (BundledSample) -> Unit = {}) {
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
                    onOpenSample = onOpenSample,
                    samples = samples,
                )
            }
        }
    }

    private fun populated() = Catalog(
        books = listOf(
            LibraryFixtures.readable("ficciones", "Ficciones", "Jorge Luis Borges", "ficciones.epub"),
        ),
    ).also { check(it.books.single().status == BookStatus.READABLE) }
}
