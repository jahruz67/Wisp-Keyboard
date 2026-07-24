package org.futo.inputmethod.latin.uix.addons

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.SafeBrowsingResponse
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.LocalKeyboardScheme
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.UUID

private const val LOCAL_HOST = "wisp.addon"
private const val MAX_TEXT_RESPONSE_BYTES = 10L * 1024L * 1024L
private const val MAX_MEDIA_RESPONSE_BYTES = 25L * 1024L * 1024L
private const val MAX_MEDIA_CACHE_BYTES = 100L * 1024L * 1024L

private data class PermissionRequest(
    val capability: String,
    val description: String,
    val result: CompletableDeferred<Boolean>,
)

class AddonPanelWebView(context: Context) : WebView(context) {
    var onInputConnectionCreated: ((InputConnection, EditorInfo) -> Unit)? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        return super.onCreateInputConnection(outAttrs)?.also {
            onInputConnectionCreated?.invoke(it, outAttrs)
        }
    }
}

private open class LocalAddonWebViewClient(
    private val addon: InstalledAddon,
    private val manager: AddonManager,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
        request?.url?.host != LOCAL_HOST

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse {
        val uri = request?.url ?: return blocked()
        if (uri.scheme != "https" || uri.host != LOCAL_HOST) return blocked()
        val segments = uri.pathSegments
        if (segments.isEmpty()) return blocked()

        val file = when (segments.first()) {
            "package" -> resolveUnder(addon.directory, segments.drop(1).joinToString("/"))
            "media" -> resolveUnder(manager.mediaDirectory(addon.id), segments.drop(1).joinToString("/"))
            else -> null
        } ?: return blocked()

        val mime = if (segments.first() == "media") {
            File(file.parentFile, "${file.name}.mime")
                .takeIf { it.isFile }
                ?.readText()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } else {
            null
        } ?: MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?: java.net.URLConnection.guessContentTypeFromName(file.name)
            ?: "application/octet-stream"
        val headers = mapOf(
            "Content-Security-Policy" to
                "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline'; " +
                    "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'none'; " +
                    "object-src 'none'; frame-src 'none'; worker-src 'none'; base-uri 'none'; form-action 'none'",
            "X-Content-Type-Options" to "nosniff",
            "Cache-Control" to "no-store",
        )
        val stream = if (mime == "text/html") {
            val html = file.readText()
            val injected = if (Regex("<head[^>]*>", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
                html.replaceFirst(
                    Regex("(<head[^>]*>)", RegexOption.IGNORE_CASE),
                    "$1<script>$BRIDGE_BOOTSTRAP</script>",
                )
            } else {
                "<script>$BRIDGE_BOOTSTRAP</script>$html"
            }
            ByteArrayInputStream(injected.toByteArray())
        } else {
            file.inputStream()
        }
        return WebResourceResponse(mime, null, 200, "OK", headers, stream)
    }

    override fun onSafeBrowsingHit(
        view: WebView?,
        request: WebResourceRequest?,
        threatType: Int,
        callback: SafeBrowsingResponse?,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) callback?.backToSafety(true)
    }

    private fun resolveUnder(root: File, relative: String): File? {
        if (relative.isBlank() || relative.contains('\\')) return null
        val canonicalRoot = root.canonicalFile
        val file = File(root, relative).canonicalFile
        return file.takeIf {
            it.path.startsWith(canonicalRoot.path + File.separator) && it.isFile
        }
    }

    private fun blocked() = WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Blocked",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream("Blocked by the Wisp add-on sandbox.".toByteArray()),
    )
}

private class AddonJavascriptBridge(
    private val webView: WebView,
    private val addon: InstalledAddon,
    private val manager: AddonManager,
    private val keyboardManager: KeyboardManagerForAction?,
    private val requestPermission: suspend (String, String) -> Boolean,
    private val requestVoice: (((String?) -> Unit) -> Unit)?,
    private val setExpanded: (Boolean) -> Unit,
    private val environment: () -> JSONObject,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    @JavascriptInterface
    fun postMessage(message: String) {
        scope.launch {
            val envelope = runCatching { JSONObject(message) }.getOrElse {
                return@launch
            }
            val id = envelope.optString("id")
            val operation = envelope.optString("operation")
            val arguments = envelope.optJSONObject("arguments") ?: JSONObject()
            try {
                val value = when (operation) {
                    "settings.get" -> settingGet(arguments)
                    "settings.set" -> settingSet(arguments)
                    "storage.get" -> storageGet(arguments)
                    "storage.set" -> storageSet(arguments)
                    "storage.remove" -> storageRemove(arguments)
                    "network.fetch" -> networkFetch(arguments)
                    "keyboard.insertText" -> insertText(arguments)
                    "keyboard.insertMedia" -> insertMedia(arguments)
                    "keyboard.startVoiceInput" -> startVoiceInput()
                    "ui.close" -> close()
                    "ui.setExpanded" -> setExpanded(arguments.optBoolean("expanded", true))
                    "ui.getEnvironment" -> environment()
                    else -> error("Unsupported Wisp API operation: $operation")
                }
                respond(id, true, value)
            } catch (e: Exception) {
                respond(id, false, e.message ?: "Add-on operation failed.")
            }
        }
    }

    private fun settingGet(arguments: JSONObject): Any? {
        val key = arguments.requireString("key")
        return manager.getSetting(addon.id, key)
    }

    private fun settingSet(arguments: JSONObject): Any {
        manager.setSetting(addon.id, arguments.requireString("key"), arguments.requireString("value"))
        return true
    }

    private fun storageGet(arguments: JSONObject): Any? =
        manager.getState(addon.id, arguments.requireString("key"))

    private fun storageSet(arguments: JSONObject): Any {
        manager.setState(addon.id, arguments.requireString("key"), arguments.requireString("value"))
        return true
    }

    private fun storageRemove(arguments: JSONObject): Any {
        manager.setState(addon.id, arguments.requireString("key"), null)
        return true
    }

    private suspend fun insertText(arguments: JSONObject): Any {
        require(addon.manifest.permissions.insertText) { "This add-on did not request text insertion." }
        ensurePermission("insertText", "Insert text into the current app")
        keyboardManager?.typeText(arguments.requireString("text"))
            ?: error("Text insertion is only available from a keyboard action.")
        return true
    }

    private suspend fun insertMedia(arguments: JSONObject): Any {
        require(addon.manifest.permissions.insertMedia) { "This add-on did not request media insertion." }
        ensurePermission("insertMedia", "Insert images or media into the current app")
        val handle = arguments.requireString("handle")
        require(handle.matches(Regex("[a-f0-9-]{36}"))) { "Invalid media handle." }
        val file = File(manager.mediaDirectory(addon.id), handle).canonicalFile
        require(file.parentFile == manager.mediaDirectory(addon.id).canonicalFile && file.isFile) {
            "Media handle does not exist."
        }
        val mime = arguments.requireString("mimeType")
        val uri = Uri.Builder()
            .scheme("content")
            .authority("${BuildConfig.APPLICATION_ID}.addons")
            .appendPath(addon.id)
            .appendPath(handle)
            .build()
        return keyboardManager?.typeUri(uri, listOf(mime))
            ?: error("Media insertion is only available from a keyboard action.")
    }

    private suspend fun startVoiceInput(): Any? {
        require(addon.manifest.permissions.voiceInput) { "This add-on did not request voice input." }
        ensurePermission("voiceInput", "Capture a voice transcription")
        val handler = requestVoice ?: error("Voice input is only available from a keyboard action.")
        val result = CompletableDeferred<String?>()
        handler { result.complete(it) }
        return result.await()
    }

    private fun close(): Any {
        keyboardManager?.closeActionWindow()
        return true
    }

    private suspend fun networkFetch(arguments: JSONObject): JSONObject {
        val requestedUrl = arguments.requireString("url")
        val initialOrigin = validateNetworkUrl(requestedUrl)
        ensurePermission("network:$initialOrigin", "Connect to $initialOrigin")

        return withContext(Dispatchers.IO) {
            var url = URL(requestedUrl)
            var redirects = 0
            while (true) {
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = arguments.optString("method", "GET").uppercase()
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", "WispKeyboardAddon/${addon.manifest.versionName}")
                    arguments.optJSONObject("headers")?.let { headers ->
                        headers.keys().forEach { key ->
                            require(key.lowercase() !in setOf("host", "cookie", "origin")) {
                                "Header $key is controlled by the host."
                            }
                            setRequestProperty(key, headers.getString(key))
                        }
                    }
                    if (arguments.has("body")) {
                        doOutput = true
                        outputStream.use {
                            it.write(arguments.getString("body").toByteArray())
                        }
                    }
                }

                val status = connection.responseCode
                if (status in 300..399) {
                    require(redirects++ < 5) { "Too many redirects." }
                    val location = connection.getHeaderField("Location")
                        ?: error("Redirect has no Location header.")
                    val redirected = URL(url, location)
                    val redirectedOrigin = validateNetworkUrl(redirected.toString())
                    require(
                        redirectedOrigin == initialOrigin ||
                            manager.hasGrant(addon.id, "network:$redirectedOrigin")
                    ) {
                        "Cross-origin redirect to $redirectedOrigin was not approved."
                    }
                    url = redirected
                    connection.disconnect()
                    continue
                }

                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val responseType = arguments.optString("responseType", "text")
                if (responseType == "media") {
                    val handle = UUID.randomUUID().toString()
                    val target = File(manager.mediaDirectory(addon.id), handle)
                    stream.use { input ->
                        target.outputStream().use { output ->
                            copyLimited(input, output, MAX_MEDIA_RESPONSE_BYTES)
                        }
                    }
                    val mime = connection.contentType?.substringBefore(';') ?: "application/octet-stream"
                    File(target.parentFile, "$handle.mime").writeText(mime)
                    pruneMediaCache(manager.mediaDirectory(addon.id))
                    return@withContext JSONObject()
                        .put("status", status)
                        .put("handle", handle)
                        .put("mimeType", mime)
                        .put("previewUrl", "https://$LOCAL_HOST/media/$handle")
                }

                val bytes = stream.use { readLimited(it, MAX_TEXT_RESPONSE_BYTES) }
                return@withContext JSONObject()
                    .put("status", status)
                    .put("contentType", connection.contentType ?: "")
                    .put("body", bytes.decodeToString())
            }
            error("Unreachable")
        }
    }

    private fun validateNetworkUrl(value: String): String {
        val uri = URI(value)
        require(uri.scheme == "https" || uri.scheme == "http") { "Only HTTP(S) requests are supported." }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null) { "Invalid network URL." }
        if (uri.scheme == "http") {
            require(addon.manifest.permissions.allowInsecureHttp) {
                "This add-on did not request insecure HTTP access."
            }
        }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val origin = "${uri.scheme.lowercase()}://${uri.host.lowercase()}$port"
        val fixedOrigins = addon.manifest.permissions.networkOrigins.map {
            val fixed = URI(it)
            val fixedPort = if (fixed.port == -1) "" else ":${fixed.port}"
            "${fixed.scheme.lowercase()}://${fixed.host.lowercase()}$fixedPort"
        }
        require(origin in fixedOrigins || addon.manifest.permissions.allowUserOrigins) {
            "Network origin $origin is not declared by this add-on."
        }
        return origin
    }

    private suspend fun ensurePermission(capability: String, description: String) {
        if (manager.hasGrant(addon.id, capability)) return
        require(requestPermission(capability, description)) { "Permission was denied." }
        manager.setGrant(addon.id, capability, true)
    }

    private fun respond(id: String, success: Boolean, value: Any?) {
        val payload = JSONObject().put("value", value).toString()
        val script = "window.__wispResolve(" +
            "${JSONObject.quote(id)},$success,${JSONObject.quote(payload)});"
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun JSONObject.requireString(key: String): String =
        getString(key).also { require(it.length <= 1_000_000) { "$key is too large." } }

    private fun readLimited(input: java.io.InputStream, limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        copyLimited(input, output, limit)
        return output.toByteArray()
    }

    private fun copyLimited(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        limit: Long,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "Network response is too large." }
            output.write(buffer, 0, read)
        }
    }

    private fun pruneMediaCache(directory: File) {
        val media = directory.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".mime") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        var size = 0L
        media.forEach { file ->
            size += file.length()
            if (size > MAX_MEDIA_CACHE_BYTES) {
                file.delete()
                File(file.parentFile, "${file.name}.mime").delete()
            }
        }
    }
}

private const val BRIDGE_BOOTSTRAP = """
(function () {
  if (window.wisp) return;
  const pending = new Map();
  let nextId = 1;
  window.__wispResolve = function (id, ok, payload) {
    const entry = pending.get(id);
    if (!entry) return;
    pending.delete(id);
    const parsed = JSON.parse(payload);
    if (ok) entry.resolve(parsed.value);
    else entry.reject(new Error(parsed.value));
  };
  function call(operation, arguments) {
    return new Promise((resolve, reject) => {
      const id = String(nextId++);
      pending.set(id, {resolve, reject});
      window.WispBridge.postMessage(JSON.stringify({id, operation, arguments: arguments || {}}));
    });
  }
  window.wisp = {
    settings: {
      get: key => call('settings.get', {key}),
      set: (key, value) => call('settings.set', {key, value: String(value)})
    },
    storage: {
      get: key => call('storage.get', {key}),
      set: (key, value) => call('storage.set', {key, value: String(value)}),
      remove: key => call('storage.remove', {key})
    },
    network: { fetch: request => call('network.fetch', request) },
    keyboard: {
      insertText: text => call('keyboard.insertText', {text}),
      insertMedia: (handle, mimeType) => call('keyboard.insertMedia', {handle, mimeType}),
      startVoiceInput: () => call('keyboard.startVoiceInput', {})
    },
    ui: {
      close: () => call('ui.close', {}),
      setExpanded: expanded => call('ui.setExpanded', {expanded: !!expanded}),
      getEnvironment: () => call('ui.getEnvironment', {})
    }
  };
  window.__wispSetEnvironment = function (environment) {
    window.dispatchEvent(new CustomEvent('wisp:environment', {detail: environment}));
  };
})();
"""

private fun Color.toCssColor(): String =
    String.format(Locale.ROOT, "#%06X", toArgb() and 0xFFFFFF)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AddonWebPanel(
    addon: InstalledAddon,
    entrypoint: String,
    modifier: Modifier = Modifier.fillMaxSize(),
    keyboardManager: KeyboardManagerForAction? = null,
    keyboardShown: Boolean = false,
    onVoiceRequest: (((String?) -> Unit) -> Unit)? = null,
    onExpandedChanged: (Boolean) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val addonManager = remember(context) { AddonManager.get(context) }
    val scope = rememberCoroutineScope()
    var permissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }
    val keyboardScheme = LocalKeyboardScheme.current
    val materialColors = MaterialTheme.colorScheme
    val environment = remember(
        keyboardShown,
        keyboardScheme.keyboardContainer,
        keyboardScheme.onKeyboardContainer,
        materialColors.primary,
        materialColors.onSurface,
        materialColors.error,
        materialColors.surfaceContainerHighest,
    ) {
        JSONObject()
            .put("keyboardShown", keyboardShown)
            .put("dark", keyboardScheme.keyboardContainer.luminance() < 0.5f)
            .put("keyboardContainer", keyboardScheme.keyboardContainer.toCssColor())
            .put("onKeyboardContainer", keyboardScheme.onKeyboardContainer.toCssColor())
            .put("primary", materialColors.primary.toCssColor())
            .put("onSurface", materialColors.onSurface.toCssColor())
            .put("error", materialColors.error.toCssColor())
            .put("surfaceContainerHighest", materialColors.surfaceContainerHighest.toCssColor())
    }
    val currentEnvironment by rememberUpdatedState(environment)

    val requestPermission: suspend (String, String) -> Boolean = { capability, description ->
        val deferred = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            permissionRequest = PermissionRequest(capability, description, deferred)
        }
        deferred.await()
    }

    val webView = remember(addon.id, entrypoint) {
        AddonPanelWebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            CookieManager.getInstance().setAcceptCookie(false)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            clearCache(true)
            clearHistory()
            webViewClient = object : LocalAddonWebViewClient(addon, addonManager) {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(BRIDGE_BOOTSTRAP, null)
                    view?.evaluateJavascript(
                        "window.__wispSetEnvironment(${currentEnvironment});",
                        null,
                    )
                }
            }
            val bridge = AddonJavascriptBridge(
                webView = this,
                addon = addon,
                manager = addonManager,
                keyboardManager = keyboardManager,
                requestPermission = requestPermission,
                requestVoice = onVoiceRequest,
                setExpanded = onExpandedChanged,
                environment = { currentEnvironment },
                scope = scope,
            )
            addJavascriptInterface(bridge, "WispBridge")
            onInputConnectionCreated = { inputConnection, editorInfo ->
                keyboardManager?.overrideInputConnection(inputConnection, editorInfo)
            }
            loadUrl("https://$LOCAL_HOST/package/$entrypoint")
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
    )

    LaunchedEffect(webView, environment.toString()) {
        webView.evaluateJavascript(
            "if(window.__wispSetEnvironment){" +
                "window.__wispSetEnvironment($environment);}",
            null,
        )
    }

    DisposableEffect(webView) {
        onDispose {
            keyboardManager?.unsetInputConnection()
            webView.removeJavascriptInterface("WispBridge")
            webView.stopLoading()
            webView.destroy()
        }
    }

    permissionRequest?.let { request ->
        AlertDialog(
            onDismissRequest = {
                request.result.complete(false)
                permissionRequest = null
            },
            title = { Text("Allow ${addon.manifest.name}?") },
            text = {
                Text("${request.description}.\n\nThis is a one-time approval for this add-on.")
            },
            confirmButton = {
                TextButton(onClick = {
                    request.result.complete(true)
                    permissionRequest = null
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = {
                    request.result.complete(false)
                    permissionRequest = null
                }) { Text("Deny") }
            },
        )
    }
}
