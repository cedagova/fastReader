package com.cedagova.fastreader.reader.ui

/**
 * The line the paused paragraph scrolls to so the current word stays on screen.
 *
 * The paragraph can be taller than the area under the word — landscape, a large
 * font scale, a window that runs to its full 91 tokens — and the mark on the
 * current word must never sit below the fold (REQ-010): a reader who cannot see
 * the marked word has nothing to pick the thread up from. The lines are cut into
 * sections from the top, each as many whole lines as fit in [viewportHeight],
 * and the view shows the section holding [currentLine].
 *
 * Sections rather than a scroll that trails the word line by line: with "Always
 * show paragraph" on, the mark moves sixteen times a second at the speed
 * ceiling, and text that creeps under a word is harder to read back than text
 * that turns a page. A word leaving the shown lines is the one moment the shown
 * lines change.
 *
 * Pure so the cut is provable without a render. [lineTop] and [lineBottom] are
 * the layout's own line bounds, in pixels from the top of the text.
 */
internal fun sectionStartLine(
    lineCount: Int,
    viewportHeight: Float,
    currentLine: Int,
    lineTop: (Int) -> Float,
    lineBottom: (Int) -> Float,
): Int {
    require(currentLine in 0 until lineCount) { "line $currentLine of $lineCount" }
    var start = 0
    while (true) {
        val top = lineTop(start)
        var end = start
        // A section always takes its first line, even one taller than the
        // viewport, so the walk cannot stall.
        while (end + 1 < lineCount && lineBottom(end + 1) - top <= viewportHeight) end++
        if (currentLine <= end) return start
        start = end + 1
    }
}
