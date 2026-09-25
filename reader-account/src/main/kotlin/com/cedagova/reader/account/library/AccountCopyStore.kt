package com.cedagova.reader.account.library

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/**
 * The private copies of account books on this device (REQ-510, D2, AD-24).
 *
 * ## One rule, enforced by the shape of [place]
 *
 * A copy is never readable before its content SHA-256 matches the identity the
 * account holds for that book. This class is built so that there is no way to
 * express the other order: [place] owns the temporary file, wraps it in a
 * [DigestOutputStream] before the caller can write a byte, compares the digest
 * it computed itself against the expected identity, and only then renames the
 * temporary file into the copy's real name. Every other outcome — a digest that
 * disagrees, a full disk, a dropped connection, a short body — deletes the
 * temporary file and returns a reason. Nothing partial is ever left under a
 * name anything would open.
 *
 * The caller cannot take the other order either, because it never learns a
 * path: [place] hands out an [OutputStream], and [copy] only ever returns a
 * file that has already been verified.
 *
 * ## Where the copies live
 *
 * [DIRECTORY_NAME] under `filesDir`, beside the catalog, the covers and the
 * account documents. That is the `file` domain of the backup rules, which is
 * excluded whole in both extraction sections and in the API 26-30 rules — so
 * "downloaded copies are excluded from backup" needs no new rule, and
 * `AppVersionTest` asserts that this directory really does sit in an excluded
 * domain rather than leaving it to be noticed.
 *
 * A copy is named after the content it holds, never after a title, a path or an
 * account (AD-23). Two accounts that hold the same book share one file; signing
 * out touches nothing.
 *
 * ## App death mid-download
 *
 * A download in flight is a `*.part` file, which no lookup here can reach.
 * [discardPartials] deletes any that a killed process left behind, and the
 * catalog never gained a source for one — so the next start has nothing to
 * repair.
 */
public class AccountCopyStore(private val directory: File) {

    /** The verified copy of the book with this content identity, or null when there is none. */
    public fun copy(contentSha256: String): File? = placedFile(contentSha256)?.takeIf { it.isFile }

    /** True when this device holds a verified copy of that book. */
    public fun has(contentSha256: String): Boolean = copy(contentSha256) != null

    /** Every content identity this device currently holds a copy of. */
    public fun contents(): Set<String> = (directory.listFiles() ?: emptyArray())
        .asSequence()
        .filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
        .map { it.name.removePrefix(PREFIX).removeSuffix(SUFFIX) }
        .filter { SHA256.matches(it) }
        .toSet()

    /** The bytes this device holds for that book, or 0 when it holds none. */
    public fun sizeBytes(contentSha256: String): Long = copy(contentSha256)?.length() ?: 0

    /**
     * Frees this device's copy. Returns true when a copy was there to free.
     *
     * The account is not touched: this is D2's **Remove downloaded copy**, and
     * the book stays in the account's library exactly as it was.
     */
    public fun delete(contentSha256: String): Boolean {
        val file = placedFile(contentSha256) ?: return false
        return file.isFile && file.delete()
    }

    /**
     * Deletes every partial download a dead process left behind, and returns
     * how many went.
     *
     * Called on start. A `*.part` file is unreachable by every other method
     * here, so the only thing that could have been using one is a process that
     * no longer exists.
     */
    public fun discardPartials(): Int = (directory.listFiles() ?: emptyArray())
        .count { it.isFile && it.name.endsWith(PARTIAL_SUFFIX) && it.delete() }

    /**
     * Stream a book into private storage and place it only if it is the book it
     * claims to be.
     *
     * [write] is handed a stream over a temporary file. Whatever it writes is
     * digested as it goes — one pass, no second read of a fifty-megabyte file —
     * and when it returns, the digest decides:
     *
     * - equal to [contentSha256]: the temporary file is renamed into place and
     *   [CopyPlacement.Placed] carries the file the reader will open;
     * - anything else: nothing is placed and the temporary file is gone before
     *   this returns.
     *
     * [expectedSizeBytes] is the grant's own declared length. A body of some
     * other length is reported as [CopyPlacement.Refused] for the same reason a
     * digest mismatch is — the caller asked for a specific object and got
     * something else — but it is a cheap check beside the digest, never instead
     * of it: the digest is compared first and a length that agrees proves
     * nothing on its own. Pass 0 when there is no declared length to check.
     */
    public suspend fun place(
        contentSha256: String,
        expectedSizeBytes: Long,
        write: suspend (OutputStream) -> Unit,
    ): CopyPlacement {
        val expected = normalise(contentSha256)
        if (!SHA256.matches(expected)) {
            return CopyPlacement.Refused("a copy is keyed by a content SHA-256, not by '$contentSha256'")
        }
        if (!directory.isDirectory && !directory.mkdirs()) {
            return CopyPlacement.NoStorage("could not open the private copies directory")
        }

        val partial = File(directory, PREFIX + expected + PARTIAL_SUFFIX)
        partial.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            DigestOutputStream(FileOutputStream(partial), digest).use { sink ->
                write(sink)
                sink.flush()
            }
        } catch (cancelled: CancellationException) {
            // A cancelled download is not an outcome to report: the caller went
            // away. The temporary file goes with it, and cancellation keeps
            // propagating, because swallowing it here would leave the coroutine
            // that owns this download alive after its scope had been torn down.
            partial.delete()
            throw cancelled
        } catch (error: Throwable) {
            partial.delete()
            return failure(error)
        }

        val actual = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        if (actual != expected) {
            partial.delete()
            return CopyPlacement.Mismatched(expected = expected, actual = actual)
        }
        val written = partial.length()
        if (expectedSizeBytes > 0 && written != expectedSizeBytes) {
            partial.delete()
            return CopyPlacement.Refused("the grant declared $expectedSizeBytes bytes and $written arrived")
        }
        return place(expected, partial)
    }

    /** The rename that makes a verified copy readable, and the only one there is. */
    private fun place(contentSha256: String, partial: File): CopyPlacement {
        val placed = File(directory, PREFIX + contentSha256 + SUFFIX)
        if (placed.exists() && !placed.delete()) {
            partial.delete()
            return CopyPlacement.NoStorage("could not replace the existing copy")
        }
        if (!partial.renameTo(placed)) {
            partial.delete()
            return CopyPlacement.NoStorage("could not place the copy in private storage")
        }
        return CopyPlacement.Placed(placed)
    }

    /** The copy's path for a well-formed identity, or null when the identity is not one. */
    private fun placedFile(contentSha256: String): File? {
        val normalised = normalise(contentSha256)
        if (!SHA256.matches(normalised)) return null
        return File(directory, PREFIX + normalised + SUFFIX)
    }

    /** Lowercase hex without the contract's optional `sha256:` prefix. */
    private fun normalise(contentSha256: String): String = contentSha256.removePrefix(SHA256_PREFIX).lowercase()

    /**
     * A throwable from [write] or from the filesystem, as one of the two
     * answers a host acts on differently.
     *
     * "No space left on device" is the storage-full refusal REQ-510 names. It
     * is recognised by the message because that is how Android surfaces a full
     * disk — [IOException] has no typed variant for it — and the whole cause
     * chain is searched, since the message arrives wrapped in whatever the
     * download client threw.
     */
    private fun failure(error: Throwable): CopyPlacement {
        val full = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .any { it.contains(NO_SPACE, ignoreCase = true) || it.contains(ENOSPC, ignoreCase = true) }
        return if (full) {
            CopyPlacement.NoStorage("there is no room on this device for the copy")
        } else {
            CopyPlacement.Failed(error)
        }
    }

    public companion object {

        /**
         * The directory name under `filesDir`, beside `catalog/`, `covers/` and
         * `account-library/`. Named in `AppVersionTest`, which is what ties it
         * to the backup exclusion.
         */
        public const val DIRECTORY_NAME: String = "account-copies"

        /** The name a verified copy carries: its content, and nothing about its owner. */
        public const val PREFIX: String = "copy-"
        public const val SUFFIX: String = ".epub"

        /** A download in flight. No lookup in this class can return one. */
        public const val PARTIAL_SUFFIX: String = ".part"

        private const val SHA256_PREFIX: String = "sha256:"
        private val SHA256: Regex = Regex("^[0-9a-f]{64}$")
        private const val NO_SPACE: String = "No space left"
        private const val ENOSPC: String = "ENOSPC"
    }
}

/** What came of trying to put a book's bytes in private storage. */
public sealed interface CopyPlacement {

    /** Verified and placed. [file] is what the reader opens. */
    public data class Placed(val file: File) : CopyPlacement

    /**
     * The bytes are not the book they claimed to be, so nothing was placed.
     *
     * Both digests travel because this is the one refusal the owner is shown a
     * reason for (REQ-510's "a tampered download is refused with the reason").
     */
    public data class Mismatched(val expected: String, val actual: String) : CopyPlacement

    /** There is no room, or private storage would not take the file. Nothing was placed. */
    public data class NoStorage(val reason: String) : CopyPlacement

    /** The bytes did not arrive. Nothing was placed; a later attempt may work. */
    public data class Failed(val error: Throwable) : CopyPlacement

    /** The request itself was wrong — a bad identity, or a length the grant contradicts. */
    public data class Refused(val reason: String) : CopyPlacement
}
