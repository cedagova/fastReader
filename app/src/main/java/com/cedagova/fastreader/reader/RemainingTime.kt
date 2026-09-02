package com.cedagova.fastreader.reader

import com.cedagova.fastreader.content.BookContent
import com.cedagova.fastreader.timing.PauseStrength
import com.cedagova.fastreader.timing.RsvpTiming
import com.cedagova.fastreader.timing.RsvpTimingEngine
import com.cedagova.fastreader.timing.TimingSettings

/**
 * Time remaining at the current speed (REQ-017), in constant time per word.
 *
 * The honest answer is [RsvpTimingEngine.estimatedMillis] over every token still
 * to come — LEAF202 exists precisely because `words / wpm` under-reports the real
 * stream by a large factor. But that call is `O(tokens)`, and the reader needs the
 * number again every time a word changes: at 1000 WPM on a 100k-token book that is
 * a full sweep of the book roughly sixteen times a second, on the main thread,
 * inside the frame budget the "no visible stutter" guardrail is measured against.
 *
 * So this precomputes the sweep once, at book open, on the parsing dispatcher.
 *
 * ## Why a multiplier and not a millisecond
 *
 * A token's steady-state duration is `plainWordMillis * multiplier`, where the
 * multiplier depends only on the token's boundary and word classes — never on
 * speed. So a suffix sum of multipliers, scaled by [TimingSettings.targetWordMillis]
 * at read time, answers every speed instantly and a mid-stream speed change costs
 * nothing (REQ-012).
 *
 * The multipliers are *measured* from LEAF202 rather than recomputed here:
 * [RsvpTimingEngine.durationMillis] is asked for each token at a fixed reference
 * speed and the answer divided by that speed's plain word. This file therefore
 * holds no copy of the timing formula, and a change to LEAF202's multipliers flows
 * through automatically. The only cost is LEAF202's per-token rounding to whole
 * milliseconds: at the reference speed a plain word is 600 ms, so each multiplier
 * is accurate to within 0.08%, and `RemainingTimeTest` pins the whole-book result
 * against [RsvpTimingEngine.estimatedMillis] itself.
 */
class RemainingTimeIndex private constructor(
    private val suffixMultipliers: DoubleArray,
    /** The pause strength the multipliers were measured at; a different one needs a new index. */
    val pauseStrength: PauseStrength,
) {

    /**
     * Milliseconds still to stream *after* the token at [tokenIndex] at [settings]'
     * speed. Pass `-1` for the whole book.
     *
     * Ramp-up and the re-orientation hold are excluded, matching
     * [RsvpTimingEngine.estimatedMillis]: an estimate for the rest of a book should
     * not lurch every time the reader jumps.
     */
    fun millisAfter(tokenIndex: Int, settings: TimingSettings): Long {
        val from = (tokenIndex + 1).coerceIn(0, suffixMultipliers.size - 1)
        return (suffixMultipliers[from] * settings.targetWordMillis).toLong().coerceAtLeast(0L)
    }

    companion object {

        /**
         * The speed the multipliers are measured at. The slowest allowed speed gives
         * the longest plain word — 600 ms — and therefore the smallest relative
         * error from LEAF202's rounding to whole milliseconds.
         */
        private val REFERENCE = TimingSettings(wpm = RsvpTiming.MIN_WPM, rampEnabled = false)

        fun build(content: BookContent, pauseStrength: PauseStrength): RemainingTimeIndex {
            val reference = REFERENCE.copy(pauseStrength = pauseStrength)
            val plainWord = reference.targetWordMillis
            val tokens = content.tokens
            // suffix[i] is the multiplier sum of tokens i..last, so suffix[size] is 0
            // and `millisAfter(lastIndex)` correctly reports nothing left.
            val suffix = DoubleArray(tokens.size + 1)
            for (i in tokens.indices.reversed()) {
                val millis = RsvpTimingEngine.durationMillis(tokens[i], reference, STEADY_REFERENCE)
                suffix[i] = suffix[i + 1] + millis / plainWord
            }
            return RemainingTimeIndex(suffix, pauseStrength)
        }

        /**
         * Warmed up and not re-orienting — the same state
         * [RsvpTimingEngine.estimatedMillis] reasons in, reconstructed here because
         * it is private there. [REFERENCE] disables the ramp as well, so the two
         * agree even if that private constant ever moves.
         */
        private val STEADY_REFERENCE = com.cedagova.fastreader.timing.TimingState(
            elapsedPlaybackMillis = RsvpTiming.RAMP_DURATION_MILLIS,
            reorientationPending = false,
        )
    }
}
