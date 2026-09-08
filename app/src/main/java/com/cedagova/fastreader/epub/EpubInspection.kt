package com.cedagova.fastreader.epub

/**
 * Result of inspecting one candidate EPUB file.
 *
 * Inspection never throws on malformed input: an unusable file becomes
 * [Rejected] with a distinct [EpubRejectReason] the catalog persists.
 */
sealed interface EpubInspection {

    /** Content-derived identity of the inspected bytes, when they could be read at all. */
    val contentDigest: String?

    data class Readable(
        override val contentDigest: String,
        val metadata: EpubMetadata,
        val cover: EpubCover?,
    ) : EpubInspection

    data class Rejected(
        override val contentDigest: String?,
        val reason: EpubRejectReason,
        val detail: String,
    ) : EpubInspection
}

/**
 * Why a file cannot be read as a DRM-free EPUB. Values are persisted by name in
 * the catalog, so renaming one is a schema change.
 */
enum class EpubRejectReason {
    /** Encrypted with something other than the two standard font-obfuscation schemes. */
    DRM_PROTECTED,

    /** The bytes are not a readable zip archive. */
    CORRUPT_ARCHIVE,

    /** A readable zip that is not a usable EPUB (no container, no OPF, no spine). */
    INVALID_STRUCTURE,

    /** The bytes could not be read from their source at all. */
    UNREADABLE,
}

/** Metadata this leaf extracts. Absent fields stay null; callers supply fallbacks. */
data class EpubMetadata(
    val title: String? = null,
    val author: String? = null,
    val language: String? = null,
    val publicationId: String? = null,
)

/** Raw cover image bytes as stored in the EPUB, plus its declared media type. */
class EpubCover(
    val mediaType: String?,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is EpubCover && mediaType == other.mediaType && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * (mediaType?.hashCode() ?: 0) + bytes.contentHashCode()

    override fun toString(): String = "EpubCover(mediaType=$mediaType, size=${bytes.size})"
}

/**
 * Opens the raw bytes of a candidate EPUB. May be called more than once per
 * inspection.
 *
 * Two ways in, because the two things this app does with a book have opposite
 * shapes:
 *
 * - [open] is a plain forward stream. Ingestion needs it: the whole-file
 *   SHA-256 that gives a book its identity (AD-2) has to see every byte anyway.
 * - [openChannel] is the same bytes with a position. The reader needs it: a
 *   book's text is a few hundred kilobytes of XHTML inside a file that may be
 *   fifty megabytes of pictures, and the zip central directory says exactly
 *   where those entries are. Seeking to them is what makes opening cost the
 *   text rather than the file (REQ-110).
 *
 * A source that cannot seek returns null from [openChannel] and every reader
 * falls back to one forward pass. That is slower, never wrong.
 */
fun interface EpubByteSource {
    @Throws(java.io.IOException::class)
    fun open(): java.io.InputStream

    /**
     * A random-access view of the same bytes, or null when this source has no
     * seekable form. Callers close the channel.
     */
    @Throws(java.io.IOException::class)
    fun openChannel(): java.nio.channels.SeekableByteChannel? = null
}
