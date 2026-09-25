package com.cedagova.reader.library.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

// The engine's view of a session: which account is open, whose a change is,
// and what a host's session state means for the engine. [AccountSyncEngine]
// owns the transitions between them.

/**
 * The signed-in account the engine is serving: its user id, its store and the
 * document as last written.
 */
internal class ActiveAccount(
    val userId: String,
    val store: AccountLibraryStore,
    document: AccountLibraryDocument,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /** The account document as last written; changed only by [persist]. */
    var document: AccountLibraryDocument = document
        private set

    /**
     * Writes [document], stamped with this account's user id, and only then
     * holds it: a write that fails leaves [document] as it was.
     */
    suspend fun persist(document: AccountLibraryDocument) {
        val stamped = document.copy(userId = userId)
        withContext(ioDispatcher) { store.save(stamped) }
        this.document = stamped
    }
}

/** Whose change a queued change is, decided when it is asked for (#162). */
internal sealed interface ChangeOwner {
    /** No session has been read yet (`Loading`): the first one decides. */
    data object Unresolved : ChangeOwner

    /** Nobody is signed in: there is no account to hold the change. */
    data object Nobody : ChangeOwner

    data class User(val userId: String) : ChangeOwner
}

internal sealed interface SessionTarget {
    /** Nobody is signed in. [notConfigured] separates "no stage values" from "signed out". */
    data class None(val notConfigured: Boolean) : SessionTarget
    data class User(val userId: String) : SessionTarget
}

/** `Loading` is not a target: the stored session has not been read, so nothing changes yet. */
internal fun targetOf(state: AccountSession): SessionTarget? = when (state) {
    AccountSession.Loading -> null
    AccountSession.NotConfigured -> SessionTarget.None(notConfigured = true)
    AccountSession.SignedOut -> SessionTarget.None(notConfigured = false)
    is AccountSession.SignedIn -> SessionTarget.User(state.userId)
}
