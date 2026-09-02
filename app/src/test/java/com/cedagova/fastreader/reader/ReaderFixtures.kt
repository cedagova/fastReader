package com.cedagova.fastreader.reader

import com.cedagova.fastreader.content.BookContent
import com.cedagova.fastreader.content.BookContentResult
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

    /** EPUB 3, four chapters, an inline image, a table, and a footnote reference. */
    val englishNovel: BookContent by lazy { parse(ContentFixtures.englishNovel()) }

    /** EPUB 2, Spanish: inverted punctuation, accents and dialogue dashes. */
    val spanishNovel: BookContent by lazy { parse(ContentFixtures.spanishNovel()) }

    /** A download that stopped after chapter one: chapters two and three are gaps. */
    val interrupted: BookContent by lazy { parse(ContentFixtures.interruptedMidBook()) }

    fun parse(bytes: ByteArray): BookContent = runBlocking {
        val result = EpubContentPipeline(Dispatchers.Unconfined).parse(ContentFixtures.source(bytes))
        (result as BookContentResult.Parsed).content
    }
}
