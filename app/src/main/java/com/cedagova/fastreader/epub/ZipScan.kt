package com.cedagova.fastreader.epub

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * One streaming pass over a candidate EPUB.
 *
 * [digest] is a SHA-256 over the *whole* raw file, computed underneath the zip
 * reader and completed by draining whatever the zip reader did not consume. That
 * makes it available even for archives that fail to parse, so a corrupt or
 * DRM-protected book still has a stable catalog identity.
 */
internal class ZipScan(
    val entryNames: List<String>,
    val collected: Map<String, ByteArray>,
    val digest: String?,
    val openFailure: String?,
    val zipFailure: String?,
) {
    val entryNameSet: Set<String> by lazy { entryNames.toSet() }
}

internal object ZipReader {

    const val MAX_ENTRIES = 50_000

    /**
     * Streams [source] once, keeping the bytes of every entry [collect] accepts
     * (each capped at [maxEntryBytes]) and hashing the complete file.
     */
    fun scan(
        source: EpubByteSource,
        collect: (String) -> Boolean,
        maxEntryBytes: Long,
        computeDigest: Boolean = true,
    ): ZipScan {
        val names = ArrayList<String>()
        val collected = LinkedHashMap<String, ByteArray>()
        var zipFailure: String? = null
        var digestHex: String? = null

        val raw = try {
            source.open()
        } catch (error: Exception) {
            return ZipScan(names, collected, null, error.readableMessage(), null)
        }

        raw.use { rawStream ->
            val digest = if (computeDigest) MessageDigest.getInstance("SHA-256") else null
            val hashing: InputStream = if (digest != null) DigestInputStream(rawStream, digest) else rawStream
            try {
                // NonClosingInputStream keeps this from closing the digest stream
                // before the rest of the file has been hashed.
                ZipInputStream(NonClosingInputStream(hashing)).use { zip ->
                    while (true) {
                        val entry = try {
                            zip.nextEntry ?: break
                        } catch (error: Exception) {
                            zipFailure = error.readableMessage()
                            break
                        }
                        if (names.size >= MAX_ENTRIES) {
                            zipFailure = "archive has more than $MAX_ENTRIES entries"
                            break
                        }
                        val name = entry.name
                        names += name
                        if (!entry.isDirectory && collect(name)) {
                            val bytes = try {
                                zip.readCapped(maxEntryBytes)
                            } catch (error: Exception) {
                                zipFailure = error.readableMessage()
                                break
                            }
                            if (bytes != null) collected[name] = bytes
                        }
                        try {
                            zip.closeEntry()
                        } catch (error: Exception) {
                            zipFailure = error.readableMessage()
                            break
                        }
                    }
                }
            } catch (error: Exception) {
                zipFailure = error.readableMessage()
            }

            if (digest != null) {
                // Consume whatever the zip reader left so the hash covers the whole file.
                try {
                    val drain = ByteArray(DRAIN_BUFFER)
                    while (hashing.read(drain) >= 0) {
                        // hashing as a side effect
                    }
                } catch (_: IOException) {
                    // A read failure here still leaves a digest over everything read so far;
                    // the archive itself is reported through zipFailure/openFailure.
                }
                digestHex = digest.digest().toHex()
            }
        }

        return ZipScan(names, collected, digestHex, null, zipFailure)
    }

    /** Reads exactly one entry by name, or null when it is absent or oversized. */
    fun readEntry(source: EpubByteSource, name: String, maxEntryBytes: Long): ByteArray? =
        scan(source, collect = { it == name }, maxEntryBytes = maxEntryBytes, computeDigest = false)
            .collected[name]

    private const val DRAIN_BUFFER = 64 * 1024

    private fun InputStream.readCapped(limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        val digits = "0123456789abcdef"
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            chars[index * 2] = digits[value ushr 4]
            chars[index * 2 + 1] = digits[value and 0x0F]
        }
        return String(chars)
    }

    private fun Exception.readableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
}

/** Keeps [ZipInputStream.close] from closing the digest stream before it is drained. */
private class NonClosingInputStream(private val delegate: InputStream) : InputStream() {
    override fun read(): Int = delegate.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    override fun available(): Int = delegate.available()
    override fun close() = Unit
}
