package com.codescv.delta.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.codescv.delta.DeltaApplication
import com.codescv.delta.emulation.NativeBridge
import com.codescv.delta.model.GameSystem
import com.codescv.delta.settings.AppPreferences
import com.codescv.delta.settings.VideoFilter
import com.codescv.delta.ui.components.FormCheckRow
import com.codescv.delta.ui.components.FormDivider
import com.codescv.delta.ui.components.FormRow
import com.codescv.delta.ui.components.FormSection
import com.codescv.delta.ui.components.FormSegmentedRow
import com.codescv.delta.ui.components.FormSliderRow
import com.codescv.delta.ui.components.FormToggleRow
import com.codescv.delta.ui.theme.DeltaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// ------------------------------------------------------------------------------------ Audio

@Composable
internal fun AudioSettings(prefs: AppPreferences, nav: SettingsNav) {
    SettingsPage {
        FormSection(footer = "When enabled, Delta will only play game audio if your device isn't silenced.") {
            FormToggleRow("Respect Silent Mode", prefs.respectSilentMode, { v -> nav.update { it.copy(respectSilentMode = v) } })
        }
        FormSection {
            FormToggleRow("Mute Audio", prefs.muteAudio, { v -> nav.update { it.copy(muteAudio = v) } })
        }
        FormSection(header = "Game Volume") {
            FormSliderRow(
                value = prefs.volume.toFloat(),
                onValueChange = { v -> nav.update { it.copy(volume = v.coerceIn(0f, 1f).toDouble()) } },
                leading = { Icon(Icons.AutoMirrored.Filled.VolumeMute, null, tint = DeltaColors.SystemGray, modifier = Modifier.size(20.dp)) },
                trailing = { Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = DeltaColors.SystemGray, modifier = Modifier.size(22.dp)) },
            )
        }
    }
}

// ------------------------------------------------------------------------------------ Video

@Composable
internal fun VideoSettings(prefs: AppPreferences, nav: SettingsNav) {
    SettingsPage {
        FormSection(footer = "Integer scaling keeps pixels perfectly square by scaling the game in whole steps.") {
            PickerRow(
                title = "Video Filter",
                options = VideoFilter.entries.map { it.raw to it.title },
                selected = VideoFilter.fromRaw(prefs.filter).raw,
                onSelect = { v -> nav.update { it.copy(filter = v) } },
            )
            FormDivider()
            FormToggleRow("Integer Scaling", prefs.integerScaling, { v -> nav.update { it.copy(integerScaling = v) } })
        }
    }
}

// ------------------------------------------------------------------------------------ Cores

/** One libretro core option as reported by `NativeBridge.nativeCoreOptionsJson`. */
@Serializable
data class CoreOptionInfo(
    val key: String,
    val title: String = "",
    val info: String = "",
    val values: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    val default: String = "",
    val value: String = "",
    val visible: Boolean = true,
) {
    fun label(v: String): String {
        val i = values.indexOf(v)
        return labels.getOrNull(i)?.takeIf { it.isNotBlank() } ?: v
    }

    val displayTitle: String get() = title.ifBlank { key }
}

/** Queries option definitions from a transient core instance (cached per core). */
object CoreOptionsCatalog {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val cache = ConcurrentHashMap<String, List<CoreOptionInfo>>()

    fun parse(text: String): List<CoreOptionInfo> =
        runCatching { json.decodeFromString<List<CoreOptionInfo>>(text) }.getOrDefault(emptyList())

    suspend fun load(coreId: String): List<CoreOptionInfo> = withContext(Dispatchers.IO) {
        cache[coreId] ?: runCatching {
            NativeBridge.ensureLoaded()
            val c = DeltaApplication.container
            val scratch = File(c.coreCacheDir, "options-probe").apply { mkdirs() }
            val handle = NativeBridge.nativeCreate(coreId, c.systemDir.absolutePath, scratch.absolutePath, scratch.absolutePath)
            if (handle == 0L) {
                emptyList()
            } else {
                try {
                    parse(NativeBridge.nativeCoreOptionsJson(handle)).filter { it.values.isNotEmpty() }
                } finally {
                    NativeBridge.nativeDestroy(handle)
                }
            }
        }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) cache[coreId] = it }
    }
}

@Composable
private fun rememberCoreOptions(coreId: String): List<CoreOptionInfo>? {
    val state by produceState<List<CoreOptionInfo>?>(null, coreId) { value = CoreOptionsCatalog.load(coreId) }
    return state
}

private fun AppPreferences.coreOption(coreId: String, option: CoreOptionInfo): String =
    coreOptions[coreId]?.get(option.key) ?: option.default.ifEmpty { option.value }

private fun AppPreferences.withCoreOption(coreId: String, key: String, value: String): AppPreferences =
    copy(coreOptions = coreOptions + (coreId to (coreOptions[coreId].orEmpty() + (key to value))))

@Composable
internal fun CoresSettings(prefs: AppPreferences, nav: SettingsNav) {
    val n64Options = rememberCoreOptions(SettingsData.N64_CORE_ID)
    SettingsPage {
        FormSection(header = "Registered Delta Cores") {
            SettingsData.coreSystems.forEachIndexed { i, system ->
                if (i > 0) FormDivider()
                FormRow(
                    title = system.displayName,
                    value = SettingsData.coreValue(system),
                    chevron = true,
                    onClick = { nav.push(SettingsRoute.CoreOptions(system.coreId, system.coreDisplayName)) },
                )
            }
        }
        FormSection(header = "Nintendo DS") {
            FormRow(
                title = SettingsData.MELONDS_ROW,
                value = if (prefs.ndsBiosMode == "external") "Imported BIOS" else SettingsData.BUILT_IN_BIOS,
                chevron = true,
                onClick = { nav.push(SettingsRoute.MelonDsBios) },
            )
        }
        FormSection(header = "Nintendo 64", footer = "Changes apply the next time a game starts.") {
            val coreId = SettingsData.N64_CORE_ID
            val aspect = n64Options?.firstOrNull { it.key == SettingsData.N64_ASPECT_KEY }
            FormToggleRow(
                title = "Widescreen (16:9)",
                checked = prefs.coreOptions[coreId]?.get(SettingsData.N64_ASPECT_KEY) == "widescreen",
                onCheckedChange = { on ->
                    nav.update { it.withCoreOption(coreId, SettingsData.N64_ASPECT_KEY, if (on) "widescreen" else "normal") }
                },
                enabled = n64Options == null || aspect != null,
            )
            n64Options?.firstOrNull { it.key == SettingsData.N64_RESOLUTION_KEY }?.let { res ->
                FormDivider()
                PickerRow(
                    title = "Resolution",
                    options = res.values.map { it to res.label(it) },
                    selected = prefs.coreOption(coreId, res),
                    onSelect = { v -> nav.update { it.withCoreOption(coreId, res.key, v) } },
                )
            }
            FormDivider()
            FormRow(
                title = "Core Options",
                chevron = true,
                onClick = { nav.push(SettingsRoute.CoreOptions(coreId, GameSystem.N64.coreDisplayName)) },
            )
        }
    }
}

@Composable
internal fun CoreOptionsSettings(coreId: String, prefs: AppPreferences, nav: SettingsNav) {
    val options = rememberCoreOptions(coreId)
    SettingsPage {
        val visible = options?.filter { it.visible }
        FormSection(header = "Core Options", footer = "Changes apply the next time a game starts.") {
            when {
                visible == null -> FormRow(title = "Loading…", titleColor = DeltaColors.SystemGray)
                visible.isEmpty() -> FormRow(title = "No Options", titleColor = DeltaColors.SystemGray)
                else -> visible.forEachIndexed { i, option ->
                    if (i > 0) FormDivider()
                    FormRow(
                        title = option.displayTitle,
                        value = option.label(prefs.coreOption(coreId, option)),
                        chevron = true,
                        onClick = { nav.push(SettingsRoute.CoreOptionValues(coreId, option.key, option.displayTitle)) },
                    )
                }
            }
        }
        if (!prefs.coreOptions[coreId].isNullOrEmpty()) {
            FormSection {
                ActionRow("Reset Core Options", Icons.Outlined.Replay, DestructiveRed) {
                    nav.update { it.copy(coreOptions = it.coreOptions - coreId) }
                }
            }
        }
    }
}

@Composable
internal fun CoreOptionValuesSettings(coreId: String, optionKey: String, prefs: AppPreferences, nav: SettingsNav) {
    val option = rememberCoreOptions(coreId)?.firstOrNull { it.key == optionKey }
    SettingsPage {
        FormSection(footer = option?.info?.ifBlank { null }) {
            if (option == null) {
                FormRow(title = "Loading…", titleColor = DeltaColors.SystemGray)
            } else {
                val current = prefs.coreOption(coreId, option)
                option.values.forEachIndexed { i, v ->
                    if (i > 0) FormDivider()
                    FormCheckRow(
                        title = option.label(v),
                        selected = v == current,
                        subtitle = if (v == option.default) "Default" else null,
                    ) { nav.update { it.withCoreOption(coreId, option.key, v) } }
                }
            }
        }
    }
}

// --------------------------------------------------------------------------- melonDS BIOS

@Composable
internal fun MelonDsBiosSettings(prefs: AppPreferences, nav: SettingsNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<String?>(null) }
    var manage by remember { mutableStateOf<String?>(null) }
    val systemDir = DeltaApplication.container.systemDir

    val sizes by produceState(emptyMap<String, Long>(), revision) {
        value = withContext(Dispatchers.IO) {
            SettingsData.biosFiles.mapNotNull { f -> File(systemDir, f.filename).takeIf { it.isFile }?.let { f.filename to it.length() } }.toMap()
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val name = pending
        pending = null
        if (uri == null || name == null) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = withContext(Dispatchers.IO) { importBios(context, uri, File(systemDir, name)) }
            revision++
            nav.message(msg)
        }
    }

    fun tap(filename: String) {
        if (sizes.containsKey(filename)) {
            manage = filename
        } else {
            pending = filename
            importer.launch(arrayOf("*/*"))
        }
    }

    SettingsPage {
        listOf(false to "Nintendo DS BIOS", true to "Nintendo DSi BIOS").forEach { (dsi, header) ->
            FormSection(header = header) {
                SettingsData.biosFiles.filter { it.isDSi == dsi }.forEachIndexed { i, file ->
                    if (i > 0) FormDivider()
                    FormRow(
                        title = file.filename,
                        subtitle = SettingsData.biosStatus(file, sizes[file.filename]),
                        value = if (sizes.containsKey(file.filename)) null else "Import…",
                        onClick = { tap(file.filename) },
                    )
                }
            }
        }
        FormSection(
            header = "BIOS Mode",
            footer = "The built-in BIOS works for most games. Import bios7.bin, bios9.bin and firmware.bin and choose Imported Files to boot with your own BIOS.",
        ) {
            val modes = SettingsData.ndsBiosModes
            FormSegmentedRow(
                title = null,
                options = modes.map { it.second },
                selectedIndex = modes.indexOfFirst { it.first == prefs.ndsBiosMode }.coerceAtLeast(0),
                onSelect = { i -> nav.update { it.copy(ndsBiosMode = modes[i].first) } },
            )
        }
    }

    manage?.let { filename ->
        AlertDialog(
            onDismissRequest = { manage = null },
            containerColor = DeltaColors.GroupedCard,
            title = { Text(filename, color = Color.White) },
            confirmButton = {
                TextButton(onClick = {
                    manage = null
                    pending = filename
                    importer.launch(arrayOf("*/*"))
                }) { Text("Replace…", color = DeltaColors.Purple) }
            },
            dismissButton = {
                TextButton(onClick = {
                    manage = null
                    scope.launch {
                        withContext(Dispatchers.IO) { File(systemDir, filename).delete() }
                        revision++
                    }
                }) { Text("Remove", color = DestructiveRed) }
            },
        )
    }
}

private fun importBios(context: Context, uri: Uri, target: File): String = try {
    val tmp = File(target.parentFile, target.name + ".tmp")
    val copied = context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: 0L
    if (copied <= 0L) {
        tmp.delete()
        "The selected file is empty."
    } else {
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
        "Imported ${target.name}."
    }
} catch (e: Exception) {
    e.message ?: e.toString()
}

// ------------------------------------------------------------------------- Online Multiplayer

@Composable
internal fun OnlineMultiplayerSettings(prefs: AppPreferences, nav: SettingsNav) {
    SettingsPage {
        FormSection(
            header = "Nintendo DS",
            footer = "Choose the 3rd-party Nintendo WFC server Delta should use for online play in Nintendo DS games.",
        ) {
            PickerRow(
                title = "WFC Server",
                options = SettingsData.wfcServers.map { it.dns to it.title },
                selected = prefs.preferredWFCServer,
                onSelect = { v -> nav.update { it.copy(preferredWFCServer = v) } },
            )
        }
        SettingsData.wfcServers.firstOrNull { it.dns == prefs.preferredWFCServer }?.let { server ->
            FormSection {
                FormRow(title = server.name, subtitle = server.summary, value = server.dns)
            }
        }
    }
}
