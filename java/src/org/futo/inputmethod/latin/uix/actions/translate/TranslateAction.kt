package org.futo.inputmethod.latin.uix.actions.translate

import android.Manifest
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionTextEditor
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.GROQ_API_KEY
import org.futo.inputmethod.latin.uix.GROQ_WHISPER_LANGUAGE
import org.futo.inputmethod.latin.uix.GROQ_WHISPER_MODEL
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.LocalKeyboardScheme
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSettingBlocking
import org.futo.inputmethod.latin.uix.settings.SettingsActivity
import org.futo.inputmethod.latin.uix.settings.pages.TranslateMenu
import org.futo.voiceinput.shared.GroqRecognizer
import org.futo.voiceinput.shared.types.MagnitudeState
import org.futo.voiceinput.shared.clearCommunicationDevice
import org.futo.voiceinput.shared.pauseMediaIfPlaying
import org.futo.voiceinput.shared.resumeMedia
import org.futo.voiceinput.shared.setCommunicationDevice

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

        Box {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF7A3E1D),
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

        Box {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF7A3E1D),
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
    onClose: () -> Unit,
    keyboardShown: Boolean
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

    var voiceMode by remember { mutableStateOf(false) }

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
        delay(400L)

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
        modifier = Modifier.fillMaxWidth()
    ) {
        TranslateHeader(
            sourceLangCode = sourceLang,
            targetLangCode = targetLang,
            onSourceChanged = {
                sourceLang = it
                context.setSettingBlocking(TRANSLATE_DEFAULT_SOURCE.key, it)
            },
            onTargetChanged = {
                targetLang = it
                context.setSettingBlocking(TRANSLATE_DEFAULT_TARGET.key, targetLang)
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

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = LocalKeyboardScheme.current.keyboardContainer,
            contentColor = LocalKeyboardScheme.current.onKeyboardContainer,
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFD49A76)),
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
                if (voiceMode) {
                    VoiceInputBar(
                        textState = textState,
                        onVoiceDone = { text ->
                            voiceMode = false
                            val current = textState.value.trim()
                            textState.value = if (current.isNotBlank()) current + " " + text.trim() else text.trim()
                            translatedText = ""
                            errorMessage = null
                        },
                        onVoiceCancel = { voiceMode = false }
                    )
                } else {
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
        }

        if (!voiceMode) {
            if (translatedText.isNotBlank() || errorMessage != null) {
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
}

@Composable
private fun VoiceInputBar(
    textState: androidx.compose.runtime.MutableState<String>,
    onVoiceDone: (String) -> Unit,
    onVoiceCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apiKey = context.getSetting(GROQ_API_KEY)

    if (apiKey.isBlank()) {
        Box(modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(R.drawable.mic_fill),
                    contentDescription = stringResource(R.string.action_voice_input_title),
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.action_voice_input_open_settings_button_to_grant_microphone_permission),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(8.dp))
                IconButton(
                    onClick = { SettingsActivity.openToNavDest(context, "voiceInput") }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.mic_fill),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        return
    }

    var whisperModel by remember { mutableStateOf(context.getSetting(GROQ_WHISPER_MODEL)) }
    var whisperLanguage by remember { mutableStateOf(context.getSetting(GROQ_WHISPER_LANGUAGE)) }
    var aiModel by remember { mutableStateOf(context.getSetting(org.futo.inputmethod.latin.uix.GROQ_AI_MODEL)) }

    var isRecording by remember { mutableStateOf(false) }
    var magnitude by remember { mutableFloatStateOf(0.0f) }
    var currentStatus by remember { mutableStateOf(MagnitudeState.NOT_TALKED_YET) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var wasMediaPlaying by remember { mutableStateOf(false) }
    val primaryContainerColor = MaterialTheme.colorScheme.primaryContainer

    LaunchedEffect(Unit) {
        wasMediaPlaying = pauseMediaIfPlaying(context)

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            currentStatus = MagnitudeState.MIC_MAY_BE_BLOCKED
            errorMessage = "Please grant microphone permission to use voice input"
            return@LaunchedEffect
        }

        val preferBluetooth = context.getSetting(PREFER_BLUETOOTH)
        setCommunicationDevice(context, preferBluetooth)

        try {
            val sampleRate = 16000
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                errorMessage = "Failed to initialize audio recorder"
                return@LaunchedEffect
            }

            currentStatus = MagnitudeState.NOT_TALKED_YET
            magnitude = 0.0f
            isRecording = true
            recorder.startRecording()

            val shortBuffer = ShortArray(1600)
            val audioBuffer = mutableListOf<Float>()
            var totalSamples = 0
            val maxSamples = sampleRate * 120
            var hasTalked = false

            while (totalSamples < maxSamples && isActive) {
                yield()
                val nRead = recorder.read(shortBuffer, 0, 1600, AudioRecord.READ_NON_BLOCKING)
                if (nRead <= 0) { delay(50); continue }

                var sumSq = 0.0
                for (i in 0 until nRead) {
                    val f = shortBuffer[i].toFloat() / Short.MAX_VALUE.toFloat()
                    audioBuffer.add(f)
                    sumSq += f * f
                }
                totalSamples += nRead

                val rms = sqrt(sumSq / nRead).toFloat()
                if (rms > 0.01f) hasTalked = true
                magnitude = (1.0f - 0.1f.pow(12.0f * rms)).coerceIn(0f, 1f)
                currentStatus = if (hasTalked) MagnitudeState.TALKING else MagnitudeState.NOT_TALKED_YET
            }

            recorder.stop()
            recorder.release()
            clearCommunicationDevice(context)

            val result = withContext(Dispatchers.IO) {
                GroqRecognizer.transcribe(
                    apiKey = apiKey,
                    audioData = audioBuffer.toFloatArray(),
                    sampleRate = 16000,
                    model = whisperModel,
                    language = if (whisperLanguage == "auto") null else whisperLanguage
                )
            }

            val text = if (result.isSuccess) {
                var v = result.getOrThrow().trim()
                if (v.isNotBlank() && aiModel.isNotBlank() && aiModel != "none") {
                    val enhanced = withContext(Dispatchers.IO) {
                        GroqRecognizer.enhanceText(apiKey, v, aiModel)
                    }
                    enhanced.getOrNull() ?: v
                } else v
            } else {
                throw result.exceptionOrNull() ?: Exception("Transcription failed")
            }

            isRecording = false
            if (text.isNotBlank()) {
                onVoiceDone(text)
            } else {
                errorMessage = "No speech detected"
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isActive) {
                isRecording = false
                errorMessage = e.message ?: "Recording failed"
            }
        } finally {
            isRecording = false
            if (wasMediaPlaying) resumeMedia(context)
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.size(72.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRecording && magnitude > 0.001f && currentStatus != MagnitudeState.MIC_MAY_BE_BLOCKED) {
                        Canvas(modifier = Modifier.fillMaxWidth()) {
                            val boost = sqrt(magnitude.toDouble().coerceIn(0.0, 1.0)).toFloat()
                            val d = size.minDimension
                            val minR = 24.dp.toPx()
                            val maxR = min(d / 2f, 34.dp.toPx())
                            val r = minR + (maxR - minR) * boost
                            drawCircle(
                                color = primaryContainerColor.copy(alpha = 0.35f),
                                radius = r,
                                center = Offset(d / 2f, d / 2f)
                            )
                        }
                    }
                    Icon(
                        painter = painterResource(R.drawable.mic_fill),
                        contentDescription = stringResource(R.string.action_voice_input_title),
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Text(
                    text = when (currentStatus) {
                        MagnitudeState.NOT_TALKED_YET -> stringResource(org.futo.voiceinput.shared.R.string.try_saying_something)
                        MagnitudeState.TALKING -> stringResource(org.futo.voiceinput.shared.R.string.listening)
                        MagnitudeState.MIC_MAY_BE_BLOCKED -> stringResource(org.futo.voiceinput.shared.R.string.no_audio_detected_is_your_microphone_blocked)
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.action_voice_input_title),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                IconButton(onClick = onVoiceCancel) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
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
                TranslateContents(
                    manager = manager,
                    onClose = { manager.closeActionWindow() },
                    keyboardShown = keyboardShown
                )
            }
        }
    }
)
