package com.cedagova.fastreader.library.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/**
 * Reading the semantics tree the way a screen reader does, for the REQ-060 and
 * REQ-301 sweeps over the library's surfaces.
 *
 * These are shared rather than repeated per test class because the sweep only
 * works if every class asks the same question: an actionable node with nothing
 * to announce is invisible in a golden, and a class that walked one root, or
 * read `Text` before `ContentDescription`, would quietly stop catching it.
 */

/**
 * Every root, not just the first: a dialog or a menu is its own window, so its
 * controls are invisible to a sweep that only walks the screen behind it.
 */
internal fun ComposeContentTestRule.actionableNodes(): List<SemanticsNode> =
    allNodes().filter { it.config.contains(SemanticsActions.OnClick) }

internal fun ComposeContentTestRule.allNodes(): List<SemanticsNode> {
    val out = mutableListOf<SemanticsNode>()
    fun walk(node: SemanticsNode) {
        out += node
        node.children.forEach(::walk)
    }
    onAllNodes(isRoot()).fetchSemanticsNodes().forEach(::walk)
    return out
}

internal fun SemanticsNode.testTag(): String =
    config.getOrElseNullable(SemanticsProperties.TestTag) { null }.orEmpty()

/** What a screen reader would say: the description if there is one, else the text. */
internal fun SemanticsNode.label(): String {
    val described = config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }
    if (!described.isNullOrEmpty()) return described.joinToString(" ").trim()
    val text = config.getOrElseNullable(SemanticsProperties.Text) { null }
    return text?.joinToString(" ") { it.text }?.trim().orEmpty()
}

internal fun SemanticsNode.isSelected(): Boolean =
    config.getOrElseNullable(SemanticsProperties.Selected) { null } == true
