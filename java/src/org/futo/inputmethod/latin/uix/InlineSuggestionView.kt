package org.futo.inputmethod.latin.uix

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.settings.pages.ActionBarDisplayedSetting
import org.futo.inputmethod.latin.uix.settings.pages.InlineAutofillSetting
import kotlin.math.roundToInt


private const val maxSuggestions = 5

private const val minWidthDp = 32.0f
private const val minHeightDp = 8.0f
private const val maxHeightDp = 48.0f


@SuppressLint("RestrictedApi")
@RequiresApi(Build.VERSION_CODES.R)
fun createInlineSuggestionsRequest(
    context: Context,
    activeColorScheme: KeyboardColorScheme,
    uiExtras: Bundle? = null
): InlineSuggestionsRequest? {
    if(context.getSetting(InlineAutofillSetting) == false
        || context.getSetting(ActionBarDisplayedSetting) == false) {
        return null
    }

    val fromDp = { v: Float ->
        context.fromDp(v).roundToInt()
    }

    val drawable = R.drawable.inline_suggestion_chip

    val stylesBuilder = UiVersions.newStylesBuilder()
    val suggestionStyle = InlineSuggestionUi.newStyleBuilder()
        .setSingleIconChipStyle(
            ViewStyle.Builder()
                .setBackground(
                    Icon.createWithResource(context, drawable).setTint(activeColorScheme.keyboardContainer.toArgb())
                )
                .setPadding(0, 0, 0, 0)
                .build()
        )
        .setChipStyle(
            ViewStyle.Builder()
                .setBackground(
                    Icon.createWithResource(context, drawable).setTint(activeColorScheme.keyboardContainer.toArgb())
                )
                .setPadding(
                    fromDp(6.0f),
                    fromDp(0.0f),
                    fromDp(6.0f),
                    fromDp(0.0f),
                )
                .build()
        )
        .setStartIconStyle(ImageViewStyle.Builder().setLayoutMargin(0, 0, 0, 0).build())
        .setTitleStyle(
            TextViewStyle.Builder()
                .setLayoutMargin(
                    fromDp(4.0f),
                    fromDp(0.0f),
                    fromDp(4.0f),
                    fromDp(0.0f),
                )
                .setTextColor(activeColorScheme.onKeyboardContainer.toArgb())
                .setTextSize(14.0f)
                .build()
        )
        .setSubtitleStyle(
            TextViewStyle.Builder()
                .setLayoutMargin(
                    fromDp(4.0f),
                    fromDp(0.0f),
                    fromDp(4.0f),
                    fromDp(0.0f),
                )
                .setTextColor((activeColorScheme.hintColor ?: activeColorScheme.onSurfaceVariant).toArgb())
                .setTextSize(12.0f)
                .build()
        )
        .setEndIconStyle(
            ImageViewStyle.Builder()
                .setLayoutMargin(0, 0, 0, 0)
                .build()
        )
        .build()
    stylesBuilder.addStyle(suggestionStyle)

    val stylesBundle = stylesBuilder.build()

    val displayMetrics = context.resources.displayMetrics

    val maxWidthPx = displayMetrics.widthPixels * 2 / 3

    val spec = InlinePresentationSpec.Builder(
        Size(
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, minWidthDp, displayMetrics)
                .roundToInt(),
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, minHeightDp, displayMetrics)
                .roundToInt()
        ),
        Size(
            maxWidthPx,
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, maxHeightDp, displayMetrics)
                .roundToInt()
        ),
    ).setStyle(stylesBundle).build()

    return InlineSuggestionsRequest.Builder(List(maxSuggestions) { spec }).apply {
        setMaxSuggestionCount(maxSuggestions)
        if (uiExtras != null) {
            setExtras(uiExtras)
        }
    }.build()
}

@RequiresApi(Build.VERSION_CODES.R)
fun Context.inflateInlineSuggestion(inlineSuggestion: InlineSuggestion): MutableState<View?> {
    val mutableState: MutableState<View?> = mutableStateOf(null)

    val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, fromDp(32f).roundToInt())

    try {
        inlineSuggestion.inflate(this, size, mainExecutor) { inflatedView ->
            if (inflatedView != null) {
                mutableState.value = inflatedView
            }
        }
    } catch (e: Exception) {
        println(e.toString())
    }

    return mutableState
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun InlineSuggestionView(inlineSuggestion: MutableState<View?>, leftBound: Int, rightBound: Int) {
    key(inlineSuggestion.value) {
        val view = inlineSuggestion.value
        if (view != null) {
            var pos by remember { mutableStateOf(IntOffset.Zero) }

            fun updateClipping(targetView: View, x: Int) {
                val width = targetView.width
                val height = targetView.height
                if (width <= 0 || height <= 0 || rightBound <= leftBound) {
                    targetView.clipBounds = null
                    targetView.visibility = View.VISIBLE
                    return
                }

                val clipLeft = (leftBound - x).coerceIn(0, width)
                val clipRight = (rightBound - x).coerceIn(0, width)

                if (clipRight <= clipLeft) {
                    targetView.clipBounds = Rect(0, 0, 0, 0)
                    targetView.visibility = View.INVISIBLE
                } else if (clipLeft == 0 && clipRight == width) {
                    targetView.clipBounds = null
                    targetView.visibility = View.VISIBLE
                } else {
                    targetView.clipBounds = Rect(clipLeft, 0, clipRight, height)
                    targetView.visibility = View.VISIBLE
                }
            }

            DisposableEffect(view) {
                val listener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    updateClipping(v, pos.x)
                }
                view.addOnLayoutChangeListener(listener)
                onDispose {
                    view.removeOnLayoutChangeListener(listener)
                }
            }

            AndroidView(
                factory = {
                    (view.parent as? ViewGroup)?.removeView(view)
                    ViewCompat.setNestedScrollingEnabled(view, true)
                    view
                },
                update = { targetView ->
                    updateClipping(targetView, pos.x)
                },
                modifier = Modifier
                    .padding(4.dp, 0.dp)
                    .onGloballyPositioned {
                        val position = it.positionInParent()
                        pos = IntOffset(
                            position.x.roundToInt(),
                            position.y.roundToInt()
                        )
                        updateClipping(view, pos.x)
                    }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Suppress("DEPRECATION")
@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun RowScope.InlineSuggestions(suggestions: List<MutableState<View?>>) {
    val scrollState = rememberScrollState()
    val leftBound = 0
    val rightBound = scrollState.viewportSize

    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        if (scrollState.value > 0) {
            VerticalDivider(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = 4.dp)
                    .width(0.dp)
            )
        }
        Row(
            modifier = Modifier
                .weight(1.0f)
                .fillMaxHeight()
                .clipToBounds()
                .horizontalScroll(scrollState)
                .clipScrollableContainer(Orientation.Horizontal),
            verticalAlignment = Alignment.CenterVertically
        ) {
            suggestions.forEach { InlineSuggestionView(it, leftBound, rightBound) }
        }
    }
}