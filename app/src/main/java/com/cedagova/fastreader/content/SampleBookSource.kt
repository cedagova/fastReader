package com.cedagova.fastreader.content

import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import com.cedagova.fastreader.epub.EpubByteSource
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel

/**
 * A bundled sample's bytes, read straight out of the installed APK.
 *
 * Nothing is copied to disk to make this work: the asset is read in place, which
 * is why opening the sample writes nothing outside the app's private storage and
 * leaves nothing behind afterwards (REQ-109).
 *
 * ## Seekable, on purpose
 *
 * `AssetManager` will only hand out a file descriptor for an asset that was
 * packaged **uncompressed** — `openFd` throws `FileNotFoundException` for a
 * deflated one. `app/build.gradle.kts` therefore lists `epub` under
 * `androidResources.noCompress`, which costs essentially nothing (an EPUB is
 * already a deflated zip, so the APK's own deflate saves almost nothing on it)
 * and buys the sample the same central-directory read path every other book
 * uses: the reader seeks to the four entries it wants instead of streaming the
 * whole file past them (REQ-110, AD-8).
 *
 * The fallback is still correct, just slower: [openChannel] returns null if the
 * descriptor cannot be had, and [com.cedagova.fastreader.epub.EpubArchives] then
 * makes one forward pass. A packaging regression costs milliseconds on a 3 KB
 * file, not a broken sample.
 */
class SampleBookSource(
    private val assets: AssetManager,
    private val sample: BundledSample,
) : EpubByteSource {

    @Throws(IOException::class)
    override fun open(): InputStream = assets.open(sample.assetPath, AssetManager.ACCESS_STREAMING)

    override fun openChannel(): SeekableByteChannel? {
        val descriptor = try {
            assets.openFd(sample.assetPath)
        } catch (_: IOException) {
            // The asset is compressed inside the APK, so it has no descriptor of
            // its own. Streaming still reads it correctly.
            return null
        }
        return try {
            AssetSliceChannel(descriptor)
        } catch (_: IOException) {
            descriptor.closeQuietly()
            null
        }
    }
}

/**
 * A seekable view of one asset inside the APK.
 *
 * The descriptor names a *slice* of the APK file — an offset and a length — so
 * every read is translated: logical position 0 is the asset's first byte, and
 * the channel ends where the asset ends rather than where the APK does. Reads
 * are absolute ([FileChannel.read] with a position) rather than seeks followed
 * by relative reads, so the shared file offset underneath is never a factor.
 */
private class AssetSliceChannel(descriptor: AssetFileDescriptor) : SeekableByteChannel {

    // Owns its own duplicated descriptor, so closing this channel does not touch
    // the AssetManager's.
    private val stream = descriptor.createInputStream()
    private val channel: FileChannel = stream.channel
    private val start = descriptor.startOffset
    private val length = descriptor.length

    private var position = 0L

    init {
        if (length < 0) {
            stream.close()
            throw IOException("asset has no known length")
        }
    }

    override fun read(destination: ByteBuffer): Int {
        val remaining = length - position
        if (remaining <= 0L) return -1
        val wanted = minOf(destination.remaining().toLong(), remaining).toInt()
        if (wanted == 0) return 0
        val limit = destination.limit()
        destination.limit(destination.position() + wanted)
        val read = try {
            channel.read(destination, start + position)
        } finally {
            destination.limit(limit)
        }
        if (read > 0) position += read
        return read
    }

    override fun write(source: ByteBuffer): Int = throw UnsupportedOperationException("read-only")

    override fun position(): Long = position

    override fun position(newPosition: Long): SeekableByteChannel = apply {
        require(newPosition >= 0L) { "negative position" }
        position = newPosition
    }

    override fun size(): Long = length

    override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException("read-only")

    override fun isOpen(): Boolean = channel.isOpen

    override fun close() = stream.close()
}

private fun AssetFileDescriptor.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // Nothing further to do with a descriptor that will not close.
    }
}
