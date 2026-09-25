package com.cedagova.reader.auth.session

import io.github.jan.supabase.auth.user.UserSession

/**
 * One session as a [SessionStore] keeps it (#198, A197-F001).
 *
 * Opaque on purpose: the identity provider's session record stays inside the
 * store boundary, so a store implementation holds and hands back the value
 * without reading it. The production store writes the provider record inside
 * it byte for byte as before, so a stored session keeps its format. Internal
 * since #199, with [SessionStore]: no host ever holds one.
 *
 * [toString] names no token, so a stored session never lands in a log.
 */
internal class StoredSession(val value: UserSession) {

    override fun equals(other: Any?): Boolean = other is StoredSession && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "StoredSession(<redacted>)"
}
