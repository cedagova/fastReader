package com.cedagova.reader.auth.session

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession

/**
 * The SDK's persistence seam, pointed at the module's [SessionStore] so the
 * provider SDK never touches its plaintext `SharedPreferences` default
 * (CONTRACT.md, "Provider SDK defaults this module overrides").
 */
class StoreSessionManager(val store: SessionStore) : SessionManager {
    override suspend fun saveSession(session: UserSession) = store.save(session)
    override suspend fun loadSession(): UserSession = store.load() ?: throw NoSessionFoundException()
    override suspend fun deleteSession() = store.clear()
}
