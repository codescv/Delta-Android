package com.codescv.delta.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.codescv.delta.DeltaApplication
import com.codescv.delta.cheats.CheatCode
import com.codescv.delta.cheats.CheatFormat
import com.codescv.delta.cheats.CheatValidator
import com.codescv.delta.emulation.EmulatorSession
import com.codescv.delta.emulation.FastForwardController
import com.codescv.delta.emulation.FastForwardMode
import com.codescv.delta.i18n.LocalDeltaStrings
import com.codescv.delta.i18n.StrKey
import com.codescv.delta.i18n.str
import com.codescv.delta.library.RomEntry
import com.codescv.delta.model.GameSystem
import com.codescv.delta.model.VirtualButton
import com.codescv.delta.settings.VideoFilter
import com.codescv.delta.skins.SkinCatalog
import com.codescv.delta.skins.SkinPreview
import com.codescv.delta.ui.components.DeltaSheet
import com.codescv.delta.ui.components.FormCheckRow
import com.codescv.delta.ui.components.FormDivider
import com.codescv.delta.ui.components.FormRow
import com.codescv.delta.ui.components.FormSection
import com.codescv.delta.ui.components.FormSegmentedRow
import com.codescv.delta.ui.components.FormToggleRow
import com.codescv.delta.ui.theme.DeltaColors
import com.codescv.delta.ui.theme.DeltaType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Composable
private fun deltaTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    cursorColor = DeltaColors.Purple,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedPlaceholderColor = DeltaColors.SystemGray,
    unfocusedPlaceholderColor = DeltaColors.SystemGray,
)

/** Cheat Codes sheet: list with toggles + swipe-free delete, and the "New Cheat" form. */
@Composable
fun CheatCodesSheet(rom: RomEntry, session: EmulatorSession?, onDismiss: () -> Unit) {
    val prefsStore = DeltaApplication.container.preferences
    val prefs by prefsStore.state.collectAsState()
    val cheats = prefs.cheatsByROM[rom.id].orEmpty()
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val supported = CheatFormat.supported(rom.system)
    val allFormats = listOf(CheatFormat.GAME_GENIE, CheatFormat.ACTION_REPLAY, CheatFormat.GAME_SHARK, CheatFormat.CODE_BREAKER)
    // Per-system formats first, but all four stay selectable (parity with Delta).
    val formats = (supported.filter { it in allFormats } + allFormats).distinct()
    var format by remember { mutableStateOf(if (CheatFormat.GAME_SHARK in supported) CheatFormat.GAME_SHARK else formats.first()) }
    var error by remember { mutableStateOf<String?>(null) }
    var formatMenu by remember { mutableStateOf(false) }

    fun update(change: (MutableList<CheatCode>) -> Unit) {
        val list = cheats.toMutableList()
        change(list)
        prefsStore.update { it.copy(cheatsByROM = it.cheatsByROM + (rom.id to list.toList())) }
        session?.applyCheats(list)
    }

    val strings = LocalDeltaStrings.current
    DeltaSheet(title = str(StrKey.PAUSE_CHEAT_CODES), onDismiss = onDismiss) {
        FormSection(header = str(StrKey.PAUSE_CHEAT_CODES)) {
            if (cheats.isEmpty()) {
                FormRow(title = str(StrKey.CHEAT_NO_CHEATS), titleColor = DeltaColors.SystemGray)
            }
            cheats.forEachIndexed { i, cheat ->
                if (i > 0) FormDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cheat.name, style = DeltaType.Subheadline.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
                        Text("${cheat.format.localizedName(strings)} · ${cheat.code}", style = DeltaType.Caption.copy(fontFamily = FontFamily.Monospace), color = DeltaColors.SystemGray)
                    }
                    Switch(
                        checked = cheat.isEnabled,
                        onCheckedChange = { on -> update { l -> l[i] = cheat.copy(isEnabled = on) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = DeltaColors.Purple, checkedThumbColor = Color.White),
                    )
                    IconButton(onClick = { update { l -> l.removeAt(i) } }) {
                        Icon(Icons.Filled.Delete, str(StrKey.COMMON_DELETE), tint = Color(0xFFFF453A))
                    }
                }
            }
        }
        FormSection(header = str(StrKey.CHEAT_NEW_HEADER), footer = error) {
            TextField(
                value = name, onValueChange = { name = it },
                placeholder = { Text(str(StrKey.CHEAT_NAME_PLACEHOLDER)) },
                singleLine = true, colors = deltaTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            FormDivider()
            TextField(
                value = code, onValueChange = { code = it; error = null },
                placeholder = { Text(str(StrKey.CHEAT_CODE_PLACEHOLDER, CheatValidator.placeholder(rom.system, format, strings))) },
                textStyle = DeltaType.Body.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                colors = deltaTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            FormDivider()
            Box {
                FormRow(title = str(StrKey.CHEAT_FORMAT), value = format.localizedName(strings), chevron = true, onClick = { formatMenu = true })
                DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                    formats.forEach { f ->
                        DropdownMenuItem(text = { Text((if (f == format) "✓ " else "    ") + f.localizedName(strings)) }, onClick = { format = f; formatMenu = false })
                    }
                }
            }
            FormDivider()
            FormRow(
                title = str(StrKey.CHEAT_ADD),
                titleColor = if (code.isBlank()) DeltaColors.SystemGray else DeltaColors.Purple,
                enabled = code.isNotBlank(),
                onClick = {
                    when (val r = CheatValidator.validate(code, rom.system, format, strings)) {
                        is CheatValidator.Result.Invalid -> error = r.reason
                        is CheatValidator.Result.Valid -> {
                            val n = name.ifBlank { strings.format(StrKey.CHEAT_DEFAULT_NAME, cheats.size + 1) }
                            update { l -> l += CheatCode(name = n, code = r.normalized, format = format, isEnabled = true) }
                            name = ""
                            code = ""
                            error = null
                        }
                    }
                },
            )
        }
    }
}

/** Hold Buttons sheet (half height): adaptive grid of A B X Y L R UP DOWN LEFT RIGHT. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HoldButtonsSheet(rom: RomEntry, held: Set<VirtualButton>, onToggle: (VirtualButton) -> Unit, onDone: () -> Unit) {
    val strings = LocalDeltaStrings.current
    val buttons = listOf(
        VirtualButton.A, VirtualButton.B, VirtualButton.X, VirtualButton.Y, VirtualButton.L, VirtualButton.R,
        VirtualButton.UP, VirtualButton.DOWN, VirtualButton.LEFT, VirtualButton.RIGHT,
    )
    val labels = rom.system.gamepadLayout.faceLabels
    DeltaSheet(title = str(StrKey.PAUSE_HOLD_BUTTONS), onDismiss = onDone, halfHeight = true) {
        Text(
            str(StrKey.HOLD_BUTTONS_HINT),
            style = DeltaType.Subheadline,
            color = DeltaColors.SystemGray,
            modifier = Modifier.padding(16.dp),
        )
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = 5,
        ) {
            buttons.forEach { b ->
                val on = b in held
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) DeltaColors.Purple else Color(0x33787880))
                        .clickable { onToggle(b) },
                    contentAlignment = Alignment.Center,
                ) {
                    val label = labels[b]?.takeIf { rom.system == GameSystem.GENESIS } ?: when (b) {
                        VirtualButton.UP -> strings[StrKey.CTRL_BTN_UP]
                        VirtualButton.DOWN -> strings[StrKey.CTRL_BTN_DOWN]
                        VirtualButton.LEFT -> strings[StrKey.CTRL_BTN_LEFT]
                        VirtualButton.RIGHT -> strings[StrKey.CTRL_BTN_RIGHT]
                        else -> b.name
                    }
                    Text(label, style = DeltaType.Headline, color = Color.White)
                }
            }
        }
    }
}

/** `…` More sheet: restart, DS screens, fast forward, controller skin switcher, display, core, events, Lu. */
@Composable
fun MoreOptionsSheet(
    rom: RomEntry,
    session: EmulatorSession?,
    ui: GameUiState,
    portrait: Boolean,
    activeSkinId: String?,
    onDismiss: () -> Unit,
    onResume: () -> Unit,
) {
    val prefsStore = DeltaApplication.container.preferences
    val prefs by prefsStore.state.collectAsState()
    val strings = LocalDeltaStrings.current
    var showCoreOptions by remember { mutableStateOf(false) }
    if (showCoreOptions) {
        CoreOptionsSheet(rom, session) { showCoreOptions = false }
        return
    }
    DeltaSheet(title = rom.title, onDismiss = onDismiss) {
        FormSection {
            FormRow(title = str(StrKey.MORE_RESTART_GAME), titleColor = Color(0xFFFF453A), onClick = {
                session?.reset()
                onDismiss()
                onResume()
            })
            if (rom.system == GameSystem.NDS || prefs.isExperimentalEnabled("reverseScreens")) {
                FormDivider()
                FormToggleRow(
                    title = str(StrKey.MORE_REVERSE_DS_SCREENS),
                    checked = ui.reverseScreens,
                    onCheckedChange = { ui.reverseScreens = it },
                )
            }
        }
        FormSection(header = str(StrKey.PAUSE_FAST_FORWARD)) {
            val mode = FastForwardMode.fromRaw(prefs.fastForwardMode)
            FormSegmentedRow(
                str(StrKey.MORE_BUTTON_MODE),
                FastForwardMode.entries.map { it.localizedTitle(strings) },
                FastForwardMode.entries.indexOf(mode),
            ) { i ->
                val m = FastForwardMode.entries[i]
                prefsStore.update { it.copy(fastForwardMode = m.raw) }
                session?.configureFastForward(m, prefs.fastForwardMultiplier)
            }
            FormDivider()
            val speeds = FastForwardController.SUPPORTED_MULTIPLIERS
            FormSegmentedRow(str(StrKey.MORE_SPEED), speeds.map { "$it×" }, speeds.indexOf(prefs.fastForwardMultiplier).coerceAtLeast(0)) { i ->
                prefsStore.update { it.copy(fastForwardMultiplier = speeds[i]) }
                session?.fastForward?.setMultiplier(speeds[i])
                ui.fastForwardMultiplier = speeds[i]
                if (session?.fastForward?.isActive == true) session.setFastForwardActive(true)
            }
        }
        val family = rom.system.skinSystem
        if (family != null) {
            FormSection(
                header = str(StrKey.MORE_CONTROLLER_SKIN_HEADER, SkinCatalog.familyShortName(family)),
                footer = str(StrKey.MORE_CONTROLLER_SKIN_FOOTER, SkinCatalog.familyDisplayName(family)),
            ) {
                val options = remember(family) { runCatching { SkinCatalog.options(family) }.getOrDefault(emptyList()) }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    options.forEach { option ->
                        val selected = option.id == (activeSkinId ?: com.codescv.delta.skins.SkinOption.OFFICIAL_ID)
                        Box(
                            Modifier.width(84.dp).clickable {
                                // Selection applies to both orientations of this system (live, no restart).
                                prefsStore.update {
                                    it.copy(
                                        portraitSkinsBySystem = it.portraitSkinsBySystem + (family to option.id),
                                        landscapeSkinsBySystem = it.landscapeSkinsBySystem + (family to option.id),
                                    )
                                }
                                session?.setTouchButtons(emptySet())
                                ui.pressed = emptySet()
                            },
                        ) {
                            SkinPreview(option, selected, Modifier.fillMaxWidth())
                            if (selected) {
                                Icon(
                                    Icons.Filled.CheckCircle, null, tint = DeltaColors.Purple,
                                    modifier = Modifier.align(Alignment.TopEnd).size(18.dp),
                                )
                            }
                        }
                    }
                }
                FormDivider()
                val currentName = options.firstOrNull { it.id == activeSkinId }?.name
                    ?: options.firstOrNull()?.name
                    ?: str(StrKey.COMMON_STANDARD)
                FormRow(title = str(StrKey.MORE_CURRENT_SKIN, currentName), titleColor = DeltaColors.SystemGray)
            }
        }
        FormSection(header = str(StrKey.MORE_DISPLAY)) {
            val filter = VideoFilter.fromRaw(prefs.filter)
            VideoFilter.entries.forEachIndexed { i, f ->
                if (i > 0) FormDivider()
                FormCheckRow(f.localizedTitle(strings), f == filter) { prefsStore.update { it.copy(filter = f.raw) } }
            }
            FormDivider()
            FormToggleRow(str(StrKey.MORE_INTEGER_SCALING), prefs.integerScaling, { v -> prefsStore.update { it.copy(integerScaling = v) } })
        }
        FormSection(header = str(StrKey.MORE_CORE)) {
            FormRow(title = str(StrKey.MORE_CORE_OPTIONS), chevron = true, onClick = { showCoreOptions = true })
            FormDivider()
            FormRow(title = str(StrKey.MORE_CORE), value = listOf(session?.libraryName, session?.libraryVersion).filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { rom.system.coreDisplayName })
        }
        FormSection(header = str(StrKey.MORE_EVENTS)) {
            DLC_EVENTS.forEachIndexed { i, (titleKey, summaryKey) ->
                if (i > 0) FormDivider()
                FormRow(title = str(titleKey), subtitle = str(summaryKey), trailing = {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF30D158))
                })
            }
        }
        if (prefs.isExperimentalEnabled("Lu")) {
            LuSection(rom)
        }
    }
}

private val DLC_EVENTS = listOf(
    StrKey.DLC_AURORA_TITLE to StrKey.DLC_AURORA_SUMMARY,
    StrKey.DLC_MYSTIC_TITLE to StrKey.DLC_MYSTIC_SUMMARY,
    StrKey.DLC_OLD_SEA_MAP_TITLE to StrKey.DLC_OLD_SEA_MAP_SUMMARY,
    StrKey.DLC_EON_TITLE to StrKey.DLC_EON_SUMMARY,
)

@Composable
private fun LuSection(rom: RomEntry) {
    val strings = LocalDeltaStrings.current
    var question by remember { mutableStateOf("") }
    var customAnswer by remember { mutableStateOf<String?>(null) }
    val answer = customAnswer ?: str(StrKey.LU_DEFAULT_TIP)
    FormSection(header = str(StrKey.LU_SECTION_HEADER)) {
        Text(answer, style = DeltaType.Caption, color = DeltaColors.SystemGray, modifier = Modifier.padding(16.dp))
        FormDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
            TextField(
                value = question, onValueChange = { question = it },
                placeholder = { Text(str(StrKey.LU_PLACEHOLDER, rom.title)) },
                singleLine = true, colors = deltaTextFieldColors(), modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = {
                if (question.isNotBlank()) {
                    customAnswer = strings.format(StrKey.LU_REPLY_TEMPLATE, rom.system.shortName, question, rom.title)
                    question = ""
                }
            }) { Text(str(StrKey.LU_ASK), color = DeltaColors.Purple) }
        }
    }
}

/** Core Options: one picker per visible core option (values from the core), persisted per core. */
@Composable
fun CoreOptionsSheet(rom: RomEntry, session: EmulatorSession?, onDismiss: () -> Unit) {
    val prefsStore = DeltaApplication.container.preferences
    val prefs by prefsStore.state.collectAsState()
    val coreId = rom.system.coreId
    var revision by remember { mutableStateOf(0) }
    val options = remember(revision, session) {
        runCatching { Json.parseToJsonElement(session?.coreOptionsJson() ?: "[]") as JsonArray }.getOrDefault(JsonArray(emptyList()))
            .mapNotNull { it as? JsonObject }
            .filter { runCatching { it["visible"]!!.jsonPrimitive.boolean }.getOrDefault(true) }
    }
    var expanded by remember { mutableStateOf<String?>(null) }
    DeltaSheet(title = str(StrKey.MORE_CORE_OPTIONS), onDismiss = onDismiss) {
        FormSection {
            if (options.isEmpty()) FormRow(title = str(StrKey.MORE_NO_CORE_OPTIONS), titleColor = DeltaColors.SystemGray)
            options.forEachIndexed { i, o ->
                if (i > 0) FormDivider()
                val key = o["key"]!!.jsonPrimitive.content
                val title = o["title"]?.jsonPrimitive?.content ?: key
                val values = o["values"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                val labels = o["labels"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                val current = prefs.coreOptions[coreId]?.get(key) ?: o["value"]?.jsonPrimitive?.content ?: ""
                val label = labels.getOrNull(values.indexOf(current)) ?: current
                Box {
                    FormRow(title = title, value = label, chevron = true, onClick = { expanded = key })
                    DropdownMenu(expanded = expanded == key, onDismissRequest = { expanded = null }) {
                        values.forEachIndexed { vi, v ->
                            DropdownMenuItem(
                                text = { Text((if (v == current) "✓ " else "    ") + (labels.getOrNull(vi) ?: v)) },
                                onClick = {
                                    expanded = null
                                    session?.setCoreOption(key, v)
                                    prefsStore.update { p ->
                                        val m = p.coreOptions[coreId].orEmpty() + (key to v)
                                        p.copy(coreOptions = p.coreOptions + (coreId to m))
                                    }
                                    revision++
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
