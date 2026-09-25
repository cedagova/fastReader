package com.cedagova.fastreader.epub

import com.cedagova.fastreader.content.BookIdentity
import java.security.MessageDigest

/**
 * The whole-file SHA-256 that gives a book its identity (v1 AD-2), on its own.
 *
 * [EpubInspector] already computes this value while it inspects a file, and the
 * catalog gets its ids that way. This exists for the one caller that needs the
 * identity of a book it is *already reading*: a book handed over from outside the
 * app (#44) opens with no identity at all, and the digest is computed once,
 * after streaming has begun, purely so the reading position has a key (AD-8).
 * Inspecting the book again there would parse an archive whose contents the
 * reader has already got.
 *
 * Byte-identical to [EpubInspection.contentDigest] by construction: both hash the
 * complete raw file and prefix the hex with `sha256:`. `BookDigestTest` pins that
 * against the real fixtures, because a digest that drifted would strand every
 * position stored under it.
 */
object BookDigest {

    private const val BUFFER_BYTES = 64 * 1024

    /**
     * Hashes [source] end to end, or returns null when its bytes cannot be read.
     *
     * Reads the whole file by definition, so it must never be called on the open
     * path — that is the cost REQ-110 exists to remove.
     */
    fun of(source: EpubByteSource): BookIdentity? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        source.open().use { stream ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        BookIdentity.ofSha256Hex(digest.digest().toHex())
    } catch (_: Exception) {
        // Every failure is the same answer to the caller: this book has no
        // identity yet, so nothing is keyed by one. It is never fatal — the
        // reader carries on with the book it already has open.
        null
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
}
