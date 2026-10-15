package org.futo.inputmethod.latin.uix

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.roundToInt

/** Native scrolling and view clipping, inside InlineSuggestionsSurfaceHost's compositor crop. */
internal class InlineSuggestionsScrollView(context: Context) : HorizontalScrollView(context) {
    private val chips = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = true
        clipToPadding = true
    }
    private val chipMargin = (4f * resources.displayMetrics.density).roundToInt()

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = true
        clipChildren = true
        clipToPadding = true
        isFocusable = false
        addView(chips, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun setSuggestions(suggestions: List<View>) {
        // Keep existing surfaces attached when another chip finishes loading.
        for (index in chips.childCount - 1 downTo 0) {
            if (chips.getChildAt(index) !in suggestions) chips.removeViewAt(index)
        }
        suggestions.forEachIndexed { index, view ->
            if (chips.getChildAt(index) !== view) {
                val previousParams = view.layoutParams
                (view.parent as? ViewGroup)?.removeView(view)
                view.clipBounds = Rect(0, 0, 0, 0)
                chips.addView(view, index, LinearLayout.LayoutParams(
                    previousParams?.width ?: LayoutParams.WRAP_CONTENT,
                    previousParams?.height ?: LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setMargins(chipMargin, 0, chipMargin, 0)
                })
            }
        }
        updateClipping()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateClipping()
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        updateClipping()
    }

    private fun updateClipping() {
        val viewport = InlineSuggestionBounds(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        for (index in 0 until chips.childCount) {
            val view = chips.getChildAt(index)
            val bounds = inlineSuggestionClipBounds(
                viewport,
                chips.left + view.left - scrollX,
                chips.top + view.top - scrollY,
                view.width,
                view.height,
            )
            // This view-local crop is supplemental. The stationary surface parent in
            // InlineSuggestionsSurfaceHost enforces the viewport during compositor updates.
            val clip = bounds?.let { Rect(it.left, it.top, it.right, it.bottom) }
                ?: Rect(0, 0, view.width, view.height)
            if (view.clipBounds != clip) view.clipBounds = clip
        }
    }
}
