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
    fun `the adverb no ends its sentence and advances the sentence ordinal`() {
        // "No." is one of the most common sentence endings in both languages;
        // treating it as the "number" abbreviation would delete the ~3.0x
        // sentence hold (REQ-011) and break "back one sentence" (REQ-014).
        val spanish = tokenize("¿Vienes conmigo? No. Ella se marchó.")

        assertEquals(Boundary.SENTENCE, spanish.first { it.text == "No" }.boundary)
        assertEquals(
            spanish.first { it.text == "No" }.sentenceIndex + 1,
            spanish.first { it.text == "Ella" }.sentenceIndex,
        )

        val english = tokenize("I said no. Then I left.")
        assertEquals(Boundary.SENTENCE, english.first { it.text == "no" }.boundary)
        assertEquals(
            english.first { it.text == "no" }.sentenceIndex + 1,
            english.first { it.text == "Then" }.sentenceIndex,
        )
    }

    @Test
    fun `the numbering sense of no is kept only when a digit follows`() {
        val tokens = tokenize("Ver No. 5 en la página.")

        assertEquals("No.", tokens[1].text)
        assertEquals(Boundary.NONE, tokens[1].boundary)
        assertEquals("5", tokens[2].text)
    }

    @Test
    fun `a decimal stays one number token in both notations`() {
        val english = tokenize("En 1984 había 3.5 millones.")
        val spanish = tokenize("En 1984 había 3,5 millones.")

        assertEquals(listOf("En", "1984", "había", "3.5", "millones"), english.map { it.text })
        assertEquals(listOf("En", "1984", "había", "3,5", "millones"), spanish.map { it.text })
        // No fabricated break inside the number, and one sentence throughout.
        assertEquals(Boundary.NONE, english.first { it.text == "3.5" }.boundary)
        assertEquals(Boundary.NONE, spanish.first { it.text == "3,5" }.boundary)
        assertEquals(1, english.map { it.sentenceIndex }.distinct().size)
        assertEquals(1, spanish.map { it.sentenceIndex }.distinct().size)
        assertTrue(
            WordClass.NUMBER in
                WordClassifier.classify(english.first { it.text == "3.5" }.text, occurrencesInBook = 1),
        )
    }

    @Test
    fun `a period after a number still ends the sentence`() {
        val tokens = tokenize("Leí el capítulo 3. Luego dormí.")

        assertEquals(Boundary.SENTENCE, tokens.first { it.text == "3" }.boundary)
        assertEquals(
            tokens.first { it.text == "3" }.sentenceIndex + 1,
            tokens.first { it.text == "Luego" }.sentenceIndex,
        )
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
        // The adverb, not the "number" abbreviation.
        assertFalse(WordClassifier.isAbbreviation("no."))
        assertTrue(WordClassifier.isNumberingAbbreviation("No"))
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
