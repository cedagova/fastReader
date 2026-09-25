package com.cedagova.fastreader.account.library

import com.cedagova.reader.library.model.ReaderLibraryStatus
import com.cedagova.reader.library.sync.AccountLibraryActions
import com.cedagova.reader.library.sync.AccountLibraryState
import com.cedagova.reader.library.sync.AccountSyncPhase
import com.cedagova.reader.library.sync.LocalReadingPosition
import com.cedagova.reader.library.testing.RecordingAccountLibraryActions
import com.cedagova.reader.library.testing.RecordingHostRecords
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
        val actions = RecordingAccountLibraryActions()
        val shelf = shelf(actions)

        shelf.removeFromAccount("acc-1", "Ficciones")
        runCurrent()

        assertEquals(listOf("remove:acc-1"), actions.calls)
        assertEquals(AccountRemoval("acc-1", "Ficciones"), shelf.undo.value)
    }

    @Test
    fun `the offer lasts the confirmation lifetime and then stops`() = runTest {
        val actions = RecordingAccountLibraryActions()
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
        val actions = RecordingAccountLibraryActions()
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
        val actions = RecordingAccountLibraryActions()
        val shelf = shelf(actions)

        shelf.undoRemove()
        runCurrent()

        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun `a second removal supersedes the first offer`() = runTest {
        val actions = RecordingAccountLibraryActions()
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

    /**
     * A window of zero is the degenerate confirmation: the offer opens and
     * closes in the same tick. `delay(0)` returns without suspending, so this
     * is the one shape in which the expiry can run *inside* the removal's own
     * turn, and the shelf must not still be holding its lock when it does.
     * The expiry is its own coroutine, so a held lock would park it rather
     * than hang the test; what this pins is that the shelf never depends on
     * that ordering, and that nothing is left on offer once the tick is over.
     */
    @Test
    fun `a zero window closes the offer in the same tick without deadlocking`() = runTest {
        val actions = RecordingAccountLibraryActions()
        val shelf = shelf(actions, undoWindowMs = 0)

        shelf.removeFromAccount("acc-1", "Ficciones")
        runCurrent()

        assertEquals("the delete still went at once", listOf("remove:acc-1"), actions.calls)
        assertNull("and the offer is already spent", shelf.undo.value)

        shelf.undoRemove()
        runCurrent()
        assertEquals("so there is nothing left to take back", listOf("remove:acc-1"), actions.calls)
    }

    /**
     * The same degenerate window on a dispatcher that runs a launched coroutine
     * at once, the way `Dispatchers.Main.immediate` does on the main thread.
     * Here the timer body genuinely runs *during* the removal's own turn, which
     * is the only shape in which it could meet a lock the removal still holds.
     */
    @Test
    fun `a zero window closes the offer even when the timer runs eagerly`() = runTest(UnconfinedTestDispatcher()) {
        val actions = RecordingAccountLibraryActions()
        val shelf = shelf(actions, undoWindowMs = 0)

        shelf.removeFromAccount("acc-1", "Ficciones")
        runCurrent()

        assertEquals(listOf("remove:acc-1"), actions.calls)
        assertNull(shelf.undo.value)

        shelf.undoRemove()
        runCurrent()
        assertEquals("nothing left to take back", listOf("remove:acc-1"), actions.calls)
    }

    @Test
    fun `a second removal supersedes the first even with a zero window`() = runTest {
        val actions = RecordingAccountLibraryActions()
        val shelf = shelf(actions, undoWindowMs = 0)

        shelf.removeFromAccount("acc-1", "Ficciones")
        shelf.removeFromAccount("acc-2", "Rayuela")
        runCurrent()

        assertEquals(listOf("remove:acc-1", "remove:acc-2"), actions.calls)
        assertNull("the second offer closed on its own window, the first on supersession", shelf.undo.value)

        shelf.undoRemove()
        runCurrent()
        advanceTimeBy(AccountShelf.DEFAULT_UNDO_WINDOW_MS)
        runCurrent()
        assertEquals(
            "neither spent timer resurrects an offer, and no restore is sent",
            listOf("remove:acc-1", "remove:acc-2"),
            actions.calls,
        )
    }

    @Test
    fun `signing out ends the offer`() = runTest {
        val actions = RecordingAccountLibraryActions()
        val state = MutableStateFlow(AccountLibraryState(phase = AccountSyncPhase.IDLE, userId = "user-1"))
        val shelf = AccountShelf(actions, AccountResumeOffers(RecordingHostRecords()), state, backgroundScope)

        shelf.removeFromAccount("acc-1", "Ficciones")
        runCurrent()
        state.value = AccountLibraryState.SIGNED_OUT
        runCurrent()

        assertNull("the row it named is off the shelf (D4)", shelf.undo.value)
    }

    /**
     * REQ-512 and REQ-508's "nothing on this device is touched", as an assertion
     * rather than as a reading of the code.
     *
     * [AccountShelf]'s only collaborator is [AccountLibraryActions] — everything
     * else it is given is the engine's state flow and a scope — so the whole set
     * of operations this surface can reach is the set this interface declares.
     * There is no `settings`, `note` or `bookmark` operation in it for the shelf
     * to call, and no catalog, file or grant operation either. Adding one would
     * fail here, which is what LEAF704's privacy statement inherits (AD-27).
     *
     * `recordPosition` is the seventh since #120: the portable position of an
     * account book. Its payload is built from the contract's own body type and
     * carries no token index or reading speed — `PortableReadingPositionTest`
     * asserts that key set — so the surface grew by a position and not by a new
     * category of data.
     *
     * Settling a resume offer (#121) is not on this interface since #147: it is a
     * host record FastReader keeps, reached through [AccountResumeOffers], whose
     * one operation writes a book row's note and puts nothing on the wire at all.
     * `AccountSyncEngineTest.a host record is stored verbatim and sends nothing`
     * holds that, and the next test holds that it is the shelf's only other
     * collaborator.
     */
    @Test
    fun `the shelf's whole library surface is these seven operations and one note`() {
        val declared = AccountLibraryActions::class.java.declaredMethods
            .map { it.name }
            .toSortedSet()

        assertEquals(
            "the shelf can reach no settings, note or bookmark operation, " +
                "and no catalog, file or grant operation",
            sortedSetOf(
                "recordFinished",
                "recordOpened",
                "recordPosition",
                "recordStatus",
                "refresh",
                "removeFromAccount",
                "undoRemove",
            ),
            declared,
        )
        assertEquals(
            "the one other operation the shelf can reach is settling a resume offer",
            sortedSetOf("settle"),
            AccountResumeOffers::class.java.declaredMethods
                .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .map { it.name }
                .toSortedSet(),
        )
    }

    /** The shelf is given the actions above, the resume-offer note, the engine's state, and a scope — nothing else. */
    @Test
    fun `the shelf is given nothing else to reach`() {
        val parameters = AccountShelf::class.java.declaredConstructors
            .first { it.parameterCount >= 4 }
            .parameterTypes
            .take(4)
            .map { it.name }

        assertEquals(
            listOf(
                AccountLibraryActions::class.java.name,
                AccountResumeOffers::class.java.name,
                kotlinx.coroutines.flow.StateFlow::class.java.name,
                kotlinx.coroutines.CoroutineScope::class.java.name,
            ),
            parameters,
        )
    }

    private fun kotlinx.coroutines.test.TestScope.shelf(
        actions: AccountLibraryActions,
        undoWindowMs: Long = AccountShelf.DEFAULT_UNDO_WINDOW_MS,
    ) = AccountShelf(
        actions = actions,
        resumeOffers = AccountResumeOffers(RecordingHostRecords()),
        state = MutableStateFlow(AccountLibraryState(phase = AccountSyncPhase.IDLE, userId = "user-1")),
        scope = backgroundScope,
        undoWindowMs = undoWindowMs,
    )
}
