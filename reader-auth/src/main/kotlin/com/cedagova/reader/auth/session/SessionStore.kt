package com.cedagova.reader.auth.session

import io.github.jan.supabase.auth.user.UserSession

/**
 * Where the session lives between process starts. One production
 * implementation exists, [FileSessionStore] over [KeystoreSessionCipher]; the
 * contract's storage rules (CONTRACT.md, "Session storage and backup
 * exclusion") are stated on that class and pinned by `FileSessionStoreTest`.
 *
 * Every method is safe to call in any state: [load] answers `null` for "no
 * session", for an unreadable file, and for a blob the device can no longer
 * decrypt — a signed-out start, never a crash.
 */
interface SessionStore {
    suspend fun save(session: UserSession)
    suspend fun load(): UserSession?
    suspend fun clear()
}
