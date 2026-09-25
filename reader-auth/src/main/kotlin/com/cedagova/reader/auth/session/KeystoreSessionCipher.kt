package com.cedagova.reader.auth.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.withLock

/**
 * AES-256-GCM with a key that is generated in, and never leaves, the Android
 * Keystore (CONTRACT.md, "Session storage and backup exclusion").
 *
 * The key asks for nothing the platform might not have: no user
 * authentication requirement, so a device without a lock screen still works,
 * and no StrongBox requirement, so a device without the secure element still
 * works. It is app-scoped, so uninstalling the host discards it, and it does
 * not survive a restore onto another device — which is why the blob it
 * protects must never be backed up either: a restored blob without its key is
 * undecryptable, and [FileSessionStore] reads that as "signed out".
 *
 * The key is created on first use, get-or-create under one process-wide lock,
 * so two first uses never generate two keys (the second replacing the first
 * under the same alias and orphaning whatever the first encrypted).
 *
 * Blob layout: one version byte, the 12-byte IV, then ciphertext and tag.
 */
class KeystoreSessionCipher internal constructor(private val alias: String, private val keys: SessionKeyStore) :
    SessionCipher {

    constructor(alias: String = DEFAULT_ALIAS) : this(alias, AndroidSessionKeyStore)

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected GCM IV length ${iv.size}" }
        val encrypted = cipher.doFinal(plaintext)
        return byteArrayOf(FORMAT_VERSION) + iv + encrypted
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size < 1 + IV_BYTES + TAG_BYTES) throw GeneralSecurityException("blob too short")
        if (blob[0] != FORMAT_VERSION) throw GeneralSecurityException("unknown blob version ${blob[0]}")
        val iv = blob.copyOfRange(1, 1 + IV_BYTES)
        val encrypted = blob.copyOfRange(1 + IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, iv))
        return cipher.doFinal(encrypted)
    }

    private fun key(): SecretKey {
        keys.find(alias)?.let { return it }
        return keyCreation.withLock {
            keys.find(alias) ?: run {
                keys.generate(alias)
                // Read back what the store now holds rather than trusting the generator's handle.
                keys.find(alias) ?: throw GeneralSecurityException("generated key $alias is not in the key store")
            }
        }
    }

    companion object {
        /** The one Keystore alias the module uses; app-scoped, so hosts never collide. */
        const val DEFAULT_ALIAS: String = "com.cedagova.reader.auth.session"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val FORMAT_VERSION: Byte = 1

        /** Serialises first-use key creation across every instance in the process. */
        internal val keyCreation = ReentrantLock()
    }
}

/** The seam between [KeystoreSessionCipher]'s get-or-create rule and the platform key store. */
internal interface SessionKeyStore {
    /** The key stored under [alias], or `null` when there is none yet. */
    fun find(alias: String): SecretKey?

    /** Generates a new key under [alias], replacing any existing one. */
    fun generate(alias: String)
}

/** The production key store: AES-256-GCM keys held by the Android Keystore. */
internal object AndroidSessionKeyStore : SessionKeyStore {
    private const val PROVIDER = "AndroidKeyStore"
    private const val KEY_BITS = 256

    override fun find(alias: String): SecretKey? =
        KeyStore.getInstance(PROVIDER).apply { load(null) }.getKey(alias, null) as? SecretKey

    override fun generate(alias: String) {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generator.generateKey()
    }
}
