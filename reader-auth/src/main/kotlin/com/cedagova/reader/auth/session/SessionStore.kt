package com.cedagova.reader.auth.session

/**
 * Where the session lives between process starts, held as an opaque
 * [StoredSession]. One production
 * implementation exists, [FileSessionStore] over [KeystoreSessionCipher]; the
 * contract's storage rules (CONTRACT.md, "Session storage and backup
 * exclusion") are stated on that class and pinned by `FileSessionStoreTest`.
 *
 * Every method is safe to call in any state: [load] answers `null` for "no
 * session", for an unreadable file, and for a blob the device can no longer
 * decrypt — a signed-out start, never a crash.
 */
public interface SessionStore {
    public suspend fun save(session: StoredSession)
    public suspend fun load(): StoredSession?
    public suspend fun clear()
}
