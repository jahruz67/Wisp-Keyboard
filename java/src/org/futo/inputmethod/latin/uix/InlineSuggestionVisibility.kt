package org.futo.inputmethod.latin.uix

import android.text.InputType
import org.futo.inputmethod.latin.utils.InputTypeUtils

internal fun shouldShowInlineSuggestions(inputType: Int, hiddenByTyping: Boolean): Boolean =
    !hiddenByTyping || InputTypeUtils.isPasswordInputType(inputType)
            || InputTypeUtils.isVisiblePasswordInputType(inputType)

internal fun inlineSuggestionFieldType(inputType: Int): Int {
    val type = inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
    // Revealing a password restarts some editors. Keep its chips, but clear them when switching
    // to an unrelated field type even if the app reuses its field ID during a restart.
    return if (type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT
        && (InputTypeUtils.isPasswordInputType(type) || InputTypeUtils.isVisiblePasswordInputType(type))) {
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    } else type
}
