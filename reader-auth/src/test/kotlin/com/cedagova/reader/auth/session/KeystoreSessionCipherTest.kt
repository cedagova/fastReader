package com.cedagova.reader.auth.session

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The get-or-create rule of [KeystoreSessionCipher], with the Android Keystore
 * replaced by an in-memory [SessionKeyStore] holding software AES keys. The
 * real Keystore path is proven on a device (docs/evidence/93/).
 */
class KeystoreSessionCipherTest {

    private val alias = "test-alias"

    /** Generates a fresh random key per call, replacing the stored one — as the Keystore does. */
    private class FakeKeyStore(private val persist: Boolean = true) : SessionKeyStore {
        val keys = ConcurrentHashMap<String, SecretKey>()
        val generated = AtomicInteger()
        val generating = CountDownLatch(1)
        var release: CountDownLatch? = null

        override fun find(alias: String): SecretKey? = keys[alias]

        override fun generate(alias: String) {
            generated.incrementAndGet()
            generating.countDown()
            release?.let { check(it.await(10, TimeUnit.SECONDS)) { "never released" } }
            if (persist) keys[alias] = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
        }
    }

    @Test
    fun `two racing first uses create one key and each can decrypt the other's blob`() {
        val store = FakeKeyStore().apply { release = CountDownLatch(1) }
        val first = KeystoreSessionCipher(alias, store)
        val second = KeystoreSessionCipher(alias, store)
        val firstBlob = AtomicReference<ByteArray>()
        val secondBlob = AtomicReference<ByteArray>()

        val a = Thread { firstBlob.set(first.encrypt("from a".encodeToByteArray())) }.apply { start() }
        assertTrue(store.generating.await(10, TimeUnit.SECONDS))
        val b = Thread { secondBlob.set(second.encrypt("from b".encodeToByteArray())) }.apply { start() }
        // b missed the key too and is now waiting on the creation lock that a holds.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!KeystoreSessionCipher.keyCreation.hasQueuedThread(b)) {
            check(System.nanoTime() < deadline) { "second first-use never queued on the key-creation lock" }
            Thread.onSpinWait()
        }
        store.release!!.countDown()
        a.join(10_000)
        b.join(10_000)

        assertEquals(1, store.generated.get())
        assertArrayEquals("from b".encodeToByteArray(), first.decrypt(secondBlob.get()))
        assertArrayEquals("from a".encodeToByteArray(), second.decrypt(firstBlob.get()))
    }

    @Test
    fun `an existing key is reused, not regenerated`() {
        val store = FakeKeyStore()
        val blob = KeystoreSessionCipher(alias, store).encrypt("kept".encodeToByteArray())

        assertArrayEquals("kept".encodeToByteArray(), KeystoreSessionCipher(alias, store).decrypt(blob))
        assertEquals(1, store.generated.get())
    }

    @Test
    fun `a generated key that is not in the store afterwards fails loud`() {
        val cipher = KeystoreSessionCipher(alias, FakeKeyStore(persist = false))

        assertThrows(GeneralSecurityException::class.java) { cipher.encrypt("x".encodeToByteArray()) }
    }
}
