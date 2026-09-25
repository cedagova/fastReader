package com.cedagova.fastreader.account.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.account.AccountOutcome
import com.cedagova.fastreader.account.ReaderAccountActions
import com.cedagova.fastreader.account.ReaderAccountState
import com.cedagova.fastreader.library.ui.actionableNodes
import com.cedagova.fastreader.library.ui.allNodes
import com.cedagova.fastreader.library.ui.label
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * REQ-060 for the account surface, and the form-to-operation map the goldens
 * cannot show: every control announces a label and clears 48 dp, an outcome
 * is a live region so it is announced when it appears, and each button calls
 * exactly the operation its label names with what the reader typed.
 *
 * The clock is paused before the fields can take focus (see
 * `docs/agent-first-development.md`, "Testing a text field under
 * Robolectric").
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h2200dp-xxhdpi")
class ReaderAccountAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()

    private val recording = object : ReaderAccountActions {
        override fun requestEmailCode(email: String, newAccount: Boolean) {
            calls +=
                "requestEmailCode($email, $newAccount)"
        }
        override fun verifyEmailCode(email: String, code: String) {
            calls += "verifyEmailCode($email, $code)"
        }
        override fun signInWithPassword(email: String, password: String) {
            calls +=
                "signInWithPassword($email, $password)"
        }
        override fun requestRecoveryCode(email: String) {
            calls += "requestRecoveryCode($email)"
        }
        override fun verifyRecoveryCode(email: String, code: String) {
            calls += "verifyRecoveryCode($email, $code)"
        }
        override fun setPassword(newPassword: String) {
            calls += "setPassword($newPassword)"
        }
        override fun loadCapabilities() {
            calls += "loadCapabilities()"
        }
        override fun signOut() {
            calls += "signOut()"
        }
        override fun signOutOtherDevices() {
            calls += "signOutOtherDevices()"
        }
        override fun dismissOutcome() {
            calls += "dismissOutcome()"
        }
    }

    @Before
    fun pauseTheClock() {
        composeRule.mainClock.autoAdvance = false
    }

    private fun show(state: ReaderAccountState) {
        composeRule.setContent {
            FastReaderTheme {
                ReaderAccountScreen(state = state, actions = recording, onBack = {})
            }
        }
        settle()
    }

    private fun settle() {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
    }

    @Test
    fun `every control a screen reader can act on says what it is, signed out`() {
        show(ReaderAccountState.SignedOut(outcome = AccountOutcome.CodeSent))

        val unlabelled = composeRule.actionableNodes().filter { it.label().isBlank() }
        assertEquals("actionable nodes with no label", emptyList<String>(), unlabelled.map { it.config.toString() })
        val labels = composeRule.actionableNodes().map { it.label() }
        listOf(
            "Back",
            "Create a new account for this address",
            "Send code", "Verify code",
            "Show", "Sign in with password",
            "Send recovery code", "Verify recovery code",
            "Dismiss",
        ).forEach { expected ->
            assertTrue("no control announces \"$expected\", only $labels", labels.contains(expected))
        }
    }

    @Test
    fun `every control a screen reader can act on says what it is, signed in`() {
        show(SIGNED_IN.copy(capabilities = CAPABILITIES))

        val labels = composeRule.actionableNodes().map { it.label() }
        listOf(
            "Back",
            "Refresh capabilities",
            "Show",
            "Set password",
            "Sign out",
            "Sign out other devices",
        ).forEach { expected ->
            assertTrue("no control announces \"$expected\", only $labels", labels.contains(expected))
        }
        assertTrue(
            "the request id is on the page",
            composeRule.allNodes().any { it.label() == "Request id 0f1e2d3c-4b5a-4697-8877-665544332211" },
        )
    }

    @Test
    fun `every control is at least forty-eight density-independent pixels tall`() {
        show(ReaderAccountState.SignedOut(outcome = AccountOutcome.NetworkUnavailable))

        val minimum = with(composeRule.density) { 48.dp.toPx() }
        val short = composeRule.actionableNodes().filter { it.boundsInRoot.height < minimum - 1f }
        assertEquals(
            "controls shorter than 48 dp: " + short.map {
                "${it.label()} @ ${it.boundsInRoot}"
            },
            0,
            short.size,
        )
    }

    @Test
    fun `an outcome is announced when it appears`() {
        show(
            ReaderAccountState.SignedOut(
                outcome = AccountOutcome.ProviderRejected(400, "invalid_credentials", "Invalid login credentials"),
            ),
        )

        val outcome = composeRule.onNodeWithTag("account_outcome").fetchSemanticsNode()
        assertEquals(
            androidx.compose.ui.semantics.LiveRegionMode.Polite,
            outcome.config.getOrElseNullable(SemanticsProperties.LiveRegion) { null },
        )
        assertEquals(
            "The sign-in service rejected this (HTTP 400 · invalid_credentials): Invalid login credentials",
            outcome.label(),
        )
    }

    // --- Each form calls exactly its operation, with what was typed -----------

    @Test
    fun `the code form sends and verifies with the typed address and code`() {
        show(ReaderAccountState.SignedOut())

        composeRule.onNodeWithTag("account_email").performTextInput("reader@example.test")
        composeRule.onNodeWithTag("account_new_account").performClick()
        settle()
        composeRule.onNodeWithTag("account_send_code").performClick()
        composeRule.onNodeWithTag("account_code").performTextInput("12x3456789")
        settle()
        composeRule.onNodeWithTag("account_verify_code").performClick()

        assertEquals(
            listOf("requestEmailCode(reader@example.test, true)", "verifyEmailCode(reader@example.test, 123456)"),
            calls,
        )
    }

    @Test
    fun `the password form signs in with the typed password, masked until shown`() {
        show(ReaderAccountState.SignedOut())

        composeRule.onNodeWithTag("account_email").performTextInput("reader@example.test")
        composeRule.onNodeWithTag("account_password").performTextInput("hunter2!")
        settle()
        val masked = composeRule.onNodeWithTag("account_password").fetchSemanticsNode()
        assertTrue(
            "the password must be masked",
            masked.config.getOrElseNullable(SemanticsProperties.Password) { null } != null,
        )
        assertTrue(masked.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text != "hunter2!")
        composeRule.onNodeWithTag("account_password_reveal").performClick()
        settle()
        assertEquals(
            "hunter2!",
            composeRule.onNodeWithTag(
                "account_password",
            ).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.EditableText) {
                null
            }?.text,
        )
        composeRule.onNodeWithTag("account_sign_in_password").performClick()

        assertEquals(listOf("signInWithPassword(reader@example.test, hunter2!)"), calls)
    }

    @Test
    fun `the recovery form requests and verifies a recovery code`() {
        show(ReaderAccountState.SignedOut())

        composeRule.onNodeWithTag("account_email").performTextInput("reader@example.test")
        settle()
        composeRule.onNodeWithTag("account_send_recovery").performClick()
        composeRule.onNodeWithTag("account_code").performTextInput("654321")
        settle()
        composeRule.onNodeWithTag("account_verify_recovery").performClick()

        assertEquals(
            listOf("requestRecoveryCode(reader@example.test)", "verifyRecoveryCode(reader@example.test, 654321)"),
            calls,
        )
    }

    @Test
    fun `the signed-in actions call capabilities, set password and the two sign-outs`() {
        show(SIGNED_IN)

        composeRule.onNodeWithTag("account_capabilities").performClick()
        composeRule.onNodeWithTag("account_new_password").performTextInput("new-secret")
        settle()
        composeRule.onNodeWithTag("account_set_password").performClick()
        composeRule.onNodeWithTag("account_sign_out_others").performClick()
        composeRule.onNodeWithTag("account_sign_out").performClick()

        assertEquals(
            listOf("loadCapabilities()", "setPassword(new-secret)", "signOutOtherDevices()", "signOut()"),
            calls,
        )
    }

    @Test
    fun `nothing can be tapped while an operation is in flight or when not configured`() {
        show(ReaderAccountState.NotConfigured(listOf("reader.supabaseUrl")))
        assertEquals(listOf("Back"), composeRule.actionableNodes().map { it.label() })
        assertTrue(composeRule.allNodes().any { it.label().contains("Missing: reader.supabaseUrl") })
    }
}
