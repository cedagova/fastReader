package com.cedagova.fastreader.content

import com.cedagova.fastreader.epub.EpubPaths

/**
 * Chapter titles, read from whichever table of contents the book carries.
 *
 * EPUB 3 books ship an XHTML navigation document; EPUB 2 books ship an NCX; a
 * fair number ship both, and some ship neither. Both formats are read with the
 * same lenient scanner used for content, so a navigation document with `&nbsp;`
 * in a title — common, and fatal to a strict XML parse with no DTD in reach —
 * still yields its titles.
 *
 * Only the first entry per spine item is kept: a table of contents may point at
 * several anchors inside one file, and a chapter has one title.
 */
internal object TocReader {

    /** Title by spine-item zip path, in the order the table of contents lists them. */
    fun readNavigationDocument(navPath: String, markup: String): Map<String, String> {
        val titles = LinkedHashMap<String, String>()
        var href: String? = null
        val label = StringBuilder()
        var depth = 0

        for (event in MarkupScanner.scan(markup)) {
            when (event) {
                is MarkupEvent.Open -> if (event.name == "a") {
                    href = event.attribute("href")
                    label.setLength(0)
                    depth = 1
                } else if (depth > 0 && !event.selfClosing) {
                    depth++
                }

                is MarkupEvent.Text -> if (depth > 0) label.append(event.value)

                is MarkupEvent.Close -> if (event.name == "a" && depth > 0) {
                    record(titles, navPath, href, label.toString())
                    depth = 0
                    href = null
                } else if (depth > 0) {
                    depth--
                }
            }
        }
        return titles
    }

    /** The same map, from an EPUB 2 NCX `navMap`. */
    fun readNcx(ncxPath: String, markup: String): Map<String, String> {
        val titles = LinkedHashMap<String, String>()
        val label = StringBuilder()
        var inLabel = false
        var pendingLabel: String? = null

        for (event in MarkupScanner.scan(markup)) {
            when (event) {
                is MarkupEvent.Open -> when (event.name) {
                    "navlabel" -> {
                        inLabel = true
                        label.setLength(0)
                    }
                    // `<content src="chapter1.xhtml#start"/>` closes the entry the
                    // preceding label opened.
                    "content" -> record(titles, ncxPath, event.attribute("src"), pendingLabel.orEmpty())

                    else -> Unit
                }

                is MarkupEvent.Text -> if (inLabel) label.append(event.value)

                is MarkupEvent.Close -> if (event.name == "navlabel") {
                    inLabel = false
                    pendingLabel = label.toString()
                }
            }
        }
        return titles
    }

    private fun record(
        titles: MutableMap<String, String>,
        documentPath: String,
        href: String?,
        rawTitle: String,
    ) {
        val title = rawTitle.collapseSpaces()
        if (title.isEmpty()) return
        val target = href?.takeIf { it.isNotBlank() } ?: return
        // Hrefs are relative to the navigation document, not to the package document.
        val path = EpubPaths.resolve(documentPath, target) ?: return
        titles.putIfAbsent(path, title)
    }

}
