package com.cedagova.fastreader.settings.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedagova.fastreader.R
import com.cedagova.fastreader.content.Boundary
import com.cedagova.fastreader.content.Token
import com.cedagova.fastreader.content.WordToken
import com.cedagova.fastreader.reader.ui.CueWord
import com.cedagova.fastreader.reader.ui.ReaderWord
import com.cedagova.fastreader.settings.CueSettings
import com.cedagova.fastreader.timing.PauseStrength
import com.cedagova.fastreader.timing.RsvpTiming
import com.cedagova.fastreader.timing.RsvpTimingEngine
import com.cedagova.fastreader.timing.TimingSettings
import com.cedagova.fastreader.timing.TimingState
import kotlinx.coroutines.delay

/**
 * The live preview (REQ-023): the settings above, applied to a real stream.
 *
 * Every cue and timing setting changes something here as it is adjusted, and each
 * one is shown by the mechanism it actually affects rather than by a picture of
 * it:
 *
 * - **Pivot cue, its colour, guide marks, font size** change how the word is
 *   *drawn*, so this draws it with [CueWord] — the same composable the reader
 *   uses, given the same [CueSettings]. There is no second renderer to drift.
 * - **Pause strength** changes the *rhythm*, which a still word cannot show. So
 *   the sample streams: the word changes on the duration
 *   [RsvpTimingEngine] gives that token at the chosen strength, and the reader
 *   watches the sentence break stretch or vanish. The readout under it states the
 *   same thing in numbers, measured from the engine, so the effect is legible
 *   before the loop reaches a boundary and provable in a still golden.
 *
 * ## AD-6 still holds here
 *
 * The preview is a text swap inside a fixed box, exactly like the reading
 * surface: no animation API, no background that tracks the word, nothing that
 * changes size. It runs at the default 250 WPM regardless of the reader's
 * reading speed, because it is a demonstration of *rhythm*, not a second speed
 * control.
 */
@Composable
fun SettingsPreview(
    cues: CueSettings,
    pauseStrength: PauseStrength,
    modifier: Modifier = Modifier,
    /**
     * Hold this token instead of streaming. The Roborazzi goldens pass it so the
     * captured frame is deterministic; the app never does.
     */
    heldTokenIndex: Int? = null,
) {
    var position by remember { mutableIntStateOf(heldTokenIndex ?: 0) }
    val timing = remember(pauseStrength) { previewTiming(pauseStrength) }

    if (heldTokenIndex == null) {
        // Keyed on the strength so changing it restarts the loop immediately with
        // the new durations rather than after the current word's old one.
        LaunchedEffect(pauseStrength) {
            position = 0
            while (true) {
                delay(RsvpTimingEngine.durationMillis(SAMPLE[position], timing, STEADY))
                position = (position + 1) % SAMPLE.size
            }
        }
    }

    // One static label for the whole box. Without it TalkBack would announce a
    // different word four times a second while the preview loops, which is a
    // screen a blind reader could not get past (REQ-060). What the preview
    // demonstrates is visual; the readout below it carries the part that is not.
    val label = stringResource(R.string.settings_preview_label)
    // The box is measured in `sp`, not `dp`, because what it holds is text: at a
    // large font scale a fixed-height box would have the word drawn outside it and
    // over the rest of the page. Converting through the current density means the
    // preview grows with the words it is previewing.
    val height = with(LocalDensity.current) {
        (cues.wordSizeSp * PREVIEW_HEIGHT_LINES).sp.toDp() + GuideMarkAllowance
    }
    Column(modifier = modifier.fillMaxWidth().testTag("settings_preview")) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clearAndSetSemantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            CueWord(
                token = SAMPLE[position.coerceIn(SAMPLE.indices)].toReaderWord(),
                cues = cues,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = rhythmLabel(timing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .testTag("settings_preview_rhythm"),
        )
    }
}

/**
 * What the chosen pause strength does, in milliseconds, measured from the engine
 * rather than restated here.
 *
 * At `OFF` both numbers are equal, which is REQ-011's acceptance — "all words
 * uniform" — stated as a fact the reader can read rather than a claim.
 */
@Composable
private fun rhythmLabel(timing: TimingSettings): String {
    val plain = RsvpTimingEngine.plainWordMillis(timing, STEADY)
    val sentence = RsvpTimingEngine.durationMillis(SENTENCE_END, timing, STEADY)
    return if (plain == sentence) {
        stringResource(R.string.settings_rhythm_uniform, timing.effectiveWpm, plain)
    } else {
        stringResource(R.string.settings_rhythm, timing.effectiveWpm, plain, sentence)
    }
}

private fun previewTiming(pauseStrength: PauseStrength) = TimingSettings(
    wpm = RsvpTiming.DEFAULT_WPM,
    pauseStrength = pauseStrength,
    // The ramp is a property of settling into a book, not of a two-second loop:
    // with it on, the preview would open 20% slow and the readout would describe
    // a speed the loop is not running at.
    rampEnabled = false,
)

/** Warmed up and not re-orienting, so the preview shows steady-state rhythm. */
private val STEADY = TimingState(
    elapsedPlaybackMillis = RsvpTiming.RAMP_DURATION_MILLIS,
    reorientationPending = false,
)

private fun Token.toReaderWord(): ReaderWord = when (this) {
    is WordToken -> ReaderWord(
        text = displayText,
        coreStart = coreStart,
        coreEnd = coreEnd,
    )
    else -> ReaderWord(text = displayText)
}

/**
 * The sample stream, written out rather than parsed.
 *
 * Fifteen tokens carrying the three boundaries pause strength actually scales — a
 * clause comma, a full stop, and the paragraph end that closes the loop — so the
 * difference between `off` and `strong` is visible within one pass. Building them
 * by hand keeps the preview independent of the EPUB pipeline and makes the
 * durations in `SettingsPreviewTest` exact.
 */
private val SAMPLE: List<Token> = listOf(
    sample(0, "Words"),
    sample(1, "appear"),
    sample(2, "here", trailing = ",", boundary = Boundary.CLAUSE),
    sample(3, "one"),
    sample(4, "at"),
    sample(5, "a"),
    sample(6, "time", trailing = ",", boundary = Boundary.CLAUSE),
    sample(7, "in"),
    sample(8, "a"),
    sample(9, "fixed"),
    sample(10, "place", trailing = ".", boundary = Boundary.SENTENCE, sentence = 0),
    sample(11, "Your", sentence = 1),
    sample(12, "eyes", sentence = 1),
    sample(13, "stay", sentence = 1),
    sample(14, "still", trailing = ".", boundary = Boundary.PARAGRAPH, sentence = 1),
)

/** The token the rhythm readout measures: an ordinary word that ends a sentence. */
private val SENTENCE_END: Token = SAMPLE[10]

private fun sample(
    index: Int,
    text: String,
    trailing: String = "",
    boundary: Boundary = Boundary.NONE,
    sentence: Int = 0,
): WordToken = WordToken(
    index = index,
    text = text,
    chapterIndex = 0,
    paragraphIndex = 0,
    sentenceIndex = sentence,
    boundary = boundary,
    trailing = trailing,
)

/**
 * How much taller than the word's own size the preview box is, as a multiple of
 * it. Comfortable room for one line of glyphs including descenders.
 */
private const val PREVIEW_HEIGHT_LINES = 1.8f

/**
 * Room for the guide marks under the word. Reserved whether or not they are on, so
 * toggling them does not move everything below the preview — the marks themselves
 * are laid out in `dp` by the cue layer, so this is too.
 */
private val GuideMarkAllowance = 24.dp

/** The token the goldens hold, so a captured preview is one deterministic frame. */
internal const val PREVIEW_HELD_TOKEN = 1
