package com.cedagova.fastreader.content

/**
 * One block of a content document, in reading order.
 *
 * A block is the unit a paragraph pause applies to. Headings are blocks too,
 * flagged rather than separated, because a heading is read in place — it just
 * gets a longer pause and may be styled differently.
 */
internal sealed interface ContentBlock {

    data class Paragraph(val text: String, val isHeading: Boolean = false) : ContentBlock

    data class Skip(val kind: SkipKind, val label: String) : ContentBlock
}

/**
 * Flattens one XHTML content document into blocks.
 *
 * The policy questions this file answers, all from REQ-015:
 *
 * - **Images and tables become markers, not text.** The reader is told the
 *   content existed; streaming a table cell by cell would be noise.
 * - **Footnote markers are dropped entirely.** A superscript "1" in the middle
 *   of a sentence is an interruption with no spoken form, and RSVP has no way to
 *   present a note anyway.
 * - **Everything else stays in document order**, including front and back matter,
 *   because the book is streamed as written.
 */
internal object XhtmlExtractor {

    const val IMAGE_LABEL = "[image skipped]"
    const val TABLE_LABEL = "[table skipped]"
    const val MISSING_LABEL = "[content unavailable]"

    /** Elements whose end finishes a paragraph. */
    private val BLOCK_ELEMENTS = setOf(
        "p", "div", "section", "article", "aside", "header", "footer", "main",
        "blockquote", "li", "dd", "dt", "dl", "ol", "ul", "figure", "figcaption",
        "pre", "address", "hr", "br", "tr", "td", "th", "caption", "body",
        "h1", "h2", "h3", "h4", "h5", "h6",
    )

    private val HEADING_ELEMENTS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    /** Never book text: document head, embedded code, and the navigation document's own lists. */
    private val DISCARDED_ELEMENTS = setOf("head", "script", "style", "title", "template")

    /**
     * `epub:type` values that mark apparatus rather than prose. Notes are the
     * point of REQ-015's "footnote markers dropped"; page breaks are print
     * artifacts that would otherwise stream as stray numbers.
     */
    private val DISCARDED_EPUB_TYPES = setOf(
        "noteref", "footnote", "footnotes", "endnote", "endnotes",
        "rearnote", "rearnotes", "note", "pagebreak", "page-break",
    )

    private val FOOTNOTE_CLASS_HINTS = listOf("footnote", "noteref", "endnote", "fn-ref", "fnref")

    /** A `<nav>` of these kinds is machine navigation, not content to read. */
    private val DISCARDED_NAV_TYPES = setOf("toc", "landmarks", "page-list", "lot", "loi")

    fun extract(markup: String): List<ContentBlock> {
        val blocks = ArrayList<ContentBlock>()
        val paragraph = StringBuilder()
        var headingDepth = 0
        var pendingHeading = false
        // Elements whose entire subtree is being thrown away, innermost last.
        val dropped = ArrayDeque<String>()
        // Where each open <sup> started in the paragraph, so a bare "1" can be
        // removed once its content is known.
        val superscriptMarks = ArrayDeque<Int>()
        // Open elements, so a mismatched close tag can unwind to the right place.
        val open = ArrayDeque<String>()

        fun flush() {
            val text = paragraph.toString().collapseSpaces()
            paragraph.setLength(0)
            if (text.isEmpty()) {
                pendingHeading = false
                return
            }
            blocks += ContentBlock.Paragraph(text, isHeading = pendingHeading)
            pendingHeading = false
        }

        fun addSkip(kind: SkipKind, label: String) {
            flush()
            // A figure with an SVG fallback beside its bitmap must not say it twice.
            val last = blocks.lastOrNull()
            if (last is ContentBlock.Skip && last.kind == kind) return
            blocks += ContentBlock.Skip(kind, label)
        }

        for (event in MarkupScanner.scan(markup)) {
            when (event) {
                is MarkupEvent.Text -> if (dropped.isEmpty()) paragraph.append(event.value)

                is MarkupEvent.Open -> {
                    val name = event.name
                    if (!event.selfClosing) open.addLast(name)

                    if (dropped.isNotEmpty()) {
                        if (!event.selfClosing) dropped.addLast(name)
                        continue
                    }

                    when {
                        name in DISCARDED_ELEMENTS || event.isDiscardedApparatus() -> {
                            // Only a block-level discard ends the paragraph. A
                            // footnote reference is inline: dropping it must not
                            // cut "…quiet.[1] Ada watched it." into two paragraphs.
                            if (name in DISCARDED_ELEMENTS || name in BLOCK_ELEMENTS) flush()
                            if (!event.selfClosing) dropped.addLast(name)
                        }

                        name == "table" -> {
                            addSkip(SkipKind.TABLE, TABLE_LABEL)
                            if (!event.selfClosing) dropped.addLast(name)
                        }

                        name == "img" || name == "image" || name == "svg" -> {
                            addSkip(SkipKind.IMAGE, IMAGE_LABEL)
                            if (!event.selfClosing) dropped.addLast(name)
                        }

                        name == "sup" -> if (!event.selfClosing) superscriptMarks.addLast(paragraph.length)

                        name in HEADING_ELEMENTS -> {
                            flush()
                            headingDepth++
                            pendingHeading = true
                        }

                        name in BLOCK_ELEMENTS -> flush()
                    }
                }

                is MarkupEvent.Close -> {
                    val name = event.name
                    if (dropped.isNotEmpty()) {
                        if (dropped.contains(name)) {
                            while (dropped.isNotEmpty()) {
                                val popped = dropped.removeLast()
                                if (popped == name) break
                            }
                        }
                        unwind(open, name)
                        continue
                    }
                    if (name == "sup" && superscriptMarks.isNotEmpty()) {
                        // A superscript holding only digits or symbols is a note
                        // marker with no spoken form; one with letters ("1er",
                        // "th") is part of the sentence and stays.
                        val mark = superscriptMarks.removeLast().coerceAtMost(paragraph.length)
                        if (paragraph.substring(mark).none(Char::isLetter)) {
                            paragraph.setLength(mark)
                        }
                        unwind(open, name)
                        continue
                    }
                    if (name in HEADING_ELEMENTS) {
                        flush()
                        if (headingDepth > 0) headingDepth--
                    } else if (name in BLOCK_ELEMENTS) {
                        flush()
                    }
                    unwind(open, name)
                    // A heading may hold nested markup; `pendingHeading` is re-armed
                    // for whatever text is still inside it.
                    if (headingDepth > 0) pendingHeading = true
                }
            }
        }
        flush()
        return blocks
    }

    /** Pops [open] back to [name], tolerating the unclosed tags real books contain. */
    private fun unwind(open: ArrayDeque<String>, name: String) {
        if (!open.contains(name)) return
        while (open.isNotEmpty()) {
            if (open.removeLast() == name) return
        }
    }

    private fun MarkupEvent.Open.isDiscardedApparatus(): Boolean {
        val epubType = attribute("epub:type")
        if (epubType != null && epubType.split(' ', '\t').any { it.lowercase() in DISCARDED_EPUB_TYPES }) {
            return true
        }
        if (name == "nav") {
            val navType = attribute("epub:type").orEmpty().lowercase()
            if (navType.isEmpty() || navType.split(' ').any { it in DISCARDED_NAV_TYPES }) return true
        }
        if (name == "a") {
            val classes = attribute("class").orEmpty().lowercase()
            if (FOOTNOTE_CLASS_HINTS.any { classes.contains(it) }) return true
            val role = attribute("role").orEmpty().lowercase()
            if (role == "doc-noteref") return true
        }
        if (name == "sup") {
            val classes = attribute("class").orEmpty().lowercase()
            if (FOOTNOTE_CLASS_HINTS.any { classes.contains(it) }) return true
        }
        return false
    }

}
