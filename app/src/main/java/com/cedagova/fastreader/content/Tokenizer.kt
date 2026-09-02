package com.cedagova.fastreader.content

/**
 * Turns extracted blocks into the word/marker stream.
 *
 * Word splitting is Unicode-aware through [Char.isLetterOrDigit] rather than an
 * `a-z` range, which is the whole of what REQ-019 needs for accents and `ñ`:
 * "máquina" is one word, and "¿Cómo" is the word "Cómo" preceded by punctuation
 * the stream never shows.
 *
 * Punctuation is attributed to the word *before* it, by scanning the run of
 * non-word characters that follows each word and keeping the strongest break in
 * it. That one rule is what makes Spanish dialogue work with no special case: in
 * `—¿Cómo estás? —preguntó él.` the run after "estás" holds both the `?` and the
 * dialogue dash, so that word ends a sentence, and "él." ends the next one.
 */
internal object Tokenizer {

    /** Characters that end a sentence. Spanish `¿ ¡` open one, so they are not here. */
    private const val SENTENCE_PUNCTUATION = ".!?…"

    /**
     * Characters that break a clause.
     *
     * The dashes matter for Spanish: an em or en dash opens a line of dialogue and
     * encloses the attribution inside it, so research's "comma/semicolon/colon/
     * dash → 2.0×" gives REQ-019's dialogue-dash behavior directly.
     */
    private const val CLAUSE_PUNCTUATION = ",;:—–―‒-"

    /** Word-internal marks: apostrophes in "don't" / "l'aube", hyphens in "así-así". */
    private const val WORD_INTERNAL = "'’‘‑-"

    /**
     * Splits [blocks] into tokens, continuing the counters in [state] so every
     * spine item of a book contributes to one stream.
     */
    fun tokenize(
        blocks: List<ContentBlock>,
        chapterIndex: Int,
        state: StreamState,
    ): List<Token> {
        val tokens = ArrayList<Token>()
        for (block in blocks) {
            when (block) {
                is ContentBlock.Skip -> {
                    // A marker is its own paragraph: the reader sees it alone and the
                    // timing engine gives it a paragraph-length pause.
                    state.paragraphIndex++
                    state.sentenceIndex++
                    tokens += SkipMarkerToken(
                        index = state.nextIndex++,
                        kind = block.kind,
                        chapterIndex = chapterIndex,
                        paragraphIndex = state.paragraphIndex,
                        sentenceIndex = state.sentenceIndex,
                        label = block.label,
                    )
                }

                is ContentBlock.Paragraph -> tokens += tokenizeParagraph(block, chapterIndex, state)
            }
        }
        return tokens
    }

    private fun tokenizeParagraph(
        block: ContentBlock.Paragraph,
        chapterIndex: Int,
        state: StreamState,
    ): List<WordToken> {
        val text = block.text
        val words = ArrayList<WordToken>()
        var index = 0

        while (index < text.length) {
            val runStart = index
            while (index < text.length && !text[index].isLetterOrDigit()) index++
            if (index >= text.length) break

            if (words.isNotEmpty()) {
                val previous = words.last()
                val boundary = maxOf(previous.boundary, boundaryIn(text, runStart, index))
                words[words.lastIndex] = previous.copy(boundary = boundary)
                if (boundary >= Boundary.SENTENCE) state.sentenceIndex++
            }

            val wordStart = index
            index = wordEnd(text, wordStart)

            if (words.isEmpty()) {
                state.paragraphIndex++
                state.sentenceIndex++
            }

            words += WordToken(
                index = state.nextIndex++,
                text = text.substring(wordStart, index),
                chapterIndex = chapterIndex,
                paragraphIndex = state.paragraphIndex,
                sentenceIndex = state.sentenceIndex,
                boundary = Boundary.NONE,
                isHeading = block.isHeading,
            )
        }

        if (words.isEmpty()) return emptyList()

        // Punctuation after the final word, then the block's own closing pause.
        val tail = text.indexOfLast { it.isLetterOrDigit() } + 1
        val last = words.last()
        val closing = if (block.isHeading) Boundary.HEADING else Boundary.PARAGRAPH
        words[words.lastIndex] = last.copy(
            boundary = maxOf(maxOf(last.boundary, boundaryIn(text, tail, text.length)), closing),
        )
        return words
    }

    /**
     * The end of the word starting at [start].
     *
     * Trailing apostrophes and hyphens are given back to the punctuation run — an
     * em-dash-delimited aside must not glue a dash onto a word — and a period is
     * pulled *in* when it abbreviates, so "Sr." is one token whose period does not
     * end a sentence, and "U.S.A." stays whole rather than becoming three tokens.
     */
    private fun wordEnd(text: String, start: Int): Int {
        var end = runEnd(text, start)
        while (end < text.length && text[end] == '.' &&
            WordClassifier.isAbbreviation(text.substring(start, end + 1))
        ) {
            val afterPeriod = end + 1
            val continued = runEnd(text, afterPeriod)
            end = if (continued > afterPeriod) continued else return afterPeriod
        }
        return end
    }

    /** One run of word characters, without trailing internal marks. */
    private fun runEnd(text: String, start: Int): Int {
        var end = start
        while (end < text.length && text[end].isWordChar()) end++
        while (end > start && text[end - 1] in WORD_INTERNAL) end--
        return end
    }

    /** The strongest break inside `text[from until to]`. */
    private fun boundaryIn(text: String, from: Int, to: Int): Boundary {
        var boundary = Boundary.NONE
        for (position in from until minOf(to, text.length)) {
            val char = text[position]
            boundary = when (char) {
                in SENTENCE_PUNCTUATION -> maxOf(boundary, Boundary.SENTENCE)
                in CLAUSE_PUNCTUATION -> maxOf(boundary, Boundary.CLAUSE)
                else -> boundary
            }
        }
        return boundary
    }

    private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this in WORD_INTERNAL

    /**
     * Counters carried across spine items.
     *
     * Paragraph and sentence ordinals are global, so LEAF203's "back one sentence"
     * keeps working across a chapter boundary with no extra lookup.
     */
    class StreamState(
        var nextIndex: Int = 0,
        var paragraphIndex: Int = -1,
        var sentenceIndex: Int = -1,
    )
}
