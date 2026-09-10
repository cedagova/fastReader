package com.cedagova.fastreader.library.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.library.FolderStatus
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The folder list's UI regression gate (REQ-104), one committed golden per state.
 *
 * The confirmation render is the primary visual proof that removal names the
 * number of books that would actually leave and says what happens to the files:
 * that sentence is the whole safety of the action, and it is the thing a later
 * copy change could quietly break.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = REFERENCE_PHONE)
class FolderListScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun everyFolderShowsItsNameCountAndState() {
        capture("folders_list", FolderListUiState(folders = folders()))
    }

    /**
     * REQ-104's acceptance case as the reader sees it: the folder holds three
     * books, two of them are its alone, and the confirmation says two.
     */
    @Test
    fun removingAFolderNamesHowManyBooksWouldLeave() {
        capture(
            "folders_remove_confirm",
            FolderListUiState(folders = folders(), confirming = folders().first()),
        )
    }

    /** A folder every one of whose books is also reachable elsewhere costs nothing. */
    @Test
    fun aFolderThatWouldRemoveNoBookSaysSo() {
        val duplicate = LibraryFolderItem(
            id = "tree://backup",
            displayName = "Backup copy",
            status = FolderStatus.AVAILABLE,
            bookCount = 2,
            removedBookCount = 0,
        )
        capture(
            "folders_remove_confirm_none",
            FolderListUiState(folders = listOf(duplicate), confirming = duplicate),
        )
    }

    @Test
    fun theListSaysSoWhenNoFolderHasBeenAdded() {
        capture("folders_empty", FolderListUiState(folders = emptyList()))
    }

    /** Cramped 720p phone (`Phone_Low_API33`) at a large system font scale (REQ-301). */
    @Test
    @Config(sdk = [35], qualifiers = COMPACT_PHONE)
    fun theListSurvivesACrampedScreenAtALargeFontScale() {
        capture("folders_compact_large_font", FolderListUiState(folders = folders()), fontScale = 1.3f)
    }

    /**
     * REQ-206 on the folder list: the intro sentence that promises the files
     * inside are never touched, both status lines, and the per-folder counts —
     * which are plurals, so this is also where the Spanish plural set is seen
     * rendering rather than merely passing lint.
     */
    @Test
    @Config(qualifiers = "+es")
    fun theFolderListIsSpanishOnASpanishDevice() {
        capture("folders_spanish", FolderListUiState(folders = folders()))
    }

    /**
     * REQ-206 on the confirmation, which is the folder screen's notice: the
     * count of books that would leave, and the sentence saying no file is
     * deleted. Both have to survive translation intact.
     */
    @Test
    @Config(qualifiers = "+es")
    fun theRemovalConfirmationIsSpanishOnASpanishDevice() {
        capture(
            "folders_remove_confirm_spanish",
            FolderListUiState(folders = folders(), confirming = folders().first()),
        )
    }

    private fun folders() = listOf(
        LibraryFolderItem(
            id = "tree://novels",
            displayName = "Novels",
            status = FolderStatus.AVAILABLE,
            bookCount = 3,
            removedBookCount = 2,
        ),
        LibraryFolderItem(
            id = "tree://moved",
            displayName = "Old backup",
            status = FolderStatus.MISSING,
            bookCount = 1,
            removedBookCount = 1,
        ),
        LibraryFolderItem(
            id = "tree://sd",
            displayName = "SD card books",
            status = FolderStatus.PERMISSION_LOST,
            bookCount = 12,
            removedBookCount = 12,
        ),
    )

    private fun capture(name: String, state: FolderListUiState, fontScale: Float = 1f) {
        composeRule.setContent {
            ScaledFonts(fontScale) {
                FastReaderTheme {
                    FolderListScreen(
                        state = state,
                        onBack = {},
                        onRemoveRequest = {},
                        onRemoveConfirm = {},
                        onRemoveCancel = {},
                    )
                }
            }
        }
        // A confirmation is its own window, so the compose root is no longer
        // unique; the screen capture is the only one that contains the dialog.
        if (state.confirming != null) {
            captureScreenRoboImage("screenshots/$name.png")
        } else {
            composeRule.onRoot().captureRoboImage("screenshots/$name.png")
        }
    }

    @Composable
    private fun ScaledFonts(fontScale: Float, content: @Composable () -> Unit) {
        if (fontScale == 1f) {
            content()
        } else {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                content = content,
            )
        }
    }
}
