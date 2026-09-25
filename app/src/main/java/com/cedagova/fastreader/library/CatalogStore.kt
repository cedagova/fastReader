package com.cedagova.fastreader.library

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Result of loading the catalog from storage. */
sealed interface CatalogLoad {

    /**
     * A usable catalog. [recoveredFrom] names a damaged document that was set
     * aside instead of being deleted; [migratedFrom] names an older schema
     * version that was upgraded on the way in.
     */
    data class Loaded(val catalog: Catalog, val recoveredFrom: String? = null, val migratedFrom: Int? = null) :
        CatalogLoad

    /**
     * The stored catalog must not be touched — for example it was written by a
     * newer schema. Callers surface this and refuse to write, so nothing is lost.
     */
    data class Blocked(val message: String) : CatalogLoad
}

/** Persistence boundary for the catalog document. */
interface CatalogStore {
    fun load(): CatalogLoad
    fun save(catalog: Catalog)
}

/**
 * Stores the catalog as one JSON document.
 *
 * Writes go to a temporary file that is flushed to disk and then renamed over the
 * real one, so an interrupted write cannot leave a half-written library behind.
 *
 * The old document is never removed before the new one is in place: the
 * replace is one atomic rename, and if it fails the save throws and the old
 * document stays loadable. Deleting first would open a window in which a
 * process death leaves no catalog at all — and with it the whole library index.
 * A failed save also leaves no temporary file behind.
 */
class FileCatalogStore internal constructor(
    private val file: File,
    private val codec: CatalogCodec,
    private val clock: () -> Long,
    private val replace: (source: File, target: File) -> Unit,
) : CatalogStore {

    constructor(
        file: File,
        codec: CatalogCodec = CatalogCodec(),
        clock: () -> Long = System::currentTimeMillis,
    ) : this(file, codec, clock, ::atomicReplace)

    override fun load(): CatalogLoad {
        if (!file.exists()) return CatalogLoad.Loaded(Catalog())
        val text = try {
            file.readText()
        } catch (error: IOException) {
            return CatalogLoad.Blocked("catalog could not be read: ${error.message ?: "I/O error"}")
        }
        if (text.isBlank()) return CatalogLoad.Loaded(Catalog())

        return when (val decoding = codec.decode(text)) {
            is CatalogDecoding.Decoded ->
                CatalogLoad.Loaded(decoding.catalog, migratedFrom = decoding.migratedFrom)

            is CatalogDecoding.Newer -> CatalogLoad.Blocked(
                "catalog was written by a newer version of the app " +
                    "(schema ${decoding.documentVersion}, this build understands ${decoding.supportedVersion})",
            )

            is CatalogDecoding.Damaged -> {
                val backup = setAside()
                CatalogLoad.Loaded(Catalog(), recoveredFrom = backup?.name)
            }
        }
    }

    override fun save(catalog: Catalog) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("could not create catalog directory ${parent.path}")
        }
        val temporary = File(parent, "${file.name}.tmp")
        val bytes = codec.encode(catalog).toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
        } catch (error: IOException) {
            temporary.delete()
            throw IOException("could not write catalog to ${temporary.path}", error)
        }
        try {
            replace(temporary, file)
        } catch (error: IOException) {
            temporary.delete()
            throw IOException("could not replace catalog at ${file.path}", error)
        }
        parent?.let(::syncDirectory)
    }

    /** Moves an unreadable document aside so a damaged library is never silently dropped. */
    private fun setAside(): File? {
        val backup = File(file.parentFile, "${file.name}.damaged-${clock()}")
        return if (file.renameTo(backup)) backup else null
    }
}

/**
 * Renames [source] over [target] in one step. On Linux (Android) this is
 * `rename(2)`, which replaces an existing target atomically; a filesystem that
 * cannot do that throws instead of falling back to delete-then-rename.
 */
private fun atomicReplace(source: File, target: File) {
    Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

/**
 * Flushes [directory]'s entries so the rename itself survives a power cut.
 * Best effort: some platforms cannot open a directory for syncing, and the
 * document's own bytes were already synced before the rename.
 */
private fun syncDirectory(directory: File) {
    try {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    } catch (_: IOException) {
        // Not supported here; the rename is still atomic, just not yet durable.
    }
}
