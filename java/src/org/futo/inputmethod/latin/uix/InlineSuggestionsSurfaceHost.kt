package org.futo.inputmethod.latin.uix

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Build
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.inline.InlineContentView

/**
 * A compositor-level viewport for autofill. View clipping alone cannot contain remote surfaces
 * that are composed above the IME window. Parent them to a stationary, viewport-sized surface,
 * so even a moving child's uncropped surface cannot cover the adjacent toolbar button.
 *
 * InlineContentView's surface callback also enables Android's parent-position compensation;
 * do not translate the views to compensate manually (that would move their touch targets).
 * This uses the surface-parent approach from AOSP's AutofillKeyboard/InlineContentClipView sample.
 */
@TargetApi(Build.VERSION_CODES.R)
internal class InlineSuggestionsSurfaceHost(context: Context) : FrameLayout(context) {
    private val viewportSurface = SurfaceView(context).apply {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSPARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val scroller = InlineSuggestionsScrollView(context)
    private val trackedViews = mutableSetOf<InlineContentView>()
    private val childSurfaces = mutableMapOf<InlineContentView, SurfaceControl>()
    private var parentSurface: SurfaceControl? = null

    init {
        addView(viewportSurface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        viewportSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                parentSurface = viewportSurface.surfaceControl.takeIf { it.isValid }
                cropParentSurface()
                clearBackground(holder)
                childSurfaces.values.forEach { parentChildSurface(it) }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                cropParentSurface()
                clearBackground(holder)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                parentSurface = null
                // Keep child surfaces hidden until a new viewport exists; never fall back to
                // showing them directly on top of the IME window during recreation.
                childSurfaces.values.forEach { parentChildSurface(it) }
            }
        })
    }

    fun setSuggestions(suggestions: List<View>) {
        val inlineViews = suggestions.filterIsInstance<InlineContentView>().toSet()
        (trackedViews - inlineViews).forEach { view ->
            // Detach while the callback is installed so Android cleans up its reparent listener.
            (view.parent as? ViewGroup)?.removeView(view)
            view.setSurfaceControlCallback(null)
            childSurfaces.remove(view)?.let { unparentSurface(it) }
            trackedViews.remove(view)
        }
        (inlineViews - trackedViews).forEach { view ->
            view.setSurfaceControlCallback(object : InlineContentView.SurfaceControlCallback {
                override fun onCreated(surfaceControl: SurfaceControl) {
                    childSurfaces[view] = surfaceControl
                    parentChildSurface(surfaceControl)
                }

                override fun onDestroyed(surfaceControl: SurfaceControl) {
                    if (childSurfaces[view] === surfaceControl) childSurfaces.remove(view)
                }
            })
            trackedViews.add(view)
        }
        scroller.setSuggestions(suggestions)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        cropParentSurface()
    }

    private fun parentChildSurface(surface: SurfaceControl) {
        if (!surface.isValid) return
        SurfaceControl.Transaction().use { transaction ->
            transaction.reparent(surface, parentSurface?.takeIf { it.isValid }).apply()
        }
    }

    private fun unparentSurface(surface: SurfaceControl) {
        if (!surface.isValid) return
        SurfaceControl.Transaction().use { transaction ->
            transaction.reparent(surface, null).apply()
        }
    }

    private fun cropParentSurface() {
        val surface = parentSurface?.takeIf { it.isValid } ?: return
        // On R, SurfaceView itself crops its surface to the buffer dimensions. From S onward
        // the public compositor crop API lets us also enforce the viewport explicitly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SurfaceControl.Transaction().use { transaction ->
                transaction.setCrop(surface, Rect(0, 0, width, height)).apply()
            }
        }
    }

    private fun clearBackground(holder: SurfaceHolder) {
        if (!holder.surface.isValid) return
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }
}
