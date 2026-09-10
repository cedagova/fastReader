package com.cedagova.fastreader.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * REQ-205's one breakpoint, and the only place either screen decides it.
 *
 * ## The rule
 *
 * **A window at least [WideLayoutMinWidth] wide gets the wide layout.** One
 * width comparison covers both halves of the requirement, because "in landscape"
 * and "tablet width" are the same condition measured the same way: every phone in
 * the AVD matrix turned on its side is wider than 600 dp (the 720p
 * `Phone_Low_API33` is the narrowest at 640 dp), and `Tablet_Low_API33` is exactly
 * 600 dp wide in portrait. So there is no orientation term at all — the app asks
 * how much width it has, not which way the device is being held, which is also
 * the only form of the question a split-screen or free-form window can answer
 * honestly.
 *
 * ## Why the comparison is in pixels
 *
 * `600.dp` is the classic off-by-one: a device that is *exactly* 600 dp wide
 * converts to a pixel width, and converting that pixel width back to `Dp` can land
 * on 599.99997 for any density whose product is not exact. Comparing `Dp` values
 * would then put the boundary device — the one AVD in the matrix built to sit on
 * it — on the wrong side of its own breakpoint. Both sides are therefore rounded
 * to whole pixels through the same [androidx.compose.ui.unit.Density], so exactly
 * 600 dp is wide and 599 dp is not, on every density.
 *
 * ## What it is measured against
 *
 * The window, not the content area: this wraps the screen's `Scaffold` rather than
 * sitting inside it, so the answer does not change when a banner appears or when
 * a system bar inset grows.
 */
val WideLayoutMinWidth: Dp = 600.dp

/**
 * The width the content is being laid out in, and whether that is [wide].
 *
 * A value rather than a bare `Boolean` because the wide layouts also need the
 * number: the reader sizes its control column from it (see
 * `readerControlsWidth`), and a golden that renders one of these screens at a
 * width can be read against the same arithmetic.
 */
data class LayoutWidth(val available: Dp, val wide: Boolean)

/**
 * Measures the window and hands [content] the decision, once, at the top of a
 * screen.
 *
 * Unbounded width — which no window has, but a preview or a scrolling parent can
 * hand a composable — is deliberately **not** wide: an infinite measurement is the
 * absence of an answer, and the narrow layout is the one that survives being wrong
 * about it.
 */
@Composable
fun WidthAware(modifier: Modifier = Modifier, content: @Composable (LayoutWidth) -> Unit) {
    BoxWithConstraints(modifier) {
        val minWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) {
            WideLayoutMinWidth.roundToPx()
        }
        val wide = constraints.hasBoundedWidth && constraints.maxWidth >= minWidthPx
        content(LayoutWidth(available = maxWidth, wide = wide))
    }
}
