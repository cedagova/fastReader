package com.cedagova.reader.library.sync

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Result of loading one account's document from storage. */
sealed interface AccountLibraryLoad {

    /**
     * A usable document. [recoveredFrom] names a damaged document that was set
     * aside instead of being deleted; [migratedFrom] names an older schema
     * version that was upgraded on the way in.
     */
    data class Loaded(
        val document: AccountLibraryDocument,
        val recoveredFrom: String? = null,
        val migratedFrom: Int? = null,
    ) : AccountLibraryLoad

    /**
     * The stored document must not be touched — it was written by a newer
     * schema, or could not be read at all. The engine surfaces this and writes
     * nothing, so a newer build's queue survives an older build running.
     */
    data class Blocked(val message: String) : AccountLibraryLoad
}

/** Persistence boundary for one account's document. */
interface AccountLibraryStore {
    fun load(): AccountLibraryLoad
    fun save(document: AccountLibraryDocument)
}

/**
 * Stores one account's document as one JSON file.
 *
 * Writes go to a temporary file that is flushed to disk and then renamed over
 * the real one, so an interrupted write cannot leave half a queue behind —
 * the same discipline `FileCatalogStore` uses, because the same power cut can
 * land in the middle of either write.
 */
class FileAccountLibraryStore(
    private val file: File,
    private val codec: AccountLibraryCodec = AccountLibraryCodec(),
    private val clock: () -> Long = System::currentTimeMillis,
) : AccountLibraryStore {

    override fun load(): AccountLibraryLoad {
        if (!file.exists()) return AccountLibraryLoad.Loaded(AccountLibraryDocument())
        val text = try {
            file.readText()
        } catch (error: IOException) {
            return AccountLibraryLoad.Blocked("account document could not be read: ${error.message ?: "I/O error"}")
        }
        if (text.isBlank()) return AccountLibraryLoad.Loaded(AccountLibraryDocument())

        return when (val decoding = codec.decode(text)) {
            is AccountLibraryDecoding.Decoded ->
                AccountLibraryLoad.Loaded(decoding.document, migratedFrom = decoding.migratedFrom)

            is AccountLibraryDecoding.Newer -> AccountLibraryLoad.Blocked(
                "the account library was written by a newer version of the app " +
                    "(schema ${decoding.documentVersion}, this build understands ${decoding.supportedVersion})",
            )

            is AccountLibraryDecoding.Damaged -> {
                val backup = setAside()
                AccountLibraryLoad.Loaded(AccountLibraryDocument(), recoveredFrom = backup?.name)
            }
        }
    }

    override fun save(document: AccountLibraryDocument) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("could not create account library directory ${parent.path}")
        }
        val temporary = File(parent, "${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(codec.encode(document).toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            // Rename can fail if the destination exists on some filesystems.
            if (!file.delete() || !temporary.renameTo(file)) {
                temporary.delete()
                throw IOException("could not replace the account library at ${file.path}")
            }
        }
    }

    /** Moves an unreadable document aside so a damaged account library is never silently dropped. */
    private fun setAside(): File? {
        val backup = File(file.parentFile, "${file.name}.damaged-${clock()}")
        return if (file.renameTo(backup)) backup else null
    }
}

/**
 * The account documents on this device, one per account user id.
 *
 * [exceptUser] exists for exactly one rule — D4's "a different account
 * discards the held queue" — and returns stores rather than documents so the
 * caller can rewrite each one without this interface growing a second way to
 * save.
 */
interface AccountLibraryStores {

    /** The store for [userId]; a user id this device has never seen loads as an empty document. */
    fun forUser(userId: String): AccountLibraryStore

    /** Every stored account document on this device except [userId]'s. */
    fun exceptUser(userId: String): List<AccountLibraryStore>
}

/**
 * Account documents as files in one private directory.
 *
 * A file is named after the SHA-256 of the account's user id, not after the
 * id itself: the id is a provider subject, it has no promised shape a
 * filesystem would accept, and there is no reason for it to be legible in a
 * directory listing or a bug report. The mapping is one-way and stable, which
 * is all [forUser] needs — the id is stored *inside* the document for anything
 * that has to read it back.
 */
class FileAccountLibraryStores(
    private val directory: File,
    private val codec: AccountLibraryCodec = AccountLibraryCodec(),
) : AccountLibraryStores {

    override fun forUser(userId: String): AccountLibraryStore =
        FileAccountLibraryStore(File(directory, fileName(userId)), codec)

    override fun exceptUser(userId: String): List<AccountLibraryStore> {
        val mine = fileName(userId)
        val files = directory.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) && it.name != mine }
            .sortedBy { it.name }
            .map { FileAccountLibraryStore(it, codec) }
    }

    private fun fileName(userId: String): String = PREFIX + sha256Hex(userId) + SUFFIX

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    companion object {
        /** The directory name under `filesDir`, beside the catalog's own. */
        const val DIRECTORY_NAME: String = "account-library"
        private const val PREFIX: String = "account-"
        private const val SUFFIX: String = ".json"
    }
}
