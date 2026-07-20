package org.futo.inputmethod.latin.uix.settings.pages

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.ANIMATE_BUBBLE
import org.futo.inputmethod.latin.uix.AUDIO_FOCUS
import org.futo.inputmethod.latin.uix.CAN_EXPAND_SPACE
import org.futo.inputmethod.latin.uix.DISALLOW_SYMBOLS
import org.futo.inputmethod.latin.uix.ENABLE_SOUND
import org.futo.inputmethod.latin.uix.GROQ_AI_MODEL
import org.futo.inputmethod.latin.uix.GROQ_API_KEY
import org.futo.inputmethod.latin.uix.GROQ_WHISPER_LANGUAGE
import org.futo.inputmethod.latin.uix.GROQ_WHISPER_MODEL
import org.futo.inputmethod.latin.uix.OFFLINE_MODE
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH
import org.futo.inputmethod.latin.uix.USE_PERSONAL_DICT
import org.futo.inputmethod.latin.uix.USE_VAD_AUTOSTOP
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.setSettingBlocking
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore

private val isOfflineMode = @Composable { useDataStoreValue(OFFLINE_MODE) == true }

private val isOnlineMode = @Composable { useDataStoreValue(OFFLINE_MODE) == false }

private val groqApiModels = listOf(
    "none",
    "openai/gpt-oss-120b",
    "openai/gpt-oss-20b"
)

private val whisperModels = listOf(
    "whisper-large-v3",
    "whisper-large-v3-turbo"
)

private val whisperLanguages = listOf(
    "auto" to "Auto-detect",
    "en" to "English",
    "zh" to "Chinese",
    "de" to "German",
    "es" to "Spanish",
    "ru" to "Russian",
    "ko" to "Korean",
    "fr" to "French",
    "ja" to "Japanese",
    "pt" to "Portuguese",
    "tr" to "Turkish",
    "pl" to "Polish",
    "ca" to "Catalan",
    "nl" to "Dutch",
    "ar" to "Arabic",
    "sv" to "Swedish",
    "it" to "Italian",
    "id" to "Indonesian",
    "hi" to "Hindi",
    "fi" to "Finnish",
    "vi" to "Vietnamese",
    "he" to "Hebrew",
    "uk" to "Ukrainian",
    "el" to "Greek",
    "ms" to "Malay",
    "cs" to "Czech",
    "ro" to "Romanian",
    "da" to "Danish",
    "hu" to "Hungarian",
    "ta" to "Tamil",
    "no" to "Norwegian",
    "th" to "Thai",
    "ur" to "Urdu",
    "hr" to "Croatian",
    "bg" to "Bulgarian",
    "lt" to "Lithuanian",
    "la" to "Latin",
    "mi" to "Maori",
    "ml" to "Malayalam",
    "cy" to "Welsh",
    "sk" to "Slovak",
    "te" to "Telugu",
    "fa" to "Persian",
    "lv" to "Latvian",
    "bn" to "Bengali",
    "sr" to "Serbian",
    "az" to "Azerbaijani",
    "sl" to "Slovenian",
    "kn" to "Kannada",
    "et" to "Estonian",
    "mk" to "Macedonian",
    "br" to "Breton",
    "eu" to "Basque",
    "is" to "Icelandic",
    "hy" to "Armenian",
    "ne" to "Nepali",
    "mn" to "Mongolian",
    "bs" to "Bosnian",
    "kk" to "Kazakh",
    "sq" to "Albanian",
    "sw" to "Swahili",
    "gl" to "Galician",
    "mr" to "Marathi",
    "pa" to "Punjabi",
    "si" to "Sinhala",
    "km" to "Khmer",
    "sn" to "Shona",
    "yo" to "Yoruba",
    "so" to "Somali",
    "af" to "Afrikaans",
    "oc" to "Occitan",
    "ka" to "Georgian",
    "be" to "Belarusian",
    "tg" to "Tajik",
    "sd" to "Sindhi",
    "gu" to "Gujarati",
    "am" to "Amharic",
    "yi" to "Yiddish",
    "lo" to "Lao",
    "uz" to "Uzbek",
    "fo" to "Faroese",
    "ht" to "Haitian Creole",
    "ps" to "Pashto",
    "tk" to "Turkmen",
    "nn" to "Norwegian Nynorsk",
    "mt" to "Maltese",
    "sa" to "Sanskrit",
    "lb" to "Luxembourgish",
    "my" to "Myanmar",
    "bo" to "Tibetan",
    "tl" to "Tagalog",
    "mg" to "Malagasy",
    "as" to "Assamese",
    "tt" to "Tatar",
    "haw" to "Hawaiian",
    "ln" to "Lingala",
    "ha" to "Hausa",
    "ba" to "Bashkir",
    "jw" to "Javanese",
    "su" to "Sundanese",
    "yue" to "Cantonese"
)

private val languageMap = whisperLanguages.associate { it.second to it.first }
private val languageNames = whisperLanguages.map { it.second }
private val languageCodes = whisperLanguages.map { it.first }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroqApiKeySetting() {
    val context = LocalContext.current
    val currentKey = remember { mutableStateOf(context.getSettingBlocking(GROQ_API_KEY)) }
    var showKey by remember { mutableStateOf(false) }
    val savedToast = remember { mutableStateOf<Toast?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        Text(
            text = stringResource(R.string.voice_input_settings_groq_api_key),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.voice_input_settings_groq_api_key_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = currentKey.value,
                onValueChange = { newValue ->
                    currentKey.value = newValue
                    context.setSettingBlocking(GROQ_API_KEY.key, newValue)
                    savedToast.value?.cancel()
                    Toast.makeText(context, "API key saved", Toast.LENGTH_SHORT).also {
                        savedToast.value = it
                        it.show()
                    }
                },
                placeholder = { Text("gsk_...") },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroqDropdownSetting(
    label: String,
    subtitle: String? = null,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = options.find { it == selectedOption } ?: selectedOption

    Column(modifier = modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
        } else {
            Spacer(modifier = Modifier.height(4.dp))
        }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                readOnly = true,
                value = selectedName,
                onValueChange = {},
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                colors = ExposedDropdownMenuDefaults.textFieldColors(
                    focusedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    focusedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onOptionSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroqWhisperLanguageSetting() {
    val context = LocalContext.current
    val currentLanguage = remember { mutableStateOf(context.getSettingBlocking(GROQ_WHISPER_LANGUAGE)) }
    var expanded by remember { mutableStateOf(false) }
    val displayName = whisperLanguages.find { it.first == currentLanguage.value }?.second
        ?: whisperLanguages.find { it.first == "en" }?.second ?: "English"

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        Text(
            text = stringResource(R.string.voice_input_settings_whisper_language),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                readOnly = true,
                value = displayName,
                onValueChange = {},
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                colors = ExposedDropdownMenuDefaults.textFieldColors(
                    focusedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    focusedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                whisperLanguages.forEach { (code, name) ->
                    DropdownMenuItem(
                        text = { Text("$name ($code)") },
                        onClick = {
                            currentLanguage.value = code
                            context.setSettingBlocking(GROQ_WHISPER_LANGUAGE.key, code)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

private val offlineModeToggle = userSettingToggleDataStore(
    title = R.string.voice_input_settings_offline_mode,
    subtitle = R.string.voice_input_settings_offline_mode_subtitle,
    setting = OFFLINE_MODE
)

private val groqApiKeySetting = UserSetting(
    name = R.string.voice_input_settings_groq_api_key,
    component = { GroqApiKeySetting() },
    visibilityCheck = isOnlineMode
)

private val groqWhisperModelSetting = UserSetting(
    name = R.string.voice_input_settings_whisper_model,
    component = {
        val context = LocalContext.current
        val currentModel = remember { mutableStateOf(context.getSettingBlocking(GROQ_WHISPER_MODEL)) }
        GroqDropdownSetting(
            label = stringResource(R.string.voice_input_settings_whisper_model),
            options = whisperModels,
            selectedOption = currentModel.value,
            onOptionSelected = {
                currentModel.value = it
                context.setSettingBlocking(GROQ_WHISPER_MODEL.key, it)
            }
        )
    },
    visibilityCheck = isOnlineMode
)

private val groqWhisperLanguageSetting = UserSetting(
    name = R.string.voice_input_settings_whisper_language,
    component = { GroqWhisperLanguageSetting() },
    visibilityCheck = isOnlineMode
)

private val groqAiModelSetting = UserSetting(
    name = R.string.voice_input_settings_ai_model,
    component = {
        val context = LocalContext.current
        val currentModel = remember { mutableStateOf(context.getSettingBlocking(GROQ_AI_MODEL)) }
        GroqDropdownSetting(
            label = stringResource(R.string.voice_input_settings_ai_model),
            subtitle = stringResource(R.string.voice_input_settings_ai_model_subtitle),
            options = groqApiModels,
            selectedOption = currentModel.value,
            onOptionSelected = {
                currentModel.value = it
                context.setSettingBlocking(GROQ_AI_MODEL.key, it)
            }
        )
    },
    visibilityCheck = isOnlineMode
)

// Offline mode settings (same as original, but with visibilityCheck inverted)
private val offlineIndicationSounds = userSettingToggleDataStore(
    title = R.string.voice_input_settings_indication_sounds,
    subtitle = R.string.voice_input_settings_indication_sounds_subtitle,
    setting = ENABLE_SOUND
).copy(visibilityCheck = isOfflineMode)

private val offlineUsePersonalDict = userSettingToggleDataStore(
    title = R.string.voice_input_settings_use_personal_dict,
    subtitle = R.string.voice_input_settings_use_personal_dict_subtitle,
    setting = USE_PERSONAL_DICT
).copy(visibilityCheck = isOfflineMode)

private val offlinePreferBluetooth = userSettingToggleDataStore(
    title = R.string.voice_input_settings_use_bluetooth_mic,
    subtitle = R.string.voice_input_settings_use_bluetooth_mic_subtitle,
    setting = PREFER_BLUETOOTH
).copy(visibilityCheck = isOfflineMode)

private val offlineAudioFocus = userSettingToggleDataStore(
    title = R.string.voice_input_settings_audio_focus,
    subtitle = R.string.voice_input_settings_audio_focus_subtitle,
    setting = AUDIO_FOCUS
).copy(visibilityCheck = isOfflineMode)

private val offlineSuppressSymbols = userSettingToggleDataStore(
    title = R.string.voice_input_settings_suppress_symbols,
    setting = DISALLOW_SYMBOLS
).copy(visibilityCheck = isOfflineMode)

private val offlineLongForm = userSettingToggleDataStore(
    title = R.string.voice_input_settings_long_form,
    subtitle = R.string.voice_input_settings_long_form_subtitle,
    setting = CAN_EXPAND_SPACE
).copy(visibilityCheck = isOfflineMode)

private val offlineAutostopVad = userSettingToggleDataStore(
    title = R.string.voice_input_settings_autostop_vad,
    subtitle = R.string.voice_input_settings_autostop_vad_subtitle,
    setting = USE_VAD_AUTOSTOP
).copy(visibilityCheck = isOfflineMode)

private val offlineAnimateBubble = userSettingToggleDataStore(
    title = R.string.voice_input_settings_animate_bubble,
    subtitle = R.string.voice_input_settings_animate_bubble_subtitle,
    setting = ANIMATE_BUBBLE
).copy(visibilityCheck = isOfflineMode)

private val offlineChangeModels = userSettingNavigationItem(
    title = R.string.voice_input_settings_change_models,
    subtitle = R.string.voice_input_settings_change_models_subtitle,
    style = NavigationItemStyle.Misc,
    navigateTo = "languages"
).copy(visibilityCheck = isOfflineMode)

val VoiceInputMenu = UserSettingsMenu(
    title = R.string.voice_input_settings_title,
    navPath = "voiceInput", registerNavPath = true,
    settings = listOf(
        // Online mode settings (visible when offline mode is OFF)
        groqApiKeySetting,
        groqWhisperModelSetting,
        groqWhisperLanguageSetting,
        groqAiModelSetting,

        // Offline mode settings (visible when offline mode is ON)
        offlineIndicationSounds,
        offlineUsePersonalDict,
        offlinePreferBluetooth,
        offlineAudioFocus,
        offlineSuppressSymbols,
        offlineLongForm,
        offlineAutostopVad,
        offlineAnimateBubble,
        offlineChangeModels,

        // Offline mode toggle (always visible at bottom)
        offlineModeToggle
    )
)