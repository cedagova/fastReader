package com.cedagova.fastreader.account.library

import com.cedagova.reader.library.sync.AccountLibraryActions
import com.cedagova.reader.library.sync.AccountLibraryState
import com.cedagova.reader.library.sync.AccountSyncPhase
import com.cedagova.reader.library.sync.LocalReadingPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The account book whose removal can still be taken back (REQ-508). */
data class AccountRemoval(val bookId: String, val title: String)

/**
 * The account library as the shelf uses it (LEAF703): the engine's state and
 * operations, plus the one thing the engine deliberately does not hold — the
 * window in which a removal can still be taken back.
 *
 * ## Why the window lives here
 *
 * The pinned contract gives the removal an `undo_scope` of
 * `immediate_confirmation` and no duration: the scope says the Undo belongs to
 * the confirmation the reader is looking at, and how long that confirmation
 * stays on screen is the client's. So the window is the app's, and it is the
 * *same* window a device removal already offers
 * ([com.cedagova.fastreader.library.LibraryRepository.DEFAULT_UNDO_WINDOW_MS]) —
 * one "immediate confirmation" in this app, not two that differ by screen.
 *
 * Undo is a `restore` of the same book through the engine, which queues it like
 * any other mutation: offline it waits, online it goes at once, and either way
 * the backend's canonical payload is what the row ends up saying (AD-22).
 * Nothing here deletes a file, touches a grant, or changes the device catalog:
 * removing a book from the account leaves the device book exactly as it was.
 *
 * After the window closes there is no Undo. That is the contract's own shape —
 * `immediate_confirmation` is the whole of the recovery it offers — and
 * pretending otherwise would promise a reader something the backend does not.
 */
class AccountShelf(
    private val actions: AccountLibraryActions,
    /** The answered resume offers, a host record the engine stores for this app (#147). */
    private val resumeOffers: AccountResumeOffers,
    /** The account library the engine publishes; the shelf renders exactly this. */
    val state: StateFlow<AccountLibraryState>,
    private val scope: CoroutineScope,
    private val undoWindowMs: Long = DEFAULT_UNDO_WINDOW_MS,
) {

    /**
     * Guards [timer] and [_undo] together, so an expiry, an Undo, a later removal
     * and a sign-out never interleave half-way through one another's transition.
     * It is not reentrant: nothing that runs *under* it may take it again, which
     * is why [removeFromAccount] starts the timer only once it has let go.
     */
    private val mutex = Mutex()
    private var timer: Job? = null
    private val _undo = MutableStateFlow<AccountRemoval?>(null)

    /** The account removal still on offer, or null when there is none. */
    val undo: StateFlow<AccountRemoval?> = _undo.asStateFlow()

    init {
        // Signing out ends the offer with it: the row it named is off the shelf
        // (D4) and a `restore` for an account nobody is signed in to would be
        // queued against a store this app has stopped reading.
        scope.launch {
            state.collect { if (it.phase == AccountSyncPhase.SIGNED_OUT) clearOffer(null) }
        }
    }

    /** The reader asked the shelf for a refresh: sync now (AD-21's third trigger). */
    fun refresh() = actions.refresh()

    /**
     * Removes [bookId] from the account and starts its Undo window.
     *
     * The delete is sent first and the offer opened second, on purpose: the
     * offer is a second chance at a change that has already been made, not a
     * delay before making it. Offline the delete queues and the offer is still
     * real — an Undo taken in that window queues a `restore` behind it, and the
     * backend admits both in order.
     */
    fun removeFromAccount(bookId: String, title: String) {
        actions.removeFromAccount(bookId)
        scope.launch {
            // The offer is opened under the lock but its timer is only *started*
            // after the lock is released. The timer's expiry takes the same lock,
            // and `delay(0)` returns without suspending, so a zero window on an
            // eager dispatcher would otherwise run that expiry inside this very
            // block, while it still holds the mutex. Creating the job lazily
            // keeps the "cancel the old, install the new" step atomic; a cancel
            // that lands before `start()` (an Undo, a sign-out, a later removal)
            // simply makes the start a no-op.
            val window = mutex.withLock {
                timer?.cancel()
                _undo.value = AccountRemoval(bookId, title)
                scope.launch(start = CoroutineStart.LAZY) {
                    delay(undoWindowMs)
                    clearOffer(bookId)
                }.also { timer = it }
            }
            window.start()
        }
    }

    /** Takes back the removal the bar is offering, as the contract's `restore`. */
    fun undoRemove() {
        scope.launch {
            val taken = mutex.withLock {
                val offer = _undo.value
                timer?.cancel()
                timer = null
                _undo.value = null
                offer
            }
            taken?.let { actions.undoRemove(it.bookId) }
        }
    }

    /** The account book was opened here: `reading`, and this device's clock as last-opened. */
    fun recordOpened(bookId: String) = actions.recordOpened(bookId)

    /** The account book was read to its last word. */
    fun recordFinished(bookId: String) = actions.recordFinished(bookId)

    /**
     * The reader reached a place worth stating portably in this account book
     * (REQ-511, AD-25).
     *
     * Straight through, like the two above it: the engine owns whether this says
     * anything new, and this class owns only the Undo window.
     */
    fun recordPosition(bookId: String, position: LocalReadingPosition) =
        actions.recordPosition(bookId, position)

    /**
     * The reader has answered the resume offer for one remote change (REQ-511).
     *
     * Straight through like the three above it, and the only one of the four that
     * puts nothing on the wire: it records that the question was asked, so the
     * next open of this book does not ask it again. Called for **both** answers —
     * the requirement is that the offer is *made* once.
     */
    fun settleResumeOffer(bookId: String, changeKey: String) {
        scope.launch { resumeOffers.settle(bookId, changeKey) }
    }

    /**
     * Ends the offer, or ends [expected]'s offer only when one is named.
     *
     * Takes the lock itself, so it must only ever be called from a coroutine
     * that does not already hold it: the expiry timer (started outside the lock
     * by [removeFromAccount]) and the sign-out collector.
     */
    private suspend fun clearOffer(expected: String?) {
        mutex.withLock {
            if (expected != null && _undo.value?.bookId != expected) return@withLock
            timer = null
            _undo.value = null
        }
    }

    companion object {
        /**
         * The confirmation lifetime, matching the device shelf's own undo window
         * so the two removals behave identically from the reader's side.
         */
        const val DEFAULT_UNDO_WINDOW_MS: Long = 8_000L
    }
}
