package org.futo.inputmethod.latin.uix.actions.translate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionTextEditor
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.LocalKeyboardScheme
import org.futo.inputmethod.latin.uix.deferSetSetting
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.actions.EmbeddedVoiceInput
import org.futo.inputmethod.latin.uix.settings.pages.TranslateMenu
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import java.util.concurrent.atomic.AtomicInteger

private val supportedLanguageByCode = ALL_SUPPORTED_LANGUAGES.associateBy { it.code }
private val targetLanguages = ALL_SUPPORTED_LANGUAGES.filterNot { it.code == "auto" }
private val targetLanguageByCode = targetLanguages.associateBy { it.code }

@Composable
fun TranslateHeader(
    sourceLangCode: String,
    targetLangCode: String,
    onSourceChanged: (String) -> Unit,
    onTargetChanged: (String) -> Unit,
    onSwap: () -> Unit
) {
    val srcLang = supportedLanguageByCode[sourceLangCode] ?: ALL_SUPPORTED_LANGUAGES.first()
    val tgtLang = targetLanguageByCode[targetLangCode] ?: targetLanguages.first()

    var showSrcMenu by remember { mutableStateOf(false) }
    var showTgtMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF7A3E1D),
                contentColor = Color.White,
                modifier = Modifier
                    .clickable { showSrcMenu = true }
            ) {
                Text(
                    text = srcLang.name,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            DropdownMenu(expanded = showSrcMenu, onDismissRequest = { showSrcMenu = false }) {
                ALL_SUPPORTED_LANGUAGES.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(lang.name) },
                        onClick = {
                            onSourceChanged(lang.code)
                            showSrcMenu = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        IconButton(
            onClick = onSwap,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_swap),
                contentDescription = "Swap Languages",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF7A3E1D),
                contentColor = Color.White,
                modifier = Modifier
                    .clickable { showTgtMenu = true }
            ) {
                Text(
                    text = tgtLang.name,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            DropdownMenu(expanded = showTgtMenu, onDismissRequest = { showTgtMenu = false }) {
                targetLanguages.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(lang.name) },
                        onClick = {
                            onTargetChanged(lang.code)
                            showTgtMenu = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun TranslateContents(
    manager: KeyboardManagerForAction,
    keyboardShown: Boolean,
    onSupplementalContentChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val providerType = remember(context) {
        TranslationProviderType.fromName(context.getSetting(TRANSLATE_PROVIDER))
    }
    val apiKey = remember(context) { context.getSetting(TRANSLATE_API_KEY) }
    val customUrl = remember(context) { context.getSetting(TRANSLATE_CUSTOM_URL) }
    val showLiveTranslation = useDataStoreValue(TRANSLATE_LIVE_ENABLED) == true

    var sourceLang by remember { mutableStateOf(context.getSetting(TRANSLATE_DEFAULT_SOURCE)) }
    var targetLang by remember { mutableStateOf(context.getSetting(TRANSLATE_DEFAULT_TARGET)) }

    val textState = remember { mutableStateOf("") }
    val query by remember { derivedStateOf { textState.value.trim() } }
    var translatedText by remember { mutableStateOf("") }
    var isTranslating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val translationRequestVersion = remember { AtomicInteger() }

    var voiceMode by remember { mutableStateOf(false) }

    LaunchedEffect(query, sourceLang, targetLang) {
        val requestVersion = translationRequestVersion.incrementAndGet()
        isTranslating = false
        if (query.isEmpty()) {
            translatedText = ""
            errorMessage = null
            isTranslating = false
            return@LaunchedEffect
        }

        errorMessage = null
        delay(400L)
        isTranslating = true

        try {
            val res = TranslationService.translate(
                text = query,
                sourceLang = sourceLang,
                targetLang = targetLang,
                providerType = providerType,
                apiKey = apiKey,
                customUrl = customUrl
            )

            res.onSuccess {
                translatedText = it
                errorMessage = null
            }.onFailure {
                errorMessage = it.localizedMessage ?: "Translation failed"
            }
        } finally {
            if (translationRequestVersion.get() == requestVersion) {
                isTranslating = false
            }
        }
    }

    LaunchedEffect(voiceMode, translatedText, errorMessage, showLiveTranslation) {
        onSupplementalContentChanged(
            voiceMode ||
                (showLiveTranslation && translatedText.isNotBlank()) ||
                errorMessage != null
        )
    }

    if (voiceMode) {
        EmbeddedVoiceInput(manager = manager) { text ->
            voiceMode = false
            val current = textState.value.trim()
            textState.value = if (current.isNotBlank()) {
                "$current ${text.trim()}"
            } else {
                text.trim()
            }
            translatedText = ""
            errorMessage = null
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        TranslateHeader(
            sourceLangCode = sourceLang,
            targetLangCode = targetLang,
            onSourceChanged = {
                sourceLang = it
                lifecycleOwner.deferSetSetting(context, TRANSLATE_DEFAULT_SOURCE, it)
            },
            onTargetChanged = {
                targetLang = it
                lifecycleOwner.deferSetSetting(context, TRANSLATE_DEFAULT_TARGET, it)
            },
            onSwap = {
                if (sourceLang != "auto") {
                    val temp = sourceLang
                    sourceLang = targetLang
                    targetLang = temp
                    lifecycleOwner.deferSetSetting(context, TRANSLATE_DEFAULT_SOURCE, sourceLang)
                    lifecycleOwner.deferSetSetting(context, TRANSLATE_DEFAULT_TARGET, targetLang)
                }
            }
        )

        Spacer(Modifier.height(4.dp))

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = LocalKeyboardScheme.current.keyboardContainer,
            contentColor = LocalKeyboardScheme.current.onKeyboardContainer,
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFD49A76)),
            modifier = if (keyboardShown) {
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            } else {
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            }
        ) {
            Row(
                modifier = if (keyboardShown) {
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                } else {
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp)
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = if (keyboardShown) {
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                    } else {
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    }
                ) {
                    ActionTextEditor(
                        text = textState,
                        multiline = true,
                        centerVertically = true,
                        placeholder = "Type text to translate...",
                        autofocus = true,
                        modifier = if (keyboardShown) {
                            Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                        } else {
                            Modifier.fillMaxSize()
                        }
                    )
                }

                if (isTranslating) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (translatedText.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            manager.typeText(translatedText)
                            textState.value = ""
                            translatedText = ""
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Insert Translation",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (!keyboardShown) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { voiceMode = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.mic_fill),
                            contentDescription = stringResource(R.string.action_voice_input_title),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        if ((showLiveTranslation && translatedText.isNotBlank()) || errorMessage != null) {
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text(
                    text = errorMessage ?: translatedText,
                    color = if (errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

val TranslateAction = Action(
    icon = R.drawable.ic_translate,
    name = R.string.action_translate_title,
    canShowKeyboard = true,
    simplePressImpl = null,
    settingsMenu = TranslateMenu,
    windowImpl = { manager, _ ->
        object : ActionWindow() {
            private val hasSupplementalContent = mutableStateOf(false)

            override val showCloseButton: Boolean get() = false
            override val positionIsUserManagable: Boolean get() = false
            override val fixedWindowHeightWhenKeyboardShown
                get() = if (hasSupplementalContent.value) 140.dp else 92.dp

            @Composable
            override fun windowName(): String = stringResource(R.string.action_translate_title)

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                TranslateContents(
                    manager = manager,
                    keyboardShown = keyboardShown,
                    onSupplementalContentChanged = { hasSupplementalContent.value = it }
                )
            }
        }
    }
)
