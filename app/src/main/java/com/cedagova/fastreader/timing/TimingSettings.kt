package com.cedagova.fastreader.timing

import kotlinx.serialization.Serializable

/**
 * The RSVP timing constants, all traced to the pinned research
 * (`docs/product-definitions/cedagova-fastReader-1/research-rsvp.md` at
 * definition head `dde30d5`).
 *
 * They live in one object because the whole point of AD-5 is that the numbers
 * are stated once, provable by unit test, and not smeared across a UI.
 */
object RsvpTiming {

    /**
     * Research "Recommended defaults": *Default 250 WPM; range 100-1000*.
     * Spritz's own default and range, and the top of the band where research
     * finds no comprehension loss (250-350 WPM).
     */
    const val DEFAULT_WPM: Int = 250

    /**
     * Research "Recommended defaults": range floor.
     *
     * Since #81 the range bounds the *average* speed of a book, pauses included
     * (see [TimingSettings.meanMultiplier]). A plain word inside the book runs
     * faster than the dial by the book's mean multiplier — about 1.1–1.15x on
     * the two books measured in `research-pacing.md` — so the instantaneous
     * plain-word rate at the ceiling is roughly 1150 WPM, not 1000.
     */
    const val MIN_WPM: Int = 100

    /** Research "Recommended defaults": range ceiling — of the average, see [MIN_WPM]. */
    const val MAX_WPM: Int = 1000

    /** Base word duration is `60000 / wpm`, the Squirt constant. */
    const val MILLIS_PER_MINUTE: Double = 60_000.0

    /** Research timing heuristics: *sentence end .!? -> 3.0x*. */
    const val SENTENCE_MULTIPLIER: Double = 3.0

    /** Research timing heuristics: *comma/semicolon/colon/dash -> 2.0x*. */
    const val CLAUSE_MULTIPLIER: Double = 2.0

    /** Research timing heuristics: *paragraph break -> 3.5x*. */
    const val PARAGRAPH_MULTIPLIER: Double = 3.5

    /** Research "Recommended defaults": *heading full stop or >=4x*. */
    const val HEADING_MULTIPLIER: Double = 4.0

    /**
     * The span, in words, at which a clause, sentence or paragraph pause earns its
     * full research value (#81, `research-pacing.md`): the extra part of the pause
     * is scaled by `min(1, span / SPAN_FULL_PAUSE_WORDS)`, so a full stop after a
     * three-word sentence holds `1 + 2.0 × 0.3 = 1.6x`, not 3.0x.
     *
     * The idea is Spritz's — its sentence-end pause grows with sentence length —
     * and the reading research behind it is Just & Carpenter's wrap-up effect:
     * the time a reader needs at a boundary scales with how much there is to
     * integrate. Ten words is the addendum's simulated value; it brings every
     * 60-word window of two full books within about ±4% of the dial. Heading
     * pauses are structural, not wrap-up, and are never scaled.
     */
    const val SPAN_FULL_PAUSE_WORDS: Int = 10

    /**
     * Research timing heuristics: *long word (>11 chars) -> 1.5x*, and
     * *Numbers/ALL-CAPS/rare words treated like long words (~1.5x) by
     * convention*.
     *
     * One value covers all four classes, which is also how LEAF201 documents
     * them: a word carrying several is still one slow word, not a compounded
     * pause.
     */
    const val EMPHASIS_MULTIPLIER: Double = 1.5

    /**
     * Research "Recommended defaults": *Ramp on by default: start ~80% of
     * target*.
     *
     * This is a fraction of target *speed*, not of the duration: at 250 WPM
     * playback opens at 200 WPM, so the first word holds `240 / 0.8 = 300 ms`.
     */
    const val RAMP_START_SPEED_FRACTION: Double = 0.8

    /**
     * Research "Recommended defaults": *reach target over ~15-30 s*.
     *
     * The midpoint of that window, which also satisfies REQ-013's acceptance
     * ("reaches target within ~30 s") with room to spare.
     */
    const val RAMP_DURATION_MILLIS: Long = 20_000L

    /**
     * Research timing heuristics: *after jump/rewind -> 3x on first word*.
     * REQ-013 extends it to any resume as well.
     */
    const val REORIENTATION_MULTIPLIER: Double = 3.0
}

/**
 * The single modulation control the definition allows (REQ-011): one scaler over
 * the *extra* pause, never a per-multiplier settings panel.
 *
 * [extraPauseScale] multiplies only the part of a token's duration that exceeds
 * one plain word, so [OFF] collapses every token to the same duration — which is
 * exactly REQ-011's acceptance ("setting pause strength 'off' makes all words
 * uniform").
 */
@Serializable
enum class PauseStrength(val extraPauseScale: Double) {
    /** No modulation at all: uniform word durations. */
    OFF(0.0),

    /** Half the research pause. */
    SUBTLE(0.5),

    /** The research defaults exactly. */
    NORMAL(1.0),

    /** Half again as much pause as research recommends. */
    STRONG(1.5),
}

/**
 * Everything the engine needs that a person can change, plus the one fact about
 * the *book* that makes the speed setting honest.
 *
 * [wpm] is stored as the user asked for it and clamped on read, so a value that
 * arrives out of range from persistence or a future settings screen produces a
 * sane duration instead of an exception or a divide-by-zero. Every computation in
 * [RsvpTimingEngine] reads [effectiveWpm], never [wpm].
 *
 * ## The dial names the average (#81)
 *
 * Every pause the engine adds is a multiple of the plain word, so without
 * correction the number on the dial is the *burst* speed of plain words and the
 * speed a reader actually experiences over a book is lower by the book's mean
 * multiplier — 18–23% on the two books measured in `research-pacing.md`. The
 * fix is the one Spritz's patent describes and Sprint Reader implements in the
 * open: measure the mean multiplier over the whole book once, and divide the
 * plain word by it. Then `tokens × 60000 / wpm` is exactly how long the book
 * takes, whatever its punctuation, and a comma-rich paragraph and a long
 * unpunctuated sentence at the same setting average the same speed.
 *
 * [meanMultiplier] is that measurement, made by
 * [com.cedagova.fastreader.reader.RemainingTimeIndex] at the current
 * [pauseStrength] and handed here by the session. It is not persisted — it is a
 * property of the open book, not of the reader — and it defaults to `1.0`, so
 * a settings value built without a book (the index's own reference build, a
 * hand-written test stream) sees the raw research multipliers. At
 * [PauseStrength.OFF] every multiplier is 1 and so is the mean.
 */
data class TimingSettings(
    val wpm: Int = RsvpTiming.DEFAULT_WPM,
    val pauseStrength: PauseStrength = PauseStrength.NORMAL,
    val rampEnabled: Boolean = true,
    val meanMultiplier: Double = 1.0,
) {
    /** [wpm] clamped to the definition's 100-1000 range. */
    val effectiveWpm: Int get() = wpm.coerceIn(RsvpTiming.MIN_WPM, RsvpTiming.MAX_WPM)

    /**
     * [meanMultiplier] made safe to divide by: a mean below one is impossible
     * for a stream whose multipliers are all at least one, so anything smaller,
     * or not a number, is a caller's bug and reads as "no correction".
     */
    val effectiveMeanMultiplier: Double
        get() = if (meanMultiplier.isFinite()) meanMultiplier.coerceAtLeast(1.0) else 1.0

    /**
     * Duration of one plain word at target speed, before ramp and modulation:
     * `60000 / wpm` divided by the book's mean multiplier, so that the average
     * over the book — pauses included — comes out at exactly [effectiveWpm].
     */
    val targetWordMillis: Double get() = RsvpTiming.MILLIS_PER_MINUTE / effectiveWpm / effectiveMeanMultiplier
}

/**
 * The playback state the durations depend on. Immutable, so the engine stays a
 * pure function and the scheduler (LEAF203) owns advancing it.
 *
 * Two facts, and only two:
 *
 * - [elapsedPlaybackMillis] — time actually *streamed* since playback started,
 *   which drives the ramp. It is the sum of the durations the engine already
 *   returned, not a wall clock, so pausing does not advance the ramp and the
 *   engine never reads a clock.
 * - [reorientationPending] — the next token is the first one after a start,
 *   resume, rewind or jump and therefore gets the re-orientation hold.
 */
data class TimingState(
    val elapsedPlaybackMillis: Long = 0L,
    val reorientationPending: Boolean = true,
) {
    /**
     * The state after showing a token for [durationMillis].
     *
     * The scheduler passes back the exact integer the engine returned, so the
     * ramp the engine computes and the deadlines the scheduler runs on never
     * diverge.
     */
    fun afterShowing(durationMillis: Long): TimingState =
        TimingState(
            elapsedPlaybackMillis = elapsedPlaybackMillis + durationMillis.coerceAtLeast(0L),
            reorientationPending = false,
        )

    /**
     * Re-arm the re-orientation hold without restarting the ramp — a rewind,
     * chapter jump or scrub while already warmed up.
     */
    fun reorienting(): TimingState = copy(reorientationPending = true)

    companion object {
        /**
         * Pressing play: the ramp restarts from 80% and the first word carries
         * the re-orientation hold (REQ-013 covers "any resume").
         */
        val AT_PLAYBACK_START: TimingState = TimingState()
    }
}
