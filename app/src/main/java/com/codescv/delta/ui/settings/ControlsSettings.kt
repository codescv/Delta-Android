package com.codescv.delta.ui.settings

import android.content.Context
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.codescv.delta.DeltaApplication
import com.codescv.delta.input.GamepadMapping
import com.codescv.delta.input.Gamepads
import com.codescv.delta.model.VirtualButton
import com.codescv.delta.settings.AppPreferences
import com.codescv.delta.skins.SkinCatalog
import com.codescv.delta.skins.SkinOption
import com.codescv.delta.skins.SkinPreview
import com.codescv.delta.skins.SkinSource
import com.codescv.delta.ui.components.FormDivider
import com.codescv.delta.ui.components.FormRow
import com.codescv.delta.ui.components.FormSection
import com.codescv.delta.ui.components.FormSegmentedRow
import com.codescv.delta.ui.components.FormSliderRow
import com.codescv.delta.ui.components.FormToggleRow
import com.codescv.delta.ui.theme.DeltaColors
import com.codescv.delta.ui.theme.DeltaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------------------- Controllers

/** Connected gamepads, refreshed live on connect / disconnect. */
@Composable
private fun rememberConnectedControllers(assignments: Map<String, Int>): List<Gamepads.ControllerInfo> {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { revision++ }
            override fun onInputDeviceRemoved(deviceId: Int) { revision++ }
            override fun onInputDeviceChanged(deviceId: Int) { revision++ }
        }
        im.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        onDispose { im.unregisterInputDeviceListener(listener) }
    }
    return remember(revision, assignments) { runCatching { Gamepads.connected(context, assignments) }.getOrDefault(emptyList()) }
}

@Composable
internal fun ControllersSettings(prefs: AppPreferences, nav: SettingsNav) {
    val controllers = rememberConnectedControllers(prefs.playerAssignments)
    SettingsPage {
        FormSection(
            header = "Connected Controllers",
            footer = "Pair MFi, Xbox, PlayStation or Switch controllers in the Settings app.",
        ) {
            if (controllers.isEmpty()) {
                FormRow(title = "No Controllers Connected", titleColor = DeltaColors.SystemGray)
            }
            controllers.forEachIndexed { i, c ->
                if (i > 0) FormDivider(inset = true)
                ControllerRow(c) { player ->
                    nav.update { p -> p.copy(playerAssignments = p.playerAssignments + (c.descriptor to player)) }
                }
            }
        }
        FormSection(header = "Players", footer = "Choose a player to remap its controller buttons.") {
            for (player in 1..4) {
                if (player > 1) FormDivider()
                val names = controllers.filter { it.player == player }.joinToString { it.name }
                FormRow(
                    title = "Player $player",
                    value = names.ifEmpty { "None" },
                    chevron = true,
                    onClick = { nav.push(SettingsRoute.PlayerMapping(player)) },
                )
            }
        }
        FormSection(footer = "Keep the controller skin visible while an external controller is connected.") {
            FormToggleRow(
                title = "Show Touch Controls with Controller",
                checked = prefs.alwaysShowTouchControls,
                onCheckedChange = { v -> nav.update { it.copy(alwaysShowTouchControls = v) } },
            )
        }
        FormSection(header = "Fast Forward") {
            PickerRow(
                title = "Fast-Forward Button",
                options = SettingsData.fastForwardModes,
                selected = prefs.fastForwardMode,
                onSelect = { v -> nav.update { it.copy(fastForwardMode = v) } },
            )
            FormDivider()
            val speeds = SettingsData.fastForwardMultipliers
            FormSegmentedRow(
                title = "Fast-Forward Speed",
                options = speeds.map { "$it×" },
                selectedIndex = speeds.indexOf(prefs.fastForwardMultiplier).coerceAtLeast(0),
                onSelect = { i -> nav.update { it.copy(fastForwardMultiplier = speeds[i]) } },
            )
        }
    }
}

@Composable
private fun ControllerRow(controller: Gamepads.ControllerInfo, onAssign: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable { open = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = DeltaColors.Purple, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Text(controller.name, style = DeltaType.Body, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Player ${controller.player}", style = DeltaType.Body, color = DeltaColors.SystemGray)
            Text(" ⌃⌄", color = DeltaColors.SystemGray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(DeltaColors.GroupedCard)) {
            for (p in 1..4) {
                DropdownMenuItem(
                    text = { Text("Player $p", color = Color.White) },
                    leadingIcon = {
                        Box(Modifier.width(18.dp)) { if (p == controller.player) Text("✓", color = DeltaColors.Purple) }
                    },
                    onClick = {
                        open = false
                        onAssign(p)
                    },
                )
            }
        }
    }
}

@Composable
internal fun PlayerMappingSettings(player: Int, prefs: AppPreferences, nav: SettingsNav) {
    val controllers = rememberConnectedControllers(prefs.playerAssignments).filter { it.player == player }
    val mapping = GamepadMapping.forPlayer(player, prefs.gamepadMappings)
    var capturing by remember { mutableStateOf<VirtualButton?>(null) }

    SettingsPage {
        FormSection(header = "Controller") {
            if (controllers.isEmpty()) {
                FormRow(title = "No Controller Assigned", titleColor = DeltaColors.SystemGray)
            }
            controllers.forEachIndexed { i, c ->
                if (i > 0) FormDivider()
                FormRow(title = c.name)
            }
        }
        FormSection(
            header = "Key Mapping",
            footer = "Tap a button, then press the controller button you want to use for it.",
        ) {
            mapping.entries.forEachIndexed { i, (button, key) ->
                if (i > 0) FormDivider()
                FormRow(
                    title = SettingsData.buttonLabel(button),
                    value = GamepadMapping.keyLabel(key),
                    chevron = true,
                    onClick = { capturing = button },
                )
            }
        }
        FormSection {
            ActionRow("Reset to Default Mapping", Icons.Outlined.Replay, DestructiveRed) {
                nav.update { p -> p.copy(gamepadMappings = SettingsData.withPlayerOverrides(p.gamepadMappings, player, emptyMap())) }
            }
        }
    }

    capturing?.let { button ->
        KeyCaptureDialog(
            title = "Press a button…",
            message = "Player $player · ${SettingsData.buttonLabel(button)}",
            onCancel = { capturing = null },
            onKey = { keyCode ->
                capturing = null
                nav.update { p ->
                    val effective = SettingsData.remap(GamepadMapping.forPlayer(player, p.gamepadMappings), button, keyCode)
                    val overrides = SettingsData.overridesFor(effective, GamepadMapping.defaults)
                    p.copy(gamepadMappings = SettingsData.withPlayerOverrides(p.gamepadMappings, player, overrides))
                }
            },
        )
    }
}

/** Captures the next key press (from a gamepad or keyboard) via a KeyEvent listener. */
@Composable
private fun KeyCaptureDialog(title: String, message: String, onCancel: () -> Unit, onKey: (Int) -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(DeltaColors.GroupedCard)
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    val native = e.nativeKeyEvent
                    val fromGamepad = (native.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                        (native.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                    if (e.type == KeyEventType.KeyDown && native.repeatCount == 0) {
                        if (native.keyCode == KeyEvent.KEYCODE_BACK && !fromGamepad) onCancel() else onKey(native.keyCode)
                    }
                    true
                }
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = DeltaColors.Purple, modifier = Modifier.size(40.dp))
            Text(title, style = DeltaType.Headline, color = Color.White)
            Text(message, style = DeltaType.Footnote, color = DeltaColors.SystemGray, textAlign = TextAlign.Center)
            TextButton(onClick = onCancel) { Text("Cancel", color = DeltaColors.Purple, style = DeltaType.Body) }
        }
    }
}

// ------------------------------------------------------------------------------------ Skins

private fun List<SkinOption>.resolve(id: String?): SkinOption? = firstOrNull { it.id == id } ?: firstOrNull()

@Composable
private fun rememberSkinOptions(families: List<String>, revision: Int = 0): Map<String, List<SkinOption>> {
    val state by produceState(emptyMap<String, List<SkinOption>>(), families, revision) {
        value = withContext(Dispatchers.IO) {
            families.associateWith { f -> runCatching { SkinCatalog.options(f) }.getOrDefault(emptyList()) }
        }
    }
    return state
}

@Composable
internal fun SkinsSettings(prefs: AppPreferences, nav: SettingsNav) {
    val families = SkinCatalog.configurableFamilies
    val options = rememberSkinOptions(families)
    SettingsPage {
        FormSection(footer = "Applies to controller skins shown over the game screen.") {
            FormRow(title = "Controller Skin Opacity", value = SettingsData.opacityLabel(prefs.controllerOpacity))
            FormSliderRow(
                value = prefs.controllerOpacity.toFloat(),
                onValueChange = { v -> nav.update { it.copy(controllerOpacity = SettingsData.snapOpacity(v)) } },
                valueRange = SettingsData.OPACITY_MIN..SettingsData.OPACITY_MAX,
                steps = SettingsData.OPACITY_STEPS,
            )
        }
        FormSection(header = "Controller Skins", footer = "Each system has its own portrait and landscape controller skin.") {
            families.forEachIndexed { i, family ->
                if (i > 0) FormDivider(inset = true)
                val list = options[family].orEmpty()
                SystemControllerSkinRow(
                    family = family,
                    portrait = list.resolve(prefs.portraitSkinsBySystem[family]),
                    landscape = list.resolve(prefs.landscapeSkinsBySystem[family]),
                ) { nav.push(SettingsRoute.SkinDetail(family, SkinCatalog.familyDisplayName(family))) }
            }
        }
    }
}

@Composable
private fun SystemControllerSkinRow(family: String, portrait: SkinOption?, landscape: SkinOption?, onClick: () -> Unit) {
    val palette = SettingsData.familyPalette(family)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.verticalGradient(listOf(Color(palette[0]), Color(palette[1])))),
            contentAlignment = Alignment.Center,
        ) {
            Text(SkinCatalog.familyShortName(family), fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color(palette[2]), maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(SkinCatalog.familyDisplayName(family), style = DeltaType.Body, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text("Portrait: ${portrait?.name ?: "Standard"}", style = DeltaType.Caption, color = DeltaColors.SystemGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Landscape: ${landscape?.name ?: "Standard"}", style = DeltaType.Caption, color = DeltaColors.SystemGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(4.dp))
        Text(
            portrait?.badge?.ifBlank { null } ?: SettingsData.familyBadge(family),
            fontSize = 8.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color(palette[4]),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 110.dp)
                .clip(CircleShape)
                .background(Color(palette[3]))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        Text("›", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = DeltaColors.SystemGray, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
internal fun SkinDetailSettings(family: String, prefs: AppPreferences, nav: SettingsNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    val options = rememberSkinOptions(listOf(family), revision)[family].orEmpty()
    var importMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importMessage = withContext(Dispatchers.IO) { importSkin(context, uri, family) }
            revision++
        }
    }

    val portrait = options.resolve(prefs.portraitSkinsBySystem[family])
    val landscape = options.resolve(prefs.landscapeSkinsBySystem[family])
    val imported = options.filter { it.kind == SkinOption.Kind.IMPORTED }

    SettingsPage {
        FormSection(header = "Portrait Skin", footer = "Badge: DELTA · ${portrait?.badge?.ifBlank { null } ?: SettingsData.familyBadge(family)}") {
            SkinGrid(options, portrait?.id) { id ->
                nav.update { it.copy(portraitSkinsBySystem = it.portraitSkinsBySystem + (family to id)) }
            }
        }
        FormSection(header = "Landscape Skin", footer = "Badge: DELTA · ${landscape?.badge?.ifBlank { null } ?: SettingsData.familyBadge(family)}") {
            SkinGrid(options, landscape?.id) { id ->
                nav.update { it.copy(landscapeSkinsBySystem = it.landscapeSkinsBySystem + (family to id)) }
            }
        }
        FormSection {
            ActionRow("Import .deltaskin…", Icons.Outlined.FileDownload) {
                importer.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
            }
            importMessage?.let {
                FormDivider()
                CaptionRow(it)
            }
            FormDivider()
            ActionRow("Reset to Default Skin", Icons.Outlined.Replay, DestructiveRed) {
                nav.update { p ->
                    p.copy(
                        portraitSkinsBySystem = p.portraitSkinsBySystem - family,
                        landscapeSkinsBySystem = p.landscapeSkinsBySystem - family,
                    )
                }
            }
        }
        if (imported.isNotEmpty()) {
            FormSection(header = "Imported Skins") {
                imported.forEachIndexed { i, option ->
                    if (i > 0) FormDivider()
                    FormRow(
                        title = option.name,
                        trailing = {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = "Delete ${option.name}",
                                tint = DestructiveRed,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { deleteSkin(family, option.id) }
                                            nav.update { p ->
                                                p.copy(
                                                    portraitSkinsBySystem = p.portraitSkinsBySystem.filterValues { it != option.id },
                                                    landscapeSkinsBySystem = p.landscapeSkinsBySystem.filterValues { it != option.id },
                                                )
                                            }
                                            revision++
                                        }
                                    }
                                    .padding(6.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

/** `LazyVGrid(adaptive min 84, spacing 12)` of skin previews, laid out without nested lazy lists. */
@Composable
private fun SkinGrid(options: List<SkinOption>, selectedId: String?, onSelect: (String) -> Unit) {
    if (options.isEmpty()) {
        CaptionRow("Loading skins…")
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        val spacing = 12.dp
        val columns = maxOf(1, ((maxWidth + spacing) / (84.dp + spacing)).toInt())
        val cell = (maxWidth - spacing * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            options.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    row.forEach { option ->
                        Box(
                            Modifier
                                .width(cell)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelect(option.id) },
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            SkinPreview(option, selected = option.id == selectedId, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

private fun displayNameOf(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "skin.deltaskin"

/** Imports a `.deltaskin` and returns the Delta result message. */
private fun importSkin(context: Context, uri: Uri, family: String): String {
    val fileName = displayNameOf(context, uri)
    return try {
        val repo = DeltaApplication.container.skins
        val loaded = context.contentResolver.openInputStream(uri)?.use { repo.importDeltaSkin(it, fileName, family) }
            ?: return "Unable to locate the Delta documents folder."
        val target = (loaded.source as? SkinSource.Imported)?.directory?.parentFile?.name ?: family
        "Imported $fileName into Skins/$target for ${SkinCatalog.familyDisplayName(target)}."
    } catch (e: Exception) {
        e.message ?: e.toString()
    }
}

private fun deleteSkin(family: String, optionId: String) {
    val repo = DeltaApplication.container.skins
    repo.imported(family).firstOrNull { it.source.id == optionId }?.let(repo::delete)
}

// ---------------------------------------------------------------------------- Touch & Haptics

@Composable
internal fun TouchHapticsSettings(prefs: AppPreferences, nav: SettingsNav) {
    SettingsPage {
        FormSection(header = "Haptics", footer = "When enabled, your device will vibrate in response to touch screen controls.") {
            FormToggleRow("Button Feedback", prefs.haptics, { v -> nav.update { it.copy(haptics = v) } })
            FormDivider()
            FormToggleRow("Control Stick Feedback", prefs.thumbstickHaptics, { v -> nav.update { it.copy(thumbstickHaptics = v) } })
        }
        FormSection(header = "Haptic Strength") {
            FormSegmentedRow(
                title = null,
                options = SettingsData.hapticStrengths.map { it.second },
                selectedIndex = SettingsData.hapticStrengthIndex(prefs.hapticStrength),
                onSelect = { i -> nav.update { it.copy(hapticStrength = SettingsData.hapticStrengths[i].first) } },
            )
        }
        FormSection(footer = "Play a short click sound when you press a touch screen button.") {
            FormToggleRow("Button Click Sound", prefs.buttonClickSound, { v -> nav.update { it.copy(buttonClickSound = v) } })
        }
    }
}
