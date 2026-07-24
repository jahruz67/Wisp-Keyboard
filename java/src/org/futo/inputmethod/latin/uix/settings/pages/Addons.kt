package org.futo.inputmethod.latin.uix.settings.pages

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.addons.AddonInstallResult
import org.futo.inputmethod.latin.uix.addons.AddonManager
import org.futo.inputmethod.latin.uix.addons.AddonPermissions
import org.futo.inputmethod.latin.uix.addons.AddonSettingDefinition
import org.futo.inputmethod.latin.uix.addons.AddonSettingType
import org.futo.inputmethod.latin.uix.addons.AddonWebPanel
import org.futo.inputmethod.latin.uix.addons.InstalledAddon
import org.futo.inputmethod.latin.uix.addons.SYSTEM_TRANSLATE_ADDON_ROUTE_ID
import org.futo.inputmethod.latin.uix.addons.decodeAddonIcon
import org.futo.inputmethod.latin.uix.settings.BottomSpacer
import org.futo.inputmethod.latin.uix.settings.NavigationItem
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.Route
import org.futo.inputmethod.latin.uix.settings.ScreenTitle

@Composable
private fun addonIcon(addon: InstalledAddon): Painter {
    val path = remember(addon.id, addon.manifest.versionCode) {
        java.io.File(addon.directory, addon.manifest.icon).absolutePath
    }
    val bitmap = remember(path) { decodeAddonIcon(path)?.asImageBitmap() }
    return bitmap?.let { remember(it) { BitmapPainter(it) } }
        ?: painterResource(R.drawable.ic_addon)
}

private fun permissionSummary(permissions: AddonPermissions): List<String> = buildList {
    if (permissions.networkOrigins.isNotEmpty()) {
        add("Network: ${permissions.networkOrigins.joinToString()}")
    }
    if (permissions.allowUserOrigins) add("May request additional network origins")
    if (permissions.allowInsecureHttp) add("May request insecure HTTP connections")
    if (permissions.insertText) add("Insert text into the current app")
    if (permissions.insertMedia) add("Insert images or media into the current app")
    if (permissions.voiceInput) add("Capture voice transcription")
}.ifEmpty { listOf("No sensitive host capabilities") }

@Composable
fun AddonsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val manager = remember(context) { AddonManager.get(context) }
    val addons by manager.addons.collectAsState()
    val scope = rememberCoroutineScope()
    var prepared by remember { mutableStateOf<AddonInstallResult.Ready?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    fun prepare(uri: Uri) {
        importing = true
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { manager.prepareImport(uri) }) {
                is AddonInstallResult.Ready -> prepared = result
                is AddonInstallResult.Failure -> error = result.message
            }
            importing = false
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri -> uri?.let(::prepare) },
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTitle(context.getString(R.string.addons_settings_title), showBack = true, navController)
        NavigationItem(
            title = context.getString(R.string.translate_addon_title),
            subtitle = context.getString(R.string.translate_addon_subtitle) +
                " · " + context.getString(R.string.addons_system_tag),
            style = NavigationItemStyle.HomePrimary,
            icon = painterResource(R.drawable.ic_translate),
            navigate = {
                navController.navigate(Route.AddonDetail(SYSTEM_TRANSLATE_ADDON_ROUTE_ID))
            },
        )
        addons.forEach { addon ->
            NavigationItem(
                title = addon.manifest.name,
                subtitle = buildString {
                    append(addon.manifest.description)
                    append(" · v")
                    append(addon.manifest.versionName)
                    if (addon.isSystem) {
                        append(" · ")
                        append(context.getString(R.string.addons_system_tag))
                    }
                },
                style = NavigationItemStyle.HomePrimary,
                icon = addonIcon(addon),
                navigate = { navController.navigate(Route.AddonDetail(addon.id)) },
            )
        }
        NavigationItem(
            title = context.getString(R.string.addons_import),
            subtitle = if (importing) {
                context.getString(R.string.addons_importing)
            } else {
                context.getString(R.string.addons_import_subtitle)
            },
            style = NavigationItemStyle.Misc,
            icon = painterResource(R.drawable.ic_addon),
            navigate = {
                if (!importing) picker.launch("application/zip")
            },
        )
        BottomSpacer()
    }

    prepared?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                manager.cancel(pending)
                prepared = null
            },
            title = { Text(context.getString(R.string.addons_install_title, pending.manifest.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(context.getString(R.string.addons_unsigned_warning))
                    Text("${pending.manifest.author} · v${pending.manifest.versionName}")
                    permissionSummary(pending.manifest.permissions).forEach { Text("• $it") }
                    Text(context.getString(R.string.addons_first_use_notice))
                }
            },
            confirmButton = {
                Button(onClick = {
                    importing = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { manager.install(pending) }
                        prepared = null
                        importing = false
                        result.exceptionOrNull()?.let {
                            error = it.message ?: context.getString(R.string.addons_install_failed)
                        }
                    }
                }) { Text(context.getString(R.string.addons_install)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    manager.cancel(pending)
                    prepared = null
                }) { Text(context.getString(R.string.cancel)) }
            },
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text(context.getString(R.string.addons_error_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { error = null }) {
                    Text(context.getString(android.R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun AddonTitleBar(
    title: String,
    navController: NavHostController,
    onDelete: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { navController.navigateUp() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        if (onDelete != null) {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun NativeAddonSettings(addon: InstalledAddon, manager: AddonManager) {
    val values = remember(addon.id) {
        mutableStateMapOf<String, String>().apply {
            addon.manifest.settings.forEach { setting ->
                this[setting.key] = manager.getSetting(addon.id, setting.key)
                    ?: setting.default
                    ?: ""
            }
        }
    }

    addon.manifest.settings.forEach { setting ->
        val visible = setting.visibleWhen?.let { condition ->
            values[condition.key] in condition.values
        } ?: true
        if (!visible) return@forEach

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (setting.type) {
                AddonSettingType.Boolean -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val value = values[setting.key] != "true"
                                values[setting.key] = value.toString()
                                manager.setSetting(addon.id, setting.key, value.toString())
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(setting.title, style = MaterialTheme.typography.titleMedium)
                            setting.description?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Switch(
                            checked = values[setting.key] == "true",
                            onCheckedChange = {
                                values[setting.key] = it.toString()
                                manager.setSetting(addon.id, setting.key, it.toString())
                            },
                        )
                    }
                }

                AddonSettingType.Select -> SelectAddonSetting(setting, values[setting.key].orEmpty()) {
                    values[setting.key] = it
                    manager.setSetting(addon.id, setting.key, it)
                }

                else -> {
                    var text by remember(addon.id, setting.key) {
                        mutableStateOf(values[setting.key].orEmpty())
                    }
                    LaunchedEffect(text) {
                        values[setting.key] = text
                        manager.setSetting(addon.id, setting.key, text)
                    }
                    Text(setting.title, style = MaterialTheme.typography.titleMedium)
                    setting.description?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = setting.type != AddonSettingType.String,
                        visualTransformation = if (setting.type == AddonSettingType.Secret) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectAddonSetting(
    setting: AddonSettingDefinition,
    value: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Text(setting.title, style = MaterialTheme.typography.titleMedium)
    setting.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    TextButton(onClick = { expanded = true }) {
        Text(setting.options.firstOrNull { it.value == value }?.label ?: value)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        setting.options.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.label) },
                onClick = {
                    expanded = false
                    onSelect(option.value)
                },
            )
        }
    }
}

@Composable
private fun TranslationSystemAddonSettings() {
    TranslateMenu.settings.drop(1).forEach { setting ->
        if (setting.visibilityCheck?.invoke() != false) {
            setting.component()
        }
    }
}

@Composable
private fun TranslationSystemAddonDetailScreen(navController: NavHostController) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        AddonTitleBar(
            title = context.getString(R.string.translate_addon_title),
            navController = navController,
            onDelete = null,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_translate),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(context.getString(R.string.translate_addon_subtitle))
                    Text(
                        context.getString(R.string.addons_system_managed),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                AssistChip(
                    onClick = {},
                    label = { Text(context.getString(R.string.addons_system_tag)) },
                )
            }
            Spacer(Modifier.height(12.dp))
            TranslationSystemAddonSettings()
            BottomSpacer()
        }
    }
}

@Composable
fun AddonDetailScreen(id: String, navController: NavHostController) {
    if (id == SYSTEM_TRANSLATE_ADDON_ROUTE_ID) {
        TranslationSystemAddonDetailScreen(navController)
        return
    }

    val context = LocalContext.current
    val manager = remember(context) { AddonManager.get(context) }
    val addons by manager.addons.collectAsState()
    val addon = addons.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (addon == null) {
        LaunchedEffect(id) { navController.navigateUp() }
        return
    }

    Column(Modifier.fillMaxSize()) {
        AddonTitleBar(
            title = addon.manifest.name,
            navController = navController,
            onDelete = if (addon.isSystem) null else {
                { confirmDelete = true }
            },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(addonIcon(addon), contentDescription = null, modifier = Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(addon.manifest.description)
                    Text(
                        "${addon.manifest.author} · v${addon.manifest.versionName}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (addon.isSystem) {
                    AssistChip(
                        onClick = {},
                        label = { Text(context.getString(R.string.addons_system_tag)) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            NativeAddonSettings(addon, manager)
            addon.manifest.settingsEntrypoint?.let { entrypoint ->
                Spacer(Modifier.height(8.dp))
                AddonWebPanel(
                    addon = addon,
                    entrypoint = entrypoint,
                    modifier = Modifier.fillMaxWidth().height(480.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Permissions",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            permissionSummary(addon.manifest.permissions).forEach {
                Text("• $it", modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
            }
            BottomSpacer()
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${addon.manifest.name}?") },
            text = {
                Text(
                    "This removes the add-on, its action placements, settings, secrets, " +
                        "permission grants, and cached media."
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { manager.uninstall(addon.id) }
                        result.fold(
                            onSuccess = { navController.navigateUp() },
                            onFailure = { error = it.message ?: "Could not delete the add-on." },
                        )
                    }
                }) { Text("Confirm delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    error?.let {
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Add-on error") },
            text = { Text(it) },
            confirmButton = {
                TextButton(onClick = { error = null }) { Text("OK") }
            },
        )
    }
}
