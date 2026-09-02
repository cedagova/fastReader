package com.cedagova.fastreader.content

import com.cedagova.fastreader.epub.EpubByteSource
import com.cedagova.fastreader.epub.EpubFixtures
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

/**
 * Synthetic books for the content pipeline.
 *
 * Written to exercise the shapes real EPUBs come in — an EPUB 3 with a
 * navigation document, an EPUB 2 with an NCX, a Latin-1 chapter, a download that
 * stopped mid-book — without shipping copyrighted text. Shared by the JVM tests
 * and the on-device test so both prove the same books.
 */
object ContentFixtures {

    fun source(bytes: ByteArray): EpubByteSource = EpubByteSource { ByteArrayInputStream(bytes) }

    private const val CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

    /**
     * EPUB 3 English book: front matter, two chapters, back matter, a navigation
     * document, an inline image, a table, and a footnote reference.
     */
    fun englishNovel(): ByteArray = EpubFixtures.buildArchive(
        listOf(
            "META-INF/container.xml" to CONTAINER.utf8(),
            "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="pub-id">urn:uuid:english-novel</dc:identifier>
    <dc:title>The Quiet Machine</dc:title>
    <dc:creator>Ada Fielding</dc:creator>
    <dc:language>en</dc:language>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="front" href="front.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
    <item id="back" href="back.xhtml" media-type="application/xhtml+xml"/>
    <item id="plate" href="images/plate.png" media-type="image/png"/>
  </manifest>
  <spine>
    <itemref idref="front"/>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
    <itemref idref="back"/>
  </spine>
</package>""".utf8(),
            "OEBPS/nav.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<body>
  <nav epub:type="toc">
    <ol>
      <li><a href="front.xhtml">Title&#160;Page</a></li>
      <li><a href="chapter1.xhtml">Chapter One: The Arrival</a></li>
      <li><a href="chapter2.xhtml#start">Chapter Two: The Departure</a></li>
      <li><a href="back.xhtml">Colophon</a></li>
    </ol>
  </nav>
</body>
</html>""".utf8(),
            "OEBPS/front.xhtml" to page("""<h1>Title Page</h1><p>The Quiet Machine</p>"""),
            "OEBPS/chapter1.xhtml" to page(
                """<h1>Chapter One</h1>
<p>The machine waited, patient and extraordinarily quiet.<sup><a href="notes.xhtml#n1"
   epub:type="noteref">1</a></sup> Ada watched it.</p>
<figure><img src="images/plate.png" alt="A plate"/><figcaption>Plate I.</figcaption></figure>
<table><tr><td>Speed</td><td>250</td></tr></table>
<p>Dr. Fielding arrived at 9 a.m. She said nothing.</p>""",
            ),
            "OEBPS/chapter2.xhtml" to page("""<h1>Chapter Two</h1><p id="start">They left before dawn.</p>"""),
            "OEBPS/back.xhtml" to page("""<p>Set in Garamond by NASA.</p>"""),
            "OEBPS/images/plate.png" to EpubFixtures.TINY_PNG,
        ),
    )

    /**
     * EPUB 2 Spanish book with an NCX: inverted punctuation, accents, and dialogue
     * dashes in the shape Spanish novels actually use.
     */
    fun spanishNovel(): ByteArray = EpubFixtures.buildArchive(
        listOf(
            "META-INF/container.xml" to CONTAINER.utf8(),
            "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:spanish-novel</dc:identifier>
    <dc:title>¿Quién teme a la máquina?</dc:title>
    <dc:creator>José Ramírez Ñuño</dc:creator>
    <dc:language>es</dc:language>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="cubierta" href="cubierta.xhtml" media-type="application/xhtml+xml"/>
    <item id="cap1" href="capitulo1.xhtml" media-type="application/xhtml+xml"/>
    <item id="cap2" href="capitulo2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="cubierta"/>
    <itemref idref="cap1"/>
    <itemref idref="cap2"/>
  </spine>
</package>""".utf8(),
            "OEBPS/toc.ncx" to """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <navMap>
    <navPoint id="n1" playOrder="1">
      <navLabel><text>Cubierta</text></navLabel>
      <content src="cubierta.xhtml"/>
    </navPoint>
    <navPoint id="n2" playOrder="2">
      <navLabel><text>Capítulo I</text></navLabel>
      <content src="capitulo1.xhtml"/>
    </navPoint>
    <navPoint id="n3" playOrder="3">
      <navLabel><text>Capítulo II</text></navLabel>
      <content src="capitulo2.xhtml"/>
    </navPoint>
  </navMap>
</ncx>""".utf8(),
            "OEBPS/cubierta.xhtml" to page("""<p>¿Quién teme a la máquina?</p>"""),
            "OEBPS/capitulo1.xhtml" to page(
                """<h2>Capítulo I</h2>
<p>—¿Cómo estás? —preguntó él.</p>
<p>—Muy bien, gracias —respondió ella—. La máquina es extraordinariamente silenciosa.</p>
<p>¡Qué sorpresa! El Sr. Ramírez llegó a las 9.</p>""",
            ),
            "OEBPS/capitulo2.xhtml" to page("""<h2>Capítulo II</h2><p>La niña cerró el libro.</p>"""),
        ),
    )

    /**
     * A chapter stored as ISO-8859-1 and declaring it. Reading those bytes as
     * UTF-8 would turn "canción" into mojibake.
     */
    fun latin1Book(): ByteArray = EpubFixtures.buildArchive(
        listOf(
            "META-INF/container.xml" to CONTAINER.utf8(),
            "OEBPS/content.opf" to minimalOpf("urn:uuid:latin1", listOf("chapter1.xhtml")).utf8(),
            "OEBPS/chapter1.xhtml" to (
                """<?xml version="1.0" encoding="ISO-8859-1"?>
<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Una canción antigua.</p></body></html>"""
                ).toByteArray(Charset.forName("ISO-8859-1")),
        ),
    )

    /**
     * A download interrupted after the first chapter: the spine declares three
     * content documents and the archive holds one.
     */
    fun interruptedMidBook(): ByteArray = EpubFixtures.buildArchive(
        listOf(
            "META-INF/container.xml" to CONTAINER.utf8(),
            "OEBPS/content.opf" to minimalOpf(
                "urn:uuid:interrupted",
                listOf("chapter1.xhtml", "chapter2.xhtml", "chapter3.xhtml"),
            ).utf8(),
            "OEBPS/chapter1.xhtml" to page("""<h1>Chapter One</h1><p>The first chapter survived.</p>"""),
        ),
    )

    /** No navigation document and no NCX: titles have to come from headings or position. */
    fun untitledSections(): ByteArray = EpubFixtures.buildArchive(
        listOf(
            "META-INF/container.xml" to CONTAINER.utf8(),
            "OEBPS/content.opf" to minimalOpf(
                "urn:uuid:untitled",
                listOf("chapter1.xhtml", "chapter2.xhtml", "chapter3.xhtml"),
            ).utf8(),
            "OEBPS/chapter1.xhtml" to page("""<h1>The Arrival</h1><p>It began quietly.</p>"""),
            "OEBPS/chapter2.xhtml" to page("""<p>No heading here at all.</p>"""),
            "OEBPS/chapter3.xhtml" to page("""<p>   </p>"""),
        ),
    )

    /** Structurally valid, but every content document is blank. */
    fun textlessBook(): ByteArray = EpubFixtures.buildArchive(
        listOf(
            "META-INF/container.xml" to CONTAINER.utf8(),
            "OEBPS/content.opf" to minimalOpf("urn:uuid:textless", listOf("chapter1.xhtml")).utf8(),
            "OEBPS/chapter1.xhtml" to page("""<p> </p><div></div>"""),
        ),
    )

    /** Sloppy EPUB 2 markup: unclosed tags, an undeclared entity, bare `<br>`. */
    fun malformedMarkupBook(): ByteArray = EpubFixtures.buildArchive(
        listOf(
            "META-INF/container.xml" to CONTAINER.utf8(),
            "OEBPS/content.opf" to minimalOpf("urn:uuid:sloppy", listOf("chapter1.xhtml")).utf8(),
            "OEBPS/chapter1.xhtml" to (
                """<html><body>
<p>Marks &amp; Co.&nbsp;stood at 84&nbsp;Charing Cross Road.
<p>The shop&rsquo;s door<br>was always open.
</body></html>"""
                ).utf8(),
        ),
    )

    private fun minimalOpf(identifier: String, hrefs: List<String>): String {
        val items = hrefs.mapIndexed { index, href ->
            """<item id="c$index" href="$href" media-type="application/xhtml+xml"/>"""
        }.joinToString("\n    ")
        val spine = hrefs.indices.joinToString("") { """<itemref idref="c$it"/>""" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="pub-id">$identifier</dc:identifier>
    <dc:title>Fixture</dc:title>
    <dc:language>en</dc:language>
  </metadata>
  <manifest>
    $items
  </manifest>
  <spine>$spine</spine>
</package>"""
    }

    private fun page(body: String): ByteArray = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Ignored</title></head>
<body>
$body
</body>
</html>""".utf8()

    private fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)
}
