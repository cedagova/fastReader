package com.cedagova.fastreader.reader

import com.cedagova.fastreader.content.BookContent
import com.cedagova.fastreader.content.BookContentResult
import com.cedagova.fastreader.content.BookIdentity
import com.cedagova.fastreader.content.ContentFixtures
import com.cedagova.fastreader.content.EpubContentPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Real parsed books for the reader's tests.
 *
 * They come out of LEAF201's pipeline rather than being hand-built token lists,
 * so the reader is proven against the token stream it actually receives —
 * including the chapter boundaries, the `[image skipped]` and `[table skipped]`
 * markers, and the `[content unavailable]` gap marker that a hand-written fixture
 * would quietly get wrong.
 */
object ReaderFixtures {

    /**
     * Catalog ids for the fixture books.
     *
     * Real ones: a book's id *is* its whole-file SHA-256 (AD-2), and since v1.1.0
     * the reader takes that id as the identity to stamp on the parse rather than
     * computing one (AD-8). Tests that seed a stored position have to use the same
     * id the reader will open the book under, or the position belongs to nothing.
     */
    const val ENGLISH_NOVEL_ID = "sha256:0000000000000000000000000000000000000000000000000000000000000e01"
    const val SECOND_BOOK_ID = "sha256:0000000000000000000000000000000000000000000000000000000000000e02"

    /** EPUB 3, four chapters, an inline image, a table, and a footnote reference. */
    val englishNovel: BookContent by lazy { parse(ContentFixtures.englishNovel(), ENGLISH_NOVEL_ID) }

    /** EPUB 2, Spanish: inverted punctuation, accents and dialogue dashes. */
    val spanishNovel: BookContent by lazy { parse(ContentFixtures.spanishNovel()) }

    /** EPUB 3, one chapter that is one paragraph of some 230 words. */
    val longParagraph: BookContent by lazy { parse(ContentFixtures.longParagraphNovel(), LONG_PARAGRAPH_ID) }

    const val LONG_PARAGRAPH_ID = "sha256:0000000000000000000000000000000000000000000000000000000000000e03"

    /** A download that stopped after chapter one: chapters two and three are gaps. */
    val interrupted: BookContent by lazy { parse(ContentFixtures.interruptedMidBook()) }

    fun parse(bytes: ByteArray, id: String = ENGLISH_NOVEL_ID): BookContent = runBlocking {
        val result = EpubContentPipeline(Dispatchers.Unconfined)
            .parse(ContentFixtures.source(bytes), BookIdentity(id))
        (result as BookContentResult.Parsed).content
    }
}
