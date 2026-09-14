package com.cedagova.fastreader.account.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.account.AccountActivity
import com.cedagova.fastreader.account.AccountOutcome
import com.cedagova.fastreader.account.LoadedCapabilities
import com.cedagova.fastreader.account.ReaderAccountActions
import com.cedagova.fastreader.account.ReaderAccountConfiguration
import com.cedagova.fastreader.account.ReaderAccountState
import com.cedagova.fastreader.settings.FontSize
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Reader account surface's UI regression gate (#100, REQ-401, REQ-404,
 * REQ-407, REQ-206, REQ-301).
 *
 * Every state is a literal handed to the stateless screen, so no SDK, network
 * or Keystore is behind any of these images: the three definition states, the
 * one transient outcome a reader is most likely to meet (the provider's own
 * rejection code, quoted), the document with its request id, and the two
 * stress renders the settings goldens already use — Spanish, and the
 * narrowest phone at the largest text.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TALL_PHONE)
class ReaderAccountScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** REQ-401's values-absent half: the missing keys named, nothing to tap. */
    @Test
    fun notConfiguredNamesTheMissingValues() {
        capture("account_not_configured", ReaderAccountState.NotConfigured(ReaderAccountConfiguration.PROPERTY_KEYS))
    }

    /** The sign-in methods in the contract's order: emailed code with the sign-up choice, password, recovery. */
    @Test
    fun signedOutShowsTheThreeSignInMethods() {
        capture("account_signed_out", ReaderAccountState.SignedOut())
    }

    /** REQ-404: the provider's rejection, with its code, under the form it rejected. */
    @Test
    fun aProviderRejectionQuotesTheProvidersCode() {
        capture(
            "account_signed_out_rejected",
            ReaderAccountState.SignedOut(
                outcome = AccountOutcome.ProviderRejected(400, "invalid_credentials", "Invalid login credentials"),
            ),
        )
    }

    /** An operation in flight: every control disabled and the line that says so. */
    @Test
    fun anOperationInFlightDisablesTheForms() {
        capture("account_signed_out_working", ReaderAccountState.SignedOut(activity = AccountActivity.REQUESTING_CODE))
    }

    /** Signed in as the address, before any capabilities call. */
    @Test
    fun signedInAsTheAddress() {
        capture("account_signed_in", SIGNED_IN)
    }

    /** REQ-407: the whole document as returned, with the request id of the call, and Refresh offered. */
    @Test
    fun capabilitiesAreShownInFullWithTheRequestId() {
        capture("account_signed_in_capabilities", SIGNED_IN.copy(capabilities = CAPABILITIES))
    }

    @Test
    fun theSameSurfaceOnADarkPage() {
        capture("account_dark", SIGNED_IN.copy(capabilities = CAPABILITIES), darkTheme = true)
    }

    /** REQ-206: every string of the densest new screen from `values-es`. */
    @Test
    @Config(sdk = [35], qualifiers = "es-$TALL_PHONE")
    fun theAccountScreenIsSpanishOnASpanishDevice() {
        capture("account_spanish", ReaderAccountState.SignedOut(outcome = AccountOutcome.CodeSent))
    }

    /** REQ-301: the 720p phone at font scale 2.0 and the app's largest size; every button still reachable and whole. */
    @Test
    @Config(sdk = [35], qualifiers = COMPACT_PHONE_SCROLLED)
    fun theSignedOutFormsSurviveTheNarrowestScreenAtTheLargestText() {
        capture("account_compact_large_font", ReaderAccountState.SignedOut(), fontSize = FontSize.EXTRA_LARGE, fontScale = 2f)
    }

    private fun capture(
        name: String,
        state: ReaderAccountState,
        darkTheme: Boolean = false,
        fontSize: FontSize = FontSize.MEDIUM,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            ScaledFonts(fontScale) {
                FastReaderTheme(darkTheme = darkTheme, fontSize = fontSize) {
                    ReaderAccountScreen(state = state, actions = NoActions, onBack = {})
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
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
        }
    }
}

/** The screen's actions, going nowhere: a golden is a still, so nothing here is ever tapped. */
internal object NoActions : ReaderAccountActions {
    override fun requestEmailCode(email: String, newAccount: Boolean) = Unit
    override fun verifyEmailCode(email: String, code: String) = Unit
    override fun signInWithPassword(email: String, password: String) = Unit
    override fun requestRecoveryCode(email: String) = Unit
    override fun verifyRecoveryCode(email: String, code: String) = Unit
    override fun setPassword(newPassword: String) = Unit
    override fun loadCapabilities() = Unit
    override fun signOut() = Unit
    override fun signOutOtherDevices() = Unit
    override fun dismissOutcome() = Unit
}

internal val SIGNED_IN = ReaderAccountState.SignedIn(
    userId = "3f2a9c8e-1b4d-4e6f-9a7b-2c5d8e1f4a6b",
    email = "reader@example.test",
)

/** The shape of a `reader.capabilities.v1` document, pretty-printed as the controller does, with a request id. */
internal val CAPABILITIES = LoadedCapabilities(
    document = """
        {
            "schemaVersion": "reader.capabilities.v1",
            "generatedAt": "2026-09-13T10:00:00Z",
            "freshUntil": "2026-09-13T10:05:00Z",
            "staleUntil": "2026-09-13T11:00:00Z",
            "compatibility": {
                "status": "compatible",
                "requestedVersion": "1.0.0",
                "minimumVersion": "1.0.0",
                "supportedMajor": 1
            },
            "capabilities": {
                "library": "available",
                "sync": "unavailable"
            }
        }
    """.trimIndent(),
    requestId = "0f1e2d3c-4b5a-4697-8877-665544332211",
)

/** The reference phone's width on a window tall enough to hold the whole surface at once. */
private const val TALL_PHONE = "w411dp-h1800dp-xxhdpi"

/** The 720p phone, unrolled far enough that every control is capturable at font scale 2. */
private const val COMPACT_PHONE_SCROLLED = "w360dp-h3400dp-xhdpi"
