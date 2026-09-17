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
import com.cedagova.fastreader.account.library.AccountLibraryState
import com.cedagova.fastreader.account.library.AccountSyncError
import com.cedagova.fastreader.account.library.AccountSyncPhase
import com.cedagova.fastreader.library.IngestionState
import com.cedagova.fastreader.settings.FontSize
import com.cedagova.fastreader.ui.theme.FastReaderTheme
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

    private fun shelf(
        vararg books: com.cedagova.fastreader.account.library.AccountBook,
        account: AccountLibraryState = LibraryAccountFixtures.signedIn(*books),
        accountUndo: AccountUndoNotice? = null,
    ): LibraryUiState = buildLibraryUiState(
        catalog = LibraryAccountFixtures.deviceCatalog(),
        ingestion = IngestionState.Idle,
        query = "",
        account = account,
        accountUndo = accountUndo,
    )

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
