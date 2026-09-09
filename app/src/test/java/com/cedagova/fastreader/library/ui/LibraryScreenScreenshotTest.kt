package com.cedagova.fastreader.library.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.library.BookContentStatus
import com.cedagova.fastreader.library.BookFolder
import com.cedagova.fastreader.library.Catalog
import com.cedagova.fastreader.library.IngestionState
import com.cedagova.fastreader.library.ReadingState
import com.cedagova.fastreader.library.RemovedBook
import com.cedagova.fastreader.library.ResumeBlocked
import com.cedagova.fastreader.library.ResumeBlockedReason
import com.cedagova.fastreader.library.ScanTrigger
import com.cedagova.fastreader.library.SourceAvailability
import com.cedagova.fastreader.library.SourceOrigin
import com.cedagova.fastreader.settings.FontSize
import com.cedagova.fastreader.settings.LibraryOrder
import com.cedagova.fastreader.settings.ReaderSettings
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The library's UI regression gate: one committed golden per state the screen can
 * reach. `recordRoborazziDebug` refreshes them, `verifyRoborazziDebug` diffs.
 *
 * The renders are the primary proof that each failure state gets its own
 * plain-language copy (REQ-005) and that search, loading, and the empty guidance
 * look right; the emulator pass covers the real pickers and TalkBack.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = REFERENCE_PHONE)
class LibraryScreenScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * REQ-109's first half: the screen a stranger meets has both ways to add their
     * own books *and* something they can read right now, with the licence of that
     * something stated where it is offered.
     */
    @Test
    fun emptyLibraryExplainsBothWaysToAddBooksAndOffersASample() {
        capture("library_empty", state(Catalog()))
    }

    /**
     * REQ-109's language rule, proved through the mechanism that actually decides
     * it: the composition's configuration. The `es` qualifier is what a Spanish
     * device gives the app, and the offer comes back Español first. The interface
     * around it stays English until the Spanish resource set lands (D5).
     */
    @Test
    @Config(qualifiers = "+es")
    fun aSpanishDeviceIsOfferedTheSpanishSampleFirst() {
        capture("library_empty_spanish", state(Catalog()))
    }


    @Test
    fun populatedLibraryShowsTitleAuthorCoverAndProgress() {
        capture("library_populated", state(populatedCatalog()))
    }

    @Test
    fun populatedLibraryInDarkTheme() {
        capture("library_populated_dark", state(populatedCatalog()), darkTheme = true)
    }

    @Test
    fun searchFiltersTheListAsTyped() {
        capture("library_searching", state(populatedCatalog(), query = "borg"))
    }

    @Test
    fun searchWithNoMatchesExplainsWhatIsSearched() {
        capture("library_no_results", state(populatedCatalog(), query = "tolkien"))
    }

    @Test
    fun everyFailureStateHasItsOwnPlainLanguageCopy() {
        capture("library_states", state(failureCatalog()))
    }

    @Test
    fun scanningAFolderShowsTheLoadingState() {
        capture(
            "library_scanning",
            state(
                catalog = populatedCatalog(),
                ingestion = IngestionState.Scanning(ScanTrigger.ADD_FOLDER, 128, 640, "cien-anos-de-soledad.epub"),
            ),
        )
    }

    @Test
    fun theCatalogFailingToLoadIsExplainedInPlace() {
        capture(
            "library_problem",
            state(Catalog(), ingestion = IngestionState.Failed("the stored library is newer than this app version")),
        )
    }

    /**
     * REQ-009's second half: the app could not go back to the book being read, so
     * the library opens saying which book and why, above that book's own state.
     */
    @Test
    fun aLaunchThatCouldNotResumeSaysWhichBookAndWhy() {
        capture(
            "library_resume_blocked",
            state(
                catalog = failureCatalog(),
                resumeBlocked = ResumeBlocked("revoked", ResumeBlockedReason.PERMISSION_LOST),
            ),
        )
    }

    /**
     * REQ-105: the removal is on screen with the way to take it back, and the
     * sentence says what happened to the file and to the reader's place.
     */
    @Test
    fun aRemovedBookCanStillBeBroughtBack() {
        // The row is genuinely gone from the catalog behind the banner: this is
        // the state a reader is actually in one tap after removing Rayuela.
        val afterRemoval = populatedCatalog().let { catalog ->
            catalog.copy(
                books = catalog.books.filterNot { it.id == "rayuela" },
                removedBookIds = setOf("rayuela"),
            )
        }
        capture("library_undo", state(afterRemoval, undoableRemoval = RemovedBook("rayuela", "Rayuela")))
    }

    /** REQ-104's way in, shown only once a folder exists to manage. */
    @Test
    fun addedFoldersAreReachableFromTheLibrary() {
        capture("library_folders_entry", state(folderCatalog()))
    }

    /** Cramped 720p phone (`Phone_Low_API33`) at a large system font scale. */
    @Test
    @Config(sdk = [35], qualifiers = COMPACT_PHONE)
    fun theListSurvivesACrampedScreenAtALargeFontScale() {
        capture("library_compact_large_font", state(failureCatalog()), fontScale = 1.3f)
    }

    /**
     * REQ-301 for the new controls: the smallest screen in the matrix at a large
     * system font scale. The offer's buttons wrap rather than clip, and the page
     * scrolls, so nothing on it becomes unreachable.
     */
    @Test
    @Config(sdk = [35], qualifiers = COMPACT_PHONE)
    fun theSampleOfferSurvivesACrampedScreenAtALargeFontScale() {
        capture("library_empty_compact_large_font", state(Catalog()), fontScale = 1.4f, scrollTo = "sample_offer")
    }

    /**
     * REQ-022's other half: the text-size setting is applied by the app's theme,
     * so it reaches the library as well as the reader. Same catalog and same
     * screen as `library_populated`; the only difference is the setting.
     */
    @Test
    fun theTextSizeSettingAppliesToTheLibrary() {
        capture("library_font_extra_large", state(populatedCatalog()), fontSize = FontSize.EXTRA_LARGE)
    }

    // --- REQ-203, the library's order ----------------------------------------

    /**
     * REQ-203's acceptance as an image: exactly the books of `library_populated`,
     * with the timestamps a reader who has actually been reading leaves behind.
     * Rayuela was read last and is at the top; the two books never opened are at
     * the bottom, alphabetically, under the two that were.
     */
    @Test
    fun `the default order puts the book last read at the top`() {
        capture("library_order_recently_read", state(readCatalog()))
    }

    /** The control open: the three orders, with a tick on the one in force. */
    @Test
    fun `the order control offers three orders and marks the current one`() {
        capture("library_order_menu", state(readCatalog()), click = "library_order")
    }

    /**
     * REQ-301 for the new control, on the smallest screen in the matrix at a large
     * font scale and with a folder to reach as well. Two controls that will not
     * fit side by side stack instead of clipping.
     */
    @Test
    @Config(sdk = [35], qualifiers = COMPACT_PHONE)
    fun theOrderControlSurvivesACrampedScreenAtALargeFontScale() {
        capture("library_order_compact_large_font", state(folderCatalog()), fontScale = 1.4f)
    }

    // --- REQ-205, the wide layouts -------------------------------------------
    //
    // Three widths, because they are three different questions. 600 dp is the
    // breakpoint itself, on the one AVD in the matrix built to sit exactly on it.
    // The landscape phone is the same rule reached the other way — no tablet, just
    // a device on its side — and it is where height, not width, is scarce. The 10"
    // tablet is where a rule that only ever adds columns would start looking silly.
    // Each is captured at the largest font size, because that is the size the
    // acceptance names and the size a column count can go wrong at.

    /**
     * The boundary: `Tablet_Low_API33` is exactly 600 dp wide, and this is what it
     * renders. The header is one row — search beside both add buttons — and the
     * list is two columns of books instead of one column and 300 dp of nothing.
     */
    @Test
    @Config(sdk = [35], qualifiers = TABLET_BOUNDARY)
    fun atExactlySixHundredDpTheLibraryUsesTheWidth() {
        capture("library_tablet", state(populatedCatalog()))
    }

    /**
     * The same 600 dp at the largest font size, which is the acceptance's own
     * wording: nothing clipped. The grid answers it by dropping to one wide column
     * rather than by shrinking two — a column is added when a whole one fits and
     * not before — so every title, author and status line is complete.
     */
    @Test
    @Config(sdk = [35], qualifiers = TABLET_BOUNDARY)
    fun theTabletLibraryIsWholeAtTheLargestFontSize() {
        capture("library_tablet_large_font", state(populatedCatalog()), fontSize = FontSize.EXTRA_LARGE)
    }

    /**
     * The reference phone on its side at the largest font size. 914 dp is wide
     * enough for two columns even at 1.5x, and the one-row header is what keeps
     * more than a single book on a 411 dp-tall screen.
     */
    @Test
    @Config(sdk = [35], qualifiers = LANDSCAPE_PHONE)
    fun theLandscapeLibraryIsWholeAtTheLargestFontSize() {
        capture("library_landscape_large_font", state(populatedCatalog()), fontSize = FontSize.EXTRA_LARGE)
    }

    /** `Tablet_Mid_API36`, 10" at 1280 x 800 dp: the width genuinely spent. */
    @Test
    @Config(sdk = [35], qualifiers = TABLET_LARGE)
    fun aTenInchTabletShowsSeveralBooksAcross() {
        capture("library_tablet_large", state(populatedCatalog()), fontSize = FontSize.EXTRA_LARGE)
    }

    private fun capture(
        name: String,
        state: LibraryUiState,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        fontSize: FontSize = FontSize.MEDIUM,
        /** A test tag to bring into view before capturing, for content below the fold. */
        scrollTo: String? = null,
        /** A test tag to tap before capturing, for a state that only a tap reaches. */
        click: String? = null,
    ) {
        composeRule.setContent {
            ScaledFonts(fontScale) {
                FastReaderTheme(darkTheme = darkTheme, fontSize = fontSize) {
                    LibraryScreen(
                        state = state,
                        onQueryChange = {},
                        onAddBooks = {},
                        onAddFolder = {},
                        onRefresh = {},
                        onRemove = {},
                        onGrantAccess = {},
                        onOpen = {},
                        coverLoader = FakeCovers,
                    )
                }
            }
        }
        scrollTo?.let { composeRule.onNodeWithTag(it).performScrollTo() }
        if (click == null) {
            composeRule.onRoot().captureRoboImage("screenshots/$name.png")
            return
        }
        // A menu is its own window, so the compose root is no longer unique; the
        // screen capture is the only one that contains what the tap opened.
        composeRule.onNodeWithTag(click).performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        captureScreenRoboImage("screenshots/$name.png")
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

    private fun state(
        catalog: Catalog,
        ingestion: IngestionState = IngestionState.Idle,
        query: String = "",
        resumeBlocked: ResumeBlocked? = null,
        undoableRemoval: RemovedBook? = null,
    ) = buildLibraryUiState(catalog, ingestion, query, resumeBlocked, undoableRemoval)

    /** A library whose books come from two added folders (REQ-104). */
    private fun folderCatalog() = Catalog(
        folders = listOf(
            BookFolder(id = "tree://novels", treeUri = "tree://novels", displayName = "Novels"),
            BookFolder(id = "tree://sd", treeUri = "tree://sd", displayName = "SD card books"),
        ),
        books = listOf(
            LibraryFixtures.inFolder("ficciones", "Ficciones", "tree://novels"),
            LibraryFixtures.inFolder("rayuela", "Rayuela", "tree://novels"),
            LibraryFixtures.inFolder("dubliners", "Dubliners", "tree://sd"),
        ),
    )

    private fun populatedCatalog() = Catalog(
        books = listOf(
            LibraryFixtures.readable("ficciones", "Ficciones", "Jorge Luis Borges", "ficciones.epub", hasCover = true),
            LibraryFixtures.readable("rayuela", "Rayuela", "Julio Cortázar", "rayuela.epub", hasCover = true),
            LibraryFixtures.readable("dubliners", "Dubliners", "James Joyce", "dubliners.epub"),
            LibraryFixtures.readable("anon", "Notes on a Long Winter", fileName = "notes.epub"),
        ),
        readingStates = mapOf(
            "ficciones" to ReadingState(progressFraction = 0.37f),
            "dubliners" to ReadingState(progressFraction = 1f),
        ),
    )

    /**
     * `populatedCatalog` after some reading: Rayuela most recently, Ficciones
     * before it, and the other two never opened. The stamps are the ones the
     * position writer leaves, so this is the catalog a real reader would have.
     */
    private fun readCatalog() = populatedCatalog().let { catalog ->
        catalog.copy(
            settings = ReaderSettings(libraryOrder = LibraryOrder.RECENTLY_READ),
            readingStates = mapOf(
                "ficciones" to ReadingState(progressFraction = 0.37f, updatedAtEpochMs = 1_000),
                "rayuela" to ReadingState(progressFraction = 0.12f, updatedAtEpochMs = 2_000),
                "dubliners" to ReadingState(progressFraction = 1f),
            ),
        )
    }

    private fun failureCatalog() = Catalog(
        books = listOf(
            LibraryFixtures.readable("ficciones", "Ficciones", "Jorge Luis Borges", "ficciones.epub", hasCover = true),
            LibraryFixtures.rejected(
                id = "broken",
                title = "A Damaged Download",
                contentStatus = BookContentStatus.CORRUPT,
                reason = "CORRUPT_ARCHIVE",
                author = "Unknown",
                fileName = "damaged.epub",
            ),
            LibraryFixtures.rejected(
                id = "locked",
                title = "Bought From a Store",
                contentStatus = BookContentStatus.DRM_PROTECTED,
                reason = "DRM_PROTECTED",
                author = "Ada Lovelace",
                fileName = "store-purchase.epub",
            ),
            LibraryFixtures.unavailable(
                id = "moved",
                title = "Cien años de soledad",
                availability = SourceAvailability.MISSING,
                author = "Gabriel García Márquez",
                fileName = "cien-anos.epub",
            ),
            LibraryFixtures.unavailable(
                id = "revoked",
                title = "Down and Out in Paris and London",
                availability = SourceAvailability.PERMISSION_LOST,
                author = "George Orwell",
                fileName = "down-and-out.epub",
                origin = SourceOrigin.FOLDER,
                folderId = "content://tree/novels",
            ),
        ),
        readingStates = mapOf("moved" to ReadingState(progressFraction = 0.61f)),
    )

    /** Deterministic stand-in for real cover art, so the goldens never depend on a book file. */
    private object FakeCovers : CoverLoader {
        private val colors = mapOf(
            "ficciones" to Color.rgb(0x2E, 0x4A, 0x7A),
            "rayuela" to Color.rgb(0x8A, 0x3B, 0x2E),
        )

        override suspend fun load(bookId: String): ImageBitmap? {
            val color = colors[bookId] ?: return null
            return Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(color) }
                .asImageBitmap()
        }
    }
}

/** 1080p reference phone, matching the `Phone_Mid_API36` AVD used for the emulator pass. */
internal const val REFERENCE_PHONE = "w411dp-h914dp-xxhdpi"

/** 720p, 2 GB phone, matching the `Phone_Low_API33` AVD used for cramped layouts. */
internal const val COMPACT_PHONE = "w360dp-h640dp-xhdpi"

/**
 * The `sw600dp` boundary itself, matching the `Tablet_Low_API33` AVD, which is
 * exactly 600 dp wide. Every wide-layout claim is made here first: a breakpoint
 * that is wrong by one dp is wrong only here.
 */
internal const val TABLET_BOUNDARY = "w600dp-h960dp-xhdpi"

/** 10" tablet at 2560 x 1600, matching the `Tablet_Mid_API36` AVD: 1280 x 800 dp. */
internal const val TABLET_LARGE = "w1280dp-h800dp-land-xhdpi"

/** The reference phone turned on its side; the same device the reader's goldens use. */
internal const val LANDSCAPE_PHONE = "w914dp-h411dp-land-xxhdpi"
