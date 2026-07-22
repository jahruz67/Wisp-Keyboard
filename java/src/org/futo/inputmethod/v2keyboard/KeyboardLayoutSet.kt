package org.futo.inputmethod.v2keyboard

import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.futo.inputmethod.keyboard.Keyboard
import org.futo.inputmethod.keyboard.KeyboardId
import org.futo.inputmethod.keyboard.internal.KeyboardLayoutElement
import org.futo.inputmethod.keyboard.internal.KeyboardLayoutKind
import org.futo.inputmethod.keyboard.internal.KeyboardLayoutPage
import org.futo.inputmethod.keyboard.internal.KeyboardParams
import org.futo.inputmethod.keyboard.internal.KeyboardTextsMultiSet
import org.futo.inputmethod.latin.settings.LongPressKeySettings
import org.futo.inputmethod.latin.uix.actions.BugInfo
import org.futo.inputmethod.latin.uix.actions.BugViewerState
import org.futo.inputmethod.latin.uix.settings.pages.CustomLayout
import org.futo.inputmethod.latin.utils.InputTypeUtils
import java.util.Locale
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
enum class Script(val id: Int, val iso4letterCode: String) {
    Unknown(-1, ""),
    Arabic(0, "arab"),
    Armenian(1, "armn"),
    Bengali(2, "beng"),
    Cyrillic(3, "cyrl"),
    Devanagari(4, "deva"),
    Georgian(5, "geor"),
    Greek(6, "grek"),
    Hebrew(7, "hebr"),
    Kannada(8, "knda"),
    Khmer(9, "khmr"),
    Lao(10, "laoo"),
    Latin(11, "latn"),
    Malayalam(12, "mlym"),
    Myanmar(13, "mymr"),
    Sinhala(14, "sinh"),
    Tamil(15, "taml"),
    Telugu(16, "telu"),
    Thai(17, "thai"),
}

fun Locale.getKeyboardScript(): Script =
    script.lowercase().let { code ->
        Script.entries.firstOrNull { it.iso4letterCode == code }
            ?: Script.Unknown
    }



internal fun EditorInfo.getPrivateImeOptions(): Map<String, String> {
    val options = mutableMapOf<String, String>()
    val imeOptions = privateImeOptions ?: return options

    try {
        imeOptions.split(",").forEach { option ->
            val separator = option.indexOf('=')
            if (separator <= 0 || option.indexOf('=', separator + 1) >= 0) return@forEach

            options[option.substring(0, separator).trim()] =
                option.substring(separator + 1).trim()
        }
    } catch(e: Exception) {
        e.printStackTrace()
    }

    return options
}

private fun EditorInfo.getPrivateImeOption(name: String): String? {
    val imeOptions = privateImeOptions ?: return null
    var start = 0

    while (start < imeOptions.length) {
        val end = imeOptions.indexOf(',', start).let { if (it < 0) imeOptions.length else it }
        val separator = imeOptions.indexOf('=', start)
        val nextSeparator = imeOptions.indexOf('=', separator + 1)
        if (separator in (start + 1) until end && nextSeparator !in (separator + 1) until end) {
            val key = imeOptions.substring(start, separator).trim()
            if (key == name) return imeOptions.substring(separator + 1, end).trim()
        }
        start = end + 1
    }

    return null
}

fun getPrimaryLayoutOverride(editorInfo: EditorInfo?): String? {
    return editorInfo?.getPrivateImeOption("org.futo.inputmethod.latin.ForceLayout")
}

data class KeyboardLayoutSetV2Params(
    val computedSize: ComputedKeyboardSize,
    val keyboardLayoutSet: String,
    val locale: Locale,
    val multilingualTypingLocales: List<Locale>? = null,
    val editorInfo: EditorInfo?,
    val numberRow: Boolean,
    val numberRowMode: Int,
    val useLocalNumbers: Boolean,
    val arrowRow: Boolean,
    val alternativePeriodKey: Boolean,
    val gap: Float = 4.0f,
    val bottomActionKey: Int?,
    val longPressKeySettings: LongPressKeySettings? = null
)

class KeyboardLayoutSetV2 internal constructor(
    private val context: Context,
    private val params: KeyboardLayoutSetV2Params
) {
    val script = Script.Latin

    val editorInfo = params.editorInfo ?: EditorInfo()

    val privateParams = editorInfo.getPrivateImeOptions()
    val forcedLayout = privateParams["org.futo.inputmethod.latin.ForceLayout"]
    val forcedLocale = privateParams["org.futo.inputmethod.latin.ForceLocale"]?.let { Locale.forLanguageTag(it) }
    val forcedMultilingualTypingLanguages: List<Locale>? = forcedLocale?.let { emptyList() }


    @OptIn(ExperimentalEncodingApi::class)
    val forcedCustomLayout = privateParams["org.futo.inputmethod.latin.ForceCustomLayoutYamlB64"]?.let {
        try {
            Json.Default.decodeFromString(
                CustomLayout.serializer(),
                Base64.Default.decode(it.replace("_", "=")).decodeToString()
            )
        } catch (e: Exception) {
            BugViewerState.pushBug(BugInfo(
                name = "Custom layout",
                details =
                """
Custom layout could not parsed.

Cause: ${e.message}

Stack trace: ${e.stackTrace.map { it.toString() }}

Data: $it
"""
            ))
            null
        }
    }

    // Most input sessions only use the alphabet layout. Keep fallback and secondary layouts lazy
    // so opening the keyboard does not synchronously parse every YAML layout in the set.
    val errorLayout by lazy(LazyThreadSafetyMode.NONE) {
        LayoutManager.getLayout(context, "error")
    }

    val customLayout = forcedCustomLayout?.let {
        val x = try {
            parseKeyboardYamlString(it.layoutYaml)
        } catch(e: Exception) {
            BugViewerState.pushBug(BugInfo(
                name = "Custom layout",
                details =
                """
Custom layout could not be loaded.

Cause: ${e.message}

Stack trace: ${e.stackTrace.map { it.toString() }}

Layout: $it
"""
            ))
            errorLayout
        }

        x
    }

    // Necessary for Java API
    fun getScriptId(): Int = script.id

    private val keyboardMode = getKeyboardMode(editorInfo)

    private fun safeGetLayout(name: String): org.futo.inputmethod.v2keyboard.Keyboard =
        try {
            LayoutManager.getLayout(context, name)
        } catch (e: Exception) {
            BugViewerState.pushBug(BugInfo(
                name = if(layoutName.startsWith("custom")) { "your custom layout" } else { "layout $layoutName" },
                details =
                """
Layout $name could not be loaded.

Cause: ${e.message}

Stack trace: ${e.stackTrace.map { it.toString() }}

Layout: $layoutName
"""
            ))
            errorLayout
        }

    val layoutName = customLayout?.let { "custompreview" } ?: forcedLayout ?: params.keyboardLayoutSet
    val mainLayout = customLayout ?: safeGetLayout(layoutName)

    val symbolsLayout by lazy(LazyThreadSafetyMode.NONE) {
        safeGetLayout(mainLayout.layoutSetOverrides.symbols)
    }
    val symbolsShiftedLayout by lazy(LazyThreadSafetyMode.NONE) {
        safeGetLayout(mainLayout.layoutSetOverrides.symbolsShifted)
    }
    val numberLayout by lazy(LazyThreadSafetyMode.NONE) {
        safeGetLayout(mainLayout.layoutSetOverrides.number)
    }
    val phoneLayout by lazy(LazyThreadSafetyMode.NONE) {
        safeGetLayout(mainLayout.layoutSetOverrides.phone)
    }
    val phoneSymbolsLayout by lazy(LazyThreadSafetyMode.NONE) {
        safeGetLayout(mainLayout.layoutSetOverrides.phoneShifted)
    }
    val numberBasicLayout by lazy(LazyThreadSafetyMode.NONE) {
        safeGetLayout("number_basic")
    }

    private fun getSubKeyboard(element: KeyboardLayoutElement): org.futo.inputmethod.v2keyboard.Keyboard? {
        return mainLayout.subKeyboards[element.kind]?.let {
            mainLayout.copy(
                rows = it.rows,
                attributes = it.attributes + mainLayout.attributes
            )
        }
    }

    private fun getBuiltInLayout(element: KeyboardLayoutElement): org.futo.inputmethod.v2keyboard.Keyboard? {
        val normalized = element.normalize()
        return when(normalized.kind) {
            KeyboardLayoutKind.Alphabet0 -> when(normalized.page) {
                KeyboardLayoutPage.Base, KeyboardLayoutPage.Shifted -> mainLayout
                else -> null
            }
            KeyboardLayoutKind.Symbols -> when(normalized.page) {
                KeyboardLayoutPage.Base -> symbolsLayout
                KeyboardLayoutPage.Shifted -> symbolsShiftedLayout
                else -> null
            }
            KeyboardLayoutKind.Phone -> when(normalized.page) {
                KeyboardLayoutPage.Base -> phoneLayout
                KeyboardLayoutPage.Shifted -> phoneSymbolsLayout
                else -> null
            }
            KeyboardLayoutKind.Number -> if(normalized.page == KeyboardLayoutPage.Base) numberLayout else null
            KeyboardLayoutKind.NumberBasic -> if(normalized.page == KeyboardLayoutPage.Base) numberBasicLayout else null
            else -> null
        }
    }

    private fun getKeyboardLayoutForElement(element: KeyboardLayoutElement): org.futo.inputmethod.v2keyboard.Keyboard {
        return getSubKeyboard(element) ?: getBuiltInLayout(element) ?: run {
            // If this is an alt layout, try to get the matching alt
            element.page.altIdx?.let { altIdx ->
                val baseElement = element.copy(page = KeyboardLayoutPage.Base)
                val baseLayout = getBuiltInLayout(baseElement)
                baseLayout?.altPages?.getOrNull(altIdx)
            }?.let {
                mainLayout.copy(rows = it)
            }
        } ?: run {
            // If all else fails, show the error layout
            BugViewerState.pushBug(BugInfo("KeyboardLayoutSet",
                "Keyboard $layoutName does not have element $element"))
            errorLayout
        }
    }

    private val isNumberRowActive: Boolean
        get() = when(mainLayout.numberRowMode) {
            NumberRowMode.UserConfigurable -> params.numberRow
            NumberRowMode.AlwaysEnabled    -> true
            NumberRowMode.AlwaysDisabled   -> false
        }

    private val height = params.computedSize.height

    private val padding = params.computedSize.padding

    private val widthMinusPadding = params.computedSize.totalKeyboardWidth
    private val heightMinusPadding = height - padding.top - padding.bottom

    private val singularRowHeight: Double
        get() = params.computedSize.singleRowHeight.toDouble()

    private val builtKeyboards = mutableMapOf<KeyboardLayoutElement, Keyboard>()

    fun getKeyboard(element: KeyboardLayoutElement): Keyboard =
        builtKeyboards.getOrPut(element) { buildKeyboard(element) }

    private fun buildKeyboard(element: KeyboardLayoutElement): Keyboard {

        val multilingualTypingLocales = forcedMultilingualTypingLanguages ?: params.multilingualTypingLocales

        val keyboardId = KeyboardId(
            params.keyboardLayoutSet,
            forcedLocale ?: params.locale,
            widthMinusPadding,
            heightMinusPadding,
            keyboardMode,
            element.elementId,
            editorInfo,
            false,
            params.bottomActionKey != null,
            params.bottomActionKey ?: -1,
            editorInfo.actionLabel?.toString() ?: "",
            false,
            false,
            isNumberRowActive,
            params.numberRowMode,
            params.useLocalNumbers,
            params.arrowRow,
            params.alternativePeriodKey,
            params.longPressKeySettings ?: LongPressKeySettings.load(context),
            element
        )

        val layout = getKeyboardLayoutForElement(element)

        val keyboardParams = KeyboardParams().apply {
            mId = keyboardId
            mTextsSet.setLocale(keyboardId.locale, context)

            if(multilingualTypingLocales?.isNotEmpty() == true) {
                mTextsSet = KeyboardTextsMultiSet(context, keyboardId.locale, multilingualTypingLocales)
            }
        }

        val layoutParams = LayoutParams(
            size = params.computedSize,
            gap = params.gap.dp,
            standardRowHeight = singularRowHeight,
            element = element
        )

        try {
            return layout.build(context, keyboardParams, layoutParams)
        } catch(e: Exception) {
            Log.e("KeyboardLayoutSet", "Failed to load element $element for keyboard layout set $layoutName. Message: ${e.message}")
            Log.e("KeyboardLayoutSet", "LayoutSet params: $params, keyboardId: $keyboardId")
            e.printStackTrace()

            BugViewerState.pushBug(BugInfo(
                name = "KeyboardLayoutSet",
                details =
"""
Element $element for layout $layoutName could not be loaded

Cause: ${e.message}

Params: $params

Stack trace: ${e.stackTrace.map { it.toString() }}
"""
            ))

            return errorLayout.build(context, keyboardParams, layoutParams)
        }
    }

    companion object {

        @JvmStatic
        fun onSystemLocaleChanged() {

        }

        @JvmStatic
        fun onKeyboardThemeChanged(context: Context) {
            // This is where we would clear all caches if we had any
        }
    }
}

public fun getKeyboardMode(editorInfo: EditorInfo): Int {
    val inputType = editorInfo.inputType
    val variation = inputType and InputType.TYPE_MASK_VARIATION

    return when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER -> KeyboardId.MODE_NUMBER
        InputType.TYPE_CLASS_DATETIME -> when (variation) {
            InputType.TYPE_DATETIME_VARIATION_DATE -> KeyboardId.MODE_DATE
            InputType.TYPE_DATETIME_VARIATION_TIME -> KeyboardId.MODE_TIME
            else -> KeyboardId.MODE_DATETIME
        }

        InputType.TYPE_CLASS_PHONE -> KeyboardId.MODE_PHONE
        InputType.TYPE_CLASS_TEXT -> if (InputTypeUtils.isEmailVariation(variation)) {
            KeyboardId.MODE_EMAIL
        } else if (variation == InputType.TYPE_TEXT_VARIATION_URI) {
            KeyboardId.MODE_URL
        } else if (variation == InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE) {
            KeyboardId.MODE_IM
        } else if (variation == InputType.TYPE_TEXT_VARIATION_FILTER) {
            KeyboardId.MODE_TEXT
        } else {
            KeyboardId.MODE_TEXT
        }

        else -> KeyboardId.MODE_TEXT
    }
}
