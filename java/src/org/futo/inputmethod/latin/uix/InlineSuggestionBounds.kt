package org.futo.inputmethod.latin.uix

internal data class InlineSuggestionBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Returns a view-local clip, or null when the entire provider surface is visible. */
internal fun inlineSuggestionClipBounds(
    viewport: InlineSuggestionBounds?,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
): InlineSuggestionBounds? {
    if (viewport == null || width <= 0 || height <= 0
        || viewport.right <= viewport.left || viewport.bottom <= viewport.top) {
        return InlineSuggestionBounds(0, 0, 0, 0)
    }
    val left = (viewport.left - x).coerceIn(0, width)
    val top = (viewport.top - y).coerceIn(0, height)
    val right = (viewport.right - x).coerceIn(0, width)
    val bottom = (viewport.bottom - y).coerceIn(0, height)
    return when {
        right <= left || bottom <= top -> InlineSuggestionBounds(0, 0, 0, 0)
        left == 0 && top == 0 && right == width && bottom == height -> null
        else -> InlineSuggestionBounds(left, top, right, bottom)
    }
}
