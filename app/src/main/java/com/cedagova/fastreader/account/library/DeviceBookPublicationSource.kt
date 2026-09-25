package com.cedagova.fastreader.account.library

import com.cedagova.fastreader.library.Book
import com.cedagova.fastreader.library.DocumentGateway
import com.cedagova.fastreader.library.DocumentLookup
import com.cedagova.reader.account.library.DevicePublicationSources
import com.cedagova.reader.account.library.PublicationSourceProblem
import com.cedagova.reader.account.library.PublicationSourceResult
import com.cedagova.reader.library.imports.PublicationSource
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel

/**
 * One device book, as `:reader-library`'s import path wants to read it.
 *
 * It is an adapter and nothing else: the bytes stay in the reader's own folder
 * and are read in place, one chunk at a time, straight into the transfer
 * (AD-1 is untouched by this leaf — adding a book to the account copies nothing
 * here). [openChannel] passes the provider's seekable view through when there
 * is one, which is what makes a resume cost the remaining bytes rather than the
 * whole file.
 *
 * [mimeType] is a statement about *this* file, not a policy: FastReader's
 * catalog holds EPUBs and only EPUBs (`DocumentGateway.listEpubs`), so that is
 * what it declares. What the account will *accept* is the policy's business,
 * read on every attempt — this class quotes no cap, no format list and no
 * chunk size.
 */
class DeviceBookPublicationSource(
    private val gateway: DocumentGateway,
    private val uri: String,
    override val sizeBytes: Long,
    override val sha256: String,
    override val fileName: String?,
) : PublicationSource {

    override val mimeType: String get() = EPUB_MIME_TYPE

    @Throws(IOException::class)
    override fun open(): InputStream = gateway.open(uri)

    @Throws(IOException::class)
    override fun openChannel(): SeekableByteChannel? = gateway.openSeekable(uri)

    companion object {
        /** The IANA type for the one container this app reads. */
        const val EPUB_MIME_TYPE: String = "application/epub+zip"
    }
}

/**
 * Turns a catalog [Book] into the bytes the import path reads.
 *
 * The identity is the book's own id, which is its whole-file SHA-256 under a
 * `sha256:` prefix (AD-2) — the very digest AD-23 merges rows on. Nothing here
 * re-hashes the file or invents an identity from a path or a title.
 *
 * The length is asked for in the two ways a SAF provider can answer, cheapest
 * first: the document's own reported size, then the seekable view's length. A
 * provider that offers neither yields [PublicationSourceProblem.SIZE_UNKNOWN]
 * rather than a stream count — a forward pass over a fifty-megabyte file to
 * learn a number the provider simply did not give is not a thing to do on the
 * tap of a button, and the book stays exactly as readable as it was.
 *
 * It is FastReader's [DevicePublicationSources], the import's host seam in
 * `:reader-account` (#200): [sourceFor] resolves the catalog book behind an id
 * with [bookForId] and a book this device no longer has is
 * [PublicationSourceProblem.UNREACHABLE].
 */
class DeviceBookSources(
    private val gateway: DocumentGateway,
    /** The catalog's book for an id, or null when this device no longer has it. */
    private val bookForId: (String) -> Book?,
) : DevicePublicationSources {

    override fun sourceFor(deviceBookId: String): PublicationSourceResult =
        bookForId(deviceBookId)?.let(::of) ?: unavailable(PublicationSourceProblem.UNREACHABLE)

    fun of(book: Book): PublicationSourceResult {
        val source = book.readableSource ?: return unavailable(PublicationSourceProblem.UNREACHABLE)
        val size = sizeOf(source.uri, source.sizeBytes)
            ?: return unavailable(PublicationSourceProblem.SIZE_UNKNOWN)
        return PublicationSourceResult.Ready(
            DeviceBookPublicationSource(
                gateway = gateway,
                uri = source.uri,
                sizeBytes = size,
                sha256 = book.id,
                fileName = source.displayName,
            ),
        )
    }

    private fun sizeOf(uri: String, recorded: Long): Long? {
        if (recorded > 0) return recorded
        val looked = (runCatching { gateway.lookup(uri) }.getOrNull() as? DocumentLookup.Found)?.ref?.sizeBytes
        if (looked != null && looked > 0) return looked
        return runCatching { gateway.openSeekable(uri)?.use { it.size() } }.getOrNull()?.takeIf { it > 0 }
    }

    private fun unavailable(problem: PublicationSourceProblem) = PublicationSourceResult.Unavailable(problem)
}
