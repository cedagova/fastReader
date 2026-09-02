package com.cedagova.fastreader.content

/**
 * The token stream model (AD-4) — the reader's internal contract.
 *
 * The EPUB content pipeline produces it; the timing engine (LEAF202), the reader
 * screen (LEAF203) and persistence (LEAF204) consume it. Everything downstream
 * addresses content by [Token.index], so this file defines what a position
 * *means*.
 *
 * Two rules the consumers depend on:
 *
 * 1. **Deterministic.** The same EPUB bytes always produce the same tokens in the
 *    same order with the same indices. That is what makes a stored position
 *    survive closing the book, and it is why every heuristic here is a fixed
 *    rule rather than anything adaptive.
 * 2. **Contiguous.** Indices run `0 until totalTokens` with no gaps, so progress
 *    is index arithmetic and no consumer has to search.
 *
 * Changing how tokens are produced moves every stored position, so
 * [ContentPipelineVersion.CURRENT] is bumped whenever that happens and persisted
 * alongside a position under the AD-3 migration rule.
 */

/** Version of the tokenization rules. Bump when a change would move stored positions. */
object ContentPipelineVersion {
    const val CURRENT: Int = 1
}

/**
 * What follows a token, as a pause the timing engine applies *after* showing it.
 *
 * Ordinal order is deliberate: it runs weakest to strongest, so when a word ends
 * a sentence *and* its paragraph the pipeline keeps the stronger one with
 * `maxOf`. The multipliers themselves belong to LEAF202; this enum only says
 * which break happened.
 */
enum class Boundary {
    /** Ordinary word break. */
    NONE,

    /** Comma, semicolon, colon, or a dash used as dialogue/aside punctuation. */
    CLAUSE,

    /** Sentence-final punctuation, including a Spanish `¿…?` or `¡…!` closing mark. */
    SENTENCE,

    /** Last token of a paragraph or list item. */
    PARAGRAPH,

    /** Last token of a heading. */
    HEADING,
}

/**
 * Bounded, no-NLP word properties the timing engine slows down for.
 *
 * Research pins the effect (long/number/rare ≈ 1.5×, abbreviations exempt from
 * the sentence pause) but not the detection, so each rule below is deliberately
 * mechanical and stated in one place.
 */
enum class WordClass {
    /** Longer than [WordClassifier.LONG_WORD_MIN_LENGTH] characters. */
    LONG,

    /** Contains at least one digit — "1984", "3.5", "XIV2". */
    NUMBER,

    /** Two or more letters, none of them lowercase — "NASA", "URSS". */
    ALL_CAPS,

    /** Occurs once in the whole book and is not short: rare *for this book*. */
    RARE,

    /** "Sr.", "Dr.", "J." — the trailing period does not end a sentence. */
    ABBREVIATION,
}

/** Why a piece of content is represented by a marker instead of its words. */
enum class SkipKind {
    /** An `<img>`, `<svg>` or `<figure>` image the reader cannot stream as words. */
    IMAGE,

    /** A `<table>`: skipped whole, because reading cells aloud in order is nonsense. */
    TABLE,

    /**
     * A spine item the book declares but the file does not contain, or that could
     * not be decoded. A download interrupted mid-book lands here.
     */
    MISSING_CONTENT,
}

/**
 * One position in the stream.
 *
 * [displayText] is what the reader shows for this token — a word, or the marker
 * label for skipped content — so the renderer needs no type switch to draw it.
 */
sealed interface Token {
    val index: Int
    val chapterIndex: Int

    /** Global paragraph ordinal, for paragraph-level navigation. */
    val paragraphIndex: Int

    /** Global sentence ordinal, for sentence-level navigation. */
    val sentenceIndex: Int

    /** The pause that applies *after* this token. */
    val boundary: Boundary

    val displayText: String
}

/** A single word of book text, exactly as it should be shown. */
data class WordToken(
    override val index: Int,
    val text: String,
    override val chapterIndex: Int,
    override val paragraphIndex: Int,
    override val sentenceIndex: Int,
    override val boundary: Boundary,
    val classes: Set<WordClass> = emptySet(),
    /** True for every word inside an `<h1>`–`<h6>`, so the reader can style it. */
    val isHeading: Boolean = false,
) : Token {
    override val displayText: String get() = text
}

/**
 * Content that exists in the book but cannot be streamed as words.
 *
 * REQ-015 requires the reader to be told the content was there rather than
 * silently dropping it, so this is a real token: it occupies a position and the
 * timing engine gives it a duration like any other.
 */
data class SkipMarkerToken(
    override val index: Int,
    val kind: SkipKind,
    override val chapterIndex: Int,
    override val paragraphIndex: Int,
    override val sentenceIndex: Int,
    override val boundary: Boundary = Boundary.PARAGRAPH,
    /** Plain-language label, already reader-facing: `[image skipped]`. */
    val label: String,
) : Token {
    override val displayText: String get() = label
}

/** Where a chapter's title came from, which the reader may want to present differently. */
enum class ChapterTitleSource {
    /** The EPUB 3 nav document or the EPUB 2 NCX. */
    TOC,

    /** The first heading inside the spine item. */
    HEADING,

    /** Neither existed: a positional fallback such as "Section 4". */
    FALLBACK,
}

/**
 * One chapter, in book order.
 *
 * Front and back matter are chapters too: a cover page, a dedication or a
 * colophon keeps its spine position rather than being filtered out, because the
 * reader streams the book as written.
 */
data class Chapter(
    val index: Int,
    val title: String,
    val titleSource: ChapterTitleSource,
    val startTokenIndex: Int,
    /** Exclusive. Equal to [startTokenIndex] for a chapter that produced no tokens. */
    val endTokenIndex: Int,
    /** Zip path of the spine item this chapter came from. */
    val spinePath: String,
) {
    val tokenCount: Int get() = endTokenIndex - startTokenIndex

    val isEmpty: Boolean get() = tokenCount == 0

    operator fun contains(tokenIndex: Int): Boolean =
        tokenIndex >= startTokenIndex && tokenIndex < endTokenIndex
}

/** A spine item the pipeline could not turn into words, kept so the PR-level state is honest. */
data class ContentGap(
    val spinePath: String,
    val chapterIndex: Int,
    val reason: GapReason,
    val detail: String,
)

enum class GapReason {
    /** Declared in the spine, absent from the archive — the interrupted-download case. */
    MISSING_FROM_ARCHIVE,

    /** Present but unreadable: oversized, or bytes that decode to nothing usable. */
    UNREADABLE,

    /** Read fine and simply had no text — a page that is only an image, for instance. */
    NO_TEXT,
}

/**
 * A stored reading position.
 *
 * Both other fields exist because an index alone is meaningless later: it is only
 * valid for the same book ([bookDigest], the content-derived identity from AD-2)
 * parsed by the same rules ([pipelineVersion], AD-3).
 */
data class TokenPosition(
    val bookDigest: String,
    val tokenIndex: Int,
    val pipelineVersion: Int = ContentPipelineVersion.CURRENT,
)

/** A parsed book: the whole token stream plus everything the reader needs about it. */
data class BookContent(
    /** Content-derived book identity (AD-2), the same digest the catalog stores. */
    val bookDigest: String,
    /** BCP-47 language from the package document, when it declares one. */
    val language: String?,
    val tokens: List<Token>,
    val chapters: List<Chapter>,
    /** Spine items that produced no words, in book order. Empty for an intact book. */
    val gaps: List<ContentGap> = emptyList(),
    val pipelineVersion: Int = ContentPipelineVersion.CURRENT,
) {
    /** Total positions, and therefore the denominator of progress. */
    val totalTokens: Int get() = tokens.size

    /**
     * Words only, excluding skip markers.
     *
     * This is the input to time-remaining math: at `wpm` words per minute an
     * unmodulated stream of `n` words takes `n / wpm` minutes, and LEAF202 adds
     * its pause multipliers on top.
     */
    val totalWords: Int = tokens.count { it is WordToken }

    val isEmpty: Boolean get() = tokens.isEmpty()

    /** Fraction read once [tokenIndex] has been shown, clamped to `0f..1f`. */
    fun progressFraction(tokenIndex: Int): Float {
        if (tokens.isEmpty()) return 0f
        val shown = (tokenIndex + 1).coerceIn(0, tokens.size)
        return shown.toFloat() / tokens.size
    }

    /** Words still to come after [tokenIndex], the numerator of time remaining. */
    fun wordsRemaining(tokenIndex: Int): Int {
        if (tokenIndex < 0) return totalWords
        var remaining = 0
        for (position in (tokenIndex + 1) until tokens.size) {
            if (tokens[position] is WordToken) remaining++
        }
        return remaining
    }

    fun chapterAt(tokenIndex: Int): Chapter? = chapters.firstOrNull { tokenIndex in it }

    /** The token that starts the sentence [tokenIndex] belongs to — "back one sentence". */
    fun sentenceStart(tokenIndex: Int): Int = boundedStartOf(tokenIndex) { it.sentenceIndex }

    /** The token that starts the paragraph [tokenIndex] belongs to — "back one paragraph". */
    fun paragraphStart(tokenIndex: Int): Int = boundedStartOf(tokenIndex) { it.paragraphIndex }

    private inline fun boundedStartOf(tokenIndex: Int, ordinal: (Token) -> Int): Int {
        if (tokens.isEmpty()) return 0
        val from = tokenIndex.coerceIn(0, tokens.lastIndex)
        val target = ordinal(tokens[from])
        var start = from
        while (start > 0 && ordinal(tokens[start - 1]) == target) start--
        return start
    }

    fun positionAt(tokenIndex: Int): TokenPosition =
        TokenPosition(bookDigest, tokenIndex.coerceIn(0, maxOf(0, tokens.lastIndex)), pipelineVersion)
}

/** Why a book could not be turned into a token stream at all. */
enum class ContentFailureReason {
    /** The bytes could not be read from their source. */
    UNREADABLE_SOURCE,

    /** Not a readable zip archive, or damaged partway through. */
    CORRUPT_ARCHIVE,

    /** A readable zip that is not a usable EPUB: no container, no package document, no spine. */
    INVALID_STRUCTURE,

    /** Structurally fine, but not one spine item yielded readable text. */
    NO_READABLE_CONTENT,
}

/** Parsing one book either produces content or a typed, non-throwing failure. */
sealed interface BookContentResult {

    data class Parsed(val content: BookContent) : BookContentResult

    data class Failed(
        val reason: ContentFailureReason,
        /** Plain language, safe to show the reader. */
        val detail: String,
    ) : BookContentResult
}

/**
 * Parse progress for the reader's book-open loading state (LEAF203).
 *
 * Reported per spine item, which is the only unit whose cost is knowable before
 * the file is read.
 */
data class ContentProgress(
    val completedItems: Int,
    val totalItems: Int,
) {
    val fraction: Float get() = if (totalItems <= 0) 0f else completedItems.toFloat() / totalItems
}
