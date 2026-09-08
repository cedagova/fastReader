package com.cedagova.fastreader.epub

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * An in-memory [SeekableByteChannel] that watches what is read from it.
 *
 * Two things the reader's cost claim needs and a plain byte array cannot show:
 *
 * - [bytesRead] counts every byte actually taken off the channel, so a test can
 *   assert that opening a book costs its text and not its file (REQ-110).
 * - [poisoned] ranges throw when touched, so a test can assert that entries the
 *   reader must never read — a book's images — are never read *at all*, rather
 *   than merely producing the right words in spite of being read.
 */
class TestByteChannel(
    private val bytes: ByteArray,
    private val poisoned: List<IntRange> = emptyList(),
) : SeekableByteChannel {

    var bytesRead: Long = 0
        private set

    private var position = 0L
    private var open = true

    override fun read(destination: ByteBuffer): Int {
        if (!open) throw IOException("channel is closed")
        if (position >= bytes.size) return -1
        val count = minOf(destination.remaining().toLong(), bytes.size - position).toInt()
        val start = position.toInt()
        val touched = start until (start + count)
        poisoned.firstOrNull { it.first <= touched.last && touched.first <= it.last }?.let {
            throw IOException("read $touched touched poisoned bytes $it")
        }
        destination.put(bytes, start, count)
        position += count
        bytesRead += count
        return count
    }

    override fun write(source: ByteBuffer): Int = throw UnsupportedOperationException("read-only")

    override fun position(): Long = position

    override fun position(newPosition: Long): SeekableByteChannel = apply { position = newPosition }

    override fun size(): Long = bytes.size.toLong()

    override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException("read-only")

    override fun isOpen(): Boolean = open

    override fun close() {
        open = false
    }
}
