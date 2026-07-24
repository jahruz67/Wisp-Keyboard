package org.futo.inputmethod.latin.uix.addons

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.actions.EmbeddedVoiceInput

object AddonActionRegistry {
    @Volatile
    var actions: Map<String, Action> = emptyMap()
        private set

    fun update(addons: List<InstalledAddon>) {
        actions = addons
            .sortedBy { it.id }
            .associate { addon ->
                "addon:${addon.id}" to createAction(addon)
            }
    }

    private fun createAction(addon: InstalledAddon): Action = Action(
        icon = R.drawable.ic_addon,
        name = R.string.addons_settings_title,
        dynamicName = addon.manifest.name,
        dynamicIconPath = java.io.File(addon.directory, addon.manifest.icon).absolutePath,
        addonId = addon.id,
        canShowKeyboard = addon.manifest.action.canShowKeyboard,
        simplePressImpl = null,
        windowImpl = { keyboardManager, _ ->
            object : ActionWindow() {
                private var expanded by mutableStateOf(
                    addon.manifest.action.preferredHeight == "expanded"
                )
                private var voiceCallback by mutableStateOf<((String?) -> Unit)?>(null)

                override val fixedWindowHeightWhenKeyboardShown
                    get() = if (expanded) {
                        addon.manifest.action.expandedHeightDp.dp
                    } else {
                        addon.manifest.action.compactHeightDp.dp
                    }

                @Composable
                override fun windowName(): String = addon.manifest.name

                @Composable
                override fun WindowContents(keyboardShown: Boolean) {
                    Box(Modifier.fillMaxSize()) {
                        AddonWebPanel(
                            addon = addon,
                            entrypoint = addon.manifest.action.entrypoint,
                            modifier = Modifier.fillMaxSize(),
                            keyboardManager = keyboardManager,
                            keyboardShown = keyboardShown,
                            onVoiceRequest = { callback -> voiceCallback = callback },
                            onExpandedChanged = { expanded = it },
                        )
                        voiceCallback?.let { callback ->
                            EmbeddedVoiceInput(manager = keyboardManager) { text ->
                                voiceCallback = null
                                callback(text)
                            }
                        }
                    }
                }
            }
        },
    )
}
