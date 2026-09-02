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
 * The cue layer (LEAF301): one streamed token, drawn with the cues the reader has
 * left enabled.
 *
 * This is the presentation slot increment 002 left open in [ReaderScreen]. It
 * replaces the plain centred word with three things and changes nothing else —
 * playback, navigation and layout stay exactly where LEAF203 put them:
 *
 * 1. **Pivot alignment (REQ-020).** The word's recognition point is held at a
 *    fixed column slightly left of centre, so the eye does not travel between
 *    words, and that letter is drawn in the accent colour.
 * 2. **Guide marks (REQ-021).** An optional original mark under that column —
 *    see [drawGuideMarks] for the design and how it differs from Spritz's.
 * 3. **Shrink-to-fit.** A word too wide for the space is drawn smaller, never
 *    clipped and never wrapped.
 *
 * ## AD-6 — this stays a static-luminance surface
 *
 * Everything drawn here is either a glyph or a mark at a fixed position and
 * fixed size. The pivot column does not move, the guide marks are identical
 * pixels on every frame, there is no animation, no background fill, and no
 * element whose area tracks the word. What changes between two frames at
 * 1000 WPM is glyphs, exactly as it was before cues existed.
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
    // recognition point, so it is never pivot-aligned and never carries a
    // coloured letter. Guide marks stay put, because they mark the column the
    // next real word will arrive on.
    val aligned = cues.pivotEnabled && !token.isSkipMarker

    BoxWithConstraints(
        modifier = modifier.semantics { contentDescription = token.text },
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        if (width <= 0f || height <= 0f) return@BoxWithConstraints

        val baseSize = cues.wordSizeSp.sp
        val baseLineHeight = measurer.measure("Ag", TextStyle(fontSize = baseSize)).size.height.toFloat()
        val markBand = with(density) { if (cues.guideMarksEnabled) GuideMarkBand.toPx() else 0f }
        val wordAreaHeight = (height - markBand).coerceAtLeast(1f)

        val alignX = width * if (aligned) PIVOT_COLUMN_FRACTION else 0.5f
        val style = TextStyle(
            fontSize = baseSize,
            fontStyle = if (token.isSkipMarker) FontStyle.Italic else FontStyle.Normal,
            fontWeight = if (token.isHeading) FontWeight.Bold else FontWeight.Normal,
            color = bodyColor,
        )
        val pivotOffset = if (aligned) token.pivotOffset() else null
        val text = annotate(token, pivotOffset, pivotColor)

        val fitted = fitToSpace(
            measurer = measurer,
            text = text,
            style = style,
            pivotOffset = pivotOffset,
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
                    alignX = alignX,
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
 * the returning eye, a caret reads as "here" rather than as a bracket, and both
 * marks have a fixed size and position, so they add no per-word pixel change to
 * the AD-6 static-luminance surface.
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
 * With the pivot cue on, "fits" is stricter than "narrower than the box": the
 * part left of the pivot has to fit left of the pivot column and the part right
 * of it right of the column, because the column is fixed.
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

/** Where the alignment column falls inside the drawn word: the pivot glyph's centre. */
private fun anchorIn(layout: TextLayoutResult, pivotOffset: Int?): Float {
    if (pivotOffset == null) return layout.size.width / 2f
    val box = layout.getBoundingBox(pivotOffset)
    return (box.left + box.right) / 2f
}

private fun annotate(token: ReaderWord, pivotOffset: Int?, pivotColor: Color): AnnotatedString {
    if (pivotOffset == null) return AnnotatedString(token.text)
    return buildAnnotatedString {
        append(token.text.substring(0, pivotOffset))
        withStyle(SpanStyle(color = pivotColor)) {
            append(token.text.substring(pivotOffset, pivotOffset + 1))
        }
        append(token.text.substring(pivotOffset + 1))
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
 * Where the recognition point sits across the reading width.
 *
 * "Slightly left of centre", per the definition — far enough left that a long
 * word's tail has room, not so far that short words drift to the edge.
 */
private const val PIVOT_COLUMN_FRACTION = 0.42f

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
