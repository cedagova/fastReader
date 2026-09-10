package com.cedagova.fastreader.reader

/**
 * The one-time offer to start a book past its front matter (REQ-202).
 *
 * A value rather than a screen state: the reader surface draws it, the route
 * persists the fact that it was made, and
 * [com.cedagova.fastreader.reader.ReaderViewModel] decides when it applies.
 * Keeping the three apart is what lets the offer be proven by arithmetic in the
 * session tests, by a golden on the screen, and by a store test for the flag,
 * without any of the three needing the other two.
 */
data class FrontMatterOffer(
    /**
     * The id the "already offered" record is stored under — the same key a
     * reading position uses.
     *
     * Null for a book that has no identity yet: an "Open with" whose whole-file
     * digest is still being computed off the open path (AD-8). Nothing durable
     * can be written about such a book, so answering the offer settles it for
     * this session only. It is a real but narrow window — the digest lands
     * seconds into the first stream — and the alternative, inventing a key, would
     * strand the record under something no later open matches.
     */
    val positionKey: String?,
    /** The token the offer moves to: the first word of the book's first real chapter. */
    val startTokenIndex: Int,
    /** That chapter's title, so the offer can say where it goes rather than only that it goes. */
    val chapterTitle: String,
)
