package org.futo.inputmethod.latin.uix

internal data class InlineInputField(
    val packageName: String?,
    val fieldId: Int,
    val fieldName: String?,
    val inputType: Int = 0,
)

/** Keeps asynchronous provider views scoped to the input session and response that requested them. */
internal class InlineSuggestionSession<T : Any>(
    private val onSuggestionsChanged: (List<T>) -> Unit,
) {
    private var field: InlineInputField? = null
    private var generation = 0L
    private var pending: List<T?> = emptyList()

    fun startInput(newField: InlineInputField?, restarting: Boolean): Boolean {
        // WebViews and some apps reuse field IDs. A new session must always discard old chips.
        val reset = !restarting || newField == null || field != newField
        field = newField
        if (reset) clear()
        return reset
    }

    fun finishInput() {
        field = null
        clear()
    }

    fun clear() {
        generation++
        pending = emptyList()
        onSuggestionsChanged(emptyList())
    }

    fun beginResponse(count: Int): Long? {
        if (field == null) return null
        clear()
        pending = List(count) { null }
        return generation
    }

    fun onInflated(responseGeneration: Long, index: Int, view: T?) {
        if (field == null || responseGeneration != generation) return
        pending = pending.toMutableList().also { it[index] = view }
        // Pending or failed views must not replace the toolbar with an empty autofill row.
        onSuggestionsChanged(pending.filterNotNull())
    }
}
