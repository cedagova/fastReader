package com.cedagova.fastreader.reader

/**
 * When the word on screen should be replaced by the next one.
 *
 * The plan flags naive delay-based scheduling as the real jank risk at the
 * 1000 WPM ceiling, where a word lasts about 60 ms — under four frames at 60 Hz.
 * Two things go wrong with `delay(duration)`:
 *
 * 1. **The word change lands between frames.** The recomposition it triggers is
 *    picked up by whichever frame comes next, so a 60 ms word is drawn for three
 *    or four frames essentially at random. That irregularity is exactly what
 *    "visible stutter" looks like.
 * 2. **The error accumulates.** Each `delay` returns a little late, and starting
 *    the next one from that moment throws the overshoot away. At 60 ms a word,
 *    losing a few milliseconds each time makes the reader measurably slower than
 *    the speed it is showing.
 *
 * So playback is driven from the frame clock instead (`withFrameNanos`), and this
 * class is the arithmetic that runs on each frame: the word changes *on* a frame
 * boundary, and the deadline advances by the exact duration the timing engine
 * returned rather than by "now". A word that is due 4 ms into a frame simply waits
 * for that frame, and its overshoot is carried into the next word, so the cadence
 * over a long run has no drift.
 *
 * It holds no clock of its own — frame times are handed in — which is what lets
 * `PlaybackSchedulerTest` run a full minute of 1000 WPM playback as arithmetic.
 */
class PlaybackScheduler(
    private val resyncThresholdNanos: Long = RESYNC_THRESHOLD_NANOS,
) {

    /** Frame time the word on screen was shown at, in the frame clock's nanoseconds. */
    private var shownAtNanos: Long = 0L

    /** How late the last word change was, for the smoothness measurement. */
    var lastOvershootNanos: Long = 0L
        private set

    /** Number of times playback fell far enough behind to give up on catching up. */
    var resyncCount: Int = 0
        private set

    /** Starts a run with the word already on screen at [frameNanos]. */
    fun start(frameNanos: Long) {
        shownAtNanos = frameNanos
        lastOvershootNanos = 0L
    }

    /** True when the word shown has had its full [durationMillis] by [frameNanos]. */
    fun isDue(frameNanos: Long, durationMillis: Long): Boolean =
        frameNanos - shownAtNanos >= durationMillis * NANOS_PER_MILLI

    /**
     * Records that the next word is now on screen, after the previous one was
     * shown for [durationMillis] and the change was drawn on the frame at
     * [frameNanos].
     *
     * The deadline moves by the duration, not to `frameNanos`, so sub-frame
     * remainders accumulate into the next word instead of being lost. The one
     * exception is a real stall — the app was backgrounded, or the device dropped
     * a long run of frames — where catching up would flash a burst of words past
     * the reader. Past [resyncThresholdNanos] the schedule restarts from this
     * frame and the lost time is simply lost.
     */
    fun advanced(frameNanos: Long, durationMillis: Long) {
        val next = shownAtNanos + durationMillis * NANOS_PER_MILLI
        lastOvershootNanos = frameNanos - next
        if (frameNanos - next > resyncThresholdNanos) {
            shownAtNanos = frameNanos
            resyncCount++
        } else {
            shownAtNanos = next
        }
    }

    companion object {
        const val NANOS_PER_MILLI: Long = 1_000_000L

        /**
         * A quarter of a second behind. Longer than any plausible run of dropped
         * frames, far shorter than a trip to another app, and — at the 1000 WPM
         * ceiling — about four words, which is the most a reader could lose to a
         * hitch without noticing text they never saw.
         */
        const val RESYNC_THRESHOLD_NANOS: Long = 250L * NANOS_PER_MILLI
    }
}
