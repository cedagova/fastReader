package com.cedagova.reader.auth.session

import android.content.Context
import io.github.jan.supabase.auth.user.UserSession
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The production [SessionStore]: the session as JSON, encrypted by a
 * [SessionCipher], written as one file. The rules (CONTRACT.md, "Session
 * storage and backup exclusion"), each pinned by `FileSessionStoreTest`:
 *
 * - The file lives under the application's no-backup files directory
 *   ([Context.getNoBackupFilesDir]), so the platform's own backup skips it
 *   before the host's extraction rules exclude it a second time.
 * - Nothing about the session touches `SharedPreferences`, DataStore, or any
 *   other file: the module never creates a `shared_prefs` entry.
 * - The token bytes never appear in the file; only the cipher's output does.
 * - An absent, unreadable, corrupt, or undecryptable file loads as `null` —
 *   the device is signed out — and the unusable file is removed so the next
 *   start does not try again.
 * - The blob carries a format version so a later Reader client can migrate
 *   or discard an older layout deliberately.
 */
class FileSessionStore(
    private val directory: File,
    private val cipher: SessionCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SessionStore {

    /** The one file the session is ever written to. */
    val file: File get() = File(directory, FILE_NAME)

    override suspend fun save(session: UserSession) = withContext(ioDispatcher) {
        directory.mkdirs()
        val plaintext = json.encodeToString(Envelope.serializer(), Envelope(session = session)).encodeToByteArray()
        val blob = cipher.encrypt(plaintext)
        val temporary = File(directory, "$FILE_NAME.tmp")
        temporary.writeBytes(blob)
        if (!temporary.renameTo(file)) {
            file.writeBytes(blob)
            temporary.delete()
        }
    }

    override suspend fun load(): UserSession? = withContext(ioDispatcher) {
        val target = file
        if (!target.isFile) return@withContext null
        try {
            val plaintext = cipher.decrypt(target.readBytes())
            val envelope = json.decodeFromString(Envelope.serializer(), plaintext.decodeToString())
            if (envelope.formatVersion != FORMAT_VERSION) throw IllegalStateException("unsupported format ${envelope.formatVersion}")
            envelope.session
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    override suspend fun clear() = withContext(ioDispatcher) {
        file.delete()
        File(directory, "$FILE_NAME.tmp").delete()
        Unit
    }

    @Serializable
    private class Envelope(
        @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
        val session: UserSession,
    )

    companion object {
        /** The file name under the no-backup directory's `reader-auth` folder. */
        const val FILE_NAME: String = "session.bin"

        /** The subdirectory of the no-backup files directory the store owns. */
        const val DIRECTORY_NAME: String = "reader-auth"

        /** The stored layout's version; bump it when the envelope changes shape. */
        const val FORMAT_VERSION: Int = 1

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** The production location: `<noBackupFilesDir>/reader-auth/session.bin`. */
        fun directoryIn(context: Context): File = File(context.noBackupFilesDir, DIRECTORY_NAME)
    }
}
