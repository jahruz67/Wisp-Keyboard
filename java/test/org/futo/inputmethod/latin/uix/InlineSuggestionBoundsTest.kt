package org.futo.inputmethod.latin.uix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InlineSuggestionBoundsTest {
    // A viewport offset by the action button, keyboard padding, and the window's vertical position.
    private val viewport = InlineSuggestionBounds(120, 500, 420, 538)

    @Test
    fun fullyVisibleChipKeepsBothIcons() {
        assertNull(inlineSuggestionClipBounds(viewport, 124, 500, 160, 38))
    }

    @Test
    fun scrollingLeftClipsOnlyThePartOutsideTheViewport() {
        assertEquals(
            InlineSuggestionBounds(40, 0, 160, 38),
            inlineSuggestionClipBounds(viewport, 80, 500, 160, 38)
        )
    }

    @Test
    fun scrollingRightClipsOnlyThePartOutsideTheViewport() {
        assertEquals(
            InlineSuggestionBounds(0, 0, 20, 38),
            inlineSuggestionClipBounds(viewport, 400, 500, 160, 38)
        )
    }

    @Test
    fun offscreenChipIsHiddenAndBecomesFullyVisibleAfterScrollingBack() {
        assertEquals(
            InlineSuggestionBounds(0, 0, 0, 0),
            inlineSuggestionClipBounds(viewport, 450, 500, 160, 38)
        )
        assertNull(inlineSuggestionClipBounds(viewport, 150, 500, 160, 38))
    }

    @Test
    fun verticalOverflowCannotCoverKeyboardOrToolbarBorders() {
        assertEquals(
            InlineSuggestionBounds(0, 5, 160, 43),
            inlineSuggestionClipBounds(viewport, 124, 495, 160, 48)
        )
    }

    @Test
    fun unmeasuredOrEmptyViewportHidesSurface() {
        val empty = InlineSuggestionBounds(0, 0, 0, 0)
        assertEquals(empty, inlineSuggestionClipBounds(null, 124, 500, 160, 38))
        assertEquals(empty, inlineSuggestionClipBounds(empty, 124, 500, 160, 38))
        assertEquals(empty, inlineSuggestionClipBounds(viewport, 124, 500, 0, 0))
    }

    @Test
    fun wideChipCanScrollWithoutLosingItsUnclippedContent() {
        assertEquals(
            InlineSuggestionBounds(100, 0, 400, 38),
            inlineSuggestionClipBounds(viewport, 20, 500, 500, 38)
        )
    }
}
