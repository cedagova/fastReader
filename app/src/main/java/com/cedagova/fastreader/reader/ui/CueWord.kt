package com.cedagova.fastreader.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedagova.fastreader.settings.CueSettings
import com.cedagova.fastreader.settings.PivotColor

/**
 * The cue layer: one streamed token, drawn with the cues the reader has left
 * enabled.
 *
 * This is the presentation slot increment 002 left open in [ReaderScreen]. It
 * replaces the plain centred word with four things and changes nothing else —
 * playback, navigation and layout stay exactly where LEAF203 put them:
 *
 * 1. **Letter highlight (REQ-020).** The word's recognition point is drawn in the
 *    chosen colour, so the eye has a place to land. On by default.
 * 2. **Fixed focus alignment (REQ-020, opt-in).** The word is shifted so that
 *    letter sits on a fixed column slightly left of centre instead of being
 *    centred. **Off by default** — see [CueSettings] for the owner decision.
 * 3. **Guide marks (REQ-021).** An optional original mark under the column —
 *    see [drawGuideMarks] for the design and how it differs from Spritz's.
 * 4. **Shrink-to-fit.** A word too wide for the space is drawn smaller, never
 *    clipped and never wrapped.
 *
 * The highlight and the alignment are independent: a centred word carries its
 * coloured letter exactly as an aligned one does. Only where the word sits
 * changes.
 *
 * ## AD-6 — this stays a static-luminance surface
 *
 * Everything drawn here is either a glyph or a mark at a fixed position and
 * fixed size. The column does not move within a session, the guide marks are
 * identical pixels on every frame, there is no animation, no background fill, and
 * no element whose area tracks the word. What changes between two frames at
 * 1000 WPM is glyphs, exactly as it was before cues existed. Centring the word by
 * default moves the column once, at a setting change; it adds nothing to the
 * frame path.
 */
@Composable
fun CueWord(
    token: ReaderWord,
    cues: CueSettings = CueSettings(),
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val markColor = MaterialTheme.colorScheme.onSurfaceVariant
    val pivotColor = cues.pivotColor.resolve()
    val bodyColor = if (token.isSkipMarker) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onBackground
    }

    // A skip marker is the app talking, not the book (REQ-015): it has no
    // recognition point, so it is never aligned on one and never carries a
    // coloured letter.
    val aligned = cues.focusAlignmentEnabled && !token.isSkipMarker
    val highlighted = cues.highlightEnabled && !token.isSkipMarker

    BoxWithConstraints(
        modifier = modifier.semantics { contentDescription = token.text },
    ) {
        val baseSize = cues.wordSizeSp.sp
        val baseLineHeight = measurer.measure("Ag", TextStyle(fontSize = baseSize)).size.height.toFloat()
        val markBand = with(density) { if (cues.guideMarksEnabled) GuideMarkBand.toPx() else 0f }

        val width = constraints.maxWidth.toFloat()
        // An unbounded slot would otherwise centre the word in a region the size of
        // Int.MAX_VALUE, which is to say off the screen. The reader gives this a
        // bounded height; a caller that does not gets one line plus the marks.
        val height = if (constraints.hasBoundedHeight) {
            constraints.maxHeight.toFloat()
        } else {
            baseLineHeight + markBand
        }
        if (width <= 0f || height <= 0f || !constraints.hasBoundedWidth) return@BoxWithConstraints

        val wordAreaHeight = (height - markBand).coerceAtLeast(1f)

        // Two columns, and they are not the same thing. The *marks* hold the
        // column the stream is aligned to, which is a property of the layout and
        // of nothing else: it must not move when a skip marker goes past, or the
        // eye's anchor jumps at exactly the moment it is meant to be holding
        // still for the next real word. Without the opt-in alignment that column
        // is the centre of the reading width. The *word* is placed on it only
        // when the alignment is on and it has a recognition point to put there.
        val columnX = width * if (cues.focusAlignmentEnabled) FOCUS_COLUMN_FRACTION else 0.5f
        val alignX = if (aligned) columnX else width * 0.5f
        val style = TextStyle(
            fontSize = baseSize,
            fontStyle = if (token.isSkipMarker) FontStyle.Italic else FontStyle.Normal,
            fontWeight = if (token.isHeading) FontWeight.Bold else FontWeight.Normal,
            color = bodyColor,
        )
        // Two offsets from the one recognition point, because the two cues are
        // independent. `anchorOffset` is a *placement* constraint and exists only
        // while the word is being held on the column; `highlightOffset` is the
        // letter that gets the colour, in either alignment.
        val recognitionOffset = token.pivotOffset()
        val anchorOffset = if (aligned) recognitionOffset else null
        val highlightOffset = if (highlighted) recognitionOffset else null
        val text = annotate(token, highlightOffset, pivotColor)

        val fitted = fitToSpace(
            measurer = measurer,
            text = text,
            style = style,
            pivotOffset = anchorOffset,
            width = width,
            height = wordAreaHeight,
            alignX = alignX,
            minSizeSp = MIN_WORD_SIZE_SP,
        )

        val layout = fitted.layout
        val anchor = fitted.anchor
        val x = if (layout.size.width >= width) {
            (width - layout.size.width) / 2f
        } else {
            (alignX - anchor).coerceIn(0f, width - layout.size.width)
        }
        val y = (wordAreaHeight - layout.size.height) / 2f

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawText(layout, topLeft = Offset(x, y))
            if (cues.guideMarksEnabled) {
                drawGuideMarks(
                    alignX = columnX,
                    railY = wordAreaHeight / 2f + baseLineHeight / 2f + with(density) { GuideMarkGap.toPx() },
                    railWidth = width * GUIDE_RAIL_FRACTION,
                    color = markColor,
                    density = density.density,
                )
            }
        }
    }
}

/**
 * The guide marks (REQ-021) — an original design, deliberately unlike Spritz's.
 *
 * **What is drawn.** One hairline rail parallel to the text, *below* the word,
 * spanning a fixed fraction of the reading width and centred on the alignment
 * column; and one small solid triangle standing on that rail, apex pointing up at
 * the pivot letter. Nothing is drawn above the word, nothing vertical is drawn,
 * and nothing encloses the word.
 *
 * **How that differs from US 8,903,174.** The patent's FIG. 2a marks the fixed
 * display location with "hash marks" — depicted as a short vertical tick above
 * the word and another below it — and its only narrowed visual claim (claim 33)
 * is "vertical lines above and below the fixed display location". Spritz's
 * shipped "Redicle" adds full-width horizontal rules above and below the word,
 * boxing it in. This design shares none of that shape:
 *
 * | | Spritz redicle / claim 33 | fastReader guide marks |
 * | --- | --- | --- |
 * | Where | above **and** below the word | below only |
 * | Form | vertical lines, perpendicular to the text | a horizontal rail parallel to the text, plus a triangle |
 * | Enclosure | full-width rules top and bottom frame the word | no frame, no rule above, nothing spanning the width |
 * | Marker shape | a line segment pointing in from each edge | a filled caret standing under the line, like a cursor |
 *
 * **Why it is drawn this way.** Marks below the text do not sit in the path of
 * the returning eye, and a caret reads as "here" rather than as a bracket. Their
 * size and position depend only on the reading width and on whether the
 * fixed-focus alignment is on — never on the token being drawn — so they are the same pixels on
 * every frame of a running stream and add no per-word change to the AD-6
 * static-luminance surface.
 */
private fun DrawScope.drawGuideMarks(
    alignX: Float,
    railY: Float,
    railWidth: Float,
    color: Color,
    density: Float,
) {
    val railThickness = 1f * density
    val railStart = (alignX - railWidth / 2f).coerceAtLeast(0f)
    val railEnd = (alignX + railWidth / 2f).coerceAtMost(size.width)
    drawRect(
        color = color.copy(alpha = RAIL_ALPHA),
        topLeft = Offset(railStart, railY),
        size = Size(railEnd - railStart, railThickness),
    )

    val notchHalfBase = 5f * density
    val notchHeight = 7f * density
    val notch = Path().apply {
        moveTo(alignX, railY - notchHeight)
        lineTo(alignX - notchHalfBase, railY)
        lineTo(alignX + notchHalfBase, railY)
        close()
    }
    drawPath(notch, color = color.copy(alpha = NOTCH_ALPHA))
}

/** The measured word plus the x, within it, that must land on the alignment column. */
private class FittedWord(val layout: TextLayoutResult, val anchor: Float)

/**
 * Shrink-to-fit: the largest size at or below the reader's chosen one at which the
 * word still fits, never truncated and never wrapped.
 *
 * Text width is very close to linear in font size, so scaling by the overflow
 * ratio lands within a pixel or two in one step; the loop runs a few times to
 * absorb hinting and then stops. It is deterministic, which is what lets the
 * overflow golden be a regression gate.
 *
 * With the fixed-focus alignment on, "fits" is stricter than "narrower than the
 * box": [pivotOffset] is then the anchor being held on the column, so the part
 * left of it has to fit left of the column and the part right of it right of the
 * column. A centred word has no anchor and only has to fit the box.
 */
private fun fitToSpace(
    measurer: TextMeasurer,
    text: AnnotatedString,
    style: TextStyle,
    pivotOffset: Int?,
    width: Float,
    height: Float,
    alignX: Float,
    minSizeSp: Float,
): FittedWord {
    var sizeSp = style.fontSize.value
    var layout = measure(measurer, text, style, sizeSp)
    var anchor = anchorIn(layout, pivotOffset)

    var attempt = 0
    while (attempt < MAX_FIT_ATTEMPTS && sizeSp > minSizeSp) {
        val leftBudget = if (pivotOffset == null) Float.MAX_VALUE else alignX
        val rightBudget = if (pivotOffset == null) Float.MAX_VALUE else width - alignX
        val wide = layout.size.width.toFloat()
        val scale = minOf(
            if (wide <= 0f) 1f else width / wide,
            if (layout.size.height <= 0) 1f else height / layout.size.height,
            if (anchor <= 0f) 1f else leftBudget / anchor,
            if (wide - anchor <= 0f) 1f else rightBudget / (wide - anchor),
        )
        if (scale >= 1f) break
        sizeSp = (sizeSp * scale * FIT_UNDERSHOOT).coerceAtLeast(minSizeSp)
        layout = measure(measurer, text, style, sizeSp)
        anchor = anchorIn(layout, pivotOffset)
        attempt++
    }
    return FittedWord(layout, anchor)
}

private fun measure(
    measurer: TextMeasurer,
    text: AnnotatedString,
    style: TextStyle,
    sizeSp: Float,
): TextLayoutResult = measurer.measure(
    text = text,
    style = style.copy(fontSize = sizeSp.sp),
    softWrap = false,
    maxLines = 1,
    constraints = Constraints(),
)

/** Where the alignment column falls inside the drawn word: the anchor glyph's centre. */
private fun anchorIn(layout: TextLayoutResult, pivotOffset: Int?): Float {
    if (pivotOffset == null) return layout.size.width / 2f
    val box = layout.getBoundingBox(pivotOffset)
    return (box.left + box.right) / 2f
}

/** Colours the recognition letter, in whichever alignment the word is drawn. */
private fun annotate(token: ReaderWord, highlightOffset: Int?, pivotColor: Color): AnnotatedString {
    if (highlightOffset == null) return AnnotatedString(token.text)
    // A character outside the Basic Multilingual Plane is two `Char`s; colouring
    // one half of it would draw a replacement glyph instead of the letter.
    val end = if (
        token.text[highlightOffset].isHighSurrogate() && highlightOffset + 1 < token.text.length
    ) {
        highlightOffset + 2
    } else {
        highlightOffset + 1
    }
    return buildAnnotatedString {
        append(token.text.substring(0, highlightOffset))
        withStyle(SpanStyle(color = pivotColor)) { append(token.text.substring(highlightOffset, end)) }
        append(token.text.substring(end))
    }
}

/** The palette, resolved for the page it is drawn on. */
@Composable
internal fun PivotColor.resolve(): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (this) {
        PivotColor.ACCENT -> MaterialTheme.colorScheme.primary
        PivotColor.CRIMSON -> if (dark) Color(0xFFFF8A80) else Color(0xFFC62828)
        PivotColor.AMBER -> if (dark) Color(0xFFFFC46B) else Color(0xFFB26A00)
        PivotColor.TEAL -> if (dark) Color(0xFF4DB6AC) else Color(0xFF00695C)
        PivotColor.VIOLET -> if (dark) Color(0xFFCE93D8) else Color(0xFF6A1B9A)
    }
}

/**
 * Where the recognition point sits across the reading width **when the opt-in
 * fixed-focus alignment is on**.
 *
 * "Slightly left of centre" — far enough left that a long word's tail has room,
 * not so far that short words drift to the edge. With the alignment off, which is
 * the default, the column is the centre of the reading width instead and this is
 * unused.
 */
private const val FOCUS_COLUMN_FRACTION = 0.42f

private const val GUIDE_RAIL_FRACTION = 0.34f

private const val RAIL_ALPHA = 0.40f

private const val NOTCH_ALPHA = 0.65f

/** Vertical room reserved below the word when the guide marks are on. */
private val GuideMarkBand = 24.dp

/** Distance from the bottom of the word's line box to the rail. */
private val GuideMarkGap = 10.dp

/** The floor shrink-to-fit will not go below; past this a word is unreadable anyway. */
private const val MIN_WORD_SIZE_SP = 10f

private const val MAX_FIT_ATTEMPTS = 4

/** Shrink a hair past the computed ratio, so rounding cannot leave a pixel hanging out. */
private const val FIT_UNDERSHOOT = 0.98f
