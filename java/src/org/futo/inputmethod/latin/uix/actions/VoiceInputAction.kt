package org.futo.inputmethod.latin.uix.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.ANIMATE_BUBBLE
import org.futo.inputmethod.latin.uix.AUDIO_FOCUS
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CAN_EXPAND_SPACE
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.DISALLOW_SYMBOLS
import org.futo.inputmethod.latin.uix.ENABLE_SOUND
import org.futo.inputmethod.latin.uix.GROQ_AI_MODEL
import org.futo.inputmethod.latin.uix.GROQ_API_KEY
import org.futo.inputmethod.latin.uix.GROQ_WHISPER_LANGUAGE
import org.futo.inputmethod.latin.uix.GROQ_WHISPER_MODEL
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.OFFLINE_MODE
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH
import org.futo.inputmethod.latin.uix.PersistentActionState
import org.futo.inputmethod.latin.uix.ResourceHelper
import org.futo.inputmethod.latin.uix.USE_PERSONAL_DICT
import org.futo.inputmethod.latin.uix.USE_VAD_AUTOSTOP
import org.futo.inputmethod.latin.uix.VERBOSE_PROGRESS
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.SettingsActivity
import org.futo.inputmethod.latin.uix.utils.ModelOutputSanitizer
import org.futo.inputmethod.latin.xlm.UserDictionaryObserver
import org.futo.inputmethod.updates.openURI
import org.futo.voiceinput.shared.GroqRecognizer
import org.futo.voiceinput.shared.pauseMediaIfPlaying
import org.futo.voiceinput.shared.resumeMedia
import org.futo.voiceinput.shared.ModelDoesNotExistException
import org.futo.voiceinput.shared.RecognizerView
import org.futo.voiceinput.shared.RecognizerViewListener
import org.futo.voiceinput.shared.RecognizerViewSettings
import org.futo.voiceinput.shared.RecordingSettings
import org.futo.voiceinput.shared.SoundPlayer
import androidx.compose.runtime.mutableFloatStateOf
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.MagnitudeState
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.types.getLanguageFromWhisperString
import org.futo.voiceinput.shared.clearCommunicationDevice
import org.futo.voiceinput.shared.isBluetoothAvailable
import org.futo.voiceinput.shared.setCommunicationDevice
import org.futo.voiceinput.shared.ui.RecognizeMicError
import org.futo.voiceinput.shared.ui.InnerRecognize
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import org.futo.voiceinput.shared.ui.RecognizeLoadingCircle
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.ModelManager
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

val SystemVoiceInputAction = Action(
    icon = R.drawable.mic_fill,
    name = R.string.action_system_voice_input_title,
    simplePressImpl = { it, _ ->
        it.triggerSystemVoiceInput()
    },
    persistentState = null,
    windowImpl = null,
    shownInEditor = false
)


@Composable
fun NoModelInstalled(locale: Locale) {
    val context = LocalContext.current
    Box(modifier = Modifier
        .fillMaxSize()
        .clickable(
            enabled = true,
            onClickLabel = null,
            onClick = {
                context.openURI("https://keyboard.futo.org/voice-input-models", true)
            },
            role = null,
            indication = null,
            interactionSource = remember { MutableInteractionSource() })) {
        Text(
            stringResource(
                R.string.action_voice_input_no_model_for_language_x_installed,
                locale.getDisplayName(locale)
            ), modifier = Modifier
                .align(Alignment.Center)
                .padding(8.dp), textAlign = TextAlign.Center)
    }
}

class VoiceInputPersistentState(val manager: KeyboardManagerForAction) : PersistentActionState {
    val modelManager = ModelManager(manager.getContext())
    val soundPlayer = SoundPlayer(manager.getContext())
    val userDictionaryObserver = UserDictionaryObserver(manager.getContext())

    override suspend fun cleanUp() {
        modelManager.cleanUp()
    }

    override fun close() {
        runBlocking { modelManager.cleanUp() }
        userDictionaryObserver.unregister()
    }
}

private class VoiceInputActionWindow(
    val manager: KeyboardManagerForAction, val state: VoiceInputPersistentState,
    val model: ModelLoader, val locales: List<Locale>,
    val resultHandler: ((String) -> Unit)? = null
) : ActionWindow(), RecognizerViewListener {
    val context = manager.getContext()

    private var shouldPlaySounds: Boolean = false
    private fun loadSettings(): RecognizerViewSettings {
        val enableSound = context.getSetting(ENABLE_SOUND)
        val verboseFeedback = false//context.getSetting(VERBOSE_PROGRESS)
        val disallowSymbols = context.getSetting(DISALLOW_SYMBOLS)
        val useBluetoothAudio = context.getSetting(PREFER_BLUETOOTH)
        val requestAudioFocus = context.getSetting(AUDIO_FOCUS)
        val canExpandSpace = context.getSetting(CAN_EXPAND_SPACE)
        val useVAD = context.getSetting(USE_VAD_AUTOSTOP)
        val usePersonalDict = context.getSetting(USE_PERSONAL_DICT)
        val animateBubble = context.getSetting(ANIMATE_BUBBLE)

        val primaryModel = model
        val languageSpecificModels = mutableMapOf<Language, ModelLoader>()
        val allowedLanguages = locales.mapNotNull { getLanguageFromWhisperString(it.language) }.toSet()
        val glossary = if(usePersonalDict) {
            state.userDictionaryObserver.getWords(locales).filter { it.shortcut.isNullOrEmpty() }.map { it.word }
        } else {
            emptyList()
        }

        shouldPlaySounds = enableSound

        return RecognizerViewSettings(
            shouldShowInlinePartialResult = false,
            shouldShowVerboseFeedback = verboseFeedback,
            shouldAnimateBubble = animateBubble,
            modelRunConfiguration = MultiModelRunConfiguration(
                primaryModel = primaryModel,
                languageSpecificModels = languageSpecificModels
            ),
            decodingConfiguration = DecodingConfiguration(
                glossary = glossary,
                languages = allowedLanguages,
                suppressSymbols = disallowSymbols
            ),
            recordingConfiguration = RecordingSettings(
                preferBluetoothMic = useBluetoothAudio,
                requestAudioFocus = requestAudioFocus,
                canExpandSpace = canExpandSpace,
                useVADAutoStop = useVAD
            )
        )
    }

    private var recognizerView: MutableState<RecognizerView?> = mutableStateOf(null)
    private var modelException: MutableState<ModelDoesNotExistException?> = mutableStateOf(null)

    private val initJob = manager.getLifecycleScope().launch(Dispatchers.Default) {
        yield()
        val settings = loadSettings()

        yield()
        val recognizerView = try {
            RecognizerView(
                context = manager.getContext(),
                listener = this@VoiceInputActionWindow,
                settings = settings,
                lifecycleScope = manager.getLifecycleScope(),
                modelManager = state.modelManager
            )
        } catch(e: ModelDoesNotExistException) {
            withContext(Dispatchers.Main) {
                modelException.value = e
            }
            return@launch
        }

        // This window can be created from inside another composition (Translate). Publish and
        // start on Main so that Compose reliably observes the new recognizer state.
        withContext(Dispatchers.Main) {
            this@VoiceInputActionWindow.recognizerView.value = recognizerView
            recognizerView.reset()
            recognizerView.start()
        }
    }

    private var inputTransaction = if (resultHandler == null) manager.createInputTransaction() else null

    @Composable
    private fun ModelDownloader(modelException: ModelDoesNotExistException) {
        NoModelInstalled(locales.firstOrNull() ?: Locale.ROOT)
    }

    @Composable
    override fun windowName(): String {
        return stringResource(R.string.action_voice_input_title)
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        Box(modifier = Modifier
            .fillMaxSize()
            .clickable(
                enabled = true,
                onClickLabel = null,
                onClick = { recognizerView.value?.finish() },
                role = null,
                indication = null,
                interactionSource = remember { MutableInteractionSource() })
            .semantics(mergeDescendants = true) {
                traversalIndex = -1.0f
            }) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                when {
                    modelException.value != null -> ModelDownloader(modelException.value!!)
                    recognizerView.value != null -> recognizerView.value!!.Content()
                    else -> Column {
                        RecognizeLoadingCircle(
                            text = stringResource(org.futo.voiceinput.shared.R.string.initializing)
                        )
                    }
                }
            }
        }
    }

    override fun close(): CloseResult {
        inputTransaction?.cancel()
        initJob.cancel()
        recognizerView.value?.cancel()
        state.modelManager.cancelAll()
        return CloseResult.Default
    }

    private var wasFinished = false
    private var cancelPlayed = false
    override fun cancelled() {
        if (!wasFinished) {
            if (shouldPlaySounds && !cancelPlayed) {
                state.soundPlayer.playCancelSound()
                cancelPlayed = true
            }
            inputTransaction?.cancel()
        }
    }

    override fun recordingStarted(device: MicrophoneDeviceState) {
        if (shouldPlaySounds) {
            state.soundPlayer.playStartSound()
        }

        // Only set the setting if bluetooth is available, else it would reset the setting
        // every time it's used without a bluetooth device connected.
        if(device.bluetoothAvailable) {
            manager.getLifecycleScope().launch {
                context.setSetting(PREFER_BLUETOOTH, device.bluetoothActive)
            }
        }
    }

    override fun finished(result: String) {
        wasFinished = true

        manager.getLifecycleScope().launch(Dispatchers.Main) {
            if (resultHandler != null) {
                resultHandler.invoke(result)
            } else {
                val transaction = inputTransaction ?: return@launch
                val sanitized = ModelOutputSanitizer.sanitize(result, transaction.textContext)
                transaction.commit(sanitized)
                manager.announce(result)
                manager.closeActionWindow()
            }
        }
    }

    override fun partialResult(result: String) {
        val transaction = inputTransaction ?: return
        manager.getLifecycleScope().launch(Dispatchers.Main) {
            val sanitized = ModelOutputSanitizer.sanitize(result, transaction.textContext)
            transaction.updatePartial(sanitized)
        }
    }

    override fun requestPermission(onGranted: () -> Unit, onRejected: () -> Unit): Boolean {
        return false
    }

    override fun openSettings() {
        SettingsActivity.openToNavDest(context, "languages")
    }
}

private class VoiceInputNoModelWindow(val locale: Locale) : ActionWindow() {
    @Composable
    override fun windowName(): String {
        return stringResource(R.string.action_voice_input_title)
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        NoModelInstalled(locale)
    }
}

val VoiceInputAction = Action(icon = R.drawable.mic_fill,
    name = R.string.action_voice_input_title,
    simplePressImpl = null,
    keepScreenAwake = true,
    persistentState = { VoiceInputPersistentState(it) },
    windowImpl = { manager, persistentState ->
        val locales = manager.getActiveLocales()

        val model = ResourceHelper.tryFindingVoiceInputModelForLocale(manager.getContext(), locales.firstOrNull() ?: Locale.ROOT)

        if(model == null) {
            VoiceInputNoModelWindow(locales.firstOrNull() ?: Locale.ROOT)
        } else {
            VoiceInputActionWindow(
                manager = manager, state = persistentState as VoiceInputPersistentState,
                locales = locales, model = model
            )
        }
    }
)

// ============ Groq Voice Input ============

private class GroqVoiceInputActionWindow(
    val manager: KeyboardManagerForAction,
    val resultHandler: ((String) -> Unit)? = null
) : ActionWindow(), RecognizerViewListener {
    val context = manager.getContext()
    private val soundPlayer = SoundPlayer(context)

    private enum class CurrentView {
        LoadingCircle, InnerRecognize, PermissionError, Error
    }

    private var statusText by mutableStateOf<String?>(null)
    private var errorText by mutableStateOf<String?>(null)
    private var currentViewState by mutableStateOf(CurrentView.LoadingCircle)

    private val magnitudeState = mutableFloatStateOf(0.0f)
    private val statusState = mutableStateOf(MagnitudeState.NOT_TALKED_YET)
    private val currentDeviceState = mutableStateOf(MicrophoneDeviceState(
        bluetoothAvailable = false,
        bluetoothActive = false,
        setBluetooth = { },
        deviceName = "",
        bluetoothPreferredByUser = false
    ))

    private var inputTransaction = if (resultHandler == null) manager.createInputTransaction() else null
    private var shouldPlaySounds = context.getSetting(ENABLE_SOUND)

    private var recordingJob: kotlinx.coroutines.Job? = null
    private var audioBuffer = FloatArray(0)
    private var hasStarted = false
    private var stopRequested = false
    private var wasMediaPlaying = false

    @Composable
    override fun windowName(): String {
        return stringResource(R.string.action_voice_input_title)
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        LaunchedEffect(Unit) {
            if (!hasStarted) {
                hasStarted = true
                startRecording()
            }
        }
        Box(modifier = Modifier
            .fillMaxSize()
            .clickable(
                enabled = currentViewState == CurrentView.InnerRecognize,
                onClickLabel = null,
                onClick = { stopRecordingAndTranscribe() },
                role = null,
                indication = null,
                interactionSource = remember { MutableInteractionSource() })
            .semantics(mergeDescendants = true) {
                traversalIndex = -1.0f
            }) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                when (currentViewState) {
                    CurrentView.LoadingCircle -> {
                        Column {
                            RecognizeLoadingCircle(text = statusText ?: stringResource(org.futo.voiceinput.shared.R.string.processing))
                        }
                    }
                    CurrentView.InnerRecognize -> {
                        InnerRecognize(
                            magnitude = magnitudeState,
                            state = statusState,
                            device = currentDeviceState
                        )
                    }
                    CurrentView.PermissionError -> {
                        Column {
                            RecognizeMicError(openSettings = {
                                requestPermission(onGranted = { startRecording() }, onRejected = {})
                            })
                        }
                    }
                    CurrentView.Error -> {
                        Box(modifier = Modifier
                            .fillMaxSize()
                            .clickable(onClick = { openSettings() })) {
                            Text(
                                text = errorText ?: stringResource(org.futo.voiceinput.shared.R.string.model_load_error),
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(8.dp),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startRecording() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            currentViewState = CurrentView.PermissionError
            return
        }

        val preferBluetooth = context.getSetting(PREFER_BLUETOOTH)
        val (bluetoothActive, deviceName) = setCommunicationDevice(context, preferBluetooth)
        val bluetoothAvailable = isBluetoothAvailable(context)

        currentDeviceState.value = MicrophoneDeviceState(
            bluetoothAvailable = bluetoothAvailable,
            bluetoothActive = bluetoothActive,
            bluetoothPreferredByUser = preferBluetooth,
            setBluetooth = { enable ->
                manager.getLifecycleScope().launch {
                    context.setSetting(PREFER_BLUETOOTH, enable)
                    stopRequested = true
                    recordingJob?.cancelAndJoin()
                    startRecording()
                }
            },
            deviceName = deviceName
        )

        statusText = null
        errorText = null
        audioBuffer = FloatArray(0)
        stopRequested = false

        // Pause media only if it's actively playing — track whether we paused it
        wasMediaPlaying = pauseMediaIfPlaying(context)

        recordingJob = manager.getLifecycleScope().launch(Dispatchers.Default) {
            try {
                recordAudio()
                // Normal completion (timed out or stopRequested was set)
                if (audioBuffer.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        currentViewState = CurrentView.LoadingCircle
                        statusText = context.getString(org.futo.voiceinput.shared.R.string.processing)
                    }
                    transcribeGroq()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    currentViewState = CurrentView.Error
                    errorText = "Recording failed: ${e.message}"
                }
            }
        }
    }

    private suspend fun recordAudio() {
        val sampleRate = 16000
        val bufferSize = android.media.AudioRecord.getMinBufferSize(
            sampleRate,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )

        val recorder = android.media.AudioRecord(
            android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            bufferSize.coerceAtLeast(4096)
        )

        try {
            if (recorder.state != android.media.AudioRecord.STATE_INITIALIZED) {
                withContext(Dispatchers.Main) {
                    currentViewState = CurrentView.Error
                    errorText = "Failed to initialize audio recorder"
                }
                return
            }

            withContext(Dispatchers.Main) {
                if (shouldPlaySounds) {
                    soundPlayer.playStartSound()
                }
                magnitudeState.floatValue = 0.0f
                statusState.value = MagnitudeState.NOT_TALKED_YET
                currentViewState = CurrentView.InnerRecognize
            }

            recorder.startRecording()

            val shortBuffer = ShortArray(1600)
            var totalSamples = 0
            val maxSamples = sampleRate * 120 // 2 minutes max
            val recordedSamples = FloatArray(maxSamples)
            var hasTalked = false

            while (totalSamples < maxSamples && !stopRequested) {
                yield()
                val samplesToRead = minOf(shortBuffer.size, maxSamples - totalSamples)
                val nRead = recorder.read(
                    shortBuffer,
                    0,
                    samplesToRead,
                    android.media.AudioRecord.READ_NON_BLOCKING
                )
                if (nRead <= 0) {
                    kotlinx.coroutines.delay(50)
                    continue
                }

                var sumSq = 0.0
                for (i in 0 until nRead) {
                    val floatSample = shortBuffer[i].toFloat() / Short.MAX_VALUE.toFloat()
                    recordedSamples[totalSamples + i] = floatSample
                    sumSq += floatSample * floatSample
                }
                totalSamples += nRead

                val rms = sqrt(sumSq / nRead).toFloat()
                if (rms > 0.01f) {
                    hasTalked = true
                }
                val magnitude = (1.0f - 0.1f.toDouble().pow(24.0 * rms)).toFloat()
                val state = if (hasTalked) {
                    MagnitudeState.TALKING
                } else {
                    MagnitudeState.NOT_TALKED_YET
                }

                withContext(Dispatchers.Main) {
                    magnitudeState.floatValue = magnitude
                    statusState.value = state
                }
            }

            audioBuffer = recordedSamples.copyOf(totalSamples)
        } finally {
            try {
                if (recorder.recordingState == android.media.AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            } catch (_: IllegalStateException) {
                // The audio service may already have stopped the recorder.
            }
            recorder.release()
        }
    }

    private fun resumeMediaIfWePaused() {
        if (wasMediaPlaying) {
            resumeMedia(context)
            wasMediaPlaying = false
        }
    }

    private fun transcribeGroq() {
        manager.getLifecycleScope().launch(Dispatchers.Default) {
            try {
                val apiKey = context.getSetting(GROQ_API_KEY)
                if (apiKey.isBlank()) {
                    withContext(Dispatchers.Main) {
                        currentViewState = CurrentView.Error
                        errorText = "Groq API key not set. Please configure it in Settings."
                    }
                    return@launch
                }

                val whisperModel = context.getSetting(GROQ_WHISPER_MODEL)
                val whisperLanguage = context.getSetting(GROQ_WHISPER_LANGUAGE)
                val aiModel = context.getSetting(GROQ_AI_MODEL)

                withContext(Dispatchers.Main) {
                    currentViewState = CurrentView.LoadingCircle
                    statusText = context.getString(org.futo.voiceinput.shared.R.string.decoding)
                }

                val transcriptionResult = GroqRecognizer.transcribe(
                    apiKey = apiKey,
                    audioData = audioBuffer,
                    sampleRate = 16000,
                    model = whisperModel,
                    language = if (whisperLanguage == "auto") null else whisperLanguage
                )

                if (transcriptionResult.isFailure) {
                    withContext(Dispatchers.Main) {
                        currentViewState = CurrentView.Error
                        errorText = "Transcription failed: ${transcriptionResult.exceptionOrNull()?.message}"
                    }
                    return@launch
                }

                val transcribedText = transcriptionResult.getOrThrow()

                if (transcribedText.isBlank()) {
                    withContext(Dispatchers.Main) {
                        currentViewState = CurrentView.Error
                        errorText = "No speech detected. Please try again."
                    }
                    return@launch
                }

                val finalText = if (aiModel.isBlank() || aiModel == "none") {
                    transcribedText
                } else {
                    withContext(Dispatchers.Main) {
                        currentViewState = CurrentView.LoadingCircle
                        statusText = "Enhancing..."
                    }

                    val enhancedResult = GroqRecognizer.enhanceText(
                        apiKey = apiKey,
                        text = transcribedText,
                        model = aiModel
                    )

                    enhancedResult.getOrNull() ?: transcribedText
                }

                withContext(Dispatchers.Main) {
                    if (shouldPlaySounds) {
                        soundPlayer.playStartSound()
                    }

                    if (resultHandler != null) {
                        resultHandler.invoke(finalText)
                    } else {
                        val transaction = inputTransaction ?: return@withContext
                        val sanitized = ModelOutputSanitizer.sanitize(finalText, transaction.textContext)
                        transaction.commit(sanitized)
                        manager.announce(finalText)
                        manager.closeActionWindow()
                    }
                }

                // Resume media after successful transcription
                resumeMediaIfWePaused()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    currentViewState = CurrentView.Error
                    errorText = "Error: ${e.message}"
                }
                resumeMediaIfWePaused()
            }
        }
    }

    private fun stopRecordingAndTranscribe() {
        if (stopRequested) return
        stopRequested = true
        currentViewState = CurrentView.LoadingCircle
        statusText = context.getString(org.futo.voiceinput.shared.R.string.processing)
    }

    override fun close(): CloseResult {
        clearCommunicationDevice(context)
        resumeMediaIfWePaused()
        recordingJob?.cancel()
        inputTransaction?.cancel()
        return CloseResult.Default
    }

    override fun cancelled() {
        if (!wasFinished) {
            if (shouldPlaySounds) {
                soundPlayer.playCancelSound()
            }
            clearCommunicationDevice(context)
            inputTransaction?.cancel()
            resumeMediaIfWePaused()
        }
    }

    private var wasFinished = false
    override fun finished(result: String) {
        wasFinished = true
    }

    override fun partialResult(result: String) {}
    override fun requestPermission(onGranted: () -> Unit, onRejected: () -> Unit): Boolean {
        val intent = Intent()
        intent.setClassName(context, "org.futo.inputmethod.latin.MicPermissionActivity")
        intent.setFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        context.startActivity(intent)
        return true
    }
    override fun openSettings() {
        SettingsActivity.openToNavDest(context, "voiceInput")
    }
    override fun recordingStarted(device: MicrophoneDeviceState) {}
}

private class GroqVoiceInputNoApiKeyWindow : ActionWindow() {
    @Composable
    override fun windowName(): String {
        return stringResource(R.string.action_voice_input_title)
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        val context = LocalContext.current
        Box(modifier = Modifier
            .fillMaxSize()
            .clickable(
                enabled = true,
                onClick = {
                    SettingsActivity.openToNavDest(context, "voiceInput")
                },
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            )) {
            Text(
                stringResource(R.string.voice_input_settings_groq_api_key_subtitle),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(8.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

val GroqVoiceInputAction = Action(
    icon = R.drawable.mic_fill,
    name = R.string.action_voice_input_title,
    simplePressImpl = null,
    keepScreenAwake = true,
    persistentState = null,
    windowImpl = { manager, _ ->
        val context = manager.getContext()
        val apiKey = context.getSetting(GROQ_API_KEY)
        if (apiKey.isBlank()) {
            GroqVoiceInputNoApiKeyWindow()
        } else {
            GroqVoiceInputActionWindow(manager = manager)
        }
    }
)

/**
 * Embeds the same voice-input session used by the keyboard microphone in another action.
 * This keeps provider selection, recording UI, audio routing, and transcription behavior aligned.
 */
@Composable
fun EmbeddedVoiceInput(
    manager: KeyboardManagerForAction,
    onResult: (String) -> Unit
) {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)

    if (context.getSetting(OFFLINE_MODE)) {
        val locales = remember(manager) { manager.getActiveLocales() }
        val state = remember(manager) { VoiceInputPersistentState(manager) }
        val model = remember(manager, locales) {
            ResourceHelper.tryFindingVoiceInputModelForLocale(
                manager.getContext(),
                locales.firstOrNull() ?: Locale.ROOT
            )
        }

        if (model == null) {
            DisposableEffect(state) {
                onDispose {
                    manager.getLifecycleScope().launch(Dispatchers.Default) {
                        state.close()
                    }
                }
            }
            NoModelInstalled(locales.firstOrNull() ?: Locale.ROOT)
        } else {
            val window = remember(manager, state, model, locales) {
                VoiceInputActionWindow(
                    manager = manager,
                    state = state,
                    locales = locales,
                    model = model,
                    resultHandler = { currentOnResult(it) }
                )
            }
            DisposableEffect(window, state) {
                onDispose {
                    window.close()
                    manager.getLifecycleScope().launch(Dispatchers.Default) {
                        state.close()
                    }
                }
            }
            window.WindowContents(keyboardShown = false)
        }
    } else if (context.getSetting(GROQ_API_KEY).isBlank()) {
        GroqVoiceInputNoApiKeyWindow().WindowContents(keyboardShown = false)
    } else {
        val window = remember(manager) {
            GroqVoiceInputActionWindow(
                manager = manager,
                resultHandler = { currentOnResult(it) }
            )
        }
        DisposableEffect(window) {
            onDispose { window.close() }
        }
        window.WindowContents(keyboardShown = false)
    }
}
