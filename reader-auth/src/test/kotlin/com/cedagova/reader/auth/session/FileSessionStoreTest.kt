package com.cedagova.reader.auth.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.reader.auth.FakeCipher
import com.cedagova.reader.auth.session
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The storage rules of CONTRACT.md, on the production path
 * (`FileSessionStore.directoryIn`) with the cipher faked. The real Keystore
 * cipher is proven on a device (docs/evidence/93/), because Robolectric has
 * no `AndroidKeyStore`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FileSessionStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cipher = FakeCipher()
    private lateinit var store: FileSessionStore
    private val stored = session(accessToken = "access-token-bytes", refreshToken = "refresh-token-bytes", expiresAt = Clock.System.now() + 3600.seconds)

    @Before
    fun freshStore() {
        store = FileSessionStore(FileSessionStore.directoryIn(context), cipher)
        store.file.parentFile?.deleteRecursively()
    }

    @Test
    fun `the session file lives under the no-backup files directory and nowhere else`() = runTest {
        store.save(stored)

        assertTrue(store.file.isFile)
        assertTrue(
            "${store.file} is not under ${context.noBackupFilesDir}",
            store.file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator),
        )
        assertEquals("session.bin", store.file.name)
        val sharedPrefs = File(context.applicationInfo.dataDir, "shared_prefs")
        assertTrue("a shared_prefs directory appeared: ${sharedPrefs.list()?.toList()}", !sharedPrefs.exists() || sharedPrefs.list().isNullOrEmpty())
        val filesDir = context.filesDir.listFiles().orEmpty().map { it.name }
        assertTrue("the ordinary files directory gained $filesDir", filesDir.isEmpty())
    }

    @Test
    fun `the token bytes never appear in the file`() = runTest {
        store.save(stored)

        val bytes = store.file.readBytes()
        listOf("access-token-bytes", "refresh-token-bytes", "reader@example.test").forEach { secret ->
            assertFalse("$secret is in the stored file", bytes.decodeToString().contains(secret))
            assertFalse("$secret bytes are in the stored file", bytes.containsSlice(secret.encodeToByteArray()))
        }
    }

    @Test
    fun `a saved session loads back intact`() = runTest {
        store.save(stored)

        val loaded = store.load()

        assertNotNull(loaded)
        assertEquals(stored.accessToken, loaded!!.accessToken)
        assertEquals(stored.refreshToken, loaded.refreshToken)
        assertEquals(stored.expiresAt, loaded.expiresAt)
        assertEquals(stored.user?.id, loaded.user?.id)
    }

    @Test
    fun `no file means signed out`() = runTest {
        assertNull(store.load())
    }

    @Test
    fun `a corrupt file means signed out and is removed`() = runTest {
        store.save(stored)
        store.file.writeBytes(byteArrayOf(1, 2, 3, 4))

        assertNull(store.load())
        assertFalse("the corrupt file was left for the next start to trip over", store.file.exists())
    }

    @Test
    fun `an undecryptable file means signed out, never a crash`() = runTest {
        store.save(stored)
        cipher.failDecrypt = true

        assertNull(store.load())
        assertFalse(store.file.exists())
    }

    @Test
    fun `clear removes the file`() = runTest {
        store.save(stored)

        store.clear()

        assertFalse(store.file.exists())
        assertNull(store.load())
    }

    @Test
    fun `the SDK adapter maps absence to the SDK's no-session exception`() = runTest {
        val manager = StoreSessionManager(store)
        assertNull(manager.loadSessionOrNull())
        manager.saveSession(stored)
        assertEquals(stored.accessToken, manager.loadSession().accessToken)
        manager.deleteSession()
        assertNull(manager.loadSessionOrNull())
    }

    private fun ByteArray.containsSlice(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
            return true
        }
        return false
    }
}
