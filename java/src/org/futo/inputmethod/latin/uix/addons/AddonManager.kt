package org.futo.inputmethod.latin.uix.addons

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.uix.actions.MaximumAddonActionCount
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AddonManager private constructor(private val context: Context) {
    private val validStorageKey = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")
    private val root = File(context.filesDir, "addons")
    private val installedRoot = File(root, "installed")
    private val stagingRoot = File(root, "staging")
    private val mediaRoot = File(context.cacheDir, "addon-media")
    private val preferences = context.getSharedPreferences("wisp_addons", Context.MODE_PRIVATE)
    private val mutableAddons = MutableStateFlow<List<InstalledAddon>>(emptyList())
    val addons: StateFlow<List<InstalledAddon>> = mutableAddons.asStateFlow()
    @Volatile private var addonsById: Map<String, InstalledAddon> = emptyMap()
    private val mediaDirectories = ConcurrentHashMap<String, File>()

    init {
        installedRoot.mkdirs()
        stagingRoot.mkdirs()
        mediaRoot.mkdirs()
        clearStaging()
        recoverInterruptedBundledUpdates()
        removeSupersededNativeSystemPackages()
        refresh()

        val bundled = context.assets.list("addons")
            ?.filter { it.endsWith(".zip", ignoreCase = true) }
            ?.sorted()
            ?: emptyList()
        val bundledSignature = bundled.joinToString(separator = "\u0000")
        val bundledStateChanged =
            preferences.getInt(BUNDLED_APP_VERSION_KEY, Int.MIN_VALUE) != BuildConfig.VERSION_CODE ||
                preferences.getString(BUNDLED_ASSET_SIGNATURE_KEY, null) != bundledSignature ||
                addons.value.count { it.isSystem } < bundled.size
        if (bundledStateChanged) {
            if (seedBundledAddons(bundled)) {
                preferences.edit()
                    .putInt(BUNDLED_APP_VERSION_KEY, BuildConfig.VERSION_CODE)
                    .putString(BUNDLED_ASSET_SIGNATURE_KEY, bundledSignature)
                    .apply()
            }
            refresh()
        }
    }

    @Synchronized
    fun refresh() {
        val installed = installedRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith('.') }
            ?.mapNotNull { directory ->
                runCatching {
                    val manifest = AddonPackage.readInstalled(directory)
                    InstalledAddon(manifest, directory, manifest.system)
                }.getOrNull()
            }
            ?.sortedBy { it.id }
            ?: emptyList()
        addonsById = installed.associateBy { it.id }
        mutableAddons.value = installed
        AddonActionRegistry.update(installed)
    }

    fun get(id: String): InstalledAddon? = addonsById[id]

    fun prepareImport(uri: Uri): AddonInstallResult {
        val archive = File(stagingRoot, "${UUID.randomUUID()}.zip")
        val staged = File(stagingRoot, UUID.randomUUID().toString())
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                archive.outputStream().use { output ->
                    copyLimited(input, output, AddonPackage.MAX_ARCHIVE_BYTES)
                }
            } ?: return AddonInstallResult.Failure("Could not open the selected ZIP.")

            val manifest = AddonPackage.extractAndValidate(archive, staged, allowSystem = false)
            if (
                get(manifest.id) != null ||
                manifest.id.startsWith("org.futo.") ||
                addons.value.size >= MaximumAddonActionCount
            ) {
                cleanupPrepared(staged, archive)
                AddonInstallResult.Failure(
                    if (addons.value.size >= MaximumAddonActionCount) {
                        "The maximum number of add-ons is already installed."
                    } else {
                        "An add-on with ID ${manifest.id} is already installed or reserved."
                    }
                )
            } else {
                AddonInstallResult.Ready(
                    manifest = manifest,
                    stagedDirectory = staged,
                    sourceArchive = archive,
                )
            }
        } catch (e: Exception) {
            cleanupPrepared(staged, archive)
            AddonInstallResult.Failure(e.message ?: "The selected file is not a valid add-on.")
        }
    }

    @Synchronized
    fun install(prepared: AddonInstallResult.Ready): Result<InstalledAddon> =
        runCatching {
            require(get(prepared.manifest.id) == null) {
                "An add-on with ID ${prepared.manifest.id} is already installed."
            }
            require(!prepared.manifest.system) {
                "Imported add-ons cannot claim the reserved system tag."
            }
            require(!prepared.manifest.id.startsWith("org.futo.")) {
                "The org.futo namespace is reserved for bundled add-ons."
            }
            require(addons.value.size < MaximumAddonActionCount) {
                "The maximum number of add-ons is already installed."
            }
            require(prepared.stagedDirectory.parentFile?.canonicalFile == stagingRoot.canonicalFile) {
                "Unsafe staged add-on directory."
            }
            require(prepared.sourceArchive.parentFile?.canonicalFile == stagingRoot.canonicalFile) {
                "Unsafe staged add-on archive."
            }
            val destination = File(installedRoot, prepared.manifest.id)
            require(!destination.exists()) { "The add-on destination already exists." }
            require(prepared.stagedDirectory.renameTo(destination)) {
                "Could not finish installing the add-on."
            }
            prepared.sourceArchive.delete()
            applySettingDefaults(prepared.manifest)
            refresh()
            val installed = get(prepared.manifest.id)
                ?: error("Installed add-on could not be loaded.")
            refreshActionSettingsSafely()
            installed
        }.onFailure {
            cleanupPrepared(prepared.stagedDirectory, prepared.sourceArchive)
        }

    fun cancel(prepared: AddonInstallResult.Ready) {
        cleanupPrepared(prepared.stagedDirectory, prepared.sourceArchive)
    }

    @Synchronized
    fun uninstall(id: String): Result<Unit> = runCatching {
        val addon = get(id) ?: error("Add-on is not installed.")
        require(!addon.isSystem) { "System add-ons cannot be uninstalled." }
        require(addon.directory.canonicalFile.parentFile == installedRoot.canonicalFile) {
            "Unsafe add-on directory."
        }
        require(addon.directory.deleteRecursively()) { "Could not delete the add-on files." }
        mediaDirectories.remove(id)
        File(mediaRoot, id).deleteRecursively()
        clearNamespacedPreferences(id)
        refresh()
        refreshActionSettingsSafely()
    }

    @Synchronized
    fun getSetting(id: String, key: String): String? {
        require(get(id)?.manifest?.settings?.any { it.key == key } == true) {
            "Unknown add-on setting $key"
        }
        return preferences.getString(settingKey(id, key), null)
    }

    @Synchronized
    fun setSetting(id: String, key: String, value: String) {
        val definition = get(id)?.manifest?.settings?.firstOrNull { it.key == key }
            ?: throw IllegalArgumentException("Unknown add-on setting $key")
        require(value.toByteArray().size <= MAX_ADDON_STORED_VALUE_BYTES) {
            "Setting $key is too large."
        }
        if (definition.type == AddonSettingType.Boolean) {
            require(value == "true" || value == "false") { "Invalid boolean value for $key" }
        }
        if (definition.type == AddonSettingType.Select) {
            require(definition.options.any { it.value == value }) { "Invalid option for $key" }
        }
        preferences.edit().putString(settingKey(id, key), value).apply()
    }

    @Synchronized
    fun getState(id: String, key: String): String? {
        require(get(id) != null) { "Add-on is not installed." }
        require(validStorageKey.matches(key)) { "Invalid storage key." }
        return preferences.getString(stateKey(id, key), null)
    }

    @Synchronized
    fun setState(id: String, key: String, value: String?) {
        require(get(id) != null) { "Add-on is not installed." }
        require(validStorageKey.matches(key)) { "Invalid storage key." }
        if (value != null) validateStorageQuota(id, key, value)
        preferences.edit().apply {
            if (value == null) remove(stateKey(id, key)) else putString(stateKey(id, key), value)
        }.apply()
    }

    @Synchronized
    fun hasGrant(id: String, capability: String): Boolean {
        require(get(id) != null) { "Add-on is not installed." }
        return preferences.getBoolean(grantKey(id, capability), false)
    }

    @Synchronized
    fun setGrant(id: String, capability: String, granted: Boolean) {
        require(get(id) != null) { "Add-on is not installed." }
        preferences.edit().putBoolean(grantKey(id, capability), granted).apply()
    }

    fun mediaDirectory(id: String): File =
        mediaDirectories.getOrPut(id) { File(mediaRoot, id).also { it.mkdirs() } }

    private fun applySettingDefaults(manifest: AddonManifest) {
        val editor = preferences.edit()
        manifest.settings.forEach { setting ->
            val key = settingKey(manifest.id, setting.key)
            if (!preferences.contains(key) && setting.default != null) {
                editor.putString(key, setting.default)
            }
        }
        editor.apply()
    }

    private fun removeSupersededNativeSystemPackages() {
        if (preferences.getBoolean(SUPERSEDED_TRANSLATE_REMOVED_KEY, false)) return

        val directory = File(installedRoot, SUPERSEDED_TRANSLATE_PACKAGE_ID)
        var removalSucceeded = true
        if (
            directory.exists() &&
            directory.canonicalFile.parentFile == installedRoot.canonicalFile
        ) {
            removalSucceeded = directory.deleteRecursively()
        }
        mediaDirectories.remove(SUPERSEDED_TRANSLATE_PACKAGE_ID)
        val media = File(mediaRoot, SUPERSEDED_TRANSLATE_PACKAGE_ID)
        if (media.exists()) removalSucceeded = media.deleteRecursively() && removalSucceeded
        clearNamespacedPreferences(SUPERSEDED_TRANSLATE_PACKAGE_ID)
        if (removalSucceeded) {
            preferences.edit().putBoolean(SUPERSEDED_TRANSLATE_REMOVED_KEY, true).apply()
        }
    }

    private fun seedBundledAddons(bundled: List<String>): Boolean {
        val bundledIds = mutableSetOf<String>()
        var allBundlesValid = true
        bundled.forEach { assetName ->
            val archive = File(stagingRoot, "bundled-${UUID.randomUUID()}.zip")
            val staged = File(stagingRoot, "bundled-${UUID.randomUUID()}")
            try {
                context.assets.open("addons/$assetName").use { input ->
                    archive.outputStream().use { output ->
                        copyLimited(input, output, AddonPackage.MAX_ARCHIVE_BYTES)
                    }
                }
                val manifest = AddonPackage.extractAndValidate(archive, staged, allowSystem = true)
                require(manifest.system) { "Bundled add-on ${manifest.id} must set system=true." }
                require(manifest.id.startsWith("org.futo.")) {
                    "Bundled add-on ${manifest.id} must use the reserved org.futo namespace."
                }
                require(bundledIds.add(manifest.id)) {
                    "Multiple bundled packages use add-on ID ${manifest.id}."
                }
                val destination = File(installedRoot, manifest.id)
                require(
                    destination.exists() ||
                        (
                            installedRoot.listFiles()
                                ?.count { it.isDirectory && !it.name.startsWith('.') }
                                ?: 0
                            ) < MaximumAddonActionCount
                ) {
                    "The maximum number of add-ons is already installed."
                }
                val replacingExisting = destination.exists()
                val installedVersion = runCatching {
                    AddonPackage.readInstalled(destination).versionCode
                }.getOrDefault(-1)
                if (!destination.exists() || installedVersion != manifest.versionCode) {
                    replaceBundledAddon(staged, destination)
                    if (replacingExisting) clearGrantPreferences(manifest.id)
                    applySettingDefaults(manifest)
                }
                cleanupPrepared(staged, archive)
            } catch (e: Exception) {
                allBundlesValid = false
                Log.e("AddonManager", "Could not seed bundled add-on $assetName", e)
                cleanupPrepared(staged, archive)
            }
        }
        if (allBundlesValid) removeObsoleteBundledAddons(bundledIds)
        return allBundlesValid
    }

    private fun clearNamespacedPreferences(id: String) {
        val prefixes = listOf("setting:$id:", "state:$id:", "grant:$id:")
        val keys = preferences.all.keys.filter { key -> prefixes.any { key.startsWith(it) } }
        if (keys.isNotEmpty()) {
            preferences.edit().apply { keys.forEach { remove(it) } }.apply()
        }
    }

    private fun clearGrantPreferences(id: String) {
        val prefix = "grant:$id:"
        val keys = preferences.all.keys.filter { it.startsWith(prefix) }
        if (keys.isNotEmpty()) {
            preferences.edit().apply { keys.forEach { remove(it) } }.apply()
        }
    }

    private fun removeObsoleteBundledAddons(bundledIds: Set<String>) {
        installedRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith('.') }
            ?.forEach { directory ->
                val manifest = runCatching { AddonPackage.readInstalled(directory) }.getOrNull()
                    ?: return@forEach
                if (manifest.system && manifest.id !in bundledIds) {
                    if (directory.deleteRecursively()) {
                        mediaDirectories.remove(manifest.id)
                        File(mediaRoot, manifest.id).deleteRecursively()
                        clearNamespacedPreferences(manifest.id)
                    } else {
                        Log.e("AddonManager", "Could not remove obsolete bundled add-on ${manifest.id}")
                    }
                }
            }
    }

    private fun cleanupPrepared(directory: File, archive: File) {
        if (directory.parentFile?.canonicalFile == stagingRoot.canonicalFile) {
            directory.deleteRecursively()
        }
        if (archive.parentFile?.canonicalFile == stagingRoot.canonicalFile) {
            archive.delete()
        }
    }

    private fun validateStorageQuota(id: String, key: String, value: String) {
        val valueBytes = value.toByteArray().size.toLong()
        require(valueBytes <= MAX_ADDON_STORED_VALUE_BYTES) { "Storage value is too large." }

        val prefix = "state:$id:"
        val targetKey = stateKey(id, key)
        val existing = preferences.all.filterKeys { it.startsWith(prefix) }
        val keyCount = existing.size + if (targetKey in existing) 0 else 1
        require(keyCount <= MAX_ADDON_STORAGE_KEYS) {
            "Add-on storage has too many keys."
        }
        val otherBytes = existing.entries.sumOf { (storedKey, storedValue) ->
            if (storedKey == targetKey) {
                0L
            } else {
                (storedValue as? String)?.toByteArray()?.size?.toLong() ?: 0L
            }
        }
        require(otherBytes + valueBytes <= MAX_ADDON_STORAGE_BYTES) {
            "Add-on storage quota exceeded."
        }
    }

    private fun clearStaging() {
        stagingRoot.listFiles()?.forEach { file ->
            if (file.parentFile?.canonicalFile == stagingRoot.canonicalFile) {
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            }
        }
    }

    private fun recoverInterruptedBundledUpdates() {
        installedRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(BUNDLED_BACKUP_PREFIX) }
            ?.forEach { backup ->
                val id = backup.name.removePrefix(BUNDLED_BACKUP_PREFIX)
                val destination = File(installedRoot, id)
                if (destination.exists()) {
                    backup.deleteRecursively()
                } else if (!backup.renameTo(destination)) {
                    Log.e("AddonManager", "Could not restore bundled add-on $id")
                }
            }
    }

    private fun replaceBundledAddon(staged: File, destination: File) {
        val backup = File(installedRoot, "$BUNDLED_BACKUP_PREFIX${destination.name}")
        require(!backup.exists()) { "A bundled add-on backup already exists." }
        if (destination.exists()) {
            require(destination.renameTo(backup)) {
                "Could not preserve the installed add-on ${destination.name}."
            }
        }
        if (!staged.renameTo(destination)) {
            val restored = !backup.exists() || backup.renameTo(destination)
            require(restored) {
                "Could not install or restore bundled add-on ${destination.name}."
            }
            error("Could not seed bundled add-on ${destination.name}.")
        }
        if (backup.exists() && !backup.deleteRecursively()) {
            Log.w("AddonManager", "Could not remove bundled add-on backup ${backup.name}")
        }
    }

    private fun refreshActionSettingsSafely() {
        runCatching {
            org.futo.inputmethod.latin.uix.actions.refreshActionSettings(context)
        }.onFailure {
            Log.e("AddonManager", "Could not refresh action settings", it)
        }
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
            require(total <= limit) { "The add-on ZIP is larger than 25 MiB." }
            output.write(buffer, 0, read)
        }
    }

    private fun settingKey(id: String, key: String) = "setting:$id:$key"
    private fun stateKey(id: String, key: String) = "state:$id:$key"
    private fun grantKey(id: String, capability: String) = "grant:$id:$capability"

    companion object {
        private const val BUNDLED_BACKUP_PREFIX = ".bundled-backup-"
        private const val BUNDLED_APP_VERSION_KEY = "bundled_addons_app_version"
        private const val BUNDLED_ASSET_SIGNATURE_KEY = "bundled_addons_asset_signature"
        private const val SUPERSEDED_TRANSLATE_REMOVED_KEY = "superseded_translate_removed"
        @Volatile private var instance: AddonManager? = null

        fun get(context: Context): AddonManager =
            instance ?: synchronized(this) {
                instance ?: AddonManager(context.applicationContext).also { instance = it }
            }
    }
}
