package org.futo.inputmethod.latin.uix.addons

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddonEditorInfoTest {
    private val webText = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
    private val searchText = webText or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    private val noSuggestions = "org.futo.inputmethod.latin.NoSuggestions=1"

    @Test
    fun searchEditorExplicitlyDisablesWispCompositionAndSuggestions() {
        assertEquals(noSuggestions, addonEditorPrivateImeOptions(searchText, null))
    }

    @Test
    fun ordinaryWebEditorKeepsSuggestions() {
        assertNull(addonEditorPrivateImeOptions(webText, null))
    }

    @Test
    fun explicitAutoCorrectionKeepsExistingBehavior() {
        assertEquals("custom=1", addonEditorPrivateImeOptions(
            searchText or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT, "custom=1",
        ))
    }

    @Test
    fun preservesExistingOptionsWithoutDuplicatingSuppression() {
        assertEquals("custom=1,$noSuggestions", addonEditorPrivateImeOptions(searchText, "custom=1,"))
        assertEquals("custom=1,$noSuggestions", addonEditorPrivateImeOptions(searchText, "custom=1,$noSuggestions"))
    }

    @Test
    fun numberEditorIsUnaffected() {
        assertNull(addonEditorPrivateImeOptions(
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, null,
        ))
    }
}
