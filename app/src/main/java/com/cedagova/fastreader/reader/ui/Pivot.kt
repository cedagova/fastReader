package com.cedagova.fastreader.reader.ui

/**
 * The recognition point, as an offset into [ReaderWord.text].
 *
 * The index inside the word follows the open-source RSVP convention the
 * definition's research cites (Squirt): the first letter of a one-letter word,
 * the second of a two- or three-letter word, and `floor(length / 2) - 1` from
 * then on — which puts the point slightly left of the word's middle, where
 * Optimal Viewing Position research puts the fastest fixation.
 *
 * It is measured over the word's letters only, so the punctuation the book prints
 * around it never shifts the recognition point: the pivot of `—¿Quién` is a
 * letter of "Quién".
 */
internal fun ReaderWord.pivotOffset(): Int? {
    val length = coreEnd - coreStart
    if (length <= 0) return null
    return coreStart + pivotIndex(length)
}

/** The recognition point inside a word of [length] characters. */
internal fun pivotIndex(length: Int): Int = when {
    length <= 1 -> 0
    length <= 3 -> 1
    else -> length / 2 - 1
}
