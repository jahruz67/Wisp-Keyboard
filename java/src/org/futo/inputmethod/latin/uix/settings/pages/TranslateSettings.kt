package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.actions.translate.TRANSLATE_ADDON_ENABLED
import org.futo.inputmethod.latin.uix.actions.translate.TRANSLATE_API_KEY
import org.futo.inputmethod.latin.uix.actions.translate.TRANSLATE_CUSTOM_URL
import org.futo.inputmethod.latin.uix.actions.translate.TRANSLATE_LIVE_ENABLED
import org.futo.inputmethod.latin.uix.actions.translate.TRANSLATE_PROVIDER
import org.futo.inputmethod.latin.uix.actions.translate.TranslationProviderType
import org.futo.inputmethod.latin.uix.SettingsTextEdit
import org.futo.inputmethod.latin.uix.setSettingBlocking
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.SettingRadioGroup
import org.futo.inputmethod.latin.uix.settings.SettingToggleDataStore
import org.futo.inputmethod.latin.uix.settings.SubScreenTitle
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly

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
            val currentProviderName = useDataStoreValue(TRANSLATE_PROVIDER)
            val selectedType = try {
                TranslationProviderType.valueOf(currentProviderName)
            } catch (e: Exception) {
                TranslationProviderType.GOOGLE_FREE
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SubScreenTitle(stringResource(R.string.translate_setting_provider))
                Spacer(Modifier.height(4.dp))
                SettingRadioGroup(
                    items = TranslationProviderType.entries.map { it.displayName },
                    selectedIndex = TranslationProviderType.entries.indexOf(selectedType).coerceAtLeast(0),
                    onSelect = { idx ->
                        val chosen = TranslationProviderType.entries[idx]
                        context.setSettingBlocking(TRANSLATE_PROVIDER.key, chosen.name)
                    }
                )
            }
        },
        UserSetting(
            name = R.string.translate_setting_api_key,
            subtitle = R.string.translate_setting_api_key_subtitle,
            visibilityCheck = {
                val currentProviderName = useDataStoreValue(TRANSLATE_PROVIDER)
                val type = try { TranslationProviderType.valueOf(currentProviderName) } catch (e: Exception) { TranslationProviderType.GOOGLE_FREE }
                type.requiresApiKey
            }
        ) {
            val context = LocalContext.current
            val apiKey = useDataStoreValue(TRANSLATE_API_KEY)
            val textState = remember(apiKey) { mutableStateOf(apiKey) }

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.translate_setting_api_key), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.translate_setting_api_key_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SettingsTextEdit(
                    text = textState,
                    placeholder = "Enter API Key...",
                    autocorrect = false
                )
                if (textState.value != apiKey) {
                    context.setSettingBlocking(TRANSLATE_API_KEY.key, textState.value)
                }
            }
        },
        UserSetting(
            name = R.string.translate_setting_custom_url,
            subtitle = R.string.translate_setting_custom_url_subtitle,
            visibilityCheck = {
                val currentProviderName = useDataStoreValue(TRANSLATE_PROVIDER)
                val type = try { TranslationProviderType.valueOf(currentProviderName) } catch (e: Exception) { TranslationProviderType.GOOGLE_FREE }
                type.supportsCustomUrl
            }
        ) {
            val context = LocalContext.current
            val customUrl = useDataStoreValue(TRANSLATE_CUSTOM_URL)
            val textState = remember(customUrl) { mutableStateOf(customUrl) }

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.translate_setting_custom_url), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.translate_setting_custom_url_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SettingsTextEdit(
                    text = textState,
                    placeholder = "https://libretranslate.com",
                    autocorrect = false
                )
                if (textState.value != customUrl) {
                    context.setSettingBlocking(TRANSLATE_CUSTOM_URL.key, textState.value)
                }
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
