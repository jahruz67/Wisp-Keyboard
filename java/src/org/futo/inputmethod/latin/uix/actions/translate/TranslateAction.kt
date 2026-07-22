package org.futo.inputmethod.latin.uix.actions.translate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionTextEditor
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.LocalKeyboardScheme
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSettingBlocking
import org.futo.inputmethod.latin.uix.settings.pages.TranslateMenu

@Composable
fun TranslateHeader(
    sourceLangCode: String,
    targetLangCode: String,
    onSourceChanged: (String) -> Unit,
    onTargetChanged: (String) -> Unit,
    onSwap: () -> Unit,
    onClose: () -> Unit
) {
    val srcLang = ALL_SUPPORTED_LANGUAGES.find { it.code == sourceLangCode } ?: ALL_SUPPORTED_LANGUAGES.first()
    val targetLangs = ALL_SUPPORTED_LANGUAGES.filter { it.code != "auto" }
    val tgtLang = targetLangs.find { it.code == targetLangCode } ?: targetLangs.first()

    var showSrcMenu by remember { mutableStateOf(false) }
    var showTgtMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back Button matching screenshot circle
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.width(8.dp))

        // Source Language Pill
        Box {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF7A3E1D), // Warm accent brown tone as shown in reference image
                contentColor = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
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

        // Swap Icon Button
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

        // Target Language Pill
        Box {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF7A3E1D), // Warm accent brown tone as shown in reference image
                contentColor = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
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
                targetLangs.forEach { lang ->
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
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val currentProviderName = context.getSetting(TRANSLATE_PROVIDER)
    val providerType = try {
        TranslationProviderType.valueOf(currentProviderName)
    } catch (e: Exception) {
        TranslationProviderType.GOOGLE_FREE
    }

    val apiKey = context.getSetting(TRANSLATE_API_KEY)
    val customUrl = context.getSetting(TRANSLATE_CUSTOM_URL)

    var sourceLang by remember { mutableStateOf(context.getSetting(TRANSLATE_DEFAULT_SOURCE)) }
    var targetLang by remember { mutableStateOf(context.getSetting(TRANSLATE_DEFAULT_TARGET)) }

    val textState = remember { mutableStateOf("") }
    var translatedText by remember { mutableStateOf("") }
    var isTranslating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Live debounce translation trigger
    LaunchedEffect(textState.value, sourceLang, targetLang) {
        val query = textState.value.trim()
        if (query.isEmpty()) {
            translatedText = ""
            errorMessage = null
            isTranslating = false
            return@LaunchedEffect
        }

        isTranslating = true
        errorMessage = null
        delay(400L) // 400ms debounce

        val res = TranslationService.translate(
            text = query,
            sourceLang = sourceLang,
            targetLang = targetLang,
            providerType = providerType,
            apiKey = apiKey,
            customUrl = customUrl
        )

        isTranslating = false
        res.onSuccess {
            translatedText = it
            errorMessage = null
        }.onFailure {
            errorMessage = it.localizedMessage ?: "Translation failed"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        // Header matching image
        TranslateHeader(
            sourceLangCode = sourceLang,
            targetLangCode = targetLang,
            onSourceChanged = {
                sourceLang = it
                context.setSettingBlocking(TRANSLATE_DEFAULT_SOURCE.key, it)
            },
            onTargetChanged = {
                targetLang = it
                context.setSettingBlocking(TRANSLATE_DEFAULT_TARGET.key, it)
            },
            onSwap = {
                if (sourceLang != "auto") {
                    val temp = sourceLang
                    sourceLang = targetLang
                    targetLang = temp
                    context.setSettingBlocking(TRANSLATE_DEFAULT_SOURCE.key, sourceLang)
                    context.setSettingBlocking(TRANSLATE_DEFAULT_TARGET.key, targetLang)
                }
            },
            onClose = onClose
        )

        Spacer(Modifier.height(4.dp))

        // Text Input Bar styled like image (rounded container with cursor)
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = LocalKeyboardScheme.current.keyboardContainer,
            contentColor = LocalKeyboardScheme.current.onKeyboardContainer,
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFD49A76)), // Warm subtle border ring
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    ActionTextEditor(
                        text = textState,
                        placeholder = "Type text to translate...",
                        autofocus = true
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
            }
        }

        // Live Translated Output Preview
        if (translatedText.isNotBlank() || errorMessage != null) {
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
            override val showCloseButton: Boolean get() = false
            override val positionIsUserManagable: Boolean get() = false

            @Composable
            override fun windowName(): String = stringResource(R.string.action_translate_title)

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                TranslateContents(manager = manager, onClose = { manager.closeActionWindow() })
            }
        }
    }
)
