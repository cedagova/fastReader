package com.cedagova.fastreader.account.library

import kotlinx.coroutines.CoroutineScope
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
    /** The account library the engine publishes; the shelf renders exactly this. */
    val state: StateFlow<AccountLibraryState>,
    private val scope: CoroutineScope,
    private val undoWindowMs: Long = DEFAULT_UNDO_WINDOW_MS,
) {

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
            mutex.withLock {
                timer?.cancel()
                _undo.value = AccountRemoval(bookId, title)
                timer = scope.launch {
                    delay(undoWindowMs)
                    clearOffer(bookId)
                }
            }
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

    /** Ends the offer, or ends [expected]'s offer only when one is named. */
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
