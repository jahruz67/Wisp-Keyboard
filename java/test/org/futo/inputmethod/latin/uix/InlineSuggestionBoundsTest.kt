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

    @Test
    fun secondChipCrossesButtonBoundaryWithoutExposingAnyPixelsOverTheButton() {
        val localViewport = InlineSuggestionBounds(0, 0, 300, 38)
        // Two 160px chips, each with a 4px margin on either side.
        val secondChipLeft = 172
        for (scrollX in 160..340) {
            val x = secondChipLeft - scrollX
            val clip = inlineSuggestionClipBounds(localViewport, x, 0, 160, 38)
                ?: InlineSuggestionBounds(0, 0, 160, 38)
            if (clip.right > clip.left) {
                assertEquals(0.coerceAtLeast(x), x + clip.left)
                assertEquals(300.coerceAtMost(x + 160), x + clip.right)
            } else {
                assertEquals(InlineSuggestionBounds(0, 0, 0, 0), clip)
            }
        }
    }

    @Test
    fun chipCrossingTheRightSideButtonBoundaryIsClippedInRtlLayouts() {
        val localViewport = InlineSuggestionBounds(0, 0, 300, 38)
        assertEquals(
            InlineSuggestionBounds(0, 0, 60, 38),
            inlineSuggestionClipBounds(localViewport, 240, 0, 160, 38)
        )
        assertEquals(
            InlineSuggestionBounds(0, 0, 0, 0),
            inlineSuggestionClipBounds(localViewport, 300, 0, 160, 38)
        )
    }
}
