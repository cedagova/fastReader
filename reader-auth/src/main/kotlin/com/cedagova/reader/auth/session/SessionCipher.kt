package com.cedagova.reader.auth.session

/**
 * The seam between the store's persistence rules and the platform's key
 * material. Production is [KeystoreSessionCipher]; unit tests substitute a
 * fake so the store's rules are proven on the JVM while the real key path is
 * proven on a device.
 */
internal interface SessionCipher {
    /** Encrypts [plaintext] into a self-contained blob (the IV travels with the ciphertext). */
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Decrypts a blob produced by [encrypt]; throws on any tampering, key loss, or format problem. */
    fun decrypt(blob: ByteArray): ByteArray
}
