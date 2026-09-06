package com.cedagova.fastreader.epub

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.util.zip.Inflater

/**
 * A zip read through its central directory.
 *
 * The directory is a table at the end of the file listing every entry and where
 * its bytes start. Reading it costs a few kilobytes regardless of how big the
 * archive is, and afterwards any single entry can be fetched by seeking straight
 * to it. That is the whole of REQ-110: a fifty-megabyte illustrated EPUB and its
 * image-stripped copy have the same few hundred kilobytes of XHTML, so opening
 * them costs the same once the pictures are simply never visited.
 *
 * Contrast with [ZipReader], which streams: skipping an entry there still means
 * reading its compressed bytes off storage, so a forward pass always costs the
 * whole file.
 *
 * ## What this reader refuses
 *
 * [open] returns null — and the caller falls back to streaming — for anything
 * outside the plain single-file layout every EPUB writer produces:
 *
 * - no end-of-central-directory record in the last 64 KiB (not a zip, or a
 *   truncated one);
 * - ZIP64, signalled either by the record's sentinel values or by an entry whose
 *   sizes or offset are `0xFFFFFFFF`;
 * - a multi-disk (spanned) archive.
 *
 * Refusing is never a failure: it hands the archive to the streaming reader,
 * which reaches the same answer by reading more.
 */
internal class ZipDirectory private constructor(
    val entryNames: List<String>,
    private val entries: Map<String, Entry>,
    private val channel: SeekableByteChannel,
) : AutoCloseable {

    /** One central-directory record: where the entry's bytes are and how big they are. */
    private class Entry(
        val localHeaderOffset: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val method: Int,
        val encrypted: Boolean,
    )

    /**
     * Reads one entry, or null when it is absent, encrypted, larger than
     * [maxBytes], or stored in a way this reader does not decode.
     *
     * Throws nothing: a damaged entry comes back as null so one bad chapter
     * degrades into a content gap instead of failing the book.
     */
    fun read(name: String, maxBytes: Long): ByteArray? {
        val entry = entries[name] ?: return null
        if (entry.encrypted) return null
        if (entry.uncompressedSize > maxBytes) return null
        // A deflate stream is never meaningfully larger than what it produces, so
        // this only rejects pathological input — and it bounds the allocation below.
        if (entry.compressedSize > maxBytes) return null
        return try {
            readEntry(entry, maxBytes)
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        try {
            channel.close()
        } catch (_: IOException) {
            // Nothing left to do with a channel that will not close.
        }
    }

    private fun readEntry(entry: Entry, maxBytes: Long): ByteArray? {
        // The local header repeats the name and may carry a *different* extra
        // field than the directory copy, so the data offset can only be computed
        // from the local header itself.
        val header = readAt(entry.localHeaderOffset, LOCAL_HEADER_BYTES) ?: return null
        if (header.int(0) != LOCAL_HEADER_SIGNATURE) return null
        val dataOffset = entry.localHeaderOffset +
            LOCAL_HEADER_BYTES +
            header.short(26) +
            header.short(28)

        val compressed = readAt(dataOffset, entry.compressedSize.toInt()) ?: return null
        return when (entry.method) {
            METHOD_STORED -> compressed.takeIf { it.size <= maxBytes }
            METHOD_DEFLATED -> inflate(compressed, maxBytes)
            else -> null
        }
    }

    private fun inflate(compressed: ByteArray, maxBytes: Long): ByteArray? {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(compressed)
            val out = ByteArrayOutputStream(compressed.size * 2)
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (!inflater.finished()) {
                val produced = inflater.inflate(buffer)
                if (produced == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                    continue
                }
                total += produced
                if (total > maxBytes) return null
                out.write(buffer, 0, produced)
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun readAt(position: Long, length: Int): ByteArray? {
        if (length < 0) return null
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(position)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) return null
        }
        return buffer.array()
    }

    companion object {

        /**
         * Reads [channel]'s central directory, or returns null when the archive is
         * not in the plain layout this reader handles. The channel is closed on a
         * null result and owned by the returned directory otherwise.
         */
        fun open(channel: SeekableByteChannel): ZipDirectory? {
            val directory = try {
                parse(channel)
            } catch (_: Exception) {
                null
            }
            if (directory == null) {
                try {
                    channel.close()
                } catch (_: IOException) {
                    // Already unusable; the caller falls back to streaming.
                }
            }
            return directory
        }

        private fun parse(channel: SeekableByteChannel): ZipDirectory? {
            val size = channel.size()
            if (size < END_RECORD_BYTES) return null

            val end = findEndRecord(channel, size) ?: return null

            // A spanned archive or a ZIP64 sentinel: both mean this reader's
            // 32-bit view of the file is not the truth.
            if (end.short(4) != 0 || end.short(6) != 0) return null
            val entryCount = end.short(10)
            val directorySize = end.int(12).toLong() and UNSIGNED_INT
            val directoryOffset = end.int(16).toLong() and UNSIGNED_INT
            if (entryCount == UNSIGNED_SHORT_MAX ||
                directorySize == UNSIGNED_INT ||
                directoryOffset == UNSIGNED_INT
            ) {
                return null
            }
            if (directoryOffset + directorySize > size) return null
            if (directorySize > MAX_DIRECTORY_BYTES) return null

            val directory = read(channel, directoryOffset, directorySize.toInt()) ?: return null
            val names = ArrayList<String>(entryCount)
            val entries = LinkedHashMap<String, Entry>(entryCount)

            var cursor = 0
            while (names.size < entryCount) {
                if (cursor + CENTRAL_HEADER_BYTES > directory.size) return null
                if (directory.int(cursor) != CENTRAL_HEADER_SIGNATURE) return null

                val flags = directory.short(cursor + 8)
                val method = directory.short(cursor + 10)
                val compressedSize = directory.int(cursor + 20).toLong() and UNSIGNED_INT
                val uncompressedSize = directory.int(cursor + 24).toLong() and UNSIGNED_INT
                val nameLength = directory.short(cursor + 28)
                val extraLength = directory.short(cursor + 30)
                val commentLength = directory.short(cursor + 32)
                val localHeaderOffset = directory.int(cursor + 42).toLong() and UNSIGNED_INT

                // Any 32-bit field at its maximum means the real value lives in a
                // ZIP64 extra field. Rather than decode it, hand the whole archive
                // to the streaming reader.
                if (compressedSize == UNSIGNED_INT ||
                    uncompressedSize == UNSIGNED_INT ||
                    localHeaderOffset == UNSIGNED_INT
                ) {
                    return null
                }

                val nameStart = cursor + CENTRAL_HEADER_BYTES
                if (nameStart + nameLength > directory.size) return null
                // Names are decoded as UTF-8 whatever the flags say, because that is
                // what the streaming reader does; the two paths must agree on entry
                // names or a spine path would resolve on one and not the other.
                val name = String(directory, nameStart, nameLength, Charsets.UTF_8)

                names += name
                if (!name.endsWith("/")) {
                    entries.putIfAbsent(
                        name,
                        Entry(
                            localHeaderOffset = localHeaderOffset,
                            compressedSize = compressedSize,
                            uncompressedSize = uncompressedSize,
                            method = method,
                            encrypted = (flags and ENCRYPTED_FLAG) != 0,
                        ),
                    )
                }
                cursor = nameStart + nameLength + extraLength + commentLength
            }

            return ZipDirectory(names, entries, channel)
        }

        /**
         * Finds the end-of-central-directory record, reading as little as it can.
         *
         * Almost every zip has no trailing comment, so the record is the last 22
         * bytes and a one-kilobyte tail finds it. Only when that fails is the full
         * 64 KiB a comment could occupy read — which matters, because a fixed 64 KiB
         * read would otherwise be most of what opening a small book costs.
         */
        private fun findEndRecord(channel: SeekableByteChannel, size: Long): ByteArray? {
            var tailLength = minOf(size, SHORT_END_RECORD_SEARCH).toInt()
            while (true) {
                val tail = read(channel, size - tailLength, tailLength) ?: return null
                lastIndexOfSignature(tail, END_RECORD_SIGNATURE)?.let { offset ->
                    return tail.copyOfRange(offset, tail.size).takeIf { it.size >= END_RECORD_BYTES }
                }
                if (tailLength.toLong() >= minOf(size, MAX_END_RECORD_SEARCH)) return null
                tailLength = minOf(size, MAX_END_RECORD_SEARCH).toInt()
            }
        }

        private fun read(channel: SeekableByteChannel, position: Long, length: Int): ByteArray? {
            val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
            channel.position(position)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) return null
            }
            return buffer.array()
        }

        private fun lastIndexOfSignature(bytes: ByteArray, signature: Int): Int? {
            for (index in bytes.size - END_RECORD_BYTES downTo 0) {
                if (bytes.int(index) == signature) return index
            }
            return null
        }

        private fun ByteArray.int(offset: Int): Int =
            (this[offset].toInt() and 0xFF) or
                ((this[offset + 1].toInt() and 0xFF) shl 8) or
                ((this[offset + 2].toInt() and 0xFF) shl 16) or
                ((this[offset + 3].toInt() and 0xFF) shl 24)

        private fun ByteArray.short(offset: Int): Int =
            (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

        private const val END_RECORD_SIGNATURE = 0x06054b50
        private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50
        private const val LOCAL_HEADER_SIGNATURE = 0x04034b50

        private const val END_RECORD_BYTES = 22
        private const val CENTRAL_HEADER_BYTES = 46
        private const val LOCAL_HEADER_BYTES = 30

        /** Where the record is when the archive has no trailing comment, with room to spare. */
        private const val SHORT_END_RECORD_SEARCH = 1_024L

        /** 64 KiB of comment plus the record itself: the furthest the record can sit from the end. */
        private const val MAX_END_RECORD_SEARCH = 65_557L

        /** Bounds the directory read for a hostile file; 50 000 entries fit well inside it. */
        private const val MAX_DIRECTORY_BYTES = 32L * 1024 * 1024

        private const val UNSIGNED_INT = 0xFFFFFFFFL
        private const val UNSIGNED_SHORT_MAX = 0xFFFF
        private const val ENCRYPTED_FLAG = 0x1

        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8
    }
}
