package com.cedagova.fastreader.reader.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cedagova.fastreader.timing.RsvpTiming
import kotlin.math.roundToInt

/**
 * REQ-108 — the focused-mode speed gesture, reduced to arithmetic.
 *
 * The gesture itself is a vertical drag on the reading surface (see
 * [ReaderScreen]); everything about *what it means* lives here, as plain Kotlin
 * with no Compose runtime, so the two things that actually go wrong with a
 * stepped drag — a step emitted twice, and a step that escapes the speed range —
 * are provable without a renderer or a device.
 *
 * ## Why a vertical drag
 *
 * The reading surface already carries the only two gestures v1 has: a tap plays
 * or pauses, a long press hides or restores the chrome. A third one has to miss
 * both of those and miss the system's own gestures:
 *
 * - It is not a tap and not a long press, so `combinedClickable` and this cannot
 *   both fire — a drag past touch slop consumes the pointer and cancels the
 *   press, and a press that never moves never reaches here.
 * - It is not horizontal, so it does not compete with the system's edge-swipe
 *   back navigation, which is the reason [ReaderScreen] documents v1's two
 *   gestures as deliberately not swipes.
 * - It starts wherever the reader's thumb already is, inside the surface, rather
 *   than at a screen edge, so it does not reach the notification shade or the
 *   home/app-switcher gesture either.
 *
 * Up is faster, down is slower: the same direction sense as every volume and
 * brightness control on the device.
 */

/** Speed lands on round 25 WPM steps, on the slider (REQ-012) and on the gesture alike. */
const val SPEED_STEP_WPM: Int = 25

/**
 * How far the reader drags for one 25 WPM step.
 *
 * A full screen height is roughly the whole 100–1000 WPM range, so the range is
 * reachable in one long drag while a small correction stays a small movement.
 * Comfortably above Compose's touch slop, so the first step needs a deliberate
 * movement rather than the wobble of a tap.
 */
val SpeedStepDistance: Dp = 40.dp

/**
 * The speed [steps] above or below [currentWpm], clamped to the v1 range.
 *
 * Snapped to the 25 WPM grid first, because a speed restored from a v1 store was
 * not necessarily written by this app's slider, and a gesture that produced 273
 * WPM would make the readout look broken. Clamping rather than wrapping is the
 * issue's stated edge behavior: a gesture at the limit shows the limit and does
 * nothing else.
 */
fun steppedWpm(currentWpm: Int, steps: Int): Int {
    val onGrid = (currentWpm.toFloat() / SPEED_STEP_WPM).roundToInt() * SPEED_STEP_WPM
    return (onGrid + steps * SPEED_STEP_WPM).coerceIn(RsvpTiming.MIN_WPM, RsvpTiming.MAX_WPM)
}

/**
 * Turns one continuous drag into whole speed steps.
 *
 * Stateful because a drag arrives as a stream of small deltas, and the obvious
 * implementation — carry a remainder, subtract a step each time it overflows —
 * accumulates float error over a long drag and can emit a step twice at the
 * boundary. This instead keeps the *total* travel and reports how many step
 * thresholds that total has crossed since the last report, so a step is emitted
 * exactly once no matter how the pointer deltas are sliced, and reversing the
 * drag gives the steps back in the same places.
 *
 * One instance per drag surface; [begin] resets it at each drag start.
 */
class SpeedDrag(private val stepPixels: Float) {

    private var travel = 0f
    private var emitted = 0

    /** A new drag has started; nothing carries over from the last one. */
    fun begin() {
        travel = 0f
        emitted = 0
    }

    /**
     * Adds one pointer delta, in pixels with the screen's sign (positive is
     * downward), and returns the steps to apply now: positive is faster.
     */
    fun drag(deltaY: Float): Int {
        travel += deltaY
        // Truncation toward zero, so a step costs a full [stepPixels] in either
        // direction and crossing back over a threshold takes the step back.
        val crossed = (-travel / stepPixels).toInt()
        val steps = crossed - emitted
        emitted = crossed
        return steps
    }
}
