package com.cedagova.fastreader.epub

/**
 * The entries of one archive, however they had to be reached.
 *
 * The reader asks for four kinds of thing — `META-INF/container.xml`, the package
 * document, the navigation document, and the spine's XHTML — and never for the
 * images, fonts, audio or video that make up the bulk of an illustrated book.
 * Whether that selectivity actually saves anything depends entirely on the
 * strategy underneath:
 *
 * - [ArchiveReadStrategy.DIRECTORY] seeks to each wanted entry through the zip
 *   central directory, so the cost is the text. This is what REQ-110 rests on.
 * - [ArchiveReadStrategy.STREAMING] makes one forward pass and keeps what it
 *   wants. Skipped entries are still read off storage, so the cost is the file.
 *
 * The strategy is chosen per source, not per book: [EpubByteSource.openChannel]
 * either offers a seekable view or it does not.
 */
internal interface EpubArchive : AutoCloseable {

    val strategy: ArchiveReadStrategy

    /**
     * A digest of this archive's structure (AD-18), or null when the open produced
     * none and a stored position therefore has nothing to be checked against.
     *
     * Null is exactly [ArchiveReadStrategy.STREAMING]: the fingerprint comes off
     * the central directory, and the streaming reader never reads one — that is
     * the difference between the two strategies. Keying the fallback on the
     * strategy rather than on whether the source *could* seek is deliberate: a
     * perfectly seekable file whose layout [ZipDirectory] refuses (ZIP64, spanned,
     * too many entries) also lands here, and would otherwise be assumed to have a
     * fingerprint it does not have.
     *
     * See [StructuralFingerprint] for what a non-null value means, and
     * `com.cedagova.fastreader.reader.ReaderPosition` for what is done with it.
     */
    val structuralFingerprint: String?

    /**
     * Reads every entry [select] accepts, each capped at [maxBytes].
     *
     * Never throws. An archive that is damaged partway through reports what it
     * managed to read plus [ArchiveRead.damage], because a book with three of
     * its five chapters is still worth opening.
     */
    fun read(select: (String) -> Boolean, maxBytes: Long): ArchiveRead
}

internal enum class ArchiveReadStrategy {
    /** Random access through the central directory: reads only the wanted entries. */
    DIRECTORY,

    /** One forward pass: reads the whole file to reach the wanted entries. */
    STREAMING,
}

/** What one [EpubArchive.read] found. */
internal class ArchiveRead(
    /** Every entry in the archive, in archive order, whether or not it was wanted. */
    val entryNames: List<String>,
    val entries: Map<String, ByteArray>,
    /** Non-null when the bytes stopped being reachable mid-read — a grant revoked under us. */
    val unreadable: String?,
    /** Non-null when the archive itself is damaged; entries already read stay usable. */
    val damage: String?,
)

/** Opening an archive: either a usable reader, or a source that would not open at all. */
internal sealed interface ArchiveOpen {
    class Opened(val archive: EpubArchive) : ArchiveOpen

    /** The bytes could not be reached — a revoked grant, a deleted file. */
    class Unopenable(val detail: String) : ArchiveOpen
}

internal object EpubArchives {

    /**
     * Opens [source] for entry reads, preferring the directory strategy.
     *
     * A source with no seekable view, or one whose layout [ZipDirectory] does not
     * decode, falls back to streaming rather than failing. Only a source that
     * cannot be opened at all is [ArchiveOpen.Unopenable].
     */
    fun open(source: EpubByteSource): ArchiveOpen {
        val directory = try {
            source.openChannel()?.let { ZipDirectory.open(it) }
        } catch (_: Exception) {
            // A seekable view that fails to open is not fatal; the forward stream
            // below is tried on its own and reports the real access failure.
            null
        }
        if (directory != null) return ArchiveOpen.Opened(DirectoryArchive(directory))

        // Prove the forward stream opens before promising an archive, so a revoked
        // grant is still reported as an access failure rather than an empty book.
        try {
            source.open().close()
        } catch (error: Exception) {
            return ArchiveOpen.Unopenable(error.readableMessage())
        }
        return ArchiveOpen.Opened(StreamingArchive(source))
    }

    private fun Exception.readableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
}

private class DirectoryArchive(private val directory: ZipDirectory) : EpubArchive {

    override val strategy = ArchiveReadStrategy.DIRECTORY

    override val structuralFingerprint: String get() = directory.structuralFingerprint

    override fun read(select: (String) -> Boolean, maxBytes: Long): ArchiveRead {
        val entries = LinkedHashMap<String, ByteArray>()
        directory.entryNames.forEach { name ->
            if (select(name)) directory.read(name, maxBytes)?.let { entries[name] = it }
        }
        // A directory read has no partial-failure mode of its own: an entry that
        // will not inflate is simply absent, which the pipeline already reports as
        // a gap in the book.
        return ArchiveRead(directory.entryNames, entries, unreadable = null, damage = null)
    }

    override fun close() = directory.close()
}

private class StreamingArchive(private val source: EpubByteSource) : EpubArchive {

    override val strategy = ArchiveReadStrategy.STREAMING

    /**
     * No fingerprint: a forward pass never reads the central directory, and
     * rebuilding the same value from the local headers would mean reading past
     * every entry's data — the whole-file cost REQ-110 exists to avoid. A book
     * opened this way keeps exactly the resume behaviour it had before the guard
     * existed, and an already-stored fingerprint is left alone rather than
     * cleared (AD-18).
     */
    override val structuralFingerprint: String? get() = null

    override fun read(select: (String) -> Boolean, maxBytes: Long): ArchiveRead {
        val scan = ZipReader.scan(source, collect = select, maxEntryBytes = maxBytes, computeDigest = false)
        return ArchiveRead(
            entryNames = scan.entryNames,
            entries = scan.collected,
            unreadable = scan.openFailure,
            damage = scan.zipFailure,
        )
    }

    override fun close() = Unit
}
