package org.futo.inputmethod.latin.uix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineSuggestionSessionTest {
    private val field = InlineInputField("browser", 0, null)
    private var visible = emptyList<String>()
    private val session = InlineSuggestionSession<String> { visible = it }

    @Test
    fun newFieldWithReusedIdDiscardsSuggestionsAndLateCallbacks() {
        session.startInput(field, restarting = false)
        val oldResponse = session.beginResponse(2)!!
        session.onInflated(oldResponse, 0, "old login")

        assertTrue(session.startInput(field.copy(), restarting = false))
        session.onInflated(oldResponse, 1, "late old login")
        assertTrue(visible.isEmpty())
    }

    @Test
    fun sameEditorRestartPreservesSuggestionsAndPendingInflation() {
        session.startInput(field, restarting = false)
        val response = session.beginResponse(2)!!
        session.onInflated(response, 0, "login")

        assertFalse(session.startInput(field.copy(), restarting = true))
        assertEquals(listOf("login"), visible)
        session.onInflated(response, 1, "vault")
        assertEquals(listOf("login", "vault"), visible)
    }

    @Test
    fun changedFieldOnRestartDiscardsSuggestions() {
        session.startInput(field, restarting = false)
        val response = session.beginResponse(1)!!
        session.onInflated(response, 0, "login")

        assertTrue(session.startInput(field.copy(fieldName = "search"), restarting = true))
        session.onInflated(response, 0, "late login")
        assertTrue(visible.isEmpty())
    }

    @Test
    fun newResponseRejectsLateViewsFromPreviousResponse() {
        session.startInput(field, restarting = false)
        val oldResponse = session.beginResponse(1)!!
        val newResponse = session.beginResponse(1)!!
        session.onInflated(newResponse, 0, "new login")
        session.onInflated(oldResponse, 0, "old login")
        assertEquals(listOf("new login"), visible)
    }

    @Test
    fun pendingAndFailedSuggestionsDoNotOccupyToolbar() {
        session.startInput(field, restarting = false)
        val response = session.beginResponse(2)!!
        assertTrue(visible.isEmpty())
        session.onInflated(response, 0, null)
        session.onInflated(response, 1, null)
        assertTrue(visible.isEmpty())
    }

    @Test
    fun outOfOrderInflationPreservesProviderOrder() {
        session.startInput(field, restarting = false)
        val response = session.beginResponse(3)!!
        session.onInflated(response, 2, "vault")
        assertEquals(listOf("vault"), visible)
        session.onInflated(response, 0, "first login")
        session.onInflated(response, 1, "second login")
        assertEquals(listOf("first login", "second login", "vault"), visible)
    }

    @Test
    fun emptyResponseClearsSuggestions() {
        session.startInput(field, restarting = false)
        val response = session.beginResponse(1)!!
        session.onInflated(response, 0, "login")
        session.beginResponse(0)
        session.onInflated(response, 0, "late login")
        assertTrue(visible.isEmpty())
    }

    @Test
    fun finishingInputRejectsResponsesAndInflationUntilNextSession() {
        session.startInput(field, restarting = false)
        val response = session.beginResponse(1)!!
        session.finishInput()
        session.onInflated(response, 0, "late login")
        assertTrue(visible.isEmpty())
        assertNull(session.beginResponse(1))

        session.startInput(field, restarting = true)
        val nextResponse = session.beginResponse(1)!!
        session.onInflated(nextResponse, 0, "new login")
        assertEquals(listOf("new login"), visible)
    }

    @Test
    fun clearingOnDisabledAutofillRejectsPendingInflation() {
        session.startInput(field, restarting = false)
        val response = session.beginResponse(1)!!
        session.clear()
        session.onInflated(response, 0, "late login")
        assertTrue(visible.isEmpty())
    }

    @Test
    fun nullEditorCannotAcceptResponses() {
        session.startInput(null, restarting = false)
        assertNull(session.beginResponse(1))
    }
}
