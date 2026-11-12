package com.codescv.delta.ui.saves

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.codescv.delta.DeltaApplication
import com.codescv.delta.emulation.EmulatorSession
import com.codescv.delta.library.RomEntry
import com.codescv.delta.saves.SaveStateInfo
import com.codescv.delta.saves.SaveStateType
import com.codescv.delta.ui.components.DeltaSheet
import com.codescv.delta.ui.components.FormDivider
import com.codescv.delta.ui.components.FormRow
import com.codescv.delta.ui.components.FormSection
import com.codescv.delta.ui.theme.DeltaColors
import com.codescv.delta.ui.theme.DeltaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How the save-state list is used. */
enum class SaveStatesMode { SAVE, LOAD, MANAGE }

private val DATE_FORMAT = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.US)

fun SaveStateInfo.displayTitle(): String = when (type) {
    SaveStateType.AUTO -> "Auto Save"
    SaveStateType.QUICK -> "Quick Save"
    else -> name ?: "Save State"
}

/**
 * Save States manager (design §5.4): Auto Save (2 rotating, never a save target but loadable /
 * previewable / exportable), Quick Save, General (+ New Save State), Locked. Row menu: Load ·
 * Rename… · Preview · Lock/Unlock · Export (.svs)… · Import into Slot… · Delete.
 * Pause menu uses SAVE / LOAD with the running [session]; Library uses MANAGE ([onLaunch]).
 */
@Composable
fun SaveStatesSheet(
    rom: RomEntry,
    mode: SaveStatesMode,
    session: EmulatorSession?,
    onDismiss: () -> Unit,
    onLaunch: (stateId: String) -> Unit = {},
    onSaved: (name: String) -> Unit = {},
    onLoaded: () -> Unit = {},
) {
    val saves = DeltaApplication.container.saves
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    val sections by produceState(emptyMap<SaveStateType, List<SaveStateInfo>>(), revision) {
        value = withContext(Dispatchers.IO) { saves.sections(rom) }
    }
    var alert by remember { mutableStateOf<Pair<String, String>?>(null) }
    var renaming by remember { mutableStateOf<SaveStateInfo?>(null) }
    var preview by remember { mutableStateOf<SaveStateInfo?>(null) }
    var exporting by remember { mutableStateOf<SaveStateInfo?>(null) }
    var importTarget by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val info = exporting ?: return@rememberLauncherForActivityResult
        exporting = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    val data = saves.exportState(rom, info.id)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(data) } ?: error("cannot write")
                }.exceptionOrNull()
            }
            if (err != null) alert = "Delta" to (err.message ?: "Export failed")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = importTarget
        importTarget = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot read")
                    saves.importState(data, rom, rom.system.coreId, targetId = target?.takeIf { it.isNotEmpty() })
                }.exceptionOrNull()
            }
            if (err != null) alert = "Delta" to (err.message ?: "Import failed")
            revision++
        }
    }

    fun load(info: SaveStateInfo) {
        if (mode == SaveStatesMode.MANAGE || session == null) {
            onLaunch(info.id)
            onDismiss()
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { session.loadState(info.id, quiet = true) }
            if (ok) onLoaded() else alert = "Unable to Load State" to "Save states are tied to the core that created them."
        }
    }

    fun save(type: SaveStateType, id: String? = null) {
        val s = session ?: return
        scope.launch {
            val info = withContext(Dispatchers.IO) { s.saveState(type, id = id, quiet = true) }
            if (info != null) onSaved(info.displayTitle()) else alert = "Unable to Save State" to "The core could not create a save state."
            revision++
        }
    }

    val title = when (mode) {
        SaveStatesMode.SAVE -> "Save State"
        SaveStatesMode.LOAD -> "Load State"
        SaveStatesMode.MANAGE -> rom.title
    }
    DeltaSheet(
        title = title,
        onDismiss = onDismiss,
        leadingLabel = if (mode == SaveStatesMode.MANAGE) null else "Cancel",
        onLeading = if (mode == SaveStatesMode.MANAGE) null else onDismiss,
        trailingLabel = if (mode == SaveStatesMode.MANAGE) "Done" else null,
        onTrailing = if (mode == SaveStatesMode.MANAGE) onDismiss else null,
    ) {
        val autos = sections[SaveStateType.AUTO].orEmpty()
        val quick = sections[SaveStateType.QUICK].orEmpty()
        val general = sections[SaveStateType.GENERAL].orEmpty()
        val locked = sections[SaveStateType.LOCKED].orEmpty()

        @Composable
        fun rows(list: List<SaveStateInfo>, canSaveOver: Boolean) {
            list.forEachIndexed { i, info ->
                if (i > 0) FormDivider(inset = true)
                SaveStateRow(
                    rom = rom,
                    info = info,
                    mode = mode,
                    revision = revision,
                    enabled = mode != SaveStatesMode.SAVE || canSaveOver,
                    onTap = {
                        if (mode == SaveStatesMode.SAVE) save(info.type, info.id) else load(info)
                    },
                    onLoad = { load(info) },
                    onRename = { renaming = info },
                    onPreview = { preview = info },
                    onToggleLock = {
                        scope.launch {
                            withContext(Dispatchers.IO) { saves.setLocked(rom, info.id, !info.isLocked) }
                            revision++
                        }
                    },
                    onExport = {
                        exporting = info
                        exportLauncher.launch(saves.suggestedStateFileName(rom, info))
                    },
                    onImportInto = if (info.isAuto || info.isLocked) null else ({
                        importTarget = info.id
                        importLauncher.launch(arrayOf("*/*"))
                    }),
                    onDelete = if (info.isLocked) null else ({
                        scope.launch {
                            withContext(Dispatchers.IO) { saves.deleteState(rom, info.id, force = false) }
                            revision++
                        }
                    }),
                )
            }
        }

        if (mode != SaveStatesMode.SAVE) {
            FormSection(header = "Auto Save") {
                if (autos.isEmpty()) FormRow(title = "Empty", titleColor = DeltaColors.SystemGray) else rows(autos, false)
            }
        }
        FormSection(header = "Quick Save") {
            if (quick.isEmpty()) {
                if (mode == SaveStatesMode.SAVE) {
                    FormRow(title = "Quick Save", subtitle = "Empty", onClick = { save(SaveStateType.QUICK) }, trailing = {
                        Icon(Icons.Filled.SaveAlt, null, tint = DeltaColors.Purple)
                    })
                } else {
                    FormRow(title = "Empty", titleColor = DeltaColors.SystemGray)
                }
            } else {
                rows(quick, true)
            }
        }
        FormSection(header = "General", footer = if (mode == SaveStatesMode.SAVE) "Saving overwrites the selected slot." else null) {
            if (mode == SaveStatesMode.SAVE) {
                FormRow(title = "New Save State", titleColor = DeltaColors.Purple, onClick = { save(SaveStateType.GENERAL) }, trailing = {
                    Icon(Icons.Filled.Add, null, tint = DeltaColors.Purple)
                })
                if (general.isNotEmpty()) FormDivider()
            } else if (general.isEmpty()) {
                FormRow(title = "Empty", titleColor = DeltaColors.SystemGray)
            }
            rows(general, true)
        }
        FormSection(
            header = "Locked",
            footer = if (mode != SaveStatesMode.SAVE) "Save states are tied to the core that created them." else null,
        ) {
            if (locked.isEmpty()) FormRow(title = "Empty", titleColor = DeltaColors.SystemGray) else rows(locked, false)
        }
        if (mode == SaveStatesMode.MANAGE) {
            FormSection {
                FormRow(title = "Import Save State…", titleColor = DeltaColors.Purple, onClick = {
                    importTarget = ""
                    importLauncher.launch(arrayOf("*/*"))
                })
            }
        }
    }

    alert?.let { (t, m) ->
        AlertDialog(
            onDismissRequest = { alert = null },
            title = { Text(t) },
            text = { Text(m) },
            confirmButton = { TextButton(onClick = { alert = null }) { Text("OK") } },
        )
    }
    renaming?.let { info ->
        var text by remember(info.id) { mutableStateOf(info.name ?: info.displayTitle()) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename Save State") },
            text = { TextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val id = info.id
                    renaming = null
                    scope.launch {
                        withContext(Dispatchers.IO) { saves.rename(rom, id, text) }
                        revision++
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    preview?.let { info ->
        Dialog(onDismissRequest = { preview = null }) {
            val img = rememberThumbnail(saves.screenshotFile(rom, info.id), revision)
            Column(
                Modifier.clip(RoundedCornerShape(16.dp)).background(DeltaColors.GroupedCard).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (img != null) {
                    Image(img, null, filterQuality = FilterQuality.None, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().aspectRatio(img.width.toFloat() / img.height))
                }
                Spacer(Modifier.size(8.dp))
                Text(info.displayTitle(), style = DeltaType.Headline, color = Color.White)
                Text(DATE_FORMAT.format(Date(info.modifiedAt)), style = DeltaType.Caption, color = DeltaColors.SystemGray)
            }
        }
    }
    LaunchedEffect(Unit) { revision++ }
}

@Composable
private fun rememberThumbnail(file: File, revision: Int): ImageBitmap? {
    val img by produceState<ImageBitmap?>(null, file.path, revision, file.lastModified()) {
        value = withContext(Dispatchers.IO) {
            if (file.isFile) runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() else null
        }
    }
    return img
}

@Composable
private fun SaveStateRow(
    rom: RomEntry,
    info: SaveStateInfo,
    mode: SaveStatesMode,
    revision: Int,
    enabled: Boolean,
    onTap: () -> Unit,
    onLoad: () -> Unit,
    onRename: () -> Unit,
    onPreview: () -> Unit,
    onToggleLock: () -> Unit,
    onExport: () -> Unit,
    onImportInto: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val saves = DeltaApplication.container.saves
    var menu by remember { mutableStateOf(false) }
    val thumb = rememberThumbnail(saves.screenshotFile(rom, info.id), revision)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clickable(enabled = enabled, onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(96.dp, 64.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x26787880)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(thumb, null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.None, modifier = Modifier.size(96.dp, 64.dp))
            } else {
                Icon(Icons.Filled.Photo, null, tint = DeltaColors.SystemGray)
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(info.displayTitle(), style = DeltaType.Headline, color = if (enabled) Color.White else DeltaColors.SystemGray)
                if (info.isLocked) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Lock, null, tint = DeltaColors.SystemGray, modifier = Modifier.size(14.dp))
                }
            }
            Text(DATE_FORMAT.format(Date(info.modifiedAt)), style = DeltaType.Caption, color = DeltaColors.SystemGray)
        }
        if (mode == SaveStatesMode.SAVE && enabled) {
            Icon(Icons.Filled.SaveAlt, null, tint = DeltaColors.Purple)
        } else if (mode == SaveStatesMode.LOAD) {
            Icon(Icons.Filled.PlayArrow, null, tint = DeltaColors.Purple)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreHoriz, "Options", tint = DeltaColors.Purple) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Load") }, onClick = { menu = false; onLoad() })
                DropdownMenuItem(text = { Text("Rename…") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Preview") }, onClick = { menu = false; onPreview() })
                if (!info.isAuto && info.kind == SaveStateInfo.KIND_GENERAL) {
                    DropdownMenuItem(text = { Text(if (info.isLocked) "Unlock" else "Lock") }, onClick = { menu = false; onToggleLock() })
                }
                DropdownMenuItem(text = { Text("Export (.svs)…") }, onClick = { menu = false; onExport() })
                if (onImportInto != null) {
                    DropdownMenuItem(text = { Text("Import into Slot…") }, onClick = { menu = false; onImportInto() })
                }
                if (onDelete != null) {
                    DropdownMenuItem(text = { Text("Delete", color = Color(0xFFFF453A)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
