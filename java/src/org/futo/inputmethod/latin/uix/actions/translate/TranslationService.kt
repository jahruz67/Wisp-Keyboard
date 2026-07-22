package org.futo.inputmethod.latin.uix.actions.translate

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.uix.SettingsKey
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class TranslationProviderType(val displayName: String, val requiresApiKey: Boolean, val supportsCustomUrl: Boolean) {
    GOOGLE_FREE("Google Translate (Free)", false, false),
    GOOGLE_CLOUD("Google Cloud Translation API", true, false),
    DEEPL("DeepL API", true, false),
    OPENAI("OpenAI (ChatGPT)", true, false),
    LIBRE_TRANSLATE("LibreTranslate", false, true)
}

val TRANSLATE_ADDON_ENABLED = SettingsKey(
    booleanPreferencesKey("translate_addon_enabled"),
    true
)

val TRANSLATE_PROVIDER = SettingsKey(
    stringPreferencesKey("translate_provider"),
    TranslationProviderType.GOOGLE_FREE.name
)

val TRANSLATE_API_KEY = SettingsKey(
    stringPreferencesKey("translate_api_key"),
    ""
)

val TRANSLATE_CUSTOM_URL = SettingsKey(
    stringPreferencesKey("translate_custom_url"),
    "https://libretranslate.com"
)

val TRANSLATE_DEFAULT_SOURCE = SettingsKey(
    stringPreferencesKey("translate_default_source"),
    "auto"
)

val TRANSLATE_DEFAULT_TARGET = SettingsKey(
    stringPreferencesKey("translate_default_target"),
    "en"
)

val TRANSLATE_LIVE_ENABLED = SettingsKey(
    booleanPreferencesKey("translate_live_enabled"),
    true
)

data class SupportedLanguage(
    val code: String,
    val name: String
)

val ALL_SUPPORTED_LANGUAGES = listOf(
    SupportedLanguage("auto", "Auto-detect"),
    SupportedLanguage("en", "English"),
    SupportedLanguage("es", "Spanish"),
    SupportedLanguage("fr", "French"),
    SupportedLanguage("de", "German"),
    SupportedLanguage("zh", "Chinese"),
    SupportedLanguage("ja", "Japanese"),
    SupportedLanguage("ko", "Korean"),
    SupportedLanguage("pt", "Portuguese"),
    SupportedLanguage("ru", "Russian"),
    SupportedLanguage("it", "Italian"),
    SupportedLanguage("ar", "Arabic"),
    SupportedLanguage("hi", "Hindi"),
    SupportedLanguage("nl", "Dutch"),
    SupportedLanguage("pl", "Polish"),
    SupportedLanguage("tr", "Turkish"),
    SupportedLanguage("uk", "Ukrainian"),
    SupportedLanguage("vi", "Vietnamese"),
    SupportedLanguage("id", "Indonesian")
)

object TranslationService {

    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        providerType: TranslationProviderType,
        apiKey: String,
        customUrl: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext Result.success("")

        try {
            val resultText = when (providerType) {
                TranslationProviderType.GOOGLE_FREE -> translateGoogleFree(text, sourceLang, targetLang)
                TranslationProviderType.GOOGLE_CLOUD -> translateGoogleCloud(text, sourceLang, targetLang, apiKey)
                TranslationProviderType.DEEPL -> translateDeepL(text, sourceLang, targetLang, apiKey)
                TranslationProviderType.OPENAI -> translateOpenAI(text, sourceLang, targetLang, apiKey)
                TranslationProviderType.LIBRE_TRANSLATE -> translateLibre(text, sourceLang, targetLang, apiKey, customUrl)
            }
            Result.success(resultText)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun translateGoogleFree(text: String, source: String, target: String): String {
        val encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8.name())
        val urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$source&tl=$target&dt=t&q=$encodedText"
        val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }

        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val jsonArray = JSONArray(response)
        val sentences = jsonArray.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until sentences.length()) {
            val sentence = sentences.getJSONArray(i)
            if (sentence.length() > 0) {
                sb.append(sentence.getString(0))
            }
        }
        return sb.toString()
    }

    private fun translateGoogleCloud(text: String, source: String, target: String, apiKey: String): String {
        if (apiKey.isBlank()) throw IllegalArgumentException("Google Cloud API Key is missing")
        val urlStr = "https://translation.googleapis.com/language/translate/v2?key=$apiKey"
        val body = JSONObject().apply {
            put("q", text)
            put("target", target)
            if (source != "auto") put("source", source)
            put("format", "text")
        }.toString()

        val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }

        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(response)
        val translations = json.getJSONObject("data").getJSONArray("translations")
        return translations.getJSONObject(0).getString("translatedText")
    }

    private fun translateDeepL(text: String, source: String, target: String, apiKey: String): String {
        if (apiKey.isBlank()) throw IllegalArgumentException("DeepL API Key is missing")
        val baseUrl = if (apiKey.endsWith(":fx")) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"
        val body = JSONObject().apply {
            put("text", JSONArray().put(text))
            put("target_lang", target.uppercase())
            if (source != "auto") put("source_lang", source.uppercase())
        }.toString()

        val connection = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Authorization", "DeepL-Auth-Key $apiKey")
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }

        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(response)
        val translations = json.getJSONArray("translations")
        return translations.getJSONObject(0).getString("text")
    }

    private fun translateOpenAI(text: String, source: String, target: String, apiKey: String): String {
        if (apiKey.isBlank()) throw IllegalArgumentException("OpenAI API Key is missing")
        val urlStr = "https://api.openai.com/v1/chat/completions"
        val prompt = "Translate the following text into target language code '$target' (source is '$source'). Return ONLY the translated text without any explanation, quotes, or markdown:\n\n$text"
        val body = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            }))
            put("temperature", 0.3)
        }.toString()

        val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 10000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }

        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(response)
        val choices = json.getJSONArray("choices")
        val content = choices.getJSONObject(0).getJSONObject("message").getString("content")
        return content.trim()
    }

    private fun translateLibre(text: String, source: String, target: String, apiKey: String, customUrl: String?): String {
        val endpoint = (customUrl?.takeIf { it.isNotBlank() } ?: "https://libretranslate.com").trimEnd('/') + "/translate"
        val body = JSONObject().apply {
            put("q", text)
            put("source", if (source == "auto") "auto" else source)
            put("target", target)
            put("format", "text")
            if (apiKey.isNotBlank()) put("api_key", apiKey)
        }.toString()

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }

        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(response)
        return json.getString("translatedText")
    }
}
