package org.futo.voiceinput.shared

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "GroqRecognizer"
private const val GROQ_API_BASE = "https://api.groq.com/openai/v1"

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class WhisperSegment(
    val id: Int = 0,
    val seek: Int = 0,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val text: String = "",
    val tokens: List<Int> = emptyList(),
    val temperature: Double = 0.0,
    val avg_logprob: Double = 0.0,
    val compression_ratio: Double = 0.0,
    val no_speech_prob: Double = 0.0
)

@Serializable
data class WhisperResponse(
    val text: String = "",
    val task: String = "",
    val language: String = "",
    val duration: Double = 0.0,
    val segments: List<WhisperSegment> = emptyList()
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.3,
    val max_tokens: Int = 512
)

@Serializable
data class ChatChoice(
    val message: ChatMessage? = null
)

@Serializable
data class ChatResponse(
    val choices: List<ChatChoice> = emptyList()
)

object GroqRecognizer {

    private const val SYSTEM_PROMPT = """You are a proofreading assistant. Your task is to correct any spelling, grammar, and minor dictation errors in the transcribed text while preserving the user's original intent and meaning. Do not rewrite the text or change its style. Only fix obvious errors. Return only the corrected text, nothing else."""

    suspend fun transcribe(
        apiKey: String,
        audioData: FloatArray,
        sampleRate: Int,
        model: String,
        language: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val wavBytes = floatArrayToWav(audioData, sampleRate)
            val result = sendAudioToWhisper(apiKey, wavBytes, model, language)
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            Result.failure(e)
        }
    }

    suspend fun enhanceText(
        apiKey: String,
        text: String,
        model: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = ChatRequest(
                model = model,
                messages = listOf(
                    ChatMessage("system", SYSTEM_PROMPT),
                    ChatMessage("user", text)
                )
            )
            val requestBody = json.encodeToString(request)
            val response = sendChatCompletion(apiKey, requestBody)
            val chatResponse = json.decodeFromString<ChatResponse>(response)
            val correctedText = chatResponse.choices.firstOrNull()?.message?.content?.trim() ?: text
            Result.success(correctedText)
        } catch (e: Exception) {
            Log.e(TAG, "Text enhancement failed", e)
            Result.success(text)
        }
    }

    private fun floatArrayToWav(floatArray: FloatArray, sampleRate: Int): ByteArray {
        val byteArrayOutputStream = ByteArrayOutputStream()
        val dataOutputStream = DataOutputStream(byteArrayOutputStream)

        val numChannels: Short = 1
        val bitsPerSample: Short = 16
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = floatArray.size * blockAlign
        val fileSize = 36 + dataSize

        // WAV header (all multi-byte values in little-endian per WAV spec)
        dataOutputStream.writeBytes("RIFF")
        dataOutputStream.writeInt(Integer.reverseBytes(fileSize))
        dataOutputStream.writeBytes("WAVE")

        // fmt chunk
        dataOutputStream.writeBytes("fmt ")
        dataOutputStream.writeInt(Integer.reverseBytes(16)) // chunk size
        dataOutputStream.writeShort(shortToLE(1)) // PCM (1 = uncompressed)
        dataOutputStream.writeShort(shortToLE(numChannels.toInt()))
        dataOutputStream.writeInt(Integer.reverseBytes(sampleRate))
        dataOutputStream.writeInt(Integer.reverseBytes(byteRate))
        dataOutputStream.writeShort(shortToLE(blockAlign.toInt()))
        dataOutputStream.writeShort(shortToLE(bitsPerSample.toInt()))

        // data chunk
        dataOutputStream.writeBytes("data")
        dataOutputStream.writeInt(Integer.reverseBytes(dataSize))

        for (sample in floatArray) {
            val clamped = sample.coerceIn(-1.0f, 1.0f)
            val pcmValue = (clamped * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            dataOutputStream.writeShort(shortToLE(pcmValue))
        }

        dataOutputStream.flush()
        return byteArrayOutputStream.toByteArray()
    }

    /** Convert a 16-bit value to little-endian bytes for WAV format. */
    private fun shortToLE(value: Int): Int {
        return ((value and 0xFF) shl 8) or ((value shr 8) and 0xFF)
    }

    private fun sendAudioToWhisper(
        apiKey: String,
        wavData: ByteArray,
        model: String,
        language: String?
    ): String {
        val boundary = "Boundary-${System.currentTimeMillis()}"
        val lineEnd = "\r\n"

        val url = URL("$GROQ_API_BASE/audio/transcriptions")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.connectTimeout = 30000
        connection.readTimeout = 60000

        val outputStream = ByteArrayOutputStream()
        val writer = OutputStreamWriter(outputStream)

        // Model field
        writer.write("--$boundary$lineEnd")
        writer.write("Content-Disposition: form-data; name=\"model\"$lineEnd$lineEnd")
        writer.write("$model$lineEnd")

        // Language field (optional)
        if (!language.isNullOrBlank() && language != "auto") {
            writer.write("--$boundary$lineEnd")
            writer.write("Content-Disposition: form-data; name=\"language\"$lineEnd$lineEnd")
            writer.write("$language$lineEnd")
        }

        // Response format: verbose_json for detailed output with segments, timestamps, etc.
        writer.write("--$boundary$lineEnd")
        writer.write("Content-Disposition: form-data; name=\"response_format\"$lineEnd$lineEnd")
        writer.write("verbose_json$lineEnd")

        // Temperature: 0 for deterministic output
        writer.write("--$boundary$lineEnd")
        writer.write("Content-Disposition: form-data; name=\"temperature\"$lineEnd$lineEnd")
        writer.write("0$lineEnd")

        // Audio file
        writer.write("--$boundary$lineEnd")
        writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"$lineEnd")
        writer.write("Content-Type: audio/wav$lineEnd$lineEnd")
        writer.flush()
        outputStream.write(wavData)
        outputStream.flush()
        writer.write(lineEnd)

        // End boundary
        writer.write("--$boundary--$lineEnd")
        writer.flush()

        connection.connect()
        connection.outputStream.write(outputStream.toByteArray())
        connection.outputStream.flush()

        val responseCode = connection.responseCode
        val responseBody = if (responseCode in 200..299) {
            connection.inputStream.bufferedReader().readText()
        } else {
            val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            Log.e(TAG, "Whisper API error $responseCode: $errorBody")
            throw RuntimeException("Groq API error $responseCode: $errorBody")
        }

        connection.disconnect()

        val whisperResponse = json.decodeFromString<WhisperResponse>(responseBody)
        return whisperResponse.text
    }

    private fun sendChatCompletion(apiKey: String, requestBody: String): String {
        val url = URL("$GROQ_API_BASE/chat/completions")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.connect()

        connection.outputStream.write(requestBody.toByteArray())
        connection.outputStream.flush()

        val responseCode = connection.responseCode
        val responseBody = if (responseCode in 200..299) {
            connection.inputStream.bufferedReader().readText()
        } else {
            val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            Log.e(TAG, "Chat API error $responseCode: $errorBody")
            throw RuntimeException("Groq API error $responseCode: $errorBody")
        }

        connection.disconnect()
        return responseBody
    }
}