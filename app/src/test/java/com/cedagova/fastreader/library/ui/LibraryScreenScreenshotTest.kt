package com.cedagova.fastreader.library.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.library.BookContentStatus
import com.cedagova.fastreader.library.Catalog
import com.cedagova.fastreader.library.IngestionState
import com.cedagova.fastreader.library.ReadingState
import com.cedagova.fastreader.library.ResumeBlocked
import com.cedagova.fastreader.library.ResumeBlockedReason
import com.cedagova.fastreader.library.ScanTrigger
import com.cedagova.fastreader.library.SourceAvailability
import com.cedagova.fastreader.library.SourceOrigin
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import com.github.takahirom.roborazzi.captureRoboImage
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

    @Test
    fun emptyLibraryExplainsBothWaysToAddBooks() {
        capture("library_empty", state(Catalog()))
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

    /** Cramped 720p phone (`Phone_Low_API33`) at a large system font scale. */
    @Test
    @Config(sdk = [35], qualifiers = COMPACT_PHONE)
    fun theListSurvivesACrampedScreenAtALargeFontScale() {
        capture("library_compact_large_font", state(failureCatalog()), fontScale = 1.3f)
    }

    private fun capture(
        name: String,
        state: LibraryUiState,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            ScaledFonts(fontScale) {
                FastReaderTheme(darkTheme = darkTheme) {
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
        composeRule.onRoot().captureRoboImage("screenshots/$name.png")
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
    ) = buildLibraryUiState(catalog, ingestion, query, resumeBlocked)

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
private const val REFERENCE_PHONE = "w411dp-h914dp-xxhdpi"

/** 720p, 2 GB phone, matching the `Phone_Low_API33` AVD used for cramped layouts. */
private const val COMPACT_PHONE = "w360dp-h640dp-xhdpi"
