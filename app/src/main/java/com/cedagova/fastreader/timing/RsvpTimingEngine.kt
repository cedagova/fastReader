package com.cedagova.fastreader.timing

import com.cedagova.fastreader.content.Boundary
import com.cedagova.fastreader.content.Token
import com.cedagova.fastreader.content.WordClass
import com.cedagova.fastreader.content.WordToken
import kotlin.math.roundToLong

/**
 * The RSVP timing engine (AD-5): how long each token of a book is shown.
 *
 * Deliberately a pure function of `(token, settings, state)` with no clock, no
 * coroutines, no Android types and no mutable field, because REQ-011's
 * modulation and REQ-013's ramp are the two behaviors that would otherwise only
 * ever be checked by watching words flash past. Everything here is provable by
 * arithmetic.
 *
 * ## The formula
 *
 * ```
 * word    = (60000 / wpm / meanMultiplier) / rampSpeedFraction   // one plain word, right now
 * pause   = max(boundaryMultiplier, emphasisMultiplier)
 * hold    = 3.0 on the first token after a start/resume/jump, else 1.0
 * duration = word * hold  +  word * (pause - 1) * pauseStrength.extraPauseScale
 * ```
 *
 * Read it as two separable costs. `word * hold` is the cost of *showing* the
 * token — speed and re-orientation. `word * (pause - 1) * scale` is the extra
 * pause *after* it — the syntactic wrap-up time research says supports
 * comprehension, and the only part the pause-strength setting touches. That
 * split is what makes REQ-011's acceptance exact rather than approximate:
 * `PauseStrength.OFF` zeroes the second term, so every token in a book takes the
 * same time.
 *
 * `meanMultiplier` (#81) is the book's mean `pause` at the current strength,
 * measured once by `RemainingTimeIndex` and carried in [TimingSettings]. Dividing
 * the plain word by it makes the dial name the *average* speed of the book,
 * pauses included: the pauses are redistributed inside a fixed budget of
 * `tokens × 60000 / wpm` rather than added on top of it. The engine itself never
 * sees the book; it only divides by the number it is handed, which is `1.0` for
 * a stream nobody has measured.
 *
 * `max` rather than a product is deliberate. A twelve-letter word ending a
 * sentence holds for the sentence pause (3x), not 4.5x: LEAF201 already
 * documents its word classes as "one slow word rather than a compounded pause",
 * and the same reasoning applies across the two axes. Compounding also makes
 * time-remaining math (REQ-017) much harder to predict for no comprehension
 * benefit research supports.
 *
 * A boundary pause is also *proportional to the span it closes* (#81): the
 * extra part of a clause, sentence or paragraph pause is scaled by
 * `min(1, span / SPAN_FULL_PAUSE_WORDS)`, where `span` is the token's own count
 * of words since the previous clause or stronger boundary. A full stop after
 * "Yes." no longer costs three words; one after twelve words costs exactly what
 * research says. Heading pauses are structural and keep their full value, skip
 * markers have no span, and emphasis is a per-word cost that is never scaled. A
 * token without a measured span — a hand-built stream — gets the full pause.
 *
 * ## Contract for the scheduler (LEAF203)
 *
 * 1. **Integer milliseconds.** Durations are whole milliseconds, so the
 *    scheduler can hold an exact absolute deadline — `deadline += duration` —
 *    instead of sleeping for a rounded interval and accumulating drift. At the
 *    1000 WPM ceiling a plain word is exactly 60 ms; rounding never costs more
 *    than half a millisecond of a frame budget that is already sub-frame.
 * 2. **Feed the same integer back.** Advance the state with
 *    [TimingState.afterShowing] using the exact value the engine returned. The
 *    ramp is then computed from the same timeline the scheduler runs on, and
 *    the two cannot drift apart.
 * 3. **Never reads a clock.** The engine has no notion of wall time, so a paused
 *    reader does not silently ramp up while nothing is on screen, and a test can
 *    fast-forward twenty seconds of playback in a loop.
 * 4. **Mid-stream changes are free.** [TimingSettings] is a value; hand the
 *    engine a new one on the next token. Nothing is cached, nothing restarts,
 *    and because the ramp is a multiplier on the *current* target the pacing
 *    changes without a discontinuity (REQ-012).
 * 5. **Every token gets a duration**, including [com.cedagova.fastreader.content.SkipMarkerToken];
 *    the scheduler needs no type switch.
 */
object RsvpTimingEngine {

    /**
     * The word classes that slow a word down.
     *
     * Written as an explicit set rather than an exhaustive `when` so that a word
     * class the engine does not know about — a future LEAF201 addition, or
     * [WordClass.ABBREVIATION], which is a parsing marker and not a slow-down —
     * falls back to the plain word duration instead of guessing.
     */
    private val EMPHASIS_CLASSES: Set<WordClass> =
        setOf(WordClass.LONG, WordClass.NUMBER, WordClass.ALL_CAPS, WordClass.RARE)

    /** Warmed up and not re-orienting: the state [estimatedMillis] reasons in. */
    private val STEADY = TimingState(
        elapsedPlaybackMillis = RsvpTiming.RAMP_DURATION_MILLIS,
        reorientationPending = false,
    )

    /**
     * How long [token] is shown, in whole milliseconds, given [settings] and the
     * current [state]. Never less than 1 ms.
     */
    fun durationMillis(token: Token, settings: TimingSettings, state: TimingState): Long {
        val word = wordMillis(settings, state)
        val hold = if (state.reorientationPending) RsvpTiming.REORIENTATION_MULTIPLIER else 1.0
        val extra = (pauseMultiplier(token) - 1.0) * settings.pauseStrength.extraPauseScale
        return (word * (hold + extra)).roundToLong().coerceAtLeast(1L)
    }

    /**
     * The duration of a plain, unmodulated word at this moment — the value every
     * other duration is a multiple of, and the frame budget the scheduler should
     * size itself against.
     *
     * Exposed because it is the honest answer to "how fast is the reader
     * actually going right now", which the reader screen needs while the ramp is
     * still climbing.
     */
    fun plainWordMillis(settings: TimingSettings, state: TimingState): Long =
        wordMillis(settings, state).roundToLong().coerceAtLeast(1L)

    /**
     * How long [tokens] take to stream at steady speed.
     *
     * This is the number REQ-017's "time remaining" needs, and it exists here
     * because nothing else can compute it. LEAF201 deliberately stops at
     * `wordsRemaining`, noting that "LEAF202 adds its pause multipliers on top" —
     * and those multipliers are not a rounding error. Streaming a book with the
     * research defaults costs meaningfully more than `words / wpm`, by an amount
     * that depends on how the book is punctuated, so a reader told "12 minutes
     * left" from the naive figure would consistently run over.
     *
     * Ramp-up and the re-orientation hold are excluded on purpose: an estimate
     * for a whole book should not lurch every time the reader jumps.
     */
    fun estimatedMillis(tokens: Iterable<Token>, settings: TimingSettings): Long =
        tokens.sumOf { durationMillis(it, settings, STEADY) }

    /**
     * The current speed as a fraction of target, `0.8..1.0` while the ramp
     * climbs and exactly `1.0` once it finishes or when the ramp is off.
     */
    fun rampSpeedFraction(settings: TimingSettings, state: TimingState): Double {
        if (!settings.rampEnabled) return 1.0
        val progress = (
            state.elapsedPlaybackMillis.toDouble() / RsvpTiming.RAMP_DURATION_MILLIS
            ).coerceIn(0.0, 1.0)
        return RsvpTiming.RAMP_START_SPEED_FRACTION +
            (1.0 - RsvpTiming.RAMP_START_SPEED_FRACTION) * progress
    }

    /** Unrounded plain-word duration, kept in Double so one rounding happens at the end. */
    private fun wordMillis(settings: TimingSettings, state: TimingState): Double =
        settings.targetWordMillis / rampSpeedFraction(settings, state)

    /**
     * The multiplier for the pause *after* [token]: the stronger of what its
     * punctuation asks for and what the word itself asks for.
     */
    private fun pauseMultiplier(token: Token): Double =
        maxOf(boundaryMultiplier(token), emphasisMultiplier(token))

    private fun boundaryMultiplier(token: Token): Double {
        // Abbreviations are exempt from the sentence pause. LEAF201's tokenizer
        // normally prevents this from arising at all — it pulls an abbreviating
        // period into the word, so no sentence punctuation is left to detect —
        // but the engine's input is a Token, not necessarily one that tokenizer
        // produced, and "abbreviations exempt" is this leaf's invariant to hold.
        // A paragraph or heading boundary is untouched: that break is structural,
        // not a full stop the abbreviation faked.
        if (token.boundary == Boundary.SENTENCE && token.hasClass(WordClass.ABBREVIATION)) {
            return 1.0
        }
        return when (token.boundary) {
            Boundary.NONE -> 1.0
            Boundary.CLAUSE -> spanScaled(token, RsvpTiming.CLAUSE_MULTIPLIER)
            Boundary.SENTENCE -> spanScaled(token, RsvpTiming.SENTENCE_MULTIPLIER)
            Boundary.PARAGRAPH -> spanScaled(token, RsvpTiming.PARAGRAPH_MULTIPLIER)
            Boundary.HEADING -> RsvpTiming.HEADING_MULTIPLIER
        }
    }

    /**
     * [full] scaled by the span [token] closes (#81): a short span earns a
     * proportionally shorter pause, a span of [RsvpTiming.SPAN_FULL_PAUSE_WORDS]
     * or more earns all of it. Only a [WordToken] carries a span; anything else,
     * and a word whose span was never measured, holds the full pause.
     */
    private fun spanScaled(token: Token, full: Double): Double {
        val span = (token as? WordToken)?.span ?: return full
        val share = (span.toDouble() / RsvpTiming.SPAN_FULL_PAUSE_WORDS).coerceIn(0.0, 1.0)
        return 1.0 + (full - 1.0) * share
    }

    private fun emphasisMultiplier(token: Token): Double =
        if (token is WordToken && token.classes.any { it in EMPHASIS_CLASSES }) {
            RsvpTiming.EMPHASIS_MULTIPLIER
        } else {
            1.0
        }

    private fun Token.hasClass(wordClass: WordClass): Boolean =
        this is WordToken && wordClass in classes
}
