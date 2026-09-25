package com.cedagova.fastreader.account.library

import com.cedagova.fastreader.library.Book
import com.cedagova.fastreader.library.DocumentGateway
import com.cedagova.fastreader.library.DocumentLookup
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

/** Why a device book cannot be turned into a [PublicationSource] right now. */
enum class PublicationSourceProblem {
    /** No source of this book is reachable: the file moved, or the grant is gone. */
    UNREACHABLE,

    /**
     * The provider will not say how long the file is, and nothing here could
     * measure it either.
     *
     * It matters because the size is not cosmetic: it is what the policy cap is
     * checked against and what `Upload-Length` declares to the storage
     * provider, so a guess would either refuse a book that fits or start a
     * transfer that can never complete.
     */
    SIZE_UNKNOWN,
}

/** A device book resolved to bytes, or the reason it could not be. */
sealed interface PublicationSourceResult {
    data class Ready(val source: PublicationSource) : PublicationSourceResult
    data class Unavailable(val problem: PublicationSourceProblem) : PublicationSourceResult
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
 */
class DeviceBookSources(private val gateway: DocumentGateway) {

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
