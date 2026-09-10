package com.cedagova.fastreader.epub

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * A cheap answer to "are these the same bytes I read last time?" (AD-18).
 *
 * Book *identity* is the whole file's SHA-256 (AD-2), and since v1.1.0 the reader
 * is handed it rather than computing it, precisely so opening a book never costs
 * a pass over the file (REQ-110, AD-8). That left stored positions with nothing
 * to compare: a file replaced in place under an unchanged catalog entry kept the
 * same identity, so the reader resumed at the stored word of text that was no
 * longer there.
 *
 * This is the second signal that repairs it. A zip's central directory already
 * records, for every entry, its name, its uncompressed size and its CRC-32 —
 * and [ZipDirectory] already reads every one of those bytes to find out where
 * the chapters are. The CRC-32 was simply being thrown away. Digesting the three
 * fields as they go past costs no read at all: the same few kilobytes of table,
 * hashed instead of discarded.
 *
 * ## What it is not
 *
 * **A change detector, not a tamper check.** Two files whose entries have
 * identical names, sizes and CRC-32s are the same content as far as this is
 * concerned, and that is the intent: the same book re-downloaded, re-copied or
 * rebuilt byte-for-byte must resume where the reader left it, not restart. A
 * CRC-32 is trivially forgeable, so a hostile file could be made to match — but
 * nothing here is a security boundary, and the failure it prevents is an honest
 * accident (an edited or re-generated file at the same URI), not an attack.
 *
 * **Not an identity.** It never keys anything, never reaches the catalog as a
 * book id, and never decides whether two library rows are the same book. It only
 * ever answers yes or no against a value stored beside one position.
 *
 * ## Encoding
 *
 * The digest is fed one record per central-directory entry, in directory order,
 * as: the name's UTF-8 length (4 bytes, big-endian), the name's UTF-8 bytes, the
 * uncompressed size (8 bytes), the CRC-32 (4 bytes). Length-prefixing the name
 * is what makes the encoding injective — without it a rename that moves a
 * character across the boundary between two entries could leave the digest
 * unchanged. Directory entries take part like any other record; they carry a
 * zero size and CRC, so their names alone are what they contribute.
 *
 * The result is prefixed [PREFIX] for the same reason identity carries
 * `sha256:`: a stored value says what produced it, so a future change of
 * encoding is a new prefix rather than a silent mismatch that would throw away
 * everyone's position.
 */
internal class StructuralFingerprint private constructor() {

    private val digest = MessageDigest.getInstance("SHA-256")
    private val record = ByteBuffer.allocate(RECORD_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)

    /** Folds one central-directory record in. Called once per entry, in directory order. */
    fun add(name: String, uncompressedSize: Long, crc32: Long) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        record.clear()
        record.putInt(nameBytes.size)
        digest.update(record.array(), 0, Int.SIZE_BYTES)
        digest.update(nameBytes)
        record.clear()
        record.putLong(uncompressedSize)
        record.putInt(crc32.toInt())
        digest.update(record.array(), 0, RECORD_HEADER_BYTES)
    }

    /** The finished fingerprint. Not reusable: the digest is consumed. */
    fun build(): String {
        val hex = StringBuilder(PREFIX.length + 64).append(PREFIX)
        digest.digest().forEach { byte -> hex.append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF]) }
        return hex.toString()
    }

    companion object {

        /** Names the encoding above, so a later one cannot be mistaken for this one. */
        const val PREFIX = "zipdir1:"

        fun builder() = StructuralFingerprint()

        private const val RECORD_HEADER_BYTES = Long.SIZE_BYTES + Int.SIZE_BYTES
        private const val HEX = "0123456789abcdef"
    }
}
