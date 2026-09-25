package com.cedagova.fastreader.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing scale every screen lays itself out on (A197-F006).
 *
 * One name per step, so a screen says *how much* room it wants rather than a
 * number, and a later change to the rhythm is one edit here instead of a search
 * through every screen. The values are the ones the screens already used; moving
 * them here changed no pixel.
 *
 * A size that belongs to one component only — a cover's height, the reader's
 * control column — stays a named constant beside that component instead.
 */
object Spacing {
    val XXSmall: Dp = 2.dp
    val XSmall: Dp = 4.dp
    val Small: Dp = 8.dp
    val Medium: Dp = 12.dp

    /** The screens' horizontal gutter, and the usual padding inside a row or banner. */
    val Large: Dp = 16.dp
    val XLarge: Dp = 20.dp
    val XXLarge: Dp = 24.dp
}

/** Sizes more than one screen draws with. */
object Sizes {
    /**
     * The smallest interactive control the app draws: Android's accessibility
     * minimum (REQ-060, REQ-301). Every button, row and chip clears it.
     */
    val TouchTarget: Dp = 48.dp

    /** Material's own icon size, for a placeholder that stands in for an icon. */
    val Icon: Dp = 24.dp

    /** The minimum height of a list row with a title and a line under it (books, folders). */
    val ListRowMinHeight: Dp = 72.dp
}
