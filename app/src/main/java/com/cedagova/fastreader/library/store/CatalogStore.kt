package com.cedagova.fastreader.library.store

import com.cedagova.fastreader.library.Catalog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Result of loading the catalog from storage. */
sealed interface CatalogLoad {

    /**
     * A usable catalog. [recoveredFrom] names a damaged document that was set
     * aside instead of being deleted; [migratedFrom] names an older schema
     * version that was upgraded on the way in.
     */
    data class Loaded(
        val catalog: Catalog,
        val recoveredFrom: String? = null,
        val migratedFrom: Int? = null,
    ) : CatalogLoad

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
 */
class FileCatalogStore(
    private val file: File,
    private val codec: CatalogCodec = CatalogCodec(),
    private val clock: () -> Long = System::currentTimeMillis,
) : CatalogStore {

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
        FileOutputStream(temporary).use { output ->
            output.write(codec.encode(catalog).toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            // Rename can fail if the destination exists on some filesystems.
            if (!file.delete() || !temporary.renameTo(file)) {
                temporary.delete()
                throw IOException("could not replace catalog at ${file.path}")
            }
        }
    }

    /** Moves an unreadable document aside so a damaged library is never silently dropped. */
    private fun setAside(): File? {
        val backup = File(file.parentFile, "${file.name}.damaged-${clock()}")
        return if (file.renameTo(backup)) backup else null
    }
}
