package com.cedagova.fastreader

import androidx.compose.runtime.saveable.SaverScope
import com.cedagova.fastreader.library.LaunchDestination
import com.cedagova.fastreader.library.ResumeBlocked
import com.cedagova.fastreader.library.ResumeBlockedReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typed back stack that replaced `MainActivity`'s eight saved flags (#202).
 *
 * Each case is one of the flag transitions it replaced, so the routing the
 * reader sees (REQ-009, REQ-103) is the same; `LaunchDestinationTest` still owns
 * the launch rule itself.
 */
class NavigationTest {

    private val blocked = ResumeBlocked("b-1", ResumeBlockedReason.PERMISSION_LOST)

    @Test
    fun `nothing is on screen until launch routing decides`() {
        assertFalse(BackStack.UNROUTED.routed)
        assertNull(BackStack.UNROUTED.top)
    }

    @Test
    fun `a readable last-read book opens over the library, and back returns to the library`() {
        val launched = BackStack.launchedInto(LaunchDestination.Reader("b-1"))

        assertEquals(Destination.Reader("b-1", chosenByLaunch = true), launched.top)
        assertEquals("b-1", launched.openBookId)
        assertEquals(BackStack(listOf(Destination.Library())), launched.pop())
    }

    @Test
    fun `a blocked last-read book lands on the library with its notice, which can be put away`() {
        val launched = BackStack.launchedInto(LaunchDestination.Library(blocked))

        assertEquals(Destination.Library(blocked), launched.top)
        assertNull(launched.openBookId)
        assertEquals(Destination.Library(), launched.dismissResumeNotice().top)
    }

    @Test
    fun `the notice outlives a book opened from the library`() {
        val stack = BackStack.launchedInto(LaunchDestination.Library(blocked)).push(Destination.Reader("b-2"))

        assertEquals(Destination.Library(blocked), stack.pop().top)
        assertEquals(stack, stack.dismissResumeNotice())
    }

    @Test
    fun `only a book the launch chose goes back to the library when it cannot open`() {
        val launched = BackStack.launchedInto(LaunchDestination.Reader("b-1"))
        assertEquals(
            BackStack(listOf(Destination.Library(ResumeBlocked("b-1", ResumeBlockedReason.UNREADABLE)))),
            launched.cannotOpen("b-1"),
        )

        val tapped = BackStack.LIBRARY.push(Destination.Reader("b-2"))
        assertEquals(tapped, tapped.cannotOpen("b-2"))
    }

    @Test
    fun `settings sit over the open book and the account over settings`() {
        val reading = BackStack.LIBRARY.push(Destination.Reader("b-2"))
        val account = reading.push(Destination.Settings).push(Destination.ReaderAccount)

        assertEquals(Destination.ReaderAccount, account.top)
        // The book stays open under both, so it is still the account's "opened".
        assertEquals("b-2", account.openBookId)
        assertEquals(Destination.Settings, account.pop().top)
        assertEquals(reading, account.pop().pop())
    }

    @Test
    fun `a handed-over book replaces the library and a library book but not what was opened over it`() {
        assertTrue(Destination.Library().showsHandedOverBook)
        assertTrue(Destination.Reader("b-1").showsHandedOverBook)
        assertFalse(Destination.Settings.showsHandedOverBook)
        assertFalse(Destination.ReaderAccount.showsHandedOverBook)
        assertEquals(BackStack(listOf(Destination.Library())), BackStack.LIBRARY)
    }

    @Test
    fun `every destination survives saved instance state`() {
        val stack = BackStack(
            listOf(
                Destination.Library(blocked),
                Destination.Reader("b-1", chosenByLaunch = true),
                Destination.Settings,
                Destination.ReaderAccount,
            ),
        )
        ResumeBlockedReason.entries.forEach { reason ->
            val withReason = BackStack(listOf(Destination.Library(ResumeBlocked("b-9", reason))))
            assertEquals(withReason, restore(save(withReason)))
        }
        assertEquals(stack, restore(save(stack)))
        assertEquals(BackStack.UNROUTED, restore(save(BackStack.UNROUTED)))
    }

    @Test
    fun `saved state that does not decode restores nothing, so launch routing runs again`() {
        assertNull(restore("""[{"type":"somewhere-new"}]"""))
        assertNull(restore("not json"))
    }

    private fun save(stack: BackStack): String = with(BackStack.SAVER) { SaverScope { true }.save(stack)!! }

    private fun restore(saved: String): BackStack? = BackStack.SAVER.restore(saved)
}
