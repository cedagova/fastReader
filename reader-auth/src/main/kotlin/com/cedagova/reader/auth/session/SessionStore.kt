package com.cedagova.reader.auth.session

/**
 * Where the session lives between process starts, held as an opaque
 * [StoredSession]. One production implementation exists, [FileSessionStore]
 * over [KeystoreSessionCipher]; the contract's storage rules (CONTRACT.md,
 * "Session storage and backup exclusion") are stated on that class and pinned
 * by `FileSessionStoreTest`.
 *
 * Internal on purpose (#199, A197-F002): session storage is not a host seam.
 * The storage rules — Keystore encryption, the no-backup directory, clearing
 * on sign-out and on a rejected token — are this module's to keep, and a host
 * store would be one more place a token could be written in the clear. The
 * only other implementation is the in-memory store of this module's test
 * fixtures, which reaches this type as the module's own test code does.
 *
 * Every method is safe to call in any state: [load] answers `null` for "no
 * session", for an unreadable file, and for a blob the device can no longer
 * decrypt — a signed-out start, never a crash.
 */
internal interface SessionStore {
    suspend fun save(session: StoredSession)
    suspend fun load(): StoredSession?
    suspend fun clear()
}
