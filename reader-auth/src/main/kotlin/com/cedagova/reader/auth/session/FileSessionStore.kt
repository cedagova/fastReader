package com.cedagova.reader.auth.session

import android.content.Context
import io.github.jan.supabase.auth.user.UserSession
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 * - A save never leaves a partial file: each save writes its own temporary
 *   file, syncs it to disk, and atomically renames it over the session file.
 *   If the rename fails, the previous file stays as it was and [save] throws
 *   an [IOException] — there is no in-place fallback write.
 */
class FileSessionStore internal constructor(
    private val directory: File,
    private val cipher: SessionCipher,
    private val ioDispatcher: CoroutineDispatcher,
    private val replace: (temporary: File, target: File) -> Unit,
) : SessionStore {

    constructor(
        directory: File,
        cipher: SessionCipher,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(directory, cipher, ioDispatcher, ::atomicReplace)

    /** The one file the session is ever written to. */
    val file: File get() = File(directory, FILE_NAME)

    override suspend fun save(session: UserSession) = withContext(ioDispatcher) {
        directory.mkdirs()
        val plaintext = json.encodeToString(Envelope.serializer(), Envelope(session = session)).encodeToByteArray()
        val blob = cipher.encrypt(plaintext)
        // A temp file per save, so two concurrent saves never write into the same bytes.
        val temporary = File.createTempFile("$FILE_NAME.", TEMP_SUFFIX, directory)
        try {
            FileOutputStream(temporary).use { out ->
                out.write(blob)
                out.flush()
                out.fd.sync()
            }
            replace(temporary, file)
        } finally {
            // Gone already after a successful rename; removes the orphan otherwise.
            temporary.delete()
        }
        Unit
    }

    override suspend fun load(): UserSession? = withContext(ioDispatcher) {
        val target = file
        if (!target.isFile) return@withContext null
        try {
            val plaintext = cipher.decrypt(target.readBytes())
            val envelope = json.decodeFromString(Envelope.serializer(), plaintext.decodeToString())
            if (envelope.formatVersion !=
                FORMAT_VERSION
            ) {
                throw IllegalStateException("unsupported format ${envelope.formatVersion}")
            }
            envelope.session
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    override suspend fun clear() = withContext(ioDispatcher) {
        file.delete()
        directory.listFiles { candidate -> candidate.isTemporary() }.orEmpty().forEach { it.delete() }
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

        private const val TEMP_SUFFIX = ".tmp"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private fun File.isTemporary(): Boolean = name.startsWith("$FILE_NAME.") && name.endsWith(TEMP_SUFFIX)

        /**
         * Atomically replaces [target] with [temporary]; throws an [IOException]
         * (the target untouched) when the filesystem cannot do it atomically.
         */
        private fun atomicReplace(temporary: File, target: File) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }

        /** The production location: `<noBackupFilesDir>/reader-auth/session.bin`. */
        fun directoryIn(context: Context): File = File(context.noBackupFilesDir, DIRECTORY_NAME)
    }
}
