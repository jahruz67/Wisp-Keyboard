package org.futo.inputmethod.latin.uix.addons

import android.text.InputType

private const val NO_SUGGESTIONS_OPTION = "org.futo.inputmethod.latin.NoSuggestions=1"

/** Honor an add-on editor's opt-out even though general web editors keep Wisp suggestions. */
internal fun addonEditorPrivateImeOptions(inputType: Int, existingOptions: String?): String? {
    val isText = inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT
    val noSuggestions = inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
    val autoCorrect = inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT != 0
    if (!isText || !noSuggestions || autoCorrect) return existingOptions
    val existing = existingOptions.orEmpty()
    if (existing.contains(NO_SUGGESTIONS_OPTION)) return existingOptions
    return if (existing.isBlank()) NO_SUGGESTIONS_OPTION
    else existing.trimEnd(',') + "," + NO_SUGGESTIONS_OPTION
}
