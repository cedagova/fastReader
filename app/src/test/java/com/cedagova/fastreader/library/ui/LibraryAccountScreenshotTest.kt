package com.cedagova.fastreader.library.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.account.library.AccountDownloadsState
import com.cedagova.fastreader.account.library.AccountImportsState
import com.cedagova.reader.library.sync.AccountLibraryState
import com.cedagova.reader.library.sync.AccountRemotePosition
import com.cedagova.reader.library.sync.AccountSyncError
import com.cedagova.reader.library.sync.AccountSyncPhase
import com.cedagova.fastreader.account.library.BookDownloadState
import com.cedagova.fastreader.account.library.DownloadProblem
import com.cedagova.fastreader.account.library.BookImportState
import com.cedagova.fastreader.account.library.ImportOffer
import com.cedagova.fastreader.account.library.ImportProblem
import com.cedagova.fastreader.account.library.ImportsOff
import com.cedagova.fastreader.library.IngestionState
import com.cedagova.fastreader.settings.FontSize
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import com.cedagova.reader.library.model.PublicationFailureCategory
import com.cedagova.reader.library.model.ReaderCapabilityReason
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The account shelf's UI regression gate (LEAF703): one golden per state this
 * leaf adds, and not one byte of any state it does not.
 *
 * Every golden here is a *new* file. The states of `LibraryScreenScreenshotTest`
 * are the signed-out shelf, which D4 says is exactly v1.6.0's, and
 * `verifyRoborazziDebug` proving those unchanged is half of REQ-501's
 * acceptance.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = REFERENCE_PHONE)
class LibraryAccountScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * REQ-501: the account's book beside the device's, as one row. Ficciones is
     * both, so it keeps its cover, its progress and its remove button and gains
     * one new control — the account removal — and Rayuela, which the account
     * does not have, is untouched.
     */
    @Test
    fun anAccountBookOnTheDeviceIsOneRowWithOneExtraAction() {
        capture("library_account_row", shelf(LibraryAccountFixtures.ficcionesInAccount()))
    }

    /** REQ-501: a book the account has and this device does not — named, and not openable. */
    @Test
    fun anAccountBookNotOnThisDeviceSaysSo() {
        capture("library_account_only_row", shelf(LibraryAccountFixtures.dublinersInAccountOnly()))
    }

    /** The states table: the first load of the account library is said, not silent. */
    @Test
    fun theFirstLoadOfTheAccountLibraryIsSaid() {
        capture(
            "library_account_bootstrapping",
            shelf(account = AccountLibraryState(phase = AccountSyncPhase.BOOTSTRAPPING, userId = "user-1")),
        )
    }

    /** The states table: offline, with the queue named and every device book still usable. */
    @Test
    fun offlineTheShelfSaysWhatIsWaiting() {
        capture(
            "library_account_offline",
            shelf(
                LibraryAccountFixtures.ficcionesInAccount(),
                account = AccountLibraryState(
                    phase = AccountSyncPhase.OFFLINE,
                    userId = "user-1",
                    books = listOf(LibraryAccountFixtures.ficcionesInAccount()),
                    queued = 2,
                ),
            ),
        )
    }

    /** The states table: `reader.sync.v1` unavailable is deferred with the backend's reason, never a sign-out. */
    @Test
    fun aCapabilityTheBackendHasNotEnabledIsShownWithItsReason() {
        capture(
            "library_account_unavailable",
            shelf(
                LibraryAccountFixtures.ficcionesInAccount(),
                account = AccountLibraryState(
                    phase = AccountSyncPhase.DEFERRED,
                    userId = "user-1",
                    books = listOf(LibraryAccountFixtures.ficcionesInAccount()),
                    capabilityReason = ReaderCapabilityReason.SERVICE_NOT_ENABLED,
                ),
            ),
        )
    }

    /**
     * REQ-516 / D4: a session the backend stopped accepting is the signed-out
     * state with its reason shown once — the account rows are gone and the
     * device books are exactly as they were.
     */
    @Test
    fun aSessionTheBackendNoLongerAcceptsSaysSoOnce() {
        capture(
            "library_account_session_gone",
            shelf(
                account = AccountLibraryState(
                    phase = AccountSyncPhase.SIGNED_OUT,
                    lastError = AccountSyncError.SessionGone("session_revoked", "01JB7Q4KQZ8X"),
                ),
            ),
        )
    }

    /** REQ-508: the question says the book leaves the account on every device. */
    @Test
    fun removingFromTheAccountAsksFirstAndSaysItReachesEveryDevice() {
        capture(
            "library_account_remove_confirm",
            shelf(LibraryAccountFixtures.ficcionesInAccount()),
            click = "library_account_remove_${LibraryAccountFixtures.FICCIONES_ID}",
        )
    }

    /** REQ-508: the contract's one immediate Undo, saying what was and was not touched. */
    @Test
    fun theAccountRemovalCanStillBeTakenBack() {
        capture(
            "library_account_undo",
            shelf(
                account = AccountLibraryState(phase = AccountSyncPhase.IDLE, userId = "user-1"),
                accountUndo = AccountUndoNotice(LibraryAccountFixtures.DUBLINERS_ACCOUNT_ID, "Dubliners"),
            ),
        )
    }

    /** REQ-301 on the cramped 720p phone: the whole shelf, nothing clipped. */
    @Test
    @Config(sdk = [35], qualifiers = COMPACT_PHONE)
    fun theAccountShelfSurvivesACrampedScreen() {
        capture("library_account_compact", wholeShelf())
    }

    /** REQ-301 at the largest text size: the new line and the new control both survive. */
    @Test
    fun theAccountShelfIsWholeAtTheLargestFontSize() {
        capture("library_account_large_font", wholeShelf(), fontSize = FontSize.EXTRA_LARGE)
    }

    /** REQ-206: every word this leaf adds comes from `values-es` on a Spanish device. */
    @Test
    @Config(qualifiers = "+es")
    fun theAccountShelfIsSpanishOnASpanishDevice() {
        capture("library_account_spanish", wholeShelf())
    }

    // ---- adding a device book to the account (#117) -------------------------------------

    /**
     * REQ-505: the question. It says the file's bytes go to the Reader account
     * and are kept there, quotes how much that is and the deployment's own cap,
     * and offers Add or Cancel. Nothing has been sent when this is on screen.
     */
    @Test
    fun theConsentQuestionSaysTheBytesLeaveAndAreKept() {
        captureDialog(
            "library_account_add_consent",
            shelf(
                LibraryAccountFixtures.ficcionesInAccount(),
                imports = importing(
                    BookImportState.Consent(sizeBytes = 8_388_608, maxSourceBytes = 52_428_800),
                ),
            ),
        )
    }

    /** REQ-506: the transfer on the row, with its own Cancel. */
    @Test
    fun anAddInProgressShowsOnTheRowAndCanBeCalledOff() {
        capture(
            "library_account_add_sending",
            shelf(
                LibraryAccountFixtures.ficcionesInAccount(),
                imports = importing(BookImportState.Sending(fraction = 0.42f)),
            ),
        )
    }

    /**
     * REQ-507: the backend's category in plain words, with the cap it quoted,
     * and the sentence that answers the reader's real question — the book here
     * is exactly as it was.
     */
    @Test
    fun aRefusedAddShowsTheBackendsCategoryAndLeavesTheBookAlone() {
        capture(
            "library_account_add_refused",
            shelf(
                LibraryAccountFixtures.ficcionesInAccount(),
                imports = importing(
                    BookImportState.Refused(
                        problem = ImportProblem.Category(PublicationFailureCategory.TOO_LARGE),
                        requestId = "01JB7Q4KQZ8X",
                        sizeBytes = 73_400_320,
                        maxSourceBytes = 52_428_800,
                        retryable = false,
                    ),
                ),
            ),
        )
    }

    /** The deployment admits no imports: the action is not offered, and the reason is. */
    @Test
    fun aDeploymentThatAdmitsNothingShowsTheReasonInsteadOfTheAction() {
        capture(
            "library_account_add_off",
            shelf(
                LibraryAccountFixtures.ficcionesInAccount(),
                imports = AccountImportsState(offer = ImportOffer.Available, disabled = ImportsOff("01JB7Q4KQZ8X")),
            ),
        )
    }

    /**
     * #139: the account's import capability is unavailable with a typed reason —
     * here exhausted active capacity — so the sentence says what to expect and
     * the reason is quoted as the code under it.
     */
    @Test
    fun anUnavailableImportCapabilityShowsItsReasonInsteadOfTheAction() {
        capture(
            "library_account_add_off_capacity",
            shelf(
                LibraryAccountFixtures.ficcionesInAccount(),
                imports = AccountImportsState(offer = ImportOffer.Unavailable(ReaderCapabilityReason.QUOTA_EXHAUSTED)),
            ),
        )
    }

    /** REQ-206: the add-to-account words come from `values-es` too. */
    @Test
    @Config(qualifiers = "+es")
    fun theAddToAccountFlowIsSpanishOnASpanishDevice() {
        capture(
            "library_account_add_spanish",
            shelf(
                LibraryAccountFixtures.ficcionesInAccount(),
                imports = importing(BookImportState.Sending(fraction = 0.42f)),
            ),
        )
    }


    // ---- downloading an account book, and freeing the copy (#119, REQ-510) --------------

    /**
     * The states table's "Open account book not on device": the row says where
     * the book is, and offers the one thing that changes that. Nothing here
     * opens — the control is **Download and open**, in that order.
     */
    @Test
    fun anAccountBookNotOnThisDeviceOffersToFetchIt() {
        capture(
            "library_account_not_on_device",
            shelf(LibraryAccountFixtures.dublinersInAccountOnly()),
        )
    }

    /** REQ-510: the transfer on the row, with its own Cancel and nothing open behind it. */
    @Test
    fun aDownloadInProgressShowsOnTheRowAndCanBeCalledOff() {
        capture(
            "library_account_downloading",
            shelf(
                LibraryAccountFixtures.dublinersInAccountOnly(),
                downloads = downloading(BookDownloadState.Downloading(received = 2_306_867, total = 6_291_456)),
            ),
        )
    }

    /**
     * REQ-510 after the copy has landed: one row, the account's and the
     * device's, with the cover-less placeholder, the reader's place in it, and
     * the two removals that mean different things — **Remove from account**
     * and **Remove downloaded copy**. There is no row-removal X, because
     * freeing the copy *is* this row's removal.
     */
    @Test
    fun aDownloadedBookReadsAsBothAndOffersToFreeItsCopy() {
        capture(
            "library_account_downloaded",
            shelf(
                LibraryAccountFixtures.dublinersInAccountOnly(),
                catalog = LibraryAccountFixtures.catalogWithDownloadedCopy(),
            ),
        )
    }

    /**
     * #142: a download the provider sent to another origin is refused with its
     * own sentence, the book stays in the account, and no Try again is offered.
     */
    @Test
    fun aDownloadRedirectedToAnotherOriginIsRefusedWithItsOwnReason() {
        capture(
            "library_account_download_redirected",
            shelf(
                LibraryAccountFixtures.dublinersInAccountOnly(),
                downloads = downloading(BookDownloadState.Refused(DownloadProblem.REDIRECTED)),
            ),
        )
    }

    /** D2: the question before the bytes go, saying what it frees and what it keeps. */
    @Test
    fun freeingTheCopyAsksFirstAndSaysTheAccountKeepsTheBook() {
        capture(
            "library_account_copy_remove_confirm",
            shelf(
                LibraryAccountFixtures.dublinersInAccountOnly(),
                catalog = LibraryAccountFixtures.catalogWithDownloadedCopy(),
            ),
            click = "library_account_copy_remove_${LibraryAccountFixtures.DUBLINERS_COPY_ID}",
        )
    }

    /** REQ-206: every word #119 adds comes from `values-es` on a Spanish device. */
    @Test
    @Config(qualifiers = "+es")
    fun theDownloadFlowIsSpanishOnASpanishDevice() {
        capture(
            "library_account_download_spanish",
            shelf(
                LibraryAccountFixtures.dublinersInAccountOnly(),
                downloads = downloading(BookDownloadState.Downloading(received = 2_306_867, total = 6_291_456)),
            ),
        )
    }

    /** The in-flight state on Dubliners, the one account-only book in the fixtures. */
    private fun downloading(state: BookDownloadState): AccountDownloadsState = AccountDownloadsState(
        byAccountBookId = mapOf(LibraryAccountFixtures.DUBLINERS_ACCOUNT_ID to state),
    )

    /** The in-flight state on Rayuela, the one device-only book in the fixtures. */
    private fun importing(state: BookImportState): AccountImportsState =
        AccountImportsState(offer = ImportOffer.Available, byDeviceBookId = mapOf(LibraryAccountFixtures.RAYUELA_ID to state))

    /** Both kinds of account row and both device rows, with the offline note over them. */
    private fun wholeShelf(): LibraryUiState = shelf(
        LibraryAccountFixtures.ficcionesInAccount(),
        LibraryAccountFixtures.dublinersInAccountOnly(),
        account = AccountLibraryState(
            phase = AccountSyncPhase.OFFLINE,
            userId = "user-1",
            books = listOf(
                LibraryAccountFixtures.ficcionesInAccount(),
                LibraryAccountFixtures.dublinersInAccountOnly(),
            ),
            queued = 1,
        ),
    )

    /**
     * REQ-511's shelf slot: Ficciones is 37 % read on this device and the account
     * holds a place at 68 %, so the row says both numbers — and Rayuela, which the
     * account does not have, still says one.
     *
     * The image is what settles that it is one line with two numbers rather than
     * two lines or a replaced number, and that the row's other controls are where
     * they were. Which number is *the* position is not in question here: this is a
     * display choice, and nothing on the shelf moves the reader's place.
     */
    @Test
    fun anAccountPlaceFurtherOnIsShownBesideThisDevices() {
        capture(
            "library_account_ahead",
            shelf(
                LibraryAccountFixtures.ficcionesInAccount().copy(
                    remotePosition = AccountRemotePosition(
                        href = "OEBPS/ch8.xhtml",
                        chapterTitle = "El jardín de senderos que se bifurcan",
                        progression = 0.68,
                        percent = 68.0,
                        updatedAt = "2026-09-20T10:00:00Z",
                        revision = 4,
                    ),
                ),
            ),
        )
    }

    private fun shelf(
        vararg books: com.cedagova.reader.library.sync.AccountBook,
        account: AccountLibraryState = LibraryAccountFixtures.signedIn(*books),
        accountUndo: AccountUndoNotice? = null,
        imports: AccountImportsState = AccountImportsState(offer = ImportOffer.Available),
        downloads: AccountDownloadsState = AccountDownloadsState.NONE,
        catalog: com.cedagova.fastreader.library.Catalog = LibraryAccountFixtures.deviceCatalog(),
    ): LibraryUiState = buildLibraryUiState(
        catalog = catalog,
        ingestion = IngestionState.Idle,
        query = "",
        account = account,
        accountUndo = accountUndo,
        imports = imports,
        downloads = downloads,
    )

    /**
     * A state whose dialog is already open, captured as a screen.
     *
     * The consent question is driven by the state rather than by a tap the
     * screen remembers, so there is nothing to click first — but a dialog is
     * still its own window, and the compose root alone would not contain it.
     */
    private fun captureDialog(name: String, state: LibraryUiState) {
        composeRule.setContent {
            FastReaderTheme { LibraryScreen(state = state, onQueryChange = {}, onAddBooks = {}, onAddFolder = {}, onRefresh = {}, onRemove = {}, onGrantAccess = {}, onOpen = {}) }
        }
        composeRule.waitForIdle()
        captureScreenRoboImage("screenshots/$name.png")
    }

    private fun capture(
        name: String,
        state: LibraryUiState,
        fontScale: Float = 1f,
        fontSize: FontSize = FontSize.MEDIUM,
        /** A test tag to tap before capturing, for a state that only a tap reaches. */
        click: String? = null,
    ) {
        composeRule.setContent {
            ScaledFonts(fontScale) {
                FastReaderTheme(fontSize = fontSize) {
                    LibraryScreen(
                        state = state,
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
        if (click == null) {
            composeRule.onRoot().captureRoboImage("screenshots/$name.png")
            return
        }
        // A dialog is its own window, so the compose root is no longer unique;
        // the screen capture is the only one that contains what the tap opened.
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
}
