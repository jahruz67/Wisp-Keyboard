package org.futo.inputmethod.latin.uix

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.settings.pages.ActionBarDisplayedSetting
import org.futo.inputmethod.latin.uix.settings.pages.InlineAutofillSetting
import kotlin.math.roundToInt


private const val maxSuggestions = 5

private const val minWidthDp = 32.0f

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

    val maxWidthPx = (displayMetrics.widthPixels * 2 / 3).coerceAtLeast(1)
    // Round the toolbar and its separators separately, as Compose does at fractional densities.
    val heightPx = (fromDp(ActionBarHeight.value) - 2 * fromDp(1f)).coerceAtLeast(1)

    val spec = InlinePresentationSpec.Builder(
        Size(
            fromDp(minWidthDp).coerceIn(1, maxWidthPx),
            heightPx
        ),
        Size(
            maxWidthPx,
            heightPx
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
fun Context.inflateInlineSuggestion(inlineSuggestion: InlineSuggestion, onInflated: (View?) -> Unit) {
    // Respect the returned presentation spec, including provider-supplied icon-only chips.
    val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    try {
        inlineSuggestion.inflate(this, size, mainExecutor, onInflated)
    } catch (e: Exception) {
        Log.w("InlineSuggestions", "Unable to inflate autofill suggestion", e)
        onInflated(null)
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun RowScope.InlineSuggestions(suggestions: List<View>) {
    AndroidView(
        factory = { InlineSuggestionsSurfaceHost(it) },
        update = { it.setSuggestions(suggestions) },
        onRelease = { it.setSuggestions(emptyList()) },
        modifier = Modifier
            .weight(1.0f)
            .fillMaxHeight()
            .clipToBounds()
    )
}
