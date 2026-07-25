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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.uix.DialogRequestItem
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.LocalKeyboardScheme
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

private const val LOCAL_HOST = "wisp.addon"
private const val MAX_HTML_BYTES = 10L * 1024L * 1024L
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
    private var pendingInitialUrl: String? = null

    fun connectFocusedEditorToKeyboard(): Boolean {
        val callback = onInputConnectionCreated ?: return false
        if (!hasFocus() && !requestFocus()) return false
        val editorInfo = EditorInfo()
        val inputConnection = super.onCreateInputConnection(editorInfo) ?: return false
        callback(inputConnection, editorInfo)
        return true
    }

    fun disconnectFocusedEditorFromKeyboard() {
        evaluateJavascript(
            "(function(){" +
                "var element=document.activeElement;" +
                "if(element&&element!==document.body&&typeof element.blur==='function'){" +
                    "element.blur();" +
                "}" +
            "})();",
            null,
        )
        clearFocus()
    }

    fun loadWhenSized(url: String) {
        if (width > 0 && height > 0) {
            loadUrl(url)
        } else {
            pendingInitialUrl = url
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            pendingInitialUrl?.let { url ->
                pendingInitialUrl = null
                loadUrl(url)
            }
        }
    }

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
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return true
        if (uri.scheme != "https" || uri.host != LOCAL_HOST) return true
        return uri.pathSegments.firstOrNull() != "package"
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse {
        val uri = request?.url ?: return blocked()
        if (uri.scheme != "https" || uri.host != LOCAL_HOST) return blocked()
        val segments = uri.pathSegments
        if (segments.isEmpty()) return blocked()
        if (segments.first() == "media" && request.isForMainFrame) return blocked()

        val file = when (segments.first()) {
            "package" -> resolveUnder(addon.directory, segments.drop(1).joinToString("/"))
            "media" -> resolveUnder(manager.mediaDirectory(addon.id), segments.drop(1).joinToString("/"))
            else -> null
        } ?: return blocked()
        val isMedia = segments.first() == "media"
        if (isMedia) file.setLastModified(System.currentTimeMillis())

        val mime = if (isMedia) {
            readAddonMediaMimeType(file)
        } else {
            null
        } ?: MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?: java.net.URLConnection.guessContentTypeFromName(file.name)
            ?: "application/octet-stream"
        val contentSecurityPolicy = if (isMedia) {
            "default-src 'none'; img-src data:; style-src 'unsafe-inline'; " +
                "script-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'"
        } else {
            "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline'; " +
                "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'none'; " +
                "object-src 'none'; frame-src 'none'; worker-src 'none'; base-uri 'none'; " +
                "form-action 'none'"
        }
        val headers = mapOf(
            "Content-Security-Policy" to contentSecurityPolicy,
            "X-Content-Type-Options" to "nosniff",
            "Cache-Control" to "no-store",
        )
        if (!isMedia && mime == "text/html" && file.length() > MAX_HTML_BYTES) return blocked()
        val stream = if (!isMedia && mime == "text/html") {
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
        val encoding = if (
            mime.startsWith("text/") ||
            mime in setOf("application/javascript", "application/json", "image/svg+xml")
        ) {
            "utf-8"
        } else {
            null
        }
        return WebResourceResponse(mime, encoding, 200, "OK", headers, stream)
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
    private val webView: AddonPanelWebView,
    private val addon: InstalledAddon,
    private val manager: AddonManager,
    private val keyboardManager: KeyboardManagerForAction?,
    private val requestPermission: suspend (String, String) -> Boolean,
    private val requestVoice: (((String?) -> Unit) -> Unit)?,
    private val setExpanded: (Boolean) -> Unit,
    private val environment: () -> JSONObject,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private data class NetworkRequest(
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val body: String?,
        val responseType: String,
    )

    private val permissionMutex = Mutex()
    private var voiceRequestInProgress = false

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
                    "ui.showKeyboard" -> showKeyboard()
                    "ui.hideKeyboard" -> hideKeyboard()
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
        val manager = keyboardManager
            ?: error("Text insertion is only available from a keyboard action.")
        val text = arguments.requireString("text")
        ensurePermission("insertText", "Insert text into the current app")
        manager.typeText(text)
        return true
    }

    private suspend fun insertMedia(arguments: JSONObject): Any {
        require(addon.manifest.permissions.insertMedia) { "This add-on did not request media insertion." }
        val keyboard = keyboardManager
            ?: error("Media insertion is only available from a keyboard action.")
        val handle = arguments.requireString("handle")
        arguments.requireString("mimeType")
        require(handle.matches(Regex("[a-f0-9-]{36}"))) { "Invalid media handle." }
        val file = File(manager.mediaDirectory(addon.id), handle).canonicalFile
        require(file.parentFile == manager.mediaDirectory(addon.id).canonicalFile && file.isFile) {
            "Media handle does not exist."
        }
        val mime = readAddonMediaMimeType(file)
        ensurePermission("insertMedia", "Insert images or media into the current app")
        val uri = Uri.Builder()
            .scheme("content")
            .authority("${BuildConfig.APPLICATION_ID}.addons")
            .appendPath(addon.id)
            .appendPath(handle)
            .build()
        require(keyboard.typeUri(uri, listOf(mime))) {
            "The current app did not accept media insertion."
        }
        return true
    }

    private suspend fun startVoiceInput(): Any? {
        require(addon.manifest.permissions.voiceInput) { "This add-on did not request voice input." }
        val handler = requestVoice ?: error("Voice input is only available from a keyboard action.")
        require(!voiceRequestInProgress) { "A voice input request is already active." }
        voiceRequestInProgress = true
        try {
            ensurePermission("voiceInput", "Capture a voice transcription")
            val result = CompletableDeferred<String?>()
            handler { result.complete(it) }
            return result.await()
        } finally {
            voiceRequestInProgress = false
        }
    }

    private fun close(): Any {
        keyboardManager?.closeActionWindow()
        return true
    }

    private suspend fun showKeyboard(): Any = withContext(Dispatchers.Main) {
        require(addon.manifest.action.canShowKeyboard) {
            "This add-on action does not allow the keyboard to be shown."
        }
        require(keyboardManager != null) {
            "Keyboard display is only available from a keyboard action."
        }
        require(webView.connectFocusedEditorToKeyboard()) {
            "No editable add-on field is focused."
        }
        true
    }

    private suspend fun hideKeyboard(): Any = withContext(Dispatchers.Main) {
        val keyboard = keyboardManager
            ?: error("Keyboard dismissal is only available from a keyboard action.")
        keyboard.hideActionKeyboard(addon.id)
    }

    private suspend fun networkFetch(arguments: JSONObject): JSONObject {
        val request = parseNetworkRequest(arguments)
        val initialOrigin = validateNetworkUrl(request.url)
        ensurePermission("network:$initialOrigin", "Connect to $initialOrigin")

        return withContext(Dispatchers.IO) {
            var url = URL(request.url)
            var method = request.method
            var body = request.body
            var includeSensitiveHeaders = true
            var redirects = 0
            while (true) {
                val connection = (url.openConnection() as HttpURLConnection)
                try {
                    connection.apply {
                        instanceFollowRedirects = false
                        requestMethod = method
                        connectTimeout = 10_000
                        readTimeout = 15_000
                        setRequestProperty("User-Agent", "WispKeyboardAddon/${addon.manifest.versionName}")
                        request.headers.forEach { (key, value) ->
                            if (
                                includeSensitiveHeaders ||
                                key.lowercase() !in setOf("authorization", "proxy-authorization")
                            ) {
                                setRequestProperty(key, value)
                            }
                        }
                        body?.let { requestBody ->
                            doOutput = true
                            outputStream.use {
                                it.write(requestBody.toByteArray())
                            }
                        }
                    }

                    val status = connection.responseCode
                    if (status in setOf(301, 302, 303, 307, 308)) {
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
                        if (redirectedOrigin != initialOrigin) includeSensitiveHeaders = false
                        if (status == 303 || (status in 301..302 && method == "POST")) {
                            method = "GET"
                            body = null
                        }
                        url = redirected
                        continue
                    }

                    val stream = (
                        if (status >= 400) connection.errorStream else connection.inputStream
                        ) ?: ByteArrayInputStream(ByteArray(0))
                    if (request.responseType == "media") {
                        val handle = UUID.randomUUID().toString()
                        val target = File(manager.mediaDirectory(addon.id), handle)
                        val sidecar = File(target.parentFile, "$handle.mime")
                        try {
                            stream.use { input ->
                                target.outputStream().use { output ->
                                    copyLimited(input, output, MAX_MEDIA_RESPONSE_BYTES)
                                }
                            }
                            val mime = sanitizeAddonMediaMimeType(connection.contentType)
                            sidecar.writeText(mime)
                            pruneMediaCache(manager.mediaDirectory(addon.id))
                            return@withContext JSONObject()
                                .put("status", status)
                                .put("handle", handle)
                                .put("mimeType", mime)
                                .put("previewUrl", "https://$LOCAL_HOST/media/$handle")
                        } catch (e: Exception) {
                            target.delete()
                            sidecar.delete()
                            throw e
                        }
                    }

                    val bytes = stream.use { readLimited(it, MAX_TEXT_RESPONSE_BYTES) }
                    return@withContext JSONObject()
                        .put("status", status)
                        .put("contentType", connection.contentType ?: "")
                        .put("body", bytes.decodeToString())
                } finally {
                    connection.disconnect()
                }
            }
            error("Unreachable")
        }
    }

    private fun parseNetworkRequest(arguments: JSONObject): NetworkRequest {
        val method = arguments.optString("method", "GET").uppercase()
        require(
            method in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        ) {
            "Unsupported HTTP method: $method"
        }
        val body = if (arguments.has("body")) arguments.requireString("body") else null
        require(body == null || method !in setOf("GET", "HEAD")) {
            "$method requests cannot include a body."
        }
        val responseType = arguments.optString("responseType", "text")
        require(responseType == "text" || responseType == "media") {
            "responseType must be text or media."
        }
        val headers = buildMap {
            arguments.optJSONObject("headers")?.let { json ->
                require(json.length() <= 64) { "Too many request headers." }
                json.keys().forEach { key ->
                    require(key.matches(Regex("[A-Za-z0-9-]{1,100}"))) {
                        "Invalid request header name."
                    }
                    require(
                        key.lowercase() !in setOf(
                            "connection",
                            "content-length",
                            "cookie",
                            "host",
                            "origin",
                            "transfer-encoding",
                        )
                    ) {
                        "Header $key is controlled by the host."
                    }
                    val value = json.getString(key)
                    require(value.length <= 8_192 && '\r' !in value && '\n' !in value) {
                        "Invalid value for header $key."
                    }
                    put(key, value)
                }
            }
        }
        return NetworkRequest(
            url = arguments.requireString("url"),
            method = method,
            headers = headers,
            body = body,
            responseType = responseType,
        )
    }

    private fun validateNetworkUrl(value: String): String {
        val parsed = parseAddonNetworkUrl(value, originOnly = false)
        if (parsed.uri.scheme.equals("http", ignoreCase = true)) {
            require(addon.manifest.permissions.allowInsecureHttp) {
                "This add-on did not request insecure HTTP access."
            }
        }
        val fixedOrigins = addon.manifest.permissions.networkOrigins.map {
            parseAddonNetworkUrl(it, originOnly = true).origin
        }
        require(parsed.origin in fixedOrigins || addon.manifest.permissions.allowUserOrigins) {
            "Network origin ${parsed.origin} is not declared by this add-on."
        }
        return parsed.origin
    }

    private suspend fun ensurePermission(capability: String, description: String) {
        permissionMutex.withLock {
            if (!manager.hasGrant(addon.id, capability)) {
                require(requestPermission(capability, description)) { "Permission was denied." }
                manager.setGrant(addon.id, capability, true)
            }
        }
    }

    private fun respond(id: String, success: Boolean, value: Any?) {
        val payload = JSONObject().put("value", value ?: JSONObject.NULL).toString()
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
      showKeyboard: () => call('ui.showKeyboard', {}),
      hideKeyboard: () => call('ui.hideKeyboard', {}),
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
            if (keyboardManager != null) {
                keyboardManager.requestDialog(
                    "Allow ${addon.manifest.name}?\n\n$description.\n\n" +
                        "This is a one-time approval for this add-on.",
                    listOf(
                        DialogRequestItem("Deny") { deferred.complete(false) },
                        DialogRequestItem("Allow") { deferred.complete(true) },
                    ),
                ) {
                    deferred.complete(false)
                }
            } else {
                permissionRequest = PermissionRequest(capability, description, deferred)
            }
        }
        deferred.await()
    }

    val webView = remember(addon.id, addon.manifest.versionCode, entrypoint) {
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
            onInputConnectionCreated = if (
                keyboardManager != null && addon.manifest.action.canShowKeyboard
            ) {
                { inputConnection, editorInfo ->
                    keyboardManager.overrideInputConnection(inputConnection, editorInfo)
                }
            } else {
                null
            }
            loadWhenSized("https://$LOCAL_HOST/package/$entrypoint")
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
    )

    var previousKeyboardShown by remember(webView) { mutableStateOf(keyboardShown) }
    LaunchedEffect(webView, keyboardShown) {
        if (previousKeyboardShown && !keyboardShown) {
            webView.disconnectFocusedEditorFromKeyboard()
        }
        previousKeyboardShown = keyboardShown
    }

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
