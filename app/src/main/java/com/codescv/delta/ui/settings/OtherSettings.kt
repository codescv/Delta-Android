package com.codescv.delta.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.codescv.delta.DeltaApplication
import com.codescv.delta.model.GameSystem
import com.codescv.delta.settings.AppPreferences
import com.codescv.delta.ui.components.FormDivider
import com.codescv.delta.ui.components.FormRow
import com.codescv.delta.ui.components.FormSection
import com.codescv.delta.ui.components.FormToggleRow
import com.codescv.delta.ui.theme.DeltaColors
import com.codescv.delta.ui.theme.DeltaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ---------------------------------------------------------------------------------- shared

@Composable
internal fun DeltaSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = DeltaColors.Purple,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = Color(0xFF39393D),
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** `LabeledContent` whose value may be long (wraps, right-aligned). */
@Composable
private fun LabeledRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = DeltaType.Body, color = Color.White, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = DeltaType.Body,
            color = if (onClick != null) DeltaColors.Purple else DeltaColors.SystemGray,
            textAlign = TextAlign.End,
            maxLines = 2,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

// --------------------------------------------------------------------------------- App Icon

@Composable
internal fun AppIconSettings(prefs: AppPreferences, nav: SettingsNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selected = AppIcons.find(prefs.selectedAppIconName).imageName
    SettingsPage {
        AppIcons.bySection().forEach { (section, icons) ->
            FormSection(header = section.title) {
                icons.forEachIndexed { i, icon ->
                    if (i > 0) {
                        Box(Modifier.fillMaxWidth().padding(start = 72.dp).height(0.5.dp).background(DeltaColors.Separator))
                    }
                    AppIconRow(icon, icon.imageName == selected) {
                        if (icon.imageName == selected) return@AppIconRow
                        nav.update { it.copy(selectedAppIconName = icon.imageName) }
                        scope.launch {
                            withContext(Dispatchers.IO) { AppIcons.apply(context.applicationContext, icon.imageName) }
                            nav.message(AppIcons.UPDATE_MESSAGE)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIconRow(icon: DeltaAppIcon, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF000000 or icon.accentHex)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Δ", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(icon.name, style = DeltaType.Body, fontWeight = FontWeight.Medium, color = Color.White)
            icon.credit?.let { Text(it, style = DeltaType.Caption, color = DeltaColors.SystemGray) }
        }
        if (selected) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = DeltaColors.Purple, modifier = Modifier.size(24.dp))
        }
    }
}

// ------------------------------------------------------------------------------- Services

@Composable
internal fun DeltaSyncSettings() {
    SettingsPage {
        FormSection(
            footer = "Delta Sync is not available in this build. Games, saves and save states are stored on this device in Files › Delta.",
        ) {
            FormRow(title = "Service", value = "None")
        }
    }
}

@Composable
internal fun RetroAchievementsSettings(prefs: AppPreferences, nav: SettingsNav) {
    SettingsPage {
        FormSection(header = "Account") {
            Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                if (prefs.retroAchievementsUsername.isEmpty()) {
                    Text("RetroAchievements Username", style = DeltaType.Body, color = Color(0xFF5A5A5F))
                }
                BasicTextField(
                    value = prefs.retroAchievementsUsername,
                    onValueChange = { v -> nav.update { it.copy(retroAchievementsUsername = v.trim()) } },
                    singleLine = true,
                    textStyle = DeltaType.Body.copy(color = Color.White),
                    cursorBrush = SolidColor(DeltaColors.Purple),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        FormSection(footer = "Hardcore mode disables save states, cheats and fast forward while earning achievements.") {
            FormToggleRow("Hardcore Mode", prefs.retroAchievementsHardcore, { v -> nav.update { it.copy(retroAchievementsHardcore = v) } })
        }
    }
}

// ---------------------------------------------------------------------------- Miscellaneous

@Composable
internal fun LilSettings(prefs: AppPreferences, nav: SettingsNav) {
    SettingsPage {
        FormSection(footer = "Automatically resume from the auto save when launching a game.") {
            FormToggleRow("Resume Where I Left Off", prefs.autoResume, { v -> nav.update { it.copy(autoResume = v) } })
        }
    }
}

@Composable
internal fun AdvancedSettings(nav: SettingsNav) {
    val context = LocalContext.current
    val library = DeltaApplication.container.library
    val state by library.state.collectAsState()
    SettingsPage {
        FormSection(header = "Storage") {
            FormRow(title = "Game Library", chevron = true, onClick = { nav.push(SettingsRoute.GameLibrary) })
            FormDivider()
            FormRow(title = "ROMs Directory", subtitle = library.romsDirectory.absolutePath)
            FormDivider()
            FormRow(title = "Total Games", value = state.entries.size.toString())
        }
        FormSection(header = "About") {
            FormRow(title = "Delta Version", value = SettingsData.VERSION_STRING)
            FormDivider()
            LabeledRow("GitHub Fork", SettingsData.GITHUB_FORK) {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://" + SettingsData.GITHUB_FORK)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------- Game Library

@Composable
internal fun GameLibrarySettings(prefs: AppPreferences, nav: SettingsNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = DeltaApplication.container.library
    val state by library.state.collectAsState()
    var busy by remember { mutableStateOf<String?>(null) }

    fun run(label: String, work: suspend () -> String) {
        if (busy != null) return
        busy = label
        scope.launch {
            val msg = runCatching { withContext(Dispatchers.IO) { work() } }.getOrElse { it.message ?: it.toString() }
            busy = null
            nav.message(msg)
        }
    }

    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) run("Importing…") { importUris(context, uris) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) run("Importing…") { importFolder(context, uri) }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) run("Exporting…") { exportSaves(context, uri) }
    }

    SettingsPage {
        FormSection(header = "Box Art", footer = "Automatically download cover artwork for newly imported games.") {
            FormToggleRow("Auto-Fetch Box Art", prefs.autoFetchBoxArt, { v -> nav.update { it.copy(autoFetchBoxArt = v) } })
            FormDivider()
            ActionRow("Re-scan Artwork", Icons.Outlined.Image, enabled = busy == null) {
                run("Fetching artwork…") { rescanArtwork() }
            }
        }
        FormSection(header = "Import") {
            ActionRow("Import Files…", Icons.Outlined.NoteAdd, enabled = busy == null) { filesPicker.launch(arrayOf("*/*")) }
            FormDivider()
            ActionRow("Import Folder…", Icons.Outlined.CreateNewFolder, enabled = busy == null) { folderPicker.launch(null) }
        }
        FormSection(header = "Export", footer = "Exports every game save (.srm) and save state into a single .zip file.") {
            ActionRow("Export All Saves (.zip)", Icons.Outlined.Archive, enabled = busy == null) { exporter.launch("Delta Saves.zip") }
        }
        busy?.let { FormSection { CaptionRow(it) } }
        FormSection(header = "Storage") {
            FormRow(title = "ROMs Directory", subtitle = library.romsDirectory.absolutePath)
            FormDivider()
            FormRow(title = "Total Games", value = state.entries.size.toString())
        }
    }
}

private val importableExtensions: Set<String> by lazy {
    (GameSystem.entries.flatMap { it.fileExtensions } + "zip").toSet()
}

private fun nameOf(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "game"

private fun importOne(context: Context, uri: Uri, name: String): Int {
    val library = DeltaApplication.container.library
    return context.contentResolver.openInputStream(uri)?.use { library.import(it, name).size } ?: 0
}

private fun importSummary(imported: Int, failures: List<String>): String = buildString {
    append(if (imported == 1) "Imported 1 game." else "Imported $imported games.")
    if (failures.isNotEmpty()) append(" Could not import: ").append(failures.take(3).joinToString())
    if (failures.size > 3) append(" …")
}

private fun importUris(context: Context, uris: List<Uri>): String {
    var count = 0
    val failures = mutableListOf<String>()
    for (uri in uris) {
        val name = nameOf(context, uri)
        runCatching { count += importOne(context, uri, name) }.onFailure { failures += name }
    }
    return importSummary(count, failures)
}

private fun importFolder(context: Context, tree: Uri): String {
    val root = DocumentFile.fromTreeUri(context, tree) ?: return "Unable to open the selected folder."
    var count = 0
    val failures = mutableListOf<String>()
    fun walk(dir: DocumentFile, depth: Int) {
        for (f in dir.listFiles()) {
            if (f.isDirectory) {
                if (depth < 4) walk(f, depth + 1)
                continue
            }
            val name = f.name ?: continue
            if (name.substringAfterLast('.', "").lowercase() !in importableExtensions) continue
            runCatching { count += importOne(context, f.uri, name) }.onFailure { failures += name }
        }
    }
    walk(root, 0)
    return importSummary(count, failures)
}

private fun rescanArtwork(): String {
    val library = DeltaApplication.container.library
    val missing = library.value.entries.filter { library.covers.coverIfExists(it) == null }
    var fetched = 0
    for (rom in missing) {
        if (runCatching { library.covers.fetchCoverIfNeeded(rom, force = true) }.getOrNull() != null) fetched++
    }
    library.bumpCovers()
    return if (missing.isEmpty()) "All games already have artwork." else "Fetched artwork for $fetched of ${missing.size} games."
}

private fun exportSaves(context: Context, target: Uri): String {
    val root = DeltaApplication.container.root
    var files = 0
    context.contentResolver.openOutputStream(target)?.use { out ->
        ZipOutputStream(out.buffered()).use { zip ->
            for (dirName in listOf("Saves", "States")) {
                val dir = File(root, dirName)
                if (!dir.isDirectory) continue
                dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(root).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    files++
                }
            }
        }
    } ?: return "Unable to create the export file."
    return if (files == 1) "Exported 1 file." else "Exported $files files."
}

// ----------------------------------------------------------------------------- Experimental

@Composable
internal fun ExperimentalFeaturesSettings(prefs: AppPreferences, nav: SettingsNav) {
    SettingsPage {
        FormSection(header = "Experimental Features", footer = "Experimental features may be unstable or incomplete.") {
            prefs.experimentalFeatures.forEachIndexed { i, feature ->
                if (i > 0) FormDivider()
                val p = SettingsData.present(feature)
                TwoLineRow(
                    title = p.name,
                    detail = p.summary,
                    trailing = { DeltaSwitch(feature.isEnabled) { v -> nav.store.setExperimental(feature.key, v) } },
                    onClick = { nav.push(SettingsRoute.ExperimentalDetail(feature.key, p.name)) },
                )
            }
        }
    }
}

@Composable
internal fun ExperimentalFeatureDetail(featureKey: String, prefs: AppPreferences, nav: SettingsNav) {
    val feature = prefs.experimentalFeatures.firstOrNull { it.key == featureKey } ?: return
    val p = SettingsData.present(feature)
    SettingsPage {
        FormSection(footer = SettingsData.detailFooter(feature)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable { nav.store.setExperimental(feature.key, !feature.isEnabled) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(p.name, style = DeltaType.Body, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                DeltaSwitch(feature.isEnabled) { v -> nav.store.setExperimental(feature.key, v) }
            }
        }
        if (feature.isEnabled && feature.options.isNotEmpty()) {
            FormSection(header = "Options") {
                feature.options.keys.sorted().forEachIndexed { i, key ->
                    if (i > 0) FormDivider()
                    val value = feature.options[key].orEmpty()
                    val next = SettingsData.nextOptionValue(key, value)
                    FormRow(
                        title = key,
                        value = value,
                        onClick = next?.let { n -> { nav.store.setExperimentalOption(feature.key, key, n) } },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- Credits

@Composable
internal fun ContributorsSettings() {
    SettingsPage {
        FormSection {
            SettingsData.contributors.forEachIndexed { i, (name, role) ->
                if (i > 0) FormDivider()
                TwoLineRow(name, role)
            }
        }
    }
}

@Composable
internal fun SoftwareLicensesSettings() {
    SettingsPage {
        FormSection {
            SettingsData.licenses.forEachIndexed { i, (name, license) ->
                if (i > 0) FormDivider()
                LabeledRow(name, license)
            }
        }
    }
}
