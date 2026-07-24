package org.futo.inputmethod.latin.uix.addons

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

class AddonManager private constructor(private val context: Context) {
    private val root = File(context.filesDir, "addons")
    private val installedRoot = File(root, "installed")
    private val stagingRoot = File(root, "staging")
    private val mediaRoot = File(context.cacheDir, "addon-media")
    private val preferences = context.getSharedPreferences("wisp_addons", Context.MODE_PRIVATE)
    private val mutableAddons = MutableStateFlow<List<InstalledAddon>>(emptyList())
    val addons: StateFlow<List<InstalledAddon>> = mutableAddons.asStateFlow()

    init {
        installedRoot.mkdirs()
        stagingRoot.mkdirs()
        mediaRoot.mkdirs()
        removeSupersededNativeSystemPackages()
        seedBundledAddons()
        refresh()
    }

    @Synchronized
    fun refresh() {
        mutableAddons.value = installedRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith('.') }
            ?.mapNotNull { directory ->
                runCatching {
                    val manifest = AddonPackage.readInstalled(directory)
                    InstalledAddon(manifest, directory, manifest.system)
                }.getOrNull()
            }
            ?.sortedBy { it.id }
            ?: emptyList()
        AddonActionRegistry.update(mutableAddons.value)
    }

    fun get(id: String): InstalledAddon? = addons.value.firstOrNull { it.id == id }

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
            if (get(manifest.id) != null || manifest.id.startsWith("org.futo.")) {
                cleanupPrepared(staged, archive)
                AddonInstallResult.Failure("An add-on with ID ${manifest.id} is already installed or reserved.")
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
    fun install(prepared: AddonInstallResult.Ready): Result<InstalledAddon> = runCatching {
        require(get(prepared.manifest.id) == null) {
            "An add-on with ID ${prepared.manifest.id} is already installed."
        }
        require(!prepared.manifest.system) {
            "Imported add-ons cannot claim the reserved system tag."
        }
        require(!prepared.manifest.id.startsWith("org.futo.")) {
            "The org.futo namespace is reserved for bundled add-ons."
        }
        val destination = File(installedRoot, prepared.manifest.id)
        require(!destination.exists()) { "The add-on destination already exists." }
        require(prepared.stagedDirectory.renameTo(destination)) {
            "Could not finish installing the add-on."
        }
        prepared.sourceArchive.delete()
        applySettingDefaults(prepared.manifest)
        refresh()
        org.futo.inputmethod.latin.uix.actions.refreshActionSettings(context)
        get(prepared.manifest.id) ?: error("Installed add-on could not be loaded.")
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
        File(mediaRoot, id).deleteRecursively()
        clearNamespacedPreferences(id)
        refresh()
        org.futo.inputmethod.latin.uix.actions.refreshActionSettings(context)
    }

    fun getSetting(id: String, key: String): String? =
        preferences.getString(settingKey(id, key), null)

    fun setSetting(id: String, key: String, value: String) {
        val definition = get(id)?.manifest?.settings?.firstOrNull { it.key == key }
            ?: throw IllegalArgumentException("Unknown add-on setting $key")
        if (definition.type == AddonSettingType.Select) {
            require(definition.options.any { it.value == value }) { "Invalid option for $key" }
        }
        preferences.edit().putString(settingKey(id, key), value).apply()
    }

    fun getState(id: String, key: String): String? =
        preferences.getString(stateKey(id, key), null)

    fun setState(id: String, key: String, value: String?) {
        require(key.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}"))) { "Invalid storage key." }
        preferences.edit().apply {
            if (value == null) remove(stateKey(id, key)) else putString(stateKey(id, key), value)
        }.apply()
    }

    fun hasGrant(id: String, capability: String): Boolean =
        preferences.getBoolean(grantKey(id, capability), false)

    fun setGrant(id: String, capability: String, granted: Boolean) {
        preferences.edit().putBoolean(grantKey(id, capability), granted).apply()
    }

    fun mediaDirectory(id: String): File = File(mediaRoot, id).also { it.mkdirs() }

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
        val directory = File(installedRoot, SUPERSEDED_TRANSLATE_PACKAGE_ID)
        if (
            directory.exists() &&
            directory.canonicalFile.parentFile == installedRoot.canonicalFile
        ) {
            directory.deleteRecursively()
        }
        File(mediaRoot, SUPERSEDED_TRANSLATE_PACKAGE_ID).deleteRecursively()
        clearNamespacedPreferences(SUPERSEDED_TRANSLATE_PACKAGE_ID)
    }

    private fun seedBundledAddons() {
        val bundled = context.assets.list("addons")
            ?.filter { it.endsWith(".zip", ignoreCase = true) }
            ?: emptyList()
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
                val destination = File(installedRoot, manifest.id)
                val installedVersion = runCatching {
                    AddonPackage.readInstalled(destination).versionCode
                }.getOrDefault(-1)
                if (!destination.exists() || installedVersion < manifest.versionCode) {
                    if (destination.exists()) destination.deleteRecursively()
                    require(staged.renameTo(destination)) {
                        "Could not seed bundled add-on ${manifest.id}."
                    }
                    applySettingDefaults(manifest)
                }
                cleanupPrepared(staged, archive)
            } catch (_: Exception) {
                cleanupPrepared(staged, archive)
            }
        }
    }

    private fun clearNamespacedPreferences(id: String) {
        val prefixes = listOf("setting:$id:", "state:$id:", "grant:$id:")
        preferences.edit().apply {
            preferences.all.keys.filter { key -> prefixes.any { key.startsWith(it) } }
                .forEach { remove(it) }
        }.apply()
    }

    private fun cleanupPrepared(directory: File, archive: File) {
        if (directory.parentFile?.canonicalFile == stagingRoot.canonicalFile) {
            directory.deleteRecursively()
        }
        if (archive.parentFile?.canonicalFile == stagingRoot.canonicalFile) {
            archive.delete()
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
        @Volatile private var instance: AddonManager? = null

        fun get(context: Context): AddonManager =
            instance ?: synchronized(this) {
                instance ?: AddonManager(context.applicationContext).also { instance = it }
            }
    }
}
