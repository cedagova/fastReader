package com.cedagova.fastreader.epub

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * A book this app owns the file of, as every reader of EPUB bytes wants it
 * (#118).
 *
 * `EpubByteSource` was always shaped so that a plain file would satisfy it —
 * [open] is a forward stream and [openChannel] the same bytes with a position —
 * and this is the class that says so out loud. It exists because owner decision
 * D2 put the first such file on the device: a private copy of an account book,
 * downloaded through the backend's grant and verified before it was placed.
 *
 * The consequence that matters is not that the copy opens but *how*. A
 * [SeekableByteChannel] over a real file is the best case the archive reader
 * has: `EpubArchives.open` takes the directory strategy, so opening the copy
 * costs the handful of zip entries its text lives in rather than a forward pass
 * over the whole book (REQ-110, AD-18), and the structural fingerprint a
 * position is guarded by is computed from the central directory exactly as it
 * is for a document-provider file. A downloaded copy is not a second kind of
 * book with a second reading path; it is the *cheapest* kind, through the one
 * path.
 *
 * Nothing here is account-aware. It is a file and two ways to read it, which is
 * why a copy keeps working after sign-out with no code that knows about
 * sign-out (D4).
 */
public class FileEpubByteSource(private val file: File) : EpubByteSource {

    @Throws(IOException::class)
    override fun open(): InputStream = FileInputStream(file)

    @Throws(IOException::class)
    override fun openChannel(): SeekableByteChannel = Files.newByteChannel(file.toPath(), StandardOpenOption.READ)
}
