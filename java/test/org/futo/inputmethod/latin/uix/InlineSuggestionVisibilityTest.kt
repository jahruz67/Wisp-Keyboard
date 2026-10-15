package org.futo.inputmethod.latin.uix

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineSuggestionVisibilityTest {
    private val passwordTypes = listOf(
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
    )

    @Test
    fun allPasswordVariationsKeepAutofillAfterTyping() {
        passwordTypes.forEach { type ->
            assertTrue(shouldShowInlineSuggestions(type, hiddenByTyping = true))
            assertTrue(shouldShowInlineSuggestions(type or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, true))
        }
    }

    @Test
    fun ordinaryTextAndEmailAllowProviderSuggestionsBeforeTyping() {
        listOf(InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).forEach { type ->
            assertTrue(shouldShowInlineSuggestions(type, hiddenByTyping = false))
            assertFalse(shouldShowInlineSuggestions(type, hiddenByTyping = true))
        }
    }

    @Test
    fun revealingPasswordKeepsSameFieldIdentity() {
        val hidden = inlineSuggestionFieldType(passwordTypes[0])
        assertEquals(hidden, inlineSuggestionFieldType(passwordTypes[1]))
        assertEquals(hidden, inlineSuggestionFieldType(passwordTypes[2]))
        assertEquals(hidden, inlineSuggestionFieldType(passwordTypes[0] or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
    }

    @Test
    fun passwordToOrdinaryTextOnRestartDiscardsSuggestions() {
        var visible = emptyList<String>()
        val session = InlineSuggestionSession<String> { visible = it }
        val field = InlineInputField("browser", 0, null, inlineSuggestionFieldType(passwordTypes[0]))
        session.startInput(field, restarting = false)
        val response = session.beginResponse(1)!!
        session.onInflated(response, 0, "login")
        session.startInput(field.copy(inputType = inlineSuggestionFieldType(InputType.TYPE_CLASS_TEXT)), true)
        assertTrue(visible.isEmpty())
    }
}
