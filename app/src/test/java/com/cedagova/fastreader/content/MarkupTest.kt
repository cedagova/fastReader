package com.cedagova.fastreader.content

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lenient markup layer: entities, encodings, and the skip/drop policy. */
class MarkupTest {

    // --- entities ---

    @Test
    fun `named numeric and hex entities resolve`() {
        assertEquals("Marks & Co.", MarkupScanner.decodeEntities("Marks &amp; Co."))
        assertEquals("canción", MarkupScanner.decodeEntities("canci&#243;n"))
        assertEquals("canción", MarkupScanner.decodeEntities("canci&#xF3;n"))
        assertEquals("¿Qué?", MarkupScanner.decodeEntities("&iquest;Qu&eacute;?"))
    }

    @Test
    fun `an unknown or bare ampersand survives untouched`() {
        // Dropping these would silently corrupt book text.
        assertEquals("A &weird; thing", MarkupScanner.decodeEntities("A &weird; thing"))
        assertEquals("Tom & Jerry", MarkupScanner.decodeEntities("Tom & Jerry"))
        assertEquals("100 & 200", MarkupScanner.decodeEntities("100 & 200"))
    }

    @Test
    fun `a non-breaking space reads as an ordinary space`() {
        assertEquals("84 Charing", "84&#160;Charing".let(MarkupScanner::decodeEntities).collapseSpaces())
    }

    // --- scanning ---

    @Test
    fun `comments doctypes and script bodies are not book text`() {
        val markup = """<!DOCTYPE html [<!ENTITY x "y">]><html><head><script>if (a > b) {}</script></head>
            |<body><!-- hidden --><p>Visible.</p></body></html>
        """.trimMargin()

        val text = MarkupScanner.scan(markup).filterIsInstance<MarkupEvent.Text>()
            .joinToString("") { it.value }

        assertTrue(text.contains("Visible."))
        assertFalse(text.contains("hidden"))
        assertFalse(text.contains("if (a"))
    }

    @Test
    fun `a less-than that cannot start a tag stays as text`() {
        val text = MarkupScanner.scan("<p>a < b and c > d</p>").filterIsInstance<MarkupEvent.Text>()
            .joinToString("") { it.value }

        assertEquals("a < b and c > d", text)
    }

    @Test
    fun `an attribute value may contain the tag terminator`() {
        val open = MarkupScanner.scan("""<a href="x?y=1>2" class="note">""")
            .filterIsInstance<MarkupEvent.Open>()
            .single()

        assertEquals("x?y=1>2", open.attribute("href"))
        assertTrue(open.hasToken("class", "note"))
    }

    @Test
    fun `cdata content is literal`() {
        val text = MarkupScanner.scan("<p><![CDATA[a &amp; b]]></p>").filterIsInstance<MarkupEvent.Text>()
            .joinToString("") { it.value }

        assertEquals("a &amp; b", text)
    }

    // --- encodings ---

    @Test
    fun `utf-8 is read correctly with and without a bom`() {
        val plain = "Una canción".toByteArray(Charsets.UTF_8)
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + plain

        assertEquals("Una canción", ContentCharsets.decode(plain))
        assertEquals("Una canción", ContentCharsets.decode(withBom))
    }

    @Test
    fun `a declared latin-1 document decodes without mojibake`() {
        val latin1 = Charset.forName("ISO-8859-1")
        val bytes = """<?xml version="1.0" encoding="ISO-8859-1"?><p>Una canción</p>""".toByteArray(latin1)

        assertTrue(ContentCharsets.decode(bytes).contains("canción"))
    }

    @Test
    fun `undeclared latin-1 bytes fall back rather than producing replacement characters`() {
        // 0xF3 is "ó" in Latin-1 and an invalid lone byte in UTF-8.
        val bytes = "<p>Una canci".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0xF3.toByte()) +
            "n</p>".toByteArray(Charsets.US_ASCII)

        val decoded = ContentCharsets.decode(bytes)

        assertTrue(decoded.contains("canción"))
        assertFalse(decoded.contains('�'))
    }

    @Test
    fun `utf-16 is detected from its bom`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Máquina".toByteArray(Charsets.UTF_16LE)

        assertEquals("Máquina", ContentCharsets.decode(bytes))
    }

    // --- block extraction policy ---

    @Test
    fun `a bare superscript is a note marker but one with letters is part of the sentence`() {
        assertEquals(
            listOf("The word. Next."),
            paragraphs("<p>The word.<sup>12</sup> Next.</p>"),
        )
        assertEquals(
            listOf("The 1er word."),
            paragraphs("<p>The 1<sup>er</sup> word.</p>"),
        )
    }

    @Test
    fun `note references and note bodies are dropped`() {
        assertEquals(
            listOf("A sentence with a note."),
            paragraphs(
                """<p>A sentence with a note.<a epub:type="noteref" href="#n1">4</a></p>
                   <aside epub:type="footnote" id="n1"><p>The note body.</p></aside>""",
            ),
        )
        assertEquals(
            listOf("Plain text."),
            paragraphs("""<p>Plain text.<a class="footnote-ref" href="#f1">*</a></p>"""),
        )
    }

    @Test
    fun `a table becomes one marker and its cells are not read`() {
        val blocks = XhtmlExtractor.extract("<p>Before.</p><table><tr><td>A</td><td>B</td></tr></table><p>After.</p>")

        assertEquals(
            listOf("Before.", XhtmlExtractor.TABLE_LABEL, "After."),
            blocks.map { if (it is ContentBlock.Paragraph) it.text else (it as ContentBlock.Skip).label },
        )
    }

    @Test
    fun `an image with an svg fallback yields a single marker`() {
        val blocks = XhtmlExtractor.extract(
            """<figure><img src="a.png"/><svg><image href="a.svg"/></svg></figure>""",
        )

        assertEquals(1, blocks.count { it is ContentBlock.Skip })
        assertEquals(SkipKind.IMAGE, (blocks.single() as ContentBlock.Skip).kind)
    }

    @Test
    fun `the navigation document is not streamed as content`() {
        assertEquals(
            listOf("Real text."),
            paragraphs("""<nav epub:type="toc"><ol><li><a href="c1.xhtml">Chapter One</a></li></ol></nav>
                          <p>Real text.</p>"""),
        )
    }

    @Test
    fun `unclosed paragraph tags still separate paragraphs`() {
        assertEquals(
            listOf("First.", "Second.", "Third."),
            paragraphs("<p>First.<p>Second.<br>Third."),
        )
    }

    @Test
    fun `headings are flagged rather than removed`() {
        val blocks = XhtmlExtractor.extract("<h2>Capítulo I</h2><p>Texto.</p>")

        assertEquals(
            listOf(ContentBlock.Paragraph("Capítulo I", isHeading = true), ContentBlock.Paragraph("Texto.")),
            blocks,
        )
    }

    private fun paragraphs(markup: String): List<String> =
        XhtmlExtractor.extract(markup).filterIsInstance<ContentBlock.Paragraph>().map { it.text }
}
