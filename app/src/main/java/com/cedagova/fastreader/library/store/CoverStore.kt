package com.cedagova.fastreader.library.store

import java.io.File
import java.io.IOException

/**
 * Caches cover images extracted from books.
 *
 * This is derived data in app storage — the book files themselves are always read
 * in place and never copied (AD-1). Losing this cache costs a re-extraction, not
 * a book.
 */
class CoverStore(private val directory: File) {

    fun write(bookId: String, bytes: ByteArray): Boolean = try {
        if (!directory.exists()) directory.mkdirs()
        fileFor(bookId).writeBytes(bytes)
        true
    } catch (_: IOException) {
        false
    }

    /** The cached cover file, or null when this book has none. */
    fun read(bookId: String): File? = fileFor(bookId).takeIf { it.isFile && it.length() > 0 }

    fun delete(bookId: String) {
        fileFor(bookId).delete()
    }

    private fun fileFor(bookId: String): File = File(directory, "${bookId.replace(NON_FILENAME, "_")}.img")

    private companion object {
        val NON_FILENAME = Regex("[^A-Za-z0-9._-]")
    }
}
