package com.cedagova.fastreader.account.library

import com.cedagova.reader.library.model.ReaderLibraryStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one thing the shelf owns that the engine does not: the window in which an
 * account removal can still be taken back (REQ-508).
 *
 * Every other method here is a pass-through, and the assertions on those are
 * about *which* operation the shelf asks for — `delete` then `restore`, and
 * nothing else — because "the device's own file and grant are never touched" is
 * in the end a claim about what this class can call at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountShelfTest {

    @Test
    fun `removing sends the delete at once and offers undo`() = runTest {
        val actions = RecordingActions()
        val shelf = shelf(actions)

        shelf.removeFromAccount("acc-1", "Ficciones")
        runCurrent()

        assertEquals(listOf("remove:acc-1"), actions.calls)
        assertEquals(AccountRemoval("acc-1", "Ficciones"), shelf.undo.value)
    }

    @Test
    fun `the offer lasts the confirmation lifetime and then stops`() = runTest {
        val actions = RecordingActions()
        val shelf = shelf(actions)

        shelf.removeFromAccount("acc-1", "Ficciones")
        runCurrent()
        advanceTimeBy(AccountShelf.DEFAULT_UNDO_WINDOW_MS - 1)
        runCurrent()
        assertEquals("still on offer just before the window closes", "acc-1", shelf.undo.value?.bookId)

        advanceTimeBy(2)
        runCurrent()

        assertNull("there is no durable recovery after it", shelf.undo.value)
        assertEquals("and nothing more was sent", listOf("remove:acc-1"), actions.calls)
    }

    @Test
    fun `undo sends the contract's restore and ends the offer`() = runTest {
        val actions = RecordingActions()
        val shelf = shelf(actions)

        shelf.removeFromAccount("acc-1", "Ficciones")
        runCurrent()
        shelf.undoRemove()
        runCurrent()

        assertEquals(listOf("remove:acc-1", "undo:acc-1"), actions.calls)
        assertNull(shelf.undo.value)

        // The window that is already spent must not fire again over a later offer.
        advanceTimeBy(AccountShelf.DEFAULT_UNDO_WINDOW_MS * 2)
        runCurrent()
        assertEquals(listOf("remove:acc-1", "undo:acc-1"), actions.calls)
    }

    @Test
    fun `undo with nothing on offer sends nothing`() = runTest {
        val actions = RecordingActions()
        val shelf = shelf(actions)

        shelf.undoRemove()
        runCurrent()

        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun `a second removal supersedes the first offer`() = runTest {
        val actions = RecordingActions()
        val shelf = shelf(actions)

        shelf.removeFromAccount("acc-1", "Ficciones")
        runCurrent()
        advanceTimeBy(AccountShelf.DEFAULT_UNDO_WINDOW_MS / 2)
        shelf.removeFromAccount("acc-2", "Rayuela")
        runCurrent()

        assertEquals("acc-2", shelf.undo.value?.bookId)

        // The first book's timer must not close the second book's offer.
        advanceTimeBy(AccountShelf.DEFAULT_UNDO_WINDOW_MS / 2 + 1)
        runCurrent()
        assertEquals("acc-2", shelf.undo.value?.bookId)

        shelf.undoRemove()
        runCurrent()
        assertEquals(listOf("remove:acc-1", "remove:acc-2", "undo:acc-2"), actions.calls)
    }

    @Test
    fun `signing out ends the offer`() = runTest {
        val actions = RecordingActions()
        val state = MutableStateFlow(AccountLibraryState(phase = AccountSyncPhase.IDLE, userId = "user-1"))
        val shelf = AccountShelf(actions, state, backgroundScope)

        shelf.removeFromAccount("acc-1", "Ficciones")
        runCurrent()
        state.value = AccountLibraryState.SIGNED_OUT
        runCurrent()

        assertNull("the row it named is off the shelf (D4)", shelf.undo.value)
    }

    private fun kotlinx.coroutines.test.TestScope.shelf(actions: AccountLibraryActions) = AccountShelf(
        actions = actions,
        state = MutableStateFlow(AccountLibraryState(phase = AccountSyncPhase.IDLE, userId = "user-1")),
        scope = backgroundScope,
    )

    /** Every library operation the shelf can reach, in the order it asked for them. */
    private class RecordingActions : AccountLibraryActions {
        val calls = mutableListOf<String>()

        override fun refresh() {
            calls += "refresh"
        }

        override fun removeFromAccount(bookId: String) {
            calls += "remove:$bookId"
        }

        override fun undoRemove(bookId: String) {
            calls += "undo:$bookId"
        }

        override fun recordOpened(bookId: String) {
            calls += "opened:$bookId"
        }

        override fun recordFinished(bookId: String) {
            calls += "finished:$bookId"
        }

        override fun recordStatus(bookId: String, status: ReaderLibraryStatus) {
            calls += "status:$bookId:$status"
        }
    }
}
