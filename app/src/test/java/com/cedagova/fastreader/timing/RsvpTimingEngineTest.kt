package com.cedagova.fastreader.timing

import com.cedagova.fastreader.content.Boundary
import com.cedagova.fastreader.content.Token
import com.cedagova.fastreader.content.WordClass
import com.cedagova.fastreader.timing.TimingScenarios.skipMarker
import com.cedagova.fastreader.timing.TimingScenarios.word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timing engine's acceptance proof.
 *
 * Issue #11 specifies this leaf as "tests only", and that is not a shortcut: the
 * engine has no I/O, no clock and no UI, so arithmetic over its public functions
 * is the *lowest capable layer* at which every acceptance criterion can be
 * established. Anything above it would only be re-checking the same numbers
 * through a scheduler that does not exist yet (LEAF203).
 */
class RsvpTimingEngineTest {

    private val steady = TimingSettings(wpm = 250, pauseStrength = PauseStrength.NORMAL, rampEnabled = false)
    private val running = TimingState(elapsedPlaybackMillis = 60_000L, reorientationPending = false)

    // --- The canonical table -------------------------------------------------

    @Test
    fun canonicalDurationsMatchTheResearchDefaults() {
        for (case in TimingScenarios.CANONICAL) {
            assertEquals(
                case.name,
                case.expectedMillis,
                RsvpTimingEngine.durationMillis(case.token, case.settings, case.state),
            )
        }
    }

    // --- REQ-011: modulation and the one pause-strength scaler ---------------

    @Test
    fun sentenceEndHoldsThreeTimesAPlainWordAt250Wpm() {
        val plain = RsvpTimingEngine.durationMillis(word(), steady, running)
        val sentenceEnd = RsvpTimingEngine.durationMillis(word(boundary = Boundary.SENTENCE), steady, running)

        assertEquals(240L, plain)
        assertEquals(3 * plain, sentenceEnd)
    }

    @Test
    fun pauseStrengthOffMakesEveryTokenUniform() {
        val off = steady.copy(pauseStrength = PauseStrength.OFF)
        val durations = mixedStream().map { RsvpTimingEngine.durationMillis(it, off, running) }

        assertEquals("a mixed stream is the point of the check", 8, durations.size)
        assertEquals(setOf(240L), durations.toSet())
    }

    @Test
    fun pauseStrengthScalesOnlyTheExtraPause() {
        val sentence = word(boundary = Boundary.SENTENCE)
        fun at(strength: PauseStrength) =
            RsvpTimingEngine.durationMillis(sentence, steady.copy(pauseStrength = strength), running)

        // The word itself always costs 240 ms; only the 480 ms of extra pause scales.
        assertEquals(240L, at(PauseStrength.OFF))
        assertEquals(240L + 240L, at(PauseStrength.SUBTLE))
        assertEquals(240L + 480L, at(PauseStrength.NORMAL))
        assertEquals(240L + 720L, at(PauseStrength.STRONG))
    }

    @Test
    fun everyBoundaryAndEmphasisUsesItsResearchMultiplier() {
        fun multiplierOf(token: Token): Double =
            RsvpTimingEngine.durationMillis(token, steady, running).toDouble() / 240.0

        assertEquals(1.0, multiplierOf(word()), 0.005)
        assertEquals(RsvpTiming.CLAUSE_MULTIPLIER, multiplierOf(word(boundary = Boundary.CLAUSE)), 0.005)
        assertEquals(RsvpTiming.SENTENCE_MULTIPLIER, multiplierOf(word(boundary = Boundary.SENTENCE)), 0.005)
        assertEquals(RsvpTiming.PARAGRAPH_MULTIPLIER, multiplierOf(word(boundary = Boundary.PARAGRAPH)), 0.005)
        assertEquals(RsvpTiming.HEADING_MULTIPLIER, multiplierOf(word(boundary = Boundary.HEADING)), 0.005)
        for (emphasis in listOf(WordClass.LONG, WordClass.NUMBER, WordClass.ALL_CAPS, WordClass.RARE)) {
            assertEquals(
                "$emphasis",
                RsvpTiming.EMPHASIS_MULTIPLIER,
                multiplierOf(word(classes = setOf(emphasis))),
                0.005,
            )
        }
    }

    @Test
    fun headingHoldsAtLeastAsLongAsASentenceEnd() {
        val heading = RsvpTimingEngine.durationMillis(word(boundary = Boundary.HEADING), steady, running)
        val sentence = RsvpTimingEngine.durationMillis(word(boundary = Boundary.SENTENCE), steady, running)

        assertTrue("heading is specified as >= 4x, never below a sentence", heading >= sentence)
    }

    @Test
    fun emphasisAndBoundaryDoNotCompound() {
        val longSentenceEnd = word(boundary = Boundary.SENTENCE, classes = setOf(WordClass.LONG, WordClass.RARE))
        val plainSentenceEnd = word(boundary = Boundary.SENTENCE)

        assertEquals(
            RsvpTimingEngine.durationMillis(plainSentenceEnd, steady, running),
            RsvpTimingEngine.durationMillis(longSentenceEnd, steady, running),
        )
    }

    @Test
    fun abbreviationIsExemptFromTheSentencePauseButNotFromItsParagraph() {
        val abbreviation = setOf(WordClass.ABBREVIATION)

        assertEquals(
            240L,
            RsvpTimingEngine.durationMillis(
                word(boundary = Boundary.SENTENCE, classes = abbreviation, text = "Dr."),
                steady,
                running,
            ),
        )
        assertEquals(
            840L,
            RsvpTimingEngine.durationMillis(
                word(boundary = Boundary.PARAGRAPH, classes = abbreviation, text = "etc."),
                steady,
                running,
            ),
        )
    }

    @Test
    fun unknownWordClassesFallBackToThePlainWordDuration() {
        val plain = RsvpTimingEngine.durationMillis(word(), steady, running)

        // ABBREVIATION is a parsing marker, not a slow-down: on its own it must not
        // change the duration. It stands in here for any class the engine does not
        // recognise as emphasis.
        assertEquals(
            plain,
            RsvpTimingEngine.durationMillis(word(classes = setOf(WordClass.ABBREVIATION)), steady, running),
        )
        assertEquals(plain, RsvpTimingEngine.durationMillis(word(classes = emptySet()), steady, running))
    }

    @Test
    fun wordsInsideAHeadingReadAtNormalSpeed() {
        // Research puts the >=4x on the *break after* a heading, not on its words.
        // `isHeading` is presentation information for LEAF203, and the engine
        // deliberately ignores it.
        assertEquals(
            240L,
            RsvpTimingEngine.durationMillis(word(isHeading = true, text = "Capítulo"), steady, running),
        )
    }

    @Test
    fun pauseStrengthOffStillHonoursTheRampAndTheHold() {
        // "Off makes all words uniform" is about REQ-011's modulation. Ramp-up and
        // the re-orientation hold are REQ-013 and are not a pause between words, so
        // turning modulation off must not silently disable them too.
        val off = steady.copy(pauseStrength = PauseStrength.OFF, rampEnabled = true)

        assertEquals(300L, RsvpTimingEngine.plainWordMillis(off, TimingState(0L, reorientationPending = false)))
        assertEquals(3 * 240L, RsvpTimingEngine.durationMillis(word(), off, TimingState(60_000L, true)))
    }

    @Test
    fun skipMarkersGetADurationLikeAnyOtherToken() {
        val marker = RsvpTimingEngine.durationMillis(skipMarker(), steady, running)

        assertEquals(840L, marker)
    }

    // --- REQ-012: speed, range, and mid-stream change ------------------------

    @Test
    fun wpmClampsToTheDefinedRange() {
        assertEquals(600L, RsvpTimingEngine.durationMillis(word(), steady.copy(wpm = 0), running))
        assertEquals(600L, RsvpTimingEngine.durationMillis(word(), steady.copy(wpm = -250), running))
        assertEquals(600L, RsvpTimingEngine.durationMillis(word(), steady.copy(wpm = 99), running))
        assertEquals(60L, RsvpTimingEngine.durationMillis(word(), steady.copy(wpm = 1_001), running))
        assertEquals(60L, RsvpTimingEngine.durationMillis(word(), steady.copy(wpm = Int.MAX_VALUE), running))
        assertEquals(RsvpTiming.DEFAULT_WPM, TimingSettings().effectiveWpm)
    }

    @Test
    fun everySpeedInRangeProducesAUsableDuration() {
        for (wpm in RsvpTiming.MIN_WPM..RsvpTiming.MAX_WPM) {
            val settings = TimingSettings(wpm = wpm, pauseStrength = PauseStrength.OFF, rampEnabled = false)
            val duration = RsvpTimingEngine.durationMillis(word(), settings, running)
            assertTrue("wpm=$wpm produced ${duration}ms", duration >= 1L)
        }
    }

    @Test
    fun midStreamWpmChangeScalesPacingWithNoOtherJump() {
        // Mid-ramp, so a naive implementation that restarted the ramp would show up.
        val midRamp = TimingState(elapsedPlaybackMillis = 12_000L, reorientationPending = false)
        val ramped = steady.copy(rampEnabled = true)

        val before = RsvpTimingEngine.plainWordMillis(ramped.copy(wpm = 250), midRamp)
        val after = RsvpTimingEngine.plainWordMillis(ramped.copy(wpm = 500), midRamp)

        // The only change is the target speed: exactly 2x, nothing else moved.
        assertEquals(2.0, before.toDouble() / after.toDouble(), 0.01)
        assertEquals(
            RsvpTimingEngine.rampSpeedFraction(ramped.copy(wpm = 250), midRamp),
            RsvpTimingEngine.rampSpeedFraction(ramped.copy(wpm = 500), midRamp),
            1e-12,
        )
    }

    @Test
    fun rampKeepsClimbingAfterAMidStreamSpeedChange() {
        val stream = List(400) { word(index = it) }
        var state = TimingState.AT_PLAYBACK_START
        val plainDurations = ArrayList<Long>()

        for ((position, token) in stream.withIndex()) {
            val settings = TimingSettings(
                wpm = if (position < 40) 250 else 400,
                pauseStrength = PauseStrength.NORMAL,
                rampEnabled = true,
            )
            plainDurations += RsvpTimingEngine.plainWordMillis(settings, state)
            state = state.afterShowing(RsvpTimingEngine.durationMillis(token, settings, state))
        }

        // Before the change and after it, pacing only ever gets faster: the ramp did
        // not restart and the speed change introduced no slow-down.
        assertTrue(
            "durations must be non-increasing across the change",
            plainDurations.zipWithNext().all { (earlier, later) -> later <= earlier },
        )
        // The change itself is exactly the speed ratio at that instant, not a jump.
        val atChange = plainDurations[40].toDouble() / plainDurations[39].toDouble()
        assertEquals(250.0 / 400.0, atChange, 0.01)
        assertEquals(150L, plainDurations.last())
    }

    // --- REQ-013: ramp-up and re-orientation ---------------------------------

    @Test
    fun playbackOpensAtEightyPercentOfTargetSpeed() {
        val ramped = steady.copy(rampEnabled = true)
        val start = TimingState(elapsedPlaybackMillis = 0L, reorientationPending = false)

        assertEquals(RsvpTiming.RAMP_START_SPEED_FRACTION, RsvpTimingEngine.rampSpeedFraction(ramped, start), 1e-12)
        assertEquals(300L, RsvpTimingEngine.plainWordMillis(ramped, start))
    }

    @Test
    fun rampReachesTargetWithinTheDocumentedWindow() {
        val ramped = TimingSettings(wpm = 250, pauseStrength = PauseStrength.NORMAL, rampEnabled = true)
        val target = 240L
        var state = TimingState.AT_PLAYBACK_START
        var firstAtTargetMillis: Long? = null
        var previous = Long.MAX_VALUE

        repeat(400) { position ->
            val plain = RsvpTimingEngine.plainWordMillis(ramped, state)
            assertTrue("pacing must never slow down while ramping", plain <= previous)
            previous = plain
            if (firstAtTargetMillis == null && plain == target) firstAtTargetMillis = state.elapsedPlaybackMillis
            state = state.afterShowing(RsvpTimingEngine.durationMillis(word(index = position), ramped, state))
        }

        val reachedAt = requireNotNull(firstAtTargetMillis) { "never reached target speed" }
        assertTrue("reached target after ${reachedAt}ms of playback", reachedAt in 15_000L..30_000L)
    }

    @Test
    fun rampIsOffWhenDisabled() {
        val start = TimingState(elapsedPlaybackMillis = 0L, reorientationPending = false)

        assertEquals(1.0, RsvpTimingEngine.rampSpeedFraction(steady, start), 1e-12)
        assertEquals(240L, RsvpTimingEngine.plainWordMillis(steady, start))
    }

    @Test
    fun reorientationHoldsTheFirstWordAndOnlyTheFirstWord() {
        var state = TimingState.AT_PLAYBACK_START.copy(elapsedPlaybackMillis = 60_000L)

        val first = RsvpTimingEngine.durationMillis(word(index = 0), steady, state)
        state = state.afterShowing(first)
        val second = RsvpTimingEngine.durationMillis(word(index = 1), steady, state)

        assertEquals(3 * 240L, first)
        assertEquals(240L, second)
    }

    @Test
    fun jumpingReArmsTheHoldWithoutRestartingTheRamp() {
        val ramped = steady.copy(rampEnabled = true)
        val warm = TimingState(elapsedPlaybackMillis = 25_000L, reorientationPending = false)

        val afterJump = warm.reorienting()
        assertEquals(3 * 240L, RsvpTimingEngine.durationMillis(word(), ramped, afterJump))
        assertEquals(1.0, RsvpTimingEngine.rampSpeedFraction(ramped, afterJump), 1e-12)

        // Pressing play from a stop is the other case: the ramp does restart.
        assertEquals(
            RsvpTiming.RAMP_START_SPEED_FRACTION,
            RsvpTimingEngine.rampSpeedFraction(ramped, TimingState.AT_PLAYBACK_START),
            1e-12,
        )
    }

    @Test
    fun theHoldAddsToThePauseInsteadOfMultiplyingIt() {
        val holding = TimingState(elapsedPlaybackMillis = 60_000L, reorientationPending = true)
        val sentenceEnd = word(boundary = Boundary.SENTENCE)

        // 240 * 3 (the hold) + 240 * 2 (the sentence pause), not 240 * 3 * 3.
        assertEquals(720L + 480L, RsvpTimingEngine.durationMillis(sentenceEnd, steady, holding))
    }

    // --- Determinism and the state contract ----------------------------------

    @Test
    fun theSameInputsAlwaysProduceTheSameDurations() {
        val settings = TimingSettings(wpm = 317, pauseStrength = PauseStrength.SUBTLE, rampEnabled = true)
        val stream = List(500) { position ->
            when (position % 7) {
                0 -> word(index = position, boundary = Boundary.CLAUSE)
                1 -> word(index = position, boundary = Boundary.SENTENCE)
                2 -> word(index = position, classes = setOf(WordClass.RARE))
                3 -> word(index = position, boundary = Boundary.PARAGRAPH)
                4 -> skipMarker(index = position)
                5 -> word(index = position, boundary = Boundary.HEADING, isHeading = true)
                else -> word(index = position)
            }
        }

        fun play(): List<Long> {
            var state = TimingState.AT_PLAYBACK_START
            return stream.map { token ->
                val duration = RsvpTimingEngine.durationMillis(token, settings, state)
                state = state.afterShowing(duration)
                duration
            }
        }

        assertEquals(play(), play())
        assertTrue("a real stream must not be uniform", play().toSet().size > 1)
    }

    @Test
    fun advancingStateAccumulatesExactlyWhatWasShown() {
        val first = TimingState.AT_PLAYBACK_START.afterShowing(300L)
        val second = first.afterShowing(240L)

        assertEquals(300L, first.elapsedPlaybackMillis)
        assertEquals(540L, second.elapsedPlaybackMillis)
        assertEquals(false, second.reorientationPending)
        // A scheduler that reports a nonsense duration must not rewind the ramp.
        assertEquals(540L, second.afterShowing(-1_000L).elapsedPlaybackMillis)
    }

    private fun mixedStream(): List<Token> = listOf(
        word(index = 0),
        word(index = 1, boundary = Boundary.CLAUSE),
        word(index = 2, boundary = Boundary.SENTENCE),
        word(index = 3, boundary = Boundary.PARAGRAPH),
        word(index = 4, boundary = Boundary.HEADING, isHeading = true),
        word(index = 5, classes = setOf(WordClass.LONG, WordClass.RARE)),
        word(index = 6, classes = setOf(WordClass.NUMBER)),
        skipMarker(index = 7),
    )
}
