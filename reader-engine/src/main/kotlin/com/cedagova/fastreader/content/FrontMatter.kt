package com.cedagova.fastreader.content

/**
 * Where a book's body starts, when the book says so plainly enough to act on
 * (REQ-202).
 *
 * Present on [BookContent] only for a book whose leading spine items really are
 * front matter *and* whose first real chapter can be named. Every uncertain case
 * is `null`, because the reader-facing consequence of a wrong answer here is
 * being dropped past the first paragraph of a book — see [FrontMatterDetector].
 */
data class FrontMatter(
    /** Index of the first chapter that is not front matter. Always greater than zero. */
    val firstBodyChapterIndex: Int,
    /** The token the skip lands on: the first word of that chapter. */
    val startTokenIndex: Int,
    /** That chapter's title, so the offer can name where it goes. */
    val bodyChapterTitle: String,
    /** How this was worked out; the offer does not change, but the diagnosis does. */
    val source: FrontMatterSource,
)

/** What told us where the body starts, strongest evidence first. */
enum class FrontMatterSource {
    /** An EPUB 3 `landmarks` navigation list with a `bodymatter` entry. The book's own answer. */
    LANDMARKS,

    /** An EPUB 2 package `<guide><reference type="text">`. The same statement, older spelling. */
    GUIDE,

    /** Neither existed, and the leading chapters are all titled as front matter. */
    TITLES,
}

/**
 * Decides whether a book opens on front matter, and where its body begins.
 *
 * ## Why this is deliberately small
 *
 * The plan bounds this to "navigation landmarks or the package guide when
 * present, else a small front-matter title heuristic; when unsure, no offer",
 * and explicitly rules out building a general document classifier. The asymmetry
 * behind that bound is the whole design: a missed offer costs a reader three
 * taps of the forward control, while a wrong offer drops them past the opening
 * of a book they have just started and gives them no obvious way to tell what
 * they skipped. So every rule below fails closed.
 *
 * ## The three answers, in order
 *
 * 1. **[FrontMatterSource.LANDMARKS]** — EPUB 3's `landmarks` nav lists a
 *    `bodymatter` entry pointing at the spine item where the body starts. That is
 *    the book stating it, so it is taken as stated.
 * 2. **[FrontMatterSource.GUIDE]** — EPUB 2's `<guide><reference type="text">`
 *    means the same thing in the package document, and a great many EPUB 2 files
 *    carry it.
 * 3. **[FrontMatterSource.TITLES]** — nothing declared, so the leading chapters
 *    are read. Every chapter before the body must carry a title from [TITLES],
 *    the short closed list below; the run must be at most
 *    [MAX_FRONT_MATTER_CHAPTERS] long; and none of those titles may be a
 *    positional fallback, because "Section 3" is not evidence of anything.
 *
 * In all three cases the body chapter has to exist and contain words. A declared
 * `bodymatter` pointing at chapter one of a book whose chapter one is an empty
 * spine item yields no offer rather than a jump into nothing.
 */
internal object FrontMatterDetector {

    /**
     * The longest run of leading chapters the title heuristic will call front
     * matter.
     *
     * Real front matter is a handful of short pages. A book whose first ten
     * chapters all look like front matter is more likely to be a book this list
     * happens to match than a book with ten covers, and the safe answer there is
     * no offer.
     */
    const val MAX_FRONT_MATTER_CHAPTERS: Int = 6

    /**
     * Titles that mean "not the book yet", normalised by [normalize].
     *
     * Closed and short on purpose. Notice what is *absent*: preface, foreword,
     * introduction, prologue and acknowledgements. Those are text the author
     * wrote and a reader may well want, so skipping them is an editorial
     * judgement this app has no business making; a book that starts with one
     * simply gets no offer.
     *
     * The Spanish entries are here because this app puts its whole interface in
     * Spanish (D5) — a Spanish EPUB is an ordinary case, not an exotic one — and
     * because each of these is as unambiguous in Spanish as its English
     * counterpart.
     */
    private val TITLES: Set<String> = setOf(
        // English
        "cover", "front cover", "title", "title page", "half title", "halftitle",
        "copyright", "copyright page", "copyright notice", "dedication", "epigraph",
        "contents", "table of contents", "toc", "frontispiece", "imprint",
        // Spanish
        "cubierta", "portada", "portadilla", "créditos", "creditos",
        "derechos de autor", "dedicatoria", "índice", "indice",
        "tabla de contenidos", "epígrafe", "epigrafe",
    )

    /**
     * [FrontMatter] for this book, or null when nothing here is sure enough to
     * offer.
     *
     * [declaredBodyPath] is the zip path an EPUB 3 `landmarks` list or an EPUB 2
     * guide pointed at, already resolved, or null when the book declared neither.
     */
    fun detect(
        chapters: List<Chapter>,
        declaredBodyPath: String? = null,
        declaredSource: FrontMatterSource? = null,
    ): FrontMatter? {
        if (chapters.isEmpty()) return null

        if (declaredBodyPath != null && declaredSource != null) {
            val index = chapters.indexOfFirst { it.spinePath == declaredBodyPath }
            return offerAt(chapters, index, declaredSource)
        }
        return offerAt(chapters, titleHeuristicBodyStart(chapters), FrontMatterSource.TITLES)
    }

    /**
     * The first chapter the title list does not recognise as front matter, or -1
     * when the run is empty, too long, made of positional fallback titles, or
     * ends on a title that only *resembles* front matter.
     *
     * That last case is the one worth spelling out. `Copyright © 2020 Ada
     * Fielding` is not in the list, so a naive walk would stop there and offer to
     * land the reader on the copyright page — a wrong offer, which is the
     * expensive kind. A title that begins with a front-matter term but is not one
     * therefore ends the walk with no offer at all rather than with an answer.
     */
    private fun titleHeuristicBodyStart(chapters: List<Chapter>): Int {
        var index = 0
        while (index < chapters.size && index <= MAX_FRONT_MATTER_CHAPTERS) {
            val chapter = chapters[index]
            // A title the pipeline invented ("Section 4") is not the book speaking,
            // so it can neither match nor be assumed to be body text.
            if (chapter.titleSource == ChapterTitleSource.FALLBACK) return -1
            val title = normalize(chapter.title)
            if (title in TITLES) {
                index++
                continue
            }
            return if (TITLES.any { title.startsWith("$it ") }) -1 else index
        }
        return -1
    }

    /** The offer for a body chapter at [index], or null when that index is not usable. */
    private fun offerAt(chapters: List<Chapter>, index: Int, source: FrontMatterSource): FrontMatter? {
        // Index 0 means the book opens on its body already: nothing to skip.
        if (index <= 0 || index > chapters.lastIndex) return null
        val body = chapters[index]
        if (body.isEmpty) return null
        return FrontMatter(
            firstBodyChapterIndex = index,
            startTokenIndex = body.startTokenIndex,
            bodyChapterTitle = body.title,
            source = source,
        )
    }

    /**
     * A title as the list above spells it: lower case, single spaces, and without
     * the decoration a typesetter adds — `TITLE PAGE.`, `Copyright ©`,
     * `— Contents —` all normalise onto their plain form.
     */
    private fun normalize(title: String): String = title
        .collapseSpaces()
        .lowercase()
        .trim { !it.isLetterOrDigit() }
        .collapseSpaces()
}
