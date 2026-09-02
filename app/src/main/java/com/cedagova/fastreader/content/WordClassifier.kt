package com.cedagova.fastreader.content

/**
 * The bounded word heuristics the timing engine needs.
 *
 * Research pins the *effect* — long, numeric and rare words hold about 1.5× as
 * long, abbreviations are exempt from the sentence pause — but not the
 * detection. The issue rules out NLP, so every rule here is mechanical, has a
 * named constant, and produces the same answer for the same book every time.
 *
 * How LEAF202 is expected to read the result: [WordClass.LONG], [WordClass.NUMBER],
 * [WordClass.ALL_CAPS] and [WordClass.RARE] each mean "slow this word down", and
 * a word carrying several of them is still one slow word rather than a compounded
 * pause. [WordClass.ABBREVIATION] is not a slow-down at all: it is the marker
 * saying the trailing period was not a full stop.
 */
internal object WordClassifier {

    /** Squirt's constant: longer than eleven characters reads as a long word. */
    const val LONG_WORD_MIN_LENGTH = 11

    /**
     * Rarity is measured *inside the book*, not against a shipped frequency list.
     *
     * A word that occurs exactly once in a whole novel is rare for this reader in
     * the only sense that matters, and it costs no dictionary, no language
     * detection and no model. The length floor keeps ordinary short words —
     * inflections, names in dialogue, numbers already covered elsewhere — from
     * flooding the class.
     */
    const val RARE_MIN_LENGTH = 8

    /**
     * Abbreviations whose period does not end a sentence.
     *
     * Deliberately short and concrete: honorifics and the handful of publishing
     * abbreviations that actually appear mid-sentence, in both languages the app
     * supports. Dotted initialisms ("U.S.", "e.g.", "J.") are recognised by shape
     * instead, so they need no entries.
     *
     * "no" is deliberately absent. It abbreviates "number", but that sense
     * appears almost only before a digit, while the ordinary adverb "No." ends
     * sentences constantly in both languages — and listing it here would delete
     * the sentence pause and the sentence boundary from every one of them. The
     * numbering sense is recognised by [isNumberingAbbreviation] with the digit
     * actually present instead. Every other entry is a word that does not stand
     * alone in prose, so the only cost it carries is the inherent one: a sentence
     * genuinely ending in "etc." keeps no sentence break, which is what "exempt
     * from the sentence pause" means.
     */
    private val KNOWN_ABBREVIATIONS = setOf(
        // English
        "mr", "mrs", "ms", "dr", "prof", "rev", "hon", "st", "jr", "sr",
        "vs", "etc", "cf", "ca", "approx", "vol", "pp", "fig", "ed",
        "eds", "inc", "ltd", "co", "dept", "univ", "ave", "blvd", "mt", "op",
        // Spanish
        "sra", "srta", "dra", "ud", "uds", "vd", "vds", "av", "avda", "núm",
        "num", "pág", "pag", "ej", "esq", "apdo", "izq", "dcha", "cía", "cia",
        "admón", "depto", "ss", "tel",
    )

    /**
     * True when [candidate] — a word *including* its trailing period — abbreviates
     * rather than ends a sentence.
     */
    fun isAbbreviation(candidate: String): Boolean {
        if (!candidate.endsWith('.')) return false
        val body = candidate.dropLast(1)
        if (body.isEmpty()) return false
        if (isDottedInitialism(candidate)) return true
        if (body.any { !it.isLetter() }) return false
        return body.lowercase() in KNOWN_ABBREVIATIONS
    }

    /**
     * "No. 5", "núm. 12" — the numbering sense of a word that is otherwise
     * ordinary prose. The caller checks that a digit really follows.
     */
    fun isNumberingAbbreviation(body: String): Boolean = body.lowercase() in NUMBERING_ABBREVIATIONS

    private val NUMBERING_ABBREVIATIONS = setOf("no", "nos", "núm", "num", "nro", "nros")

    /** `J.`, `U.S.`, `e.g.` — single letters each closed by a period. */
    private fun isDottedInitialism(candidate: String): Boolean {
        var index = 0
        var groups = 0
        while (index < candidate.length) {
            if (!candidate[index].isLetter()) return false
            if (candidate.getOrNull(index + 1) != '.') return false
            groups++
            index += 2
        }
        return groups > 0
    }

    /**
     * Classifies one word.
     *
     * [occurrencesInBook] is how many times the word's normalized form appears in
     * the whole book, which is why classification runs after the stream is built
     * rather than while it is being read.
     */
    fun classify(word: String, occurrencesInBook: Int): Set<WordClass> {
        val classes = LinkedHashSet<WordClass>(4)
        val letters = word.count { it.isLetter() }

        if (word.length > LONG_WORD_MIN_LENGTH) classes += WordClass.LONG
        if (word.any { it.isDigit() }) classes += WordClass.NUMBER
        if (letters >= 2 && word.none { it.isLowerCase() } && word.any { it.isUpperCase() }) {
            classes += WordClass.ALL_CAPS
        }
        if (isAbbreviation(word)) classes += WordClass.ABBREVIATION
        if (occurrencesInBook <= 1 && normalize(word).length >= RARE_MIN_LENGTH) {
            classes += WordClass.RARE
        }
        return classes
    }

    /**
     * The form words are counted by: case-folded, without the punctuation a word
     * can carry. "Casa", "casa" and "casa." are one word for rarity purposes.
     */
    fun normalize(word: String): String =
        word.filter { it.isLetterOrDigit() }.lowercase()
}
