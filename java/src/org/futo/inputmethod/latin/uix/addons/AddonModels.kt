package org.futo.inputmethod.latin.uix.addons

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

const val ADDON_SCHEMA_VERSION = 1
const val SYSTEM_TRANSLATE_ADDON_ROUTE_ID = "@system.translate"
internal const val SUPERSEDED_TRANSLATE_PACKAGE_ID = "org.futo.wisp.translation"
internal const val MAX_ADDON_STORED_VALUE_BYTES = 1L * 1024L * 1024L
internal const val MAX_ADDON_STORAGE_BYTES = 5L * 1024L * 1024L
internal const val MAX_ADDON_STORAGE_KEYS = 256

internal data class ParsedAddonNetworkUrl(
    val uri: URI,
    val origin: String,
)

internal fun parseAddonNetworkUrl(value: String, originOnly: Boolean): ParsedAddonNetworkUrl {
    val uri = URI(value)
    val scheme = uri.scheme?.lowercase()
    require(scheme == "https" || scheme == "http") {
        "Only HTTP(S) requests are supported."
    }
    require(!uri.host.isNullOrBlank() && uri.userInfo == null) { "Invalid network URL." }
    require(uri.port in -1..65_535) { "Invalid network port." }
    require(uri.fragment == null) { "Network URLs cannot contain fragments." }
    if (originOnly) {
        require(
            uri.rawQuery == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
        ) {
            "Network permissions must be origins without paths or queries: $value"
        }
    }

    val defaultPort = (scheme == "https" && uri.port == 443) ||
        (scheme == "http" && uri.port == 80)
    val port = if (uri.port == -1 || defaultPort) "" else ":${uri.port}"
    val normalizedHost = uri.host
        .lowercase()
        .removePrefix("[")
        .removeSuffix("]")
        .let { host ->
            if (':' in host) "[$host]" else host
        }
    return ParsedAddonNetworkUrl(uri, "$scheme://$normalizedHost$port")
}

@Serializable
enum class AddonSettingType {
    @SerialName("boolean") Boolean,
    @SerialName("string") String,
    @SerialName("secret") Secret,
    @SerialName("url") Url,
    @SerialName("select") Select,
}

@Serializable
data class AddonSettingOption(
    val value: String,
    val label: String,
)

@Serializable
data class AddonSettingVisibility(
    val key: String,
    val values: List<String>,
)

@Serializable
data class AddonSettingDefinition(
    val key: String,
    val type: AddonSettingType,
    val title: String,
    val description: String? = null,
    val default: String? = null,
    val options: List<AddonSettingOption> = emptyList(),
    val visibleWhen: AddonSettingVisibility? = null,
)

@Serializable
data class AddonActionDefinition(
    val entrypoint: String,
    val canShowKeyboard: Boolean = true,
    val preferredHeight: String = "adaptive",
    val compactHeightDp: Int = 160,
    val expandedHeightDp: Int = 280,
)

@Serializable
data class AddonPermissions(
    val networkOrigins: List<String> = emptyList(),
    val allowUserOrigins: Boolean = false,
    val allowInsecureHttp: Boolean = false,
    val insertText: Boolean = false,
    val insertMedia: Boolean = false,
    val voiceInput: Boolean = false,
)

@Serializable
data class AddonManifest(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val versionCode: Int,
    val versionName: String,
    val icon: String,
    val system: Boolean = false,
    val action: AddonActionDefinition,
    val settings: List<AddonSettingDefinition> = emptyList(),
    val settingsEntrypoint: String? = null,
    val permissions: AddonPermissions = AddonPermissions(),
)

data class InstalledAddon(
    val manifest: AddonManifest,
    val directory: java.io.File,
    val isSystem: Boolean,
) {
    val id: String get() = manifest.id
}

sealed class AddonInstallResult {
    data class Ready(
        val manifest: AddonManifest,
        internal val stagedDirectory: java.io.File,
        internal val sourceArchive: java.io.File,
    ) : AddonInstallResult()

    data class Failure(val message: String) : AddonInstallResult()
}
