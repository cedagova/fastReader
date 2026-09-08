package com.cedagova.fastreader.reader

import com.cedagova.fastreader.content.BookIdentity
import com.cedagova.fastreader.content.BundledSample
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
 * ## For the callers still to be written
 *
 * - "Open with" (#44) builds [EXTERNAL_KEEPABLE] when the catalog already has
 *   the file, and [EXTERNAL_SESSION_ONLY] with `identity = null` when it does
 *   not. In the second case it computes the digest off the open path and is the
 *   owner of however that late identity reaches storage; until it arrives,
 *   [positionKey] is null and this session simply stores no position.
 * - The bundled sample (#48) is [sample]: [SAMPLE], the identity pinned in
 *   [BundledSample] when the asset was built, and a
 *   [com.cedagova.fastreader.content.SampleBookSource] over the packaged asset.
 *   The asset is packaged uncompressed, so that source hands over a real
 *   channel and the sample reads through the central directory like any other
 *   book; the streaming fallback stays in place for a packaging regression.
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
         * A text shipped inside the APK (#48, REQ-109).
         *
         * Everything the reader needs is already decided: the title is the
         * asset's own `dc:title`, and the identity was computed when the asset
         * was built and pinned in [BundledSample], so this open path — like every
         * other one — hashes nothing.
         *
         * The identity is real rather than null because the parse stamps it onto
         * the token stream. It is *not* an invitation to store a position: a
         * sample has no catalog row, so anything recorded under it would make the
         * next launch try to resume into a book the library does not have. The
         * reader's position store drops sample keys
         * ([BundledSample.isSampleIdentity]).
         */
        fun sample(sample: BundledSample, bytes: EpubByteSource) = BookOpenRequest(
            bytes = bytes,
            identity = sample.identity,
            origin = BookOrigin.SAMPLE,
            title = sample.title,
            openKey = sample.openKey,
        )
    }
}
