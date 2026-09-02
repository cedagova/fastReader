package com.cedagova.fastreader.timing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.timing.TimingScenarios.word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The same duration table, replayed on a real device.
 *
 * The engine imports nothing from Android and reads no clock, so this is not
 * about platform APIs — it is about the one thing a desktop JVM genuinely cannot
 * vouch for: that ART's floating-point arithmetic and `roundToLong` produce
 * byte-identical durations. Increment 001 shipped code that was green on the JVM
 * and threw on every device, so "pure Kotlin" is checked here rather than
 * asserted.
 *
 * It is deliberately small: [TimingScenarios.CANONICAL] is the shared table the
 * JVM suite also asserts, so the two cannot drift.
 */
@RunWith(AndroidJUnit4::class)
class RsvpTimingEngineDeviceTest {

    @Test
    fun canonicalDurationsAreIdenticalOnDevice() {
        for (case in TimingScenarios.CANONICAL) {
            assertEquals(
                case.name,
                case.expectedMillis,
                RsvpTimingEngine.durationMillis(case.token, case.settings, case.state),
            )
        }
    }

    @Test
    fun aFullRampIsDeterministicOnDevice() {
        val settings = TimingSettings(wpm = 317, pauseStrength = PauseStrength.NORMAL, rampEnabled = true)

        fun play(): List<Long> {
            var state = TimingState.AT_PLAYBACK_START
            return List(200) { position ->
                val duration = RsvpTimingEngine.durationMillis(word(index = position), settings, state)
                state = state.afterShowing(duration)
                duration
            }
        }

        val first = play()
        assertEquals(first, play())
        // The ramp really did climb and settle at the target: 60000 / 317 = 189 ms.
        assertTrue("ramp must start slower than target", first.first() > 189L)
        assertEquals(189L, first.last())
    }
}
