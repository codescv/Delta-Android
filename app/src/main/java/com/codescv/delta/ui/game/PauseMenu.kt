package com.codescv.delta.ui.game

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codescv.delta.DeltaApplication
import com.codescv.delta.emulation.EmulatorSession
import com.codescv.delta.emulation.FastForwardController
import com.codescv.delta.library.RomEntry
import com.codescv.delta.ui.saves.SaveStatesMode
import com.codescv.delta.ui.saves.SaveStatesSheet
import com.codescv.delta.ui.theme.DeltaColors
import com.codescv.delta.ui.theme.DeltaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class PauseSheet { SAVE_STATE, LOAD_STATE, CHEAT_CODES, HOLD_BUTTONS, MORE }

/**
 * Delta pause menu (IMG_0835): blurred gameplay + 45 % black scrim, 2×3 (portrait) / 3×2
 * (landscape) frosted-glass tiles, `…` More (top-right), Home (bottom-left, glass) and
 * Resume (bottom-right, `#7A2BFA`).
 */
@Composable
fun PauseMenu(
    rom: RomEntry,
    session: EmulatorSession?,
    ui: GameUiState,
    portrait: Boolean,
    activeSkinId: String?,
    onResume: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val container = DeltaApplication.container
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<PauseSheet?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastSerial by remember { mutableStateOf(0) }
    fun showToast(text: String) {
        toast = text
        toastSerial++
    }
    LaunchedEffect(toastSerial) {
        if (toast != null) {
            delay(2000)
            toast = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (Build.VERSION.SDK_INT >= 31) DeltaColors.PauseScrim else Color(0xB3000000)),
    ) {
        // Tile grid.
        val columns = if (portrait) 2 else 3
        val tiles = listOf(
            PauseTileSpec("Save State", "PauseSaveState") { sheet = PauseSheet.SAVE_STATE },
            PauseTileSpec("Load State", "PauseLoadState") { sheet = PauseSheet.LOAD_STATE },
            PauseTileSpec("Cheat Codes", "PauseCheatCodes") { sheet = PauseSheet.CHEAT_CODES },
            PauseTileSpec("Fast Forward", "PauseFastForward", selected = ui.fastForwardActive) {
                session?.setFastForwardActive(!(session.fastForward.isActive))
                ui.fastForwardActive = session?.fastForward?.isActive == true
            },
            PauseTileSpec("Hold Buttons", "PauseHoldButtons", selected = ui.held.isNotEmpty()) {
                if (ui.held.isEmpty()) {
                    sheet = PauseSheet.HOLD_BUTTONS
                } else {
                    session?.releaseHeldButtons()
                    ui.held = emptySet()
                    showToast("Released Held Buttons")
                }
            },
            PauseTileSpec("Screenshot", "PauseScreenshot") {
                scope.launch {
                    val msg = withContext(Dispatchers.IO) { takeScreenshot(context, rom, session) }
                    showToast(msg)
                }
            },
        )
        Column(
            Modifier.align(Alignment.Center),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            tiles.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { spec ->
                        if (spec.title == "Fast Forward") {
                            FastForwardTile(spec, ui, session)
                        } else {
                            PauseMenuTile(spec)
                        }
                    }
                }
            }
        }

        // Corner controls.
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
            PauseMenuCircleButton(
                icon = { Icon(Icons.Filled.MoreHoriz, null, tint = Color.White, modifier = Modifier.size(20.dp)) },
                size = 44.dp,
                fill = null,
                label = "More Options",
                modifier = Modifier.align(Alignment.TopEnd),
            ) { sheet = PauseSheet.MORE }
            PauseMenuCircleButton(
                icon = { Icon(Icons.Filled.Home, null, tint = Color.White, modifier = Modifier.size(22.dp)) },
                size = 52.dp,
                fill = null,
                label = "Home",
                modifier = Modifier.align(Alignment.BottomStart),
            ) { onExit() }
            PauseMenuCircleButton(
                icon = { Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(26.dp)) },
                size = 52.dp,
                fill = DeltaColors.PlayPurple,
                label = "Resume",
                modifier = Modifier.align(Alignment.BottomEnd),
            ) { onResume() }
        }

        // Toast (top).
        AnimatedVisibility(
            visible = toast != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 16.dp),
        ) {
            Text(
                toast ?: "",
                style = DeltaType.Footnote.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xCC3A3A3C))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }

    when (sheet) {
        PauseSheet.SAVE_STATE -> SaveStatesSheet(
            rom = rom,
            mode = SaveStatesMode.SAVE,
            session = session,
            onDismiss = { sheet = null },
            onSaved = { name ->
                sheet = null
                showToast("Saved to $name")
            },
        )
        PauseSheet.LOAD_STATE -> SaveStatesSheet(
            rom = rom,
            mode = SaveStatesMode.LOAD,
            session = session,
            onDismiss = { sheet = null },
            onLoaded = {
                sheet = null
                onResume()
            },
        )
        PauseSheet.CHEAT_CODES -> CheatCodesSheet(rom, session) { sheet = null }
        PauseSheet.HOLD_BUTTONS -> HoldButtonsSheet(
            rom = rom,
            held = ui.held,
            onToggle = { b ->
                session?.toggleHeldButton(b)
                ui.held = session?.heldButtons ?: emptySet()
            },
            onDone = {
                sheet = null
                if (ui.held.isNotEmpty()) onResume()
            },
        )
        PauseSheet.MORE -> MoreOptionsSheet(
            rom = rom,
            session = session,
            ui = ui,
            portrait = portrait,
            activeSkinId = activeSkinId,
            onDismiss = { sheet = null },
            onResume = onResume,
        )
        null -> Unit
    }
}

private class PauseTileSpec(
    val title: String,
    val asset: String,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

private val iconCache = HashMap<String, ImageBitmap?>()

@Composable
private fun pauseIcon(name: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(name) {
        iconCache.getOrPut(name) {
            runCatching {
                context.assets.open("DeltaAssets/$name@3x.png").use { BitmapFactory.decodeStream(it) }.asImageBitmap()
            }.getOrNull()
        }
    }
}

@Composable
private fun PauseMenuTile(spec: PauseTileSpec, onLongClick: (() -> Unit)? = null) {
    PauseTileBody(spec, onLongClick)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PauseTileBody(spec: PauseTileSpec, onLongClick: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(32.dp)
    val fg = if (spec.selected) Color.Black else Color.White
    Column(
        Modifier
            .size(145.dp, 115.dp)
            .scale(if (pressed) 0.94f else 1f)
            .graphicsLayer { alpha = if (pressed) 0.85f else 1f }
            .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
            .clip(shape)
            .background(if (spec.selected) Color.White else Color(0x8C2C2C2E))
            .background(if (spec.selected) Color.Transparent else Color(0x0FFFFFFF))
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x59FFFFFF), Color(0x14FFFFFF))), shape)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = spec.onClick,
                onLongClick = onLongClick,
            )
            .semantics { contentDescription = spec.title }
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val icon = pauseIcon(spec.asset)
        if (icon != null) {
            Image(icon, null, colorFilter = ColorFilter.tint(fg), modifier = Modifier.height(36.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            spec.title,
            style = DeltaType.Headline,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Fast Forward tile: tap toggles; long-press shows 2× / 3× / 4× (choosing also enables FF). */
@Composable
private fun FastForwardTile(spec: PauseTileSpec, ui: GameUiState, session: EmulatorSession?) {
    var menu by remember { mutableStateOf(false) }
    val container = DeltaApplication.container
    Box {
        PauseTileBody(spec) { menu = true }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            for (m in FastForwardController.SUPPORTED_MULTIPLIERS) {
                DropdownMenuItem(
                    text = { Text((if (m == ui.fastForwardMultiplier) "✓ " else "    ") + "$m×") },
                    onClick = {
                        menu = false
                        container.preferences.update { it.copy(fastForwardMultiplier = m) }
                        session?.fastForward?.setMultiplier(m)
                        session?.setFastForwardActive(true)
                        ui.fastForwardMultiplier = m
                        ui.fastForwardActive = true
                    },
                )
            }
        }
    }
}

@Composable
private fun PauseMenuCircleButton(
    icon: @Composable () -> Unit,
    size: Dp,
    fill: Color?,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(size)
            .scale(if (pressed) 0.94f else 1f)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(fill ?: Color(0x993A3A3C))
            .border(1.dp, if (fill == null) Color(0x40FFFFFF) else Color.Transparent, CircleShape)
            .combinedClickableCompat(interaction, onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { icon() }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(interaction: MutableInteractionSource, onClick: () -> Unit) =
    combinedClickable(interactionSource = interaction, indication = null, onClick = onClick)

/**
 * Screenshot: PNG from the core at min width 1080 → MediaStore album "Delta Screenshots" and
 * `filesDir/Screenshots/<Safe Title> yyyy-MM-dd HH.mm.ss.png`.
 */
fun takeScreenshot(context: Context, rom: RomEntry, session: EmulatorSession?): String {
    val png = session?.screenshotPng(1080) ?: return "Unable to Take Screenshot"
    val safe = rom.title.map { if (it in "/\\:?%*|\"<>") '_' else it }.joinToString("")
    val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())
    val dir = DeltaApplication.container.screenshotsDir
    var file = File(dir, "$safe $stamp.png")
    var n = 2
    while (file.exists()) file = File(dir, "$safe $stamp ${n++}.png")
    return try {
        file.writeBytes(png)
        val album = DeltaApplication.container.preferences.value.isExperimentalEnabled("screenshotsAlbum")
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, if (album) "Pictures/Delta Screenshots" else "Pictures")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(png) }
                "Screenshot Saved to Photos"
            } else {
                "Screenshot Saved to Files › Screenshots"
            }
        } else {
            "Screenshot Saved to Files › Screenshots"
        }
    } catch (e: Exception) {
        "Unable to Save Screenshot"
    }
}

@Suppress("unused")
private val keepWidth = Modifier.width(0.dp)

@Suppress("unused")
private val keepSp = 0.sp
