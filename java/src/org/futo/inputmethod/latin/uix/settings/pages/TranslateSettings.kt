package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.SettingsTextEdit
import org.futo.inputmethod.latin.uix.deferSetSetting
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.actions.translate.TRANSLATE_ADDON_ENABLED
import org.futo.inputmethod.latin.uix.actions.translate.TRANSLATE_API_KEY
import org.futo.inputmethod.latin.uix.actions.translate.TRANSLATE_CUSTOM_URL
import org.futo.inputmethod.latin.uix.actions.translate.TRANSLATE_LIVE_ENABLED
import org.futo.inputmethod.latin.uix.actions.translate.TRANSLATE_PROVIDER
import org.futo.inputmethod.latin.uix.actions.translate.TranslationProviderType
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.SettingRadioGroup
import org.futo.inputmethod.latin.uix.settings.SettingToggleDataStore
import org.futo.inputmethod.latin.uix.settings.SubScreenTitle
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly

private const val SETTING_SAVE_DEBOUNCE_MILLIS = 300L
private val translationProviderEntries = TranslationProviderType.entries
private val translationProviderNames = translationProviderEntries.map { it.displayName }

@Composable
private fun rememberPersistedText(setting: SettingsKey<String>): MutableState<String> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val textState = remember(setting) { mutableStateOf(context.getSetting(setting)) }

    LaunchedEffect(textState.value) {
        delay(SETTING_SAVE_DEBOUNCE_MILLIS)
        val value = textState.value
        if (value != context.getSetting(setting)) {
            context.setSetting(setting, value)
        }
    }

    DisposableEffect(context, lifecycleOwner, textState) {
        onDispose {
            val value = textState.value
            if (value != context.getSetting(setting)) {
                lifecycleOwner.deferSetSetting(context, setting, value)
            }
        }
    }

    return textState
}

val TranslateMenu = UserSettingsMenu(
    title = R.string.translate_addon_title,
    navPath = "addons/translate", registerNavPath = true,
    settings = listOf(
        userSettingDecorationOnly {
            ScreenTitle(stringResource(R.string.translate_addon_title))
        },
        UserSetting(
            name = R.string.translate_setting_enable,
            subtitle = R.string.translate_addon_subtitle
        ) {
            SettingToggleDataStore(
                title = stringResource(R.string.translate_setting_enable),
                subtitle = stringResource(R.string.translate_addon_subtitle),
                setting = TRANSLATE_ADDON_ENABLED
            )
        },
        UserSetting(
            name = R.string.translate_setting_provider
        ) {
            val context = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
            val currentProviderName = useDataStoreValue(TRANSLATE_PROVIDER)
            val selectedType = TranslationProviderType.fromName(currentProviderName)

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SubScreenTitle(stringResource(R.string.translate_setting_provider))
                Spacer(Modifier.height(4.dp))
                SettingRadioGroup(
                    items = translationProviderNames,
                    selectedIndex = translationProviderEntries.indexOf(selectedType).coerceAtLeast(0),
                    onSelect = { idx ->
                        val chosen = translationProviderEntries[idx]
                        lifecycleOwner.deferSetSetting(context, TRANSLATE_PROVIDER, chosen.name)
                    }
                )
            }
        },
        UserSetting(
            name = R.string.translate_setting_api_key,
            subtitle = R.string.translate_setting_api_key_subtitle,
            visibilityCheck = {
                TranslationProviderType.fromName(useDataStoreValue(TRANSLATE_PROVIDER)).requiresApiKey
            }
        ) {
            val textState = rememberPersistedText(TRANSLATE_API_KEY)

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.translate_setting_api_key), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.translate_setting_api_key_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SettingsTextEdit(
                    text = textState,
                    placeholder = "Enter API Key...",
                    autocorrect = false
                )
            }
        },
        UserSetting(
            name = R.string.translate_setting_custom_url,
            subtitle = R.string.translate_setting_custom_url_subtitle,
            visibilityCheck = {
                TranslationProviderType.fromName(useDataStoreValue(TRANSLATE_PROVIDER)).supportsCustomUrl
            }
        ) {
            val textState = rememberPersistedText(TRANSLATE_CUSTOM_URL)

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.translate_setting_custom_url), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.translate_setting_custom_url_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SettingsTextEdit(
                    text = textState,
                    placeholder = "https://libretranslate.com",
                    autocorrect = false
                )
            }
        },
        UserSetting(
            name = R.string.translate_setting_live_translate,
            subtitle = R.string.translate_setting_live_translate_subtitle
        ) {
            SettingToggleDataStore(
                title = stringResource(R.string.translate_setting_live_translate),
                subtitle = stringResource(R.string.translate_setting_live_translate_subtitle),
                setting = TRANSLATE_LIVE_ENABLED
            )
        }
    )
)
