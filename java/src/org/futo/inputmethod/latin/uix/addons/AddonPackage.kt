package org.futo.inputmethod.latin.uix.addons

import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipInputStream

object AddonPackage {
    const val MAX_ARCHIVE_BYTES = 25L * 1024L * 1024L
    const val MAX_EXTRACTED_BYTES = 100L * 1024L * 1024L
    const val MAX_ENTRIES = 1_000

    private val manifestJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }
    private val validId = Regex("[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9_-]*)+")
    private val validKey = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")

    fun extractAndValidate(
        archive: File,
        outputDirectory: File,
        allowSystem: Boolean,
    ): AddonManifest {
        require(archive.isFile) { "The selected add-on is not a readable file." }
        require(archive.length() <= MAX_ARCHIVE_BYTES) { "The add-on ZIP is larger than 25 MiB." }
        require(outputDirectory.mkdirs() || outputDirectory.isDirectory) {
            "Could not create temporary add-on directory."
        }

        val canonicalRoot = outputDirectory.canonicalFile
        val rootPrefix = canonicalRoot.path + File.separator
        var entryCount = 0
        var extractedBytes = 0L
        val entryNames = mutableSetOf<String>()
        val targetPaths = mutableSetOf<String>()

        archive.inputStream().buffered().use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entryCount += 1
                    require(entryCount <= MAX_ENTRIES) { "The add-on ZIP contains too many files." }
                    require(entry.name.isNotBlank()) { "The add-on ZIP contains an empty path." }
                    require(entry.name.length <= 240) { "The add-on ZIP contains a path that is too long." }
                    require(entryNames.add(entry.name)) {
                        "The add-on ZIP contains a duplicate path: ${entry.name}"
                    }
                    require(!entry.name.contains('\\')) { "ZIP paths must use forward slashes." }
                    require(!entry.name.startsWith('/')) { "Absolute ZIP paths are not allowed." }

                    val target = File(outputDirectory, entry.name).canonicalFile
                    require(target.path == canonicalRoot.path || target.path.startsWith(rootPrefix)) {
                        "The add-on ZIP contains an unsafe path: ${entry.name}"
                    }
                    require(targetPaths.add(target.path)) {
                        "The add-on ZIP contains paths that overwrite the same file: ${entry.name}"
                    }

                    if (entry.isDirectory) {
                        require(target.mkdirs() || target.isDirectory) {
                            "Could not create ${entry.name}."
                        }
                    } else {
                        require(
                            target.parentFile?.let { parent ->
                                parent.mkdirs() || parent.isDirectory
                            } == true
                        ) {
                            "Could not create the parent of ${entry.name}."
                        }
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                extractedBytes += read
                                require(extractedBytes <= MAX_EXTRACTED_BYTES) {
                                    "The expanded add-on is larger than 100 MiB."
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        val manifestFile = File(outputDirectory, "addon.json")
        require(manifestFile.isFile) { "addon.json must be at the root of the ZIP." }
        val manifest = manifestJson.decodeFromString<AddonManifest>(manifestFile.readText())
        validateManifest(manifest, outputDirectory, allowSystem)
        return manifest
    }

    fun readInstalled(directory: File): AddonManifest {
        val file = File(directory, "addon.json")
        require(file.isFile) { "Installed add-on has no addon.json." }
        return manifestJson.decodeFromString(file.readText())
    }

    private fun validateManifest(
        manifest: AddonManifest,
        root: File,
        allowSystem: Boolean,
    ) {
        require(manifest.schemaVersion == ADDON_SCHEMA_VERSION) {
            "Unsupported add-on schema version ${manifest.schemaVersion}."
        }
        require(validId.matches(manifest.id)) {
            "Add-on IDs must use reverse-domain lowercase notation."
        }
        require(manifest.id.length <= 120) { "Add-on ID is too long." }
        require(manifest.name.isNotBlank() && manifest.name.length <= 80) {
            "Add-on name must be between 1 and 80 characters."
        }
        require(manifest.description.length <= 500) { "Add-on description is too long." }
        require(manifest.author.isNotBlank() && manifest.author.length <= 120) {
            "Add-on author must be between 1 and 120 characters."
        }
        require(manifest.versionCode > 0) { "versionCode must be positive." }
        require(manifest.versionName.isNotBlank() && manifest.versionName.length <= 40) {
            "versionName must be between 1 and 40 characters."
        }
        require(allowSystem || !manifest.system) {
            "Imported add-ons cannot claim the reserved system tag."
        }
        require(manifest.action.preferredHeight in setOf("compact", "expanded", "adaptive")) {
            "preferredHeight must be compact, expanded, or adaptive."
        }
        require(manifest.action.compactHeightDp in 48..600) {
            "compactHeightDp must be between 48 and 600."
        }
        require(manifest.action.expandedHeightDp in manifest.action.compactHeightDp..600) {
            "expandedHeightDp must be between compactHeightDp and 600."
        }

        validatePackageFile(root, manifest.icon, "icon")
        validatePackageFile(root, manifest.action.entrypoint, "action entrypoint")
        manifest.settingsEntrypoint?.let {
            validatePackageFile(root, it, "settings entrypoint")
        }

        val settingKeys = mutableSetOf<String>()
        manifest.settings.forEach { setting ->
            require(validKey.matches(setting.key)) { "Invalid setting key ${setting.key}." }
            require(settingKeys.add(setting.key)) { "Duplicate setting key ${setting.key}." }
            require(setting.title.isNotBlank()) { "Setting ${setting.key} has no title." }
            if (setting.type == AddonSettingType.Select) {
                require(setting.options.isNotEmpty()) {
                    "Select setting ${setting.key} must declare options."
                }
                require(setting.options.map { it.value }.toSet().size == setting.options.size) {
                    "Select setting ${setting.key} has duplicate option values."
                }
            }
        }
        manifest.settings.forEach { setting ->
            setting.visibleWhen?.let {
                require(it.key in settingKeys) {
                    "Setting ${setting.key} refers to unknown setting ${it.key}."
                }
                require(it.values.isNotEmpty()) {
                    "Setting ${setting.key} has an empty visibility condition."
                }
            }
        }

        manifest.permissions.networkOrigins.forEach { origin ->
            val uri = java.net.URI(origin)
            require(uri.scheme == "https" || (uri.scheme == "http" && manifest.permissions.allowInsecureHttp)) {
                "Network origin $origin must use HTTPS."
            }
            require(!uri.host.isNullOrBlank() && uri.path.orEmpty().let { it.isEmpty() || it == "/" }) {
                "Network permissions must be origins without paths: $origin"
            }
        }
    }

    private fun validatePackageFile(root: File, relativePath: String, label: String) {
        require(relativePath.isNotBlank() && !relativePath.contains('\\')) {
            "Invalid $label path."
        }
        val canonicalRoot = root.canonicalFile
        val file = File(root, relativePath).canonicalFile
        require(file.path.startsWith(canonicalRoot.path + File.separator) && file.isFile) {
            "Missing or unsafe $label: $relativePath"
        }
    }
}
