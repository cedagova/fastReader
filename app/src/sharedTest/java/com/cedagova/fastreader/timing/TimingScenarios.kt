package com.cedagova.fastreader.timing

import com.cedagova.fastreader.content.Boundary
import com.cedagova.fastreader.content.SkipKind
import com.cedagova.fastreader.content.SkipMarkerToken
import com.cedagova.fastreader.content.Token
import com.cedagova.fastreader.content.WordClass
import com.cedagova.fastreader.content.WordToken

/**
 * The canonical duration table, shared by the JVM unit tests and the on-device
 * test so both assert the *same* numbers.
 *
 * Every expected value below is arithmetic anyone can redo by hand from the
 * documented formula, which is the point of AD-5: the engine's behavior is a
 * table, not a feeling about how fast words go by.
 */
object TimingScenarios {

    /** 60000 / 250 — the plain word duration at the default speed. */
    const val BASE_MILLIS_AT_250: Long = 240L

    fun word(
        boundary: Boundary = Boundary.NONE,
        classes: Set<WordClass> = emptySet(),
        isHeading: Boolean = false,
        text: String = "palabra",
        index: Int = 0,
    ): WordToken = WordToken(
        index = index,
        text = text,
        chapterIndex = 0,
        paragraphIndex = 0,
        sentenceIndex = 0,
        boundary = boundary,
        classes = classes,
        isHeading = isHeading,
    )

    fun skipMarker(index: Int = 0): SkipMarkerToken = SkipMarkerToken(
        index = index,
        kind = SkipKind.IMAGE,
        chapterIndex = 0,
        paragraphIndex = 0,
        sentenceIndex = 0,
        label = "[image skipped]",
    )

    /** Default speed, research multipliers, ramp and re-orientation out of the way. */
    private val STEADY = TimingSettings(wpm = 250, pauseStrength = PauseStrength.NORMAL, rampEnabled = false)

    /** Mid-stream: nothing pending, ramp already irrelevant because it is disabled. */
    private val RUNNING = TimingState(elapsedPlaybackMillis = 60_000L, reorientationPending = false)

    data class Case(
        val name: String,
        val token: Token,
        val settings: TimingSettings,
        val state: TimingState,
        val expectedMillis: Long,
    )

    /**
     * Cases that must hold identically on any JVM and on ART.
     *
     * They cover the acceptance criteria of issue #11 plus the failure/edge
     * behavior it names, and are the exact set the device test replays.
     */
    val CANONICAL: List<Case> = listOf(
        Case("plain word at 250 WPM", word(), STEADY, RUNNING, 240),
        Case("clause 2.0x", word(boundary = Boundary.CLAUSE), STEADY, RUNNING, 480),
        Case("sentence 3.0x", word(boundary = Boundary.SENTENCE), STEADY, RUNNING, 720),
        Case("paragraph 3.5x", word(boundary = Boundary.PARAGRAPH), STEADY, RUNNING, 840),
        Case("heading 4.0x", word(boundary = Boundary.HEADING, isHeading = true), STEADY, RUNNING, 960),
        Case(
            "long word 1.5x",
            word(classes = setOf(WordClass.LONG), text = "incomprensiblemente"),
            STEADY,
            RUNNING,
            360,
        ),
        Case("number 1.5x", word(classes = setOf(WordClass.NUMBER), text = "1984"), STEADY, RUNNING, 360),
        Case("all caps 1.5x", word(classes = setOf(WordClass.ALL_CAPS), text = "NASA"), STEADY, RUNNING, 360),
        Case("rare word 1.5x", word(classes = setOf(WordClass.RARE), text = "cinabrio"), STEADY, RUNNING, 360),
        Case(
            "emphasis and boundary do not compound",
            word(boundary = Boundary.SENTENCE, classes = setOf(WordClass.LONG, WordClass.RARE)),
            STEADY,
            RUNNING,
            720,
        ),
        Case(
            "abbreviation exempt from the sentence pause",
            word(boundary = Boundary.SENTENCE, classes = setOf(WordClass.ABBREVIATION), text = "Dr."),
            STEADY,
            RUNNING,
            240,
        ),
        Case(
            "abbreviation still ends its paragraph",
            word(boundary = Boundary.PARAGRAPH, classes = setOf(WordClass.ABBREVIATION), text = "etc."),
            STEADY,
            RUNNING,
            840,
        ),
        Case("skip marker takes its paragraph pause", skipMarker(), STEADY, RUNNING, 840),
        Case(
            "pause strength off is uniform",
            word(boundary = Boundary.HEADING),
            STEADY.copy(pauseStrength = PauseStrength.OFF),
            RUNNING,
            240,
        ),
        Case(
            "pause strength subtle halves the extra pause",
            word(boundary = Boundary.SENTENCE),
            STEADY.copy(pauseStrength = PauseStrength.SUBTLE),
            RUNNING,
            480,
        ),
        Case(
            "pause strength strong adds half again",
            word(boundary = Boundary.SENTENCE),
            STEADY.copy(pauseStrength = PauseStrength.STRONG),
            RUNNING,
            960,
        ),
        Case(
            "ramp opens at 80% of target speed",
            word(),
            STEADY.copy(rampEnabled = true),
            TimingState(elapsedPlaybackMillis = 0L, reorientationPending = false),
            300,
        ),
        Case(
            "ramp is halfway at 10 s",
            word(),
            STEADY.copy(rampEnabled = true),
            TimingState(elapsedPlaybackMillis = 10_000L, reorientationPending = false),
            267,
        ),
        Case(
            "ramp reaches target at 20 s",
            word(),
            STEADY.copy(rampEnabled = true),
            TimingState(elapsedPlaybackMillis = 20_000L, reorientationPending = false),
            240,
        ),
        Case(
            "re-orientation holds the first word 3x",
            word(),
            STEADY,
            TimingState(elapsedPlaybackMillis = 60_000L, reorientationPending = true),
            720,
        ),
        Case(
            "re-orientation adds to, not multiplies, the sentence pause",
            word(boundary = Boundary.SENTENCE),
            STEADY,
            TimingState(elapsedPlaybackMillis = 60_000L, reorientationPending = true),
            1200,
        ),
        Case("WPM below range clamps to 100", word(), STEADY.copy(wpm = 50), RUNNING, 600),
        Case("WPM above range clamps to 1000", word(), STEADY.copy(wpm = 5_000), RUNNING, 60),
        Case(
            "1000 WPM plain word is exactly 60 ms",
            word(),
            STEADY.copy(wpm = 1_000, pauseStrength = PauseStrength.OFF),
            RUNNING,
            60,
        ),
    )
}
