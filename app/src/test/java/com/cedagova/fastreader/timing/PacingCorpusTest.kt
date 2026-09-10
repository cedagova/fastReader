package com.cedagova.fastreader.timing

import com.cedagova.fastreader.content.BookContent
import com.cedagova.fastreader.content.Boundary
import com.cedagova.fastreader.content.ContentBlock
import com.cedagova.fastreader.content.Token
import com.cedagova.fastreader.content.Tokenizer
import com.cedagova.fastreader.content.WordClass
import com.cedagova.fastreader.content.WordClassifier
import com.cedagova.fastreader.content.WordToken
import com.cedagova.fastreader.reader.RemainingTimeIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * #81 measured over book-sized real prose.
 *
 * The pipeline fixtures are a few dozen tokens: enough to prove the arithmetic,
 * not enough to say how a *book* paces — a 37-token stream holds no 60-word
 * window, and its two headings are 5% of it. So two public-domain novels, one
 * per supported language (`src/test/resources/corpus/README.md`), go through
 * the real tokenizer and classifier here, and the acceptance is stated the way
 * the research addendum measured it: percentiles over sentences and windows,
 * never a universal bound, because a short sentence carrying several emphasis
 * words legitimately runs at about 0.7x the dial and no constant fixes that.
 *
 * The printed table is the PR's record of the measured figures.
 */
class PacingCorpusTest {

    private val wpm = 300
    private val steady = TimingState(elapsedPlaybackMillis = 60_000L, reorientationPending = false)

    private class Corpus(val name: String, val language: String, val book: BookContent)

    private val corpora: List<Corpus> by lazy {
        listOf(
            Corpus("Pride and Prejudice", "en", load("en-pride-and-prejudice.txt", "en")),
            Corpus("Misericordia", "es", load("es-misericordia.txt", "es")),
        )
    }

    @Test
    fun `both corpora are book-sized and went through the real tokenizer`() {
        for (corpus in corpora) {
            assertTrue("${corpus.name}: ${corpus.book.totalWords} words", corpus.book.totalWords >= 50_000)
            assertTrue(corpus.book.tokens.all { it !is WordToken || it.span != null })
        }
    }

    /**
     * Sentences of four or more words: p5 at least 0.88 and p95 at most 1.08 of
     * the dial; 60-word windows: p5 at least 0.93 and p95 at most 1.07. The
     * thresholds are the addendum's simulated p5/p95 with a small margin.
     */
    @Test
    fun `sentences and windows stay near the dial at NORMAL`() {
        val table = StringBuilder("corpus                 subject    min    p5     p50    p95    max\n")
        for (corpus in corpora) {
            val settings = settingsFor(corpus.book, PauseStrength.NORMAL)
            val fractions = speedFractions(corpus.book.tokens, settings)
            val sentences = Stats(sentencesOf(corpus.book.tokens).filter { it.size >= 4 }.map { it.speed(fractions) })
            val windows = Stats(corpus.book.tokens.indices.chunked(60).filter { it.size == 60 }.map { it.speed(fractions) })
            table.append("%-22s sentences %s\n".format(corpus.name, sentences))
            table.append("%-22s windows   %s\n".format(corpus.name, windows))

            assertTrue("${corpus.name} sentences p5 ${sentences.p5}", sentences.p5 >= 0.88)
            assertTrue("${corpus.name} sentences p95 ${sentences.p95}", sentences.p95 <= 1.08)
            assertTrue("${corpus.name} windows p5 ${windows.p5}", windows.p5 >= 0.93)
            assertTrue("${corpus.name} windows p95 ${windows.p95}", windows.p95 <= 1.07)
        }
        println(table)
    }

    @Test
    fun `breath holds fire on both corpora and never inside a short run`() {
        for (corpus in corpora) {
            val tokens = corpus.book.tokens
            var breaths = 0
            var run = 0
            for (token in tokens) {
                val word = token as? WordToken
                if (word == null || word.boundary >= Boundary.CLAUSE) {
                    run = 0
                    continue
                }
                run++
                if (WordClass.BREATH in word.classes) {
                    breaths++
                    assertTrue("${corpus.name}: hold on '${word.text}' after only $run words", run >= WordClassifier.BREATH_MIN_RUN)
                    run = 0
                }
                assertTrue("${corpus.name}: '${word.text}' is the ${run}th word with no rest", run <= WordClassifier.BREATH_MAX_RUN)
            }
            println("${corpus.name}: $breaths breath holds over ${corpus.book.totalWords} words")
            assertTrue("${corpus.name} must breathe", breaths > 0)
        }
    }

    /**
     * The budget identity on a real book: the whole corpus streams in exactly
     * `tokens × 60000 / wpm`, within 0.5% or one millisecond per token, at every
     * strength and at both ends of the dial.
     */
    @Test
    fun `the whole corpus takes exactly tokens over wpm at every strength`() {
        for (corpus in corpora) {
            for (strength in listOf(PauseStrength.SUBTLE, PauseStrength.NORMAL, PauseStrength.STRONG)) {
                val mean = RemainingTimeIndex.build(corpus.book, strength).meanMultiplier
                for (speed in listOf(100, 250, 1000)) {
                    val settings = TimingSettings(wpm = speed, pauseStrength = strength, rampEnabled = false, meanMultiplier = mean)
                    val actual = corpus.book.tokens.sumOf { RsvpTimingEngine.durationMillis(it, settings, steady) }
                    val budget = corpus.book.totalTokens * 60_000.0 / speed
                    val tolerance = maxOf(budget * 0.005, corpus.book.totalTokens.toDouble())
                    assertTrue(
                        "${corpus.name} $strength at $speed WPM: ${actual}ms vs ${budget}ms (mean $mean)",
                        abs(actual - budget) <= tolerance,
                    )
                }
            }
        }
    }

    @Test
    fun `the mean of a real book is where the research put it`() {
        for (corpus in corpora) {
            val mean = RemainingTimeIndex.build(corpus.book, PauseStrength.NORMAL).meanMultiplier
            println("${corpus.name}: mean multiplier at NORMAL = $mean")
            // Between "no pauses at all" and the additive design's 1.18–1.23.
            assertTrue("${corpus.name} mean $mean", mean > 1.05 && mean < 1.25)
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun settingsFor(book: BookContent, strength: PauseStrength): TimingSettings =
        TimingSettings(
            wpm = wpm,
            pauseStrength = strength,
            rampEnabled = false,
            meanMultiplier = RemainingTimeIndex.build(book, strength).meanMultiplier,
        )

    /** Each token's cost as a multiple of what the dial promises one word. */
    private fun speedFractions(tokens: List<Token>, settings: TimingSettings): DoubleArray {
        val dialWord = RsvpTiming.MILLIS_PER_MINUTE / wpm
        return DoubleArray(tokens.size) { RsvpTimingEngine.durationMillis(tokens[it], settings, steady) / dialWord }
    }

    /** Speed of a run of tokens as a fraction of the dial: words over dial-words spent. */
    private fun List<Int>.speed(cost: DoubleArray): Double = size / sumOf { cost[it] }

    private fun sentencesOf(tokens: List<Token>): List<List<Int>> {
        val sentences = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        for ((index, token) in tokens.withIndex()) {
            current.add(index)
            if (token.boundary >= Boundary.SENTENCE) {
                sentences.add(current)
                current = ArrayList()
            }
        }
        if (current.isNotEmpty()) sentences.add(current)
        return sentences
    }

    private class Stats(values: List<Double>) {
        private val sorted = values.sorted()
        val min = sorted.first()
        val max = sorted.last()
        val p5 = at(0.05)
        val p50 = at(0.50)
        val p95 = at(0.95)
        private fun at(q: Double): Double = sorted[((sorted.size - 1) * q).toInt()]
        override fun toString() = "%.2f   %.2f   %.2f   %.2f   %.2f  (n=%d)".format(min, p5, p50, p95, max, sorted.size)
    }

    private fun load(name: String, language: String): BookContent {
        val text = requireNotNull(javaClass.classLoader?.getResource("corpus/$name")) { "missing corpus $name" }
            .readText()
        val blocks = text.split(Regex("\\n\\s*\\n"))
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .map { ContentBlock.Paragraph(it) }
        val tokens = WordClassifier.classifyStream(Tokenizer.tokenize(blocks, chapterIndex = 0, state = Tokenizer.StreamState()))
        return BookContent(bookDigest = "sha256:corpus-$language", language = language, tokens = tokens, chapters = emptyList())
    }
}
