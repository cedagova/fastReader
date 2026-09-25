package com.cedagova.fastreader.library

import com.cedagova.reader.engine.epub.EpubByteSource
import com.cedagova.reader.engine.epub.FileEpubByteSource
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.flow.StateFlow

/**
 * A library book's bytes, as everything that reads a book wants them (#118, #204).
 *
 * Read-only: it looks up where a book is in the current catalog and opens it,
 * and never writes the catalog.
 */
class BookBytes(private val catalog: StateFlow<Catalog>, private val gateway: DocumentGateway) {

    /**
     * The book's bytes as one seam with two kinds of source behind it. A picked
     * or folder-discovered book is read in place through the document provider
     * (AD-1); a private copy of an account book is a file this app owns, read
     * through [FileEpubByteSource] (AD-24). Which one it is, is decided here and
     * nowhere else — the reader, the pipeline and the archive reader see an
     * [EpubByteSource] and do not know the difference, which is exactly what
     * "a copy opens like a device book" (REQ-510) has to mean in code.
     *
     * It resolves the source lazily, on each `open`, so a reference held across
     * a rescan still opens whatever is readable now.
     */
    fun byteSource(bookId: String): EpubByteSource = object : EpubByteSource {
        override fun open(): InputStream = openBook(bookId)

        override fun openChannel(): SeekableByteChannel? = openBookChannel(bookId)
    }

    /**
     * Opens the book's bytes for reading, in place. The reading pipeline
     * (increment 002) consumes this instead of holding URIs of its own.
     */
    @Throws(IOException::class)
    fun openBook(bookId: String): InputStream = when (val source = readableSource(bookId)) {
        is ReadableSource.PrivateFile -> FileInputStream(source.file)
        is ReadableSource.Document -> gateway.open(source.uri)
    }

    /**
     * A seekable view of the book's bytes, or null when the provider has none.
     *
     * What lets the reader open a book by seeking to its text instead of reading
     * past its pictures (REQ-110). A private copy always has one, which is why a
     * downloaded book opens through the directory strategy rather than the
     * streaming fallback.
     */
    @Throws(IOException::class)
    fun openBookChannel(bookId: String): SeekableByteChannel? = when (val source = readableSource(bookId)) {
        is ReadableSource.PrivateFile -> Files.newByteChannel(source.file.toPath(), StandardOpenOption.READ)
        is ReadableSource.Document -> gateway.openSeekable(source.uri)
    }

    /** Where a book's bytes are right now: a file this app owns, or a provider document. */
    private sealed interface ReadableSource {
        data class PrivateFile(val file: File) : ReadableSource
        data class Document(val uri: String) : ReadableSource
    }

    @Throws(IOException::class)
    private fun readableSource(bookId: String): ReadableSource {
        val book = catalog.value.book(bookId) ?: throw IOException("unknown book $bookId")
        val source = book.readableSource ?: throw IOException("no reachable source for ${book.title}")
        val path = source.filePath
        if (source.isAccountCopy) {
            // A copy with no path is a row this build could not have written.
            // Failing here keeps "a copy is verified before it is readable" true
            // rather than handing an account-copy uri to the document provider,
            // which would fail later and less clearly.
            if (path == null) throw IOException("the private copy of ${book.title} has no file")
            return ReadableSource.PrivateFile(File(path))
        }
        return ReadableSource.Document(source.uri)
    }
}
