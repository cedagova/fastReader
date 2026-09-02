package com.cedagova.fastreader.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Word splitting, boundary attribution, and the classification heuristics. */
class TokenizerTest {

    // --- word splitting ---

    @Test
    fun `internal apostrophes and hyphens keep a word whole`() {
        assertEquals(listOf("don't", "así-así", "l'aube"), words("don't así-así l'aube."))
    }

    @Test
    fun `a standalone dash is punctuation and never a word`() {
        val tokens = tokenize("Uno - dos — tres.")

        assertEquals(listOf("Uno", "dos", "tres"), tokens.map { it.text })
        assertTrue(tokens.none { it.text.all { char -> !char.isLetterOrDigit() } })
        assertEquals(Boundary.CLAUSE, tokens[0].boundary)
        assertEquals(Boundary.CLAUSE, tokens[1].boundary)
    }

    @Test
    fun `a trailing apostrophe goes back to the punctuation run`() {
        assertEquals(listOf("readin", "and", "writin"), words("readin' and writin'."))
    }

    @Test
    fun `a dotted initialism stays one token`() {
        assertEquals(listOf("She", "left", "the", "U.S.A.", "quietly"), words("She left the U.S.A. quietly."))
        assertEquals(listOf("J.", "R.", "R.", "Tolkien"), words("J. R. R. Tolkien."))
    }

    // --- boundaries ---

    @Test
    fun `sentence clause paragraph and heading boundaries are attributed to the word before them`() {
        val blocks = listOf(
            ContentBlock.Paragraph("Capítulo I", isHeading = true),
            ContentBlock.Paragraph("Bien, gracias. ¡Qué sorpresa! ¿Cómo estás?"),
        )
        val tokens = Tokenizer.tokenize(blocks, chapterIndex = 0, state = Tokenizer.StreamState())
            .filterIsInstance<WordToken>()
            .associateBy { it.text }

        assertEquals(Boundary.HEADING, tokens.getValue("I").boundary)
        assertEquals(Boundary.CLAUSE, tokens.getValue("Bien").boundary)
        assertEquals(Boundary.SENTENCE, tokens.getValue("gracias").boundary)
        assertEquals(Boundary.SENTENCE, tokens.getValue("sorpresa").boundary)
        assertEquals(Boundary.PARAGRAPH, tokens.getValue("estás").boundary)
    }

    @Test
    fun `the strongest break in a punctuation run wins`() {
        // "estás? —preguntó": a sentence end and a dialogue dash in one run.
        val tokens = tokenize("—¿Cómo estás? —preguntó él.")

        assertEquals(Boundary.SENTENCE, tokens.first { it.text == "estás" }.boundary)
        assertEquals(Boundary.PARAGRAPH, tokens.last().boundary)
    }

    @Test
    fun `an abbreviation period does not end the sentence`() {
        val tokens = tokenize("El Sr. Ramírez llegó.")

        assertEquals(Boundary.NONE, tokens.first { it.text == "Sr." }.boundary)
        assertEquals(Boundary.PARAGRAPH, tokens.last().boundary)
    }

    @Test
    fun `sentence and paragraph ordinals advance across blocks`() {
        val blocks = listOf(
            ContentBlock.Paragraph("Uno. Dos."),
            ContentBlock.Skip(SkipKind.IMAGE, XhtmlExtractor.IMAGE_LABEL),
            ContentBlock.Paragraph("Tres."),
        )
        val tokens = Tokenizer.tokenize(blocks, chapterIndex = 3, state = Tokenizer.StreamState())

        assertEquals(listOf(0, 1, 2, 3), tokens.map { it.index })
        assertEquals(listOf(0, 0, 1, 2), tokens.map { it.paragraphIndex })
        assertEquals(listOf(0, 1, 2, 3), tokens.map { it.sentenceIndex })
        assertTrue(tokens.all { it.chapterIndex == 3 })
    }

    // --- classification ---

    @Test
    fun `long is measured against the pinned constant`() {
        assertFalse(WordClass.LONG in WordClassifier.classify("a".repeat(11), occurrencesInBook = 5))
        assertTrue(WordClass.LONG in WordClassifier.classify("a".repeat(12), occurrencesInBook = 5))
    }

    @Test
    fun `numbers and all-caps words are detected`() {
        assertTrue(WordClass.NUMBER in WordClassifier.classify("3.5", occurrencesInBook = 5))
        assertTrue(WordClass.NUMBER in WordClassifier.classify("1984", occurrencesInBook = 5))
        assertTrue(WordClass.ALL_CAPS in WordClassifier.classify("NASA", occurrencesInBook = 5))
        assertFalse(WordClass.ALL_CAPS in WordClassifier.classify("NASAs", occurrencesInBook = 5))
        // A single capital is a normal capitalised word, not shouting.
        assertFalse(WordClass.ALL_CAPS in WordClassifier.classify("A", occurrencesInBook = 5))
    }

    @Test
    fun `rare means once in this book and long enough to be worth slowing for`() {
        assertTrue(WordClass.RARE in WordClassifier.classify("murmuraba", occurrencesInBook = 1))
        assertFalse(WordClass.RARE in WordClassifier.classify("murmuraba", occurrencesInBook = 2))
        assertFalse(WordClass.RARE in WordClassifier.classify("casa", occurrencesInBook = 1))
    }

    @Test
    fun `abbreviations are recognised by list and by shape`() {
        assertTrue(WordClassifier.isAbbreviation("Dr."))
        assertTrue(WordClassifier.isAbbreviation("sra."))
        assertTrue(WordClassifier.isAbbreviation("e.g."))
        assertTrue(WordClassifier.isAbbreviation("U.S."))
        assertFalse(WordClassifier.isAbbreviation("casa."))
        assertFalse(WordClassifier.isAbbreviation("Dr"))
        assertFalse(WordClassifier.isAbbreviation("."))
    }

    @Test
    fun `rarity counts case-folded forms without punctuation`() {
        assertEquals("máquina", WordClassifier.normalize("Máquina,"))
        assertEquals("dont", WordClassifier.normalize("don't"))
    }

    private fun tokenize(paragraph: String): List<WordToken> =
        Tokenizer.tokenize(
            listOf(ContentBlock.Paragraph(paragraph)),
            chapterIndex = 0,
            state = Tokenizer.StreamState(),
        ).filterIsInstance<WordToken>()

    private fun words(paragraph: String): List<String> = tokenize(paragraph).map { it.text }
}
