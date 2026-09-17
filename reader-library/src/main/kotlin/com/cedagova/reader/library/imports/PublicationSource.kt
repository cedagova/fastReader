package com.cedagova.reader.library.imports

import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel

/**
 * The bytes of one publication, as its owner already holds them.
 *
 * This is a *seam*, not a store. The import path copies nothing: it reads the
 * file the host already has — the device book's own byte source — one chunk at
 * a time, straight into the PATCH body. Nothing is staged, duplicated or
 * cached, which is the difference between adding a 50 MiB book and briefly
 * needing 100 MiB of free space.
 *
 * [openChannel] is how that stays cheap on resume. A resumed transfer starts at
 * a durable offset, and a seekable source reaches it by position; a source that
 * can only stream returns null and is wound forward instead — slower, never
 * wrong. It mirrors the host's own `EpubByteSource` deliberately, so the
 * adapter is one expression.
 *
 * [sha256] and [sizeBytes] are the identity the whole lifecycle is keyed on:
 * the `client_import_id` is derived from [sha256] (AD-23 — content, never a
 * path or a title), and [sizeBytes] is what the policy cap is checked against
 * and what `Upload-Length` declares. A source whose bytes do not match what it
 * says here fails verification at the backend, which is the right place: the
 * backend never takes an uploader's word for a digest.
 */
interface PublicationSource {

    /** The exact byte length of the file. */
    val sizeBytes: Long

    /** The whole-file SHA-256, lowercase hex, with or without the `sha256:` prefix. */
    val sha256: String

    /** The source MIME type, matched against the policy's declared types. */
    val mimeType: String

    /** The original file name, for the account's own record; null when there is none. */
    val fileName: String?

    /** A fresh stream over the whole file, positioned at zero. Callers close it. */
    @Throws(IOException::class)
    fun open(): InputStream

    /** A random-access view of the same bytes, or null when this source cannot seek. */
    @Throws(IOException::class)
    fun openChannel(): SeekableByteChannel? = null
}
