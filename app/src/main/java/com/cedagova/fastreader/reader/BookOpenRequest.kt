package com.cedagova.fastreader.reader

import com.cedagova.fastreader.content.BookIdentity
import com.cedagova.fastreader.epub.EpubByteSource

/**
 * Where the book being opened came from, and therefore what the app may do with
 * it (AD-9).
 *
 * Origin is not a display label. It decides three things the reader cannot infer
 * from the bytes: whether the file is expected to still be there tomorrow,
 * whether a "keep this book" offer makes sense, and whether the catalog is
 * involved at all.
 */
enum class BookOrigin {

    /**
     * A book in the catalog, reached from the library. Its identity is its
     * `Book.id`, its position is stored under that id, and its source grant is
     * persisted. The only origin this app produced before v1.1.0.
     */
    LIBRARY,

    /**
     * A book handed over from outside the app that is *already* in the catalog —
     * the same file the reader added earlier, arriving by "Open with" instead of
     * from the library list. Identity is known immediately (the catalog matched
     * it), so it behaves exactly like [LIBRARY] once open.
     */
    EXTERNAL_KEEPABLE,

    /**
     * A book handed over from outside the app that the catalog does not know.
     * The reader gets a notice and an "Add to library" offer; nothing is
     * persisted about the file unless they take it.
     *
     * The one origin that may open with [BookOpenRequest.identity] still null:
     * the whole-file digest is computed off the open path, after streaming has
     * begun, so a stranger's first book does not wait on a hash (AD-8).
     */
    EXTERNAL_SESSION_ONLY,

    /**
     * The sample shipped inside the APK. Identity is fixed at build time, there
     * is no grant to persist and no library semantics; it exists so a stranger
     * has something to read two taps after installing.
     */
    SAMPLE,
}

/**
 * One request to open a book: **bytes, identity, origin** (AD-8/AD-9).
 *
 * This is the reader's whole entry contract. Before v1.1.0 the reader took a
 * catalog id, fetched the bytes itself, and let the content pipeline recompute
 * the file's SHA-256 while parsing — which meant opening a fifty-megabyte
 * illustrated book paid for fifty megabytes of hashing before the first word.
 * Now the caller says who the book is and the reader believes it.
 *
 * ## The callers
 *
 * - The library builds [library].
 * - "Open with" and the share sheet build [external]:
 *   [BookOrigin.EXTERNAL_KEEPABLE] when the catalog already has the file, and
 *   [BookOrigin.EXTERNAL_SESSION_ONLY] with `identity = null` when it does not.
 *   In the second case
 *   [com.cedagova.fastreader.external.ExternalOpenController] computes the digest
 *   off the open path and hands it over through [withIdentity]; until it
 *   arrives, [positionKey] is null and this session simply stores no position.
 * - The bundled sample (#48) builds [SAMPLE] with the identity it computed at
 *   build time and an [EpubByteSource] over the packaged asset. An asset is not
 *   seekable through `AssetManager` unless it is stored uncompressed, so #48
 *   should either package it uncompressed and hand over a channel, or accept the
 *   streaming fallback — the sample is small enough for either.
 */
class BookOpenRequest(

    /**
     * The book's bytes. Prefer a source that can also
     * [open a channel][EpubByteSource.openChannel]: without one the pipeline
     * falls back to a forward pass and reads the whole file.
     */
    val bytes: EpubByteSource,

    /**
     * The whole-file SHA-256, when the caller already knows it. Null only for
     * [BookOrigin.EXTERNAL_SESSION_ONLY], where it arrives after the open.
     *
     * Never recomputed here. This value is stamped straight onto the parsed
     * book, so handing over the wrong one makes stored positions unresolvable
     * for that book — it is the caller's job to have it right.
     */
    val identity: BookIdentity?,

    val origin: BookOrigin,

    /** The title to show while the book is opening, before its metadata is read. */
    val title: String,

    /**
     * What "the same book" means for an already-open check. The catalog id for a
     * library book; for an external one, whatever names that handover — a URI —
     * so re-entering the reader after a rotation does not re-parse.
     */
    val openKey: String,
) {

    /**
     * The key this book's reading position is stored under, or null while the
     * identity is still unknown.
     *
     * Positions are keyed by identity for every origin, which is what makes a
     * book opened from the Files app resume where the library left it (AD-9).
     */
    val positionKey: String? get() = identity?.value

    companion object {

        /** The ordinary case: a catalog book, whose id *is* its identity (AD-2/AD-8). */
        fun library(bookId: String, title: String, bytes: EpubByteSource) = BookOpenRequest(
            bytes = bytes,
            identity = BookIdentity(bookId),
            origin = BookOrigin.LIBRARY,
            title = title,
            openKey = bookId,
        )

        /**
         * A book handed over from outside the app (REQ-103).
         *
         * The document URI is the [openKey], because it is the only name this
         * hand-over has: the identity may still be unknown, and two different
         * files can arrive with the same title. Keying on it is what makes a
         * rotation re-enter the same open book rather than re-parse it, and what
         * makes a second "Open with" of a *different* file replace it.
         */
        fun external(
            uri: String,
            title: String,
            identity: BookIdentity?,
            origin: BookOrigin,
            bytes: EpubByteSource,
        ) = BookOpenRequest(
            bytes = bytes,
            identity = identity,
            origin = origin,
            title = title,
            openKey = uri,
        )
    }

    /**
     * The same request with its identity finally known (AD-8).
     *
     * Only ever a null-to-known transition: an identity that is already set is
     * the one the position of this book is keyed by, and replacing it would
     * strand that position under a key nothing will look up again.
     */
    fun withIdentity(resolved: BookIdentity): BookOpenRequest {
        check(identity == null) { "identity is already known for $openKey" }
        return BookOpenRequest(
            bytes = bytes,
            identity = resolved,
            origin = origin,
            title = title,
            openKey = openKey,
        )
    }
}
