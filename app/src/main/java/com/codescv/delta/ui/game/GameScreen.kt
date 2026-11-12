package com.codescv.delta.ui.game

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.codescv.delta.DeltaApplication
import com.codescv.delta.emulation.EmulatorSession
import com.codescv.delta.emulation.FastForwardMode
import com.codescv.delta.emulation.GameSurfaceView
import com.codescv.delta.emulation.ScreenQuad
import com.codescv.delta.input.GameInputRouter
import com.codescv.delta.input.Gamepads
import com.codescv.delta.input.HapticsController
import com.codescv.delta.input.MultiTouchGamepadSurface
import com.codescv.delta.library.RomEntry
import com.codescv.delta.model.GameSystem
import com.codescv.delta.model.VirtualButton
import com.codescv.delta.saves.SaveFileManager
import com.codescv.delta.saves.SaveStateType
import com.codescv.delta.settings.AppPreferences
import com.codescv.delta.settings.VideoFilter
import com.codescv.delta.skins.DeltaSkinLayout
import com.codescv.delta.skins.GamepadTouchMapper
import com.codescv.delta.skins.GamepadTouchSample
import com.codescv.delta.skins.GamepadTouchTracker
import com.codescv.delta.skins.LoadedSkin
import com.codescv.delta.skins.ProceduralLayout
import com.codescv.delta.skins.ProceduralSkins
import com.codescv.delta.skins.SizeF
import com.codescv.delta.skins.SkinCatalog
import com.codescv.delta.skins.SkinOption
import com.codescv.delta.skins.procedural.ProceduralSkinArtwork
import com.codescv.delta.ui.DeltaNavigator
import com.codescv.delta.ui.theme.DeltaColors
import com.codescv.delta.ui.theme.DeltaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.codescv.delta.skins.Box as SkinBox

/** The controller skin active for the current orientation. */
sealed interface ActiveSkin {
    val mapper: GamepadTouchMapper
    val quads: List<ScreenQuad>
    val optionId: String?

    data class Official(val loaded: LoadedSkin, val layout: DeltaSkinLayout, override val optionId: String) : ActiveSkin {
        override val mapper: GamepadTouchMapper get() = layout
        override val quads: List<ScreenQuad> get() = layout.screens.map { ScreenQuad(it.frame, it.inputFrame) }
    }

    data class Procedural(override val optionId: String, val layout: ProceduralLayout, val system: GameSystem) : ActiveSkin {
        override val mapper: GamepadTouchMapper get() = layout
        override val quads: List<ScreenQuad>
            get() {
                // Each screen's core-pixel source (NDS halves, swapped when screens are reversed).
                val sources = (layout as? com.codescv.delta.skins.procedural.ProceduralSkinLayout)?.screenSources
                return layout.screens.mapIndexed { i, dest ->
                    val src = sources?.getOrNull(i)
                    ScreenQuad(dest, if (system == GameSystem.NDS) src else null)
                }
            }
    }

    /** No skin available: game aspect-fit, no controls. */
    data class None(override val quads: List<ScreenQuad>) : ActiveSkin {
        override val optionId: String? get() = null
        override val mapper: GamepadTouchMapper = object : GamepadTouchMapper {
            override fun lockTarget(x: Float, y: Float): Int? = null
            override fun sample(x: Float, y: Float, locked: Int?) = GamepadTouchSample()
            override fun claims(x: Float, y: Float) = false
        }
    }
}

/** UI state of a running game (pressed/held buttons, FF, toasts, menu). */
class GameUiState {
    var pressed by mutableStateOf<Set<VirtualButton>>(emptySet())
    var held by mutableStateOf<Set<VirtualButton>>(emptySet())
    var fastForwardActive by mutableStateOf(false)
    var fastForwardMultiplier by mutableIntStateOf(2)
    var fastForwardHeld by mutableStateOf(false)
    var analog by mutableStateOf(0f to 0f)
    var touches by mutableStateOf<List<Pair<Float, Float>>>(emptyList())
    var message by mutableStateOf<String?>(null)
    var messageSerial by mutableIntStateOf(0)
    var menuVisible by mutableStateOf(false)
    var frozenFrame by mutableStateOf<ImageBitmap?>(null)
    var reverseScreens by mutableStateOf(false)
    var gamepadConnected by mutableStateOf(false)
    var gamepadName by mutableStateOf<String?>(null)
    /** Incremented per published frame when the software renderer is active. */
    var frameTick by mutableIntStateOf(0)
    @Volatile var useGpu = true

    fun toast(text: String) {
        message = text
        messageSerial++
    }
}

/** Resolves the skin for [system] in the given orientation from preferences. */
fun resolveSkin(
    system: GameSystem,
    prefs: AppPreferences,
    portrait: Boolean,
    width: Float,
    height: Float,
    sourceW: Float,
    sourceH: Float,
    topInset: Float,
    controllerVisible: Boolean,
    thumbRadius: Float,
    reverseScreens: Boolean,
): ActiveSkin {
    val repo = DeltaApplication.container.skins
    val family = system.skinSystem ?: "chip8"
    val chosenId = (if (portrait) prefs.portraitSkinsBySystem else prefs.landscapeSkinsBySystem)[family]
    val option: SkinOption? = runCatching { SkinCatalog.options(family).firstOrNull { it.id == chosenId } }.getOrNull()

    if (option?.kind == SkinOption.Kind.VARIANT || system.skinSystem == null) {
        val id = option?.id ?: ProceduralSkins.options(family).firstOrNull()?.id
        if (id != null) {
            ProceduralSkins.layout(id, system, width, height, sourceW, sourceH, reverseScreens = reverseScreens)
                ?.let { return ActiveSkin.Procedural(id, it, system) }
        }
    }
    val candidates = buildList {
        if (option?.kind == SkinOption.Kind.IMPORTED) repo.find(family, option.id)?.let { add(it to option.id) }
        repo.bundled(family)?.let { add(it to SkinOption.OFFICIAL_ID) }
    }
    for ((loaded, id) in candidates) {
        val layout = DeltaSkinLayout.compute(
            loaded.skin, system, width, height, sourceW, sourceH,
            topInset = topInset, controllerVisible = controllerVisible,
            thumbRadius = thumbRadius, reverseScreens = reverseScreens,
        ) ?: continue // an imported skin lacking this orientation falls back to Standard
        return ActiveSkin.Official(loaded, layout, id)
    }
    val fit = DeltaSkinLayout.aspectFit(SizeF(sourceW, sourceH), SkinBox(0f, 0f, width, height))
    return ActiveSkin.None(listOf(ScreenQuad(fit, null)))
}

/** Places this element centered on (x, y) in the parent's pixel space. */
private fun Modifier.centeredAt(x: Float, y: Float): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(constraints.maxWidth, constraints.maxHeight) {
        p.place((x - p.width / 2f).toInt(), (y - p.height / 2f).toInt())
    }
}

@Composable
fun GameScreen(rom: RomEntry, resumeStateId: String?, navigator: DeltaNavigator) {
    val container = DeltaApplication.container
    val prefs by container.preferences.state.collectAsState()
    val context = LocalContext.current
    val density = LocalDensity.current
    val ui = remember(rom.id) { GameUiState() }
    var session by remember(rom.id) { mutableStateOf<EmulatorSession?>(null) }
    val sessionHolder = remember(rom.id) { arrayOfNulls<EmulatorSession>(1) }
    val glView = remember(rom.id) { arrayOfNulls<GameSurfaceView>(1) }
    var sourceSize by remember(rom.id) { mutableStateOf(defaultSource(rom.system)) }

    // Immersive full-screen gameplay (status bar only with the experimental feature).
    val activity = context as? Activity
    val showStatusBar = prefs.isExperimentalEnabled("showStatusBar")
    DisposableEffect(showStatusBar) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showStatusBar) {
            controller?.hide(WindowInsetsCompat.Type.navigationBars())
            controller?.show(WindowInsetsCompat.Type.statusBars())
        } else {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Start the emulator session.
    LaunchedEffect(rom.id) {
        val p = container.preferences.value
        val s = EmulatorSession(
            rom = rom,
            saves = container.saves,
            // melonDS picks up bios7/bios9/firmware from the system dir when present; "Built-in
            // BIOS" mode hands it an empty directory so the built-in BIOS is always used.
            systemDirectory = if (rom.system == GameSystem.NDS && p.ndsBiosMode != "external") {
                java.io.File(container.systemDir, "builtin").apply { mkdirs() }
            } else {
                container.systemDir
            },
            cacheDirectory = container.coreCacheDir,
            coreOptions = p.coreOptions[rom.system.coreId].orEmpty(),
            fastForwardMode = FastForwardMode.fromRaw(p.fastForwardMode),
            fastForwardMultiplier = p.fastForwardMultiplier,
        )
        s.outputSampleRate = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        s.volume = p.volume.toFloat()
        s.muted = p.muteAudio || isSilenced(context, p)
        s.toastsEnabled = p.isExperimentalEnabled("toastNotifications")
        s.listener = object : EmulatorSession.Listener {
            override fun onFrame() {
                if (ui.useGpu) glView[0]?.requestRender() else ui.frameTick++
            }
            override fun onMessage(text: String) = ui.toast(text)
            override fun onFastForwardChanged(active: Boolean, multiplier: Int) {
                ui.fastForwardActive = active
                ui.fastForwardMultiplier = multiplier
            }
        }
        try {
            withContext(Dispatchers.IO) { s.start(resumeStateId, p.cheatsByROM[rom.id].orEmpty()) }
            sessionHolder[0] = s
            session = s
            ui.fastForwardMultiplier = s.fastForward.multiplier
            if (s.baseWidth > 0 && s.baseHeight > 0) sourceSize = s.baseWidth.toFloat() to s.baseHeight.toFloat()
            glView[0]?.renderer?.session = s
            container.library.markPlayed(rom)
        } catch (e: Throwable) {
            navigator.launchError = e.message ?: e.toString()
            navigator.exitGame()
        }
    }
    DisposableEffect(rom.id) {
        onDispose {
            GameInputRouter.handler = null
            val s = sessionHolder[0]
            sessionHolder[0] = null
            if (s != null) {
                container.ioScope.launch {
                    s.stop()
                    // The auto save (and its screenshot) now exist: refresh Continue Playing.
                    container.library.bumpCovers()
                }
            }
        }
    }

    // Live preference changes.
    LaunchedEffect(session, prefs.volume, prefs.muteAudio, prefs.respectSilentMode) {
        session?.volume = prefs.volume.toFloat()
        session?.muted = prefs.muteAudio || isSilenced(context, prefs)
    }
    // Respect Silent Mode: react to ringer changes while playing (design §2.4).
    DisposableEffect(session, prefs.respectSilentMode, prefs.muteAudio) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: android.content.Intent?) {
                val p = container.preferences.value
                session?.muted = p.muteAudio || isSilenced(context, p)
            }
        }
        val filter = android.content.IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(session, prefs.isExperimentalEnabled("toastNotifications")) {
        session?.toastsEnabled = prefs.isExperimentalEnabled("toastNotifications")
    }
    LaunchedEffect(ui.messageSerial) {
        if (ui.message != null) {
            delay(2000)
            ui.message = null
        }
    }

    fun openMenu() {
        val s = session ?: return
        if (ui.menuVisible) return
        s.pause()
        ui.frozenFrame = s.snapshotFrame()?.asImageBitmap()
        ui.menuVisible = true
    }

    fun closeMenu() {
        ui.menuVisible = false
        ui.frozenFrame = null
        session?.resume()
    }

    // Lifecycle: background → SRAM + auto save; foreground → resume unless the menu is open.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            val s = session ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (ui.frozenFrame == null) ui.frozenFrame = s.snapshotFrame()?.asImageBitmap()
                    container.ioScope.launch { s.suspend() }
                    glView[0]?.onPause()
                }
                Lifecycle.Event.ON_START -> {
                    glView[0]?.onResume()
                    s.resumeFromSuspend()
                    if (!ui.menuVisible) {
                        ui.frozenFrame = null
                        s.resume()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Physical gamepads (players 1–4).
    LaunchedEffect(session, prefs.playerAssignments, prefs.gamepadMappings) {
        GameInputRouter.assignments = prefs.playerAssignments
        GameInputRouter.mappings = prefs.gamepadMappings
        fun refreshControllers() {
            val list = Gamepads.connected(context, prefs.playerAssignments)
            ui.gamepadConnected = list.isNotEmpty()
            ui.gamepadName = list.firstOrNull()?.name
        }
        refreshControllers()
        val s = session ?: return@LaunchedEffect
        GameInputRouter.reset()
        GameInputRouter.handler = object : GameInputRouter.Handler {
            override fun onGamepadMask(player: Int, mask: Int) = s.setPlayerMask(player, mask)
            override fun onGamepadAnalog(player: Int, stick: Int, x: Float, y: Float) = s.setPlayerAnalog(player, stick, x, y)
            override fun onGamepadMenu() {
                if (ui.menuVisible) closeMenu() else openMenu()
            }
            override fun onGamepadConnectionChanged() = refreshControllers()
        }
    }

    BackHandler(enabled = !ui.menuVisible) { openMenu() }
    BackHandler(enabled = ui.menuVisible) { closeMenu() }

    val haptics = remember(rom.id) { arrayOfNulls<HapticsController>(1) }
    DisposableEffect(rom.id) { onDispose { haptics[0]?.release() } }

    BoxWithConstraints(Modifier.fillMaxSize().background(DeltaColors.GameBackground)) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val portrait = widthPx <= heightPx
        val topInset = if (Build.VERSION.SDK_INT >= 28) {
            (activity?.window?.decorView?.rootWindowInsets?.displayCutout?.safeInsetTop ?: 0).toFloat()
        } else {
            0f
        }
        val controllerVisible = !ui.gamepadConnected || prefs.alwaysShowTouchControls
        val thumbRadius = with(density) { 8.dp.toPx() }
        // More-sheet toggle XOR the experimental "Reverse Controller Skin Screens".
        val reverse = ui.reverseScreens xor prefs.isExperimentalEnabled("reverseScreens")
        val skin = remember(
            rom.system, prefs.portraitSkinsBySystem, prefs.landscapeSkinsBySystem, portrait, widthPx, heightPx,
            sourceSize, topInset, controllerVisible, reverse,
        ) {
            resolveSkin(
                rom.system, prefs, portrait, widthPx, heightPx, sourceSize.first, sourceSize.second,
                topInset, controllerVisible, thumbRadius, reverse,
            )
        }
        val filter = VideoFilter.fromRaw(prefs.filter)
        // Skin Debugging (experimental) draws item hit boxes, like iOS showHitBoxes.
        val showHitBoxes = prefs.isExperimentalEnabled("skinDebugging")
        val showTouches = prefs.isExperimentalEnabled("showTouches")

        // Everything the pause menu blurs: game screens (GL or frozen frame) + skin artwork.
        val blurModifier = if (ui.menuVisible && Build.VERSION.SDK_INT >= 31) Modifier.blur(24.dp) else Modifier
        val gpu = prefs.isExperimentalEnabled("metal")
        ui.useGpu = gpu
        if (!gpu) glView[0] = null
        Box(Modifier.fillMaxSize().then(blurModifier)) {
            if (!gpu && ui.frozenFrame == null) {
                SoftwareGameScreens(session, skin.quads, filter, ui.frameTick)
            }
            if (gpu) AndroidView(
                factory = { ctx ->
                    GameSurfaceView(ctx).also { v ->
                        glView[0] = v
                        v.renderer.session = sessionHolder[0]
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { v ->
                    v.renderer.quads = skin.quads
                    v.renderer.filter = filter
                    v.renderer.integerScaling = prefs.integerScaling
                    v.renderer.session = session
                    v.visibility = if (ui.frozenFrame != null) View.INVISIBLE else View.VISIBLE
                    v.requestRender()
                },
            )
            ui.frozenFrame?.let { frame -> FrozenScreens(frame, skin.quads, filter) }

            when (skin) {
                is ActiveSkin.Official -> DeltaSkinArtwork(
                    repo = container.skins,
                    loaded = skin.loaded,
                    layout = skin.layout,
                    pressed = ui.pressed,
                    held = ui.held,
                    fastForwardHeld = ui.fastForwardHeld,
                    analog = ui.analog,
                    opacity = prefs.controllerOpacity.toFloat(),
                    showHitBoxes = showHitBoxes,
                    touches = if (showTouches) ui.touches else emptyList(),
                )
                is ActiveSkin.Procedural -> ProceduralSkinArtwork(
                    layout = skin.layout,
                    optionId = skin.optionId,
                    system = rom.system,
                    pressed = ui.pressed,
                    held = ui.held,
                    fastForwardActive = ui.fastForwardActive,
                    fastForwardMultiplier = ui.fastForwardMultiplier,
                    fastForwardHeld = ui.fastForwardHeld,
                    opacity = prefs.controllerOpacity.toFloat(),
                    showHitBoxes = showHitBoxes,
                    touches = if (showTouches) ui.touches else emptyList(),
                )
                is ActiveSkin.None -> Unit
            }
        }

        // Multi-touch controller surface (transparent, on top of the artwork).
        if (!ui.menuVisible && controllerVisible) {
            AndroidView(
                factory = { ctx ->
                    MultiTouchGamepadSurface(ctx).also { surface ->
                        val h = HapticsController(ctx, surface)
                        haptics[0] = h
                        surface.tracker.listener = object : GamepadTouchTracker.Listener {
                            override fun onButtonsChanged(buttons: Set<VirtualButton>) {
                                sessionHolder[0]?.setTouchButtons(buttons)
                                ui.pressed = buttons
                            }
                            override fun onFastForwardHold(held: Boolean) {
                                ui.fastForwardHeld = held
                                val s = sessionHolder[0] ?: return
                                if (held) s.fastForwardDown() else s.fastForwardUp()
                            }
                            override fun onToggleFastForward() {
                                val s = sessionHolder[0] ?: return
                                s.setFastForwardActive(!s.fastForward.isActive)
                            }
                            override fun onMenu() = openMenu()
                            override fun onQuickSave() {
                                container.ioScope.launch {
                                    val ok = sessionHolder[0]?.saveState(SaveStateType.QUICK, quiet = true) != null
                                    withContext(Dispatchers.Main) { ui.toast(if (ok) "Quick Saved" else "Unable to Save State") }
                                }
                            }
                            override fun onQuickLoad() {
                                container.ioScope.launch {
                                    val ok = sessionHolder[0]?.loadState(SaveFileManager.QUICK_ID, quiet = true) == true
                                    withContext(Dispatchers.Main) { ui.toast(if (ok) "Quick Loaded" else "Unable to Load State") }
                                }
                            }
                            override fun onAnalog(x: Float, y: Float) {
                                ui.analog = x to y
                                sessionHolder[0]?.setAnalog(0, x, y)
                            }
                            override fun onRightAnalog(x: Float, y: Float) {
                                sessionHolder[0]?.setAnalog(1, x, y)
                            }
                            override fun onPointer(x: Float?, y: Float?) {
                                sessionHolder[0]?.setPointer(x, y)
                            }
                            override fun onTouchPoints(points: List<Pair<Float, Float>>) {
                                if (ui.touches.isNotEmpty() || points.isNotEmpty()) ui.touches = points
                            }
                            override fun onHaptic(kind: GamepadTouchTracker.HapticKind) = h.perform(kind)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { surface ->
                    surface.mapper = skin.mapper
                    surface.tracker.hasStartSelect = rom.system.gamepadLayout.hasStartSelect
                    haptics[0]?.apply {
                        enabled = prefs.haptics
                        thumbstickEnabled = prefs.thumbstickHaptics
                        clickSound = prefs.buttonClickSound
                        strength = prefs.hapticStrength
                    }
                },
            )
        }

        val screen = skin.quads.firstOrNull()?.dest ?: SkinBox(0f, 0f, widthPx, heightPx)

        // Fast-forward HUD "▶▶ 2×" centered at (screen.maxX − 34, screen.minY + 16).
        if (ui.fastForwardActive && !ui.menuVisible) {
            val cx = screen.maxX - with(density) { 34.dp.toPx() }
            val cy = screen.minY + with(density) { 16.dp.toPx() }
            Text(
                "▶▶ ${ui.fastForwardMultiplier}×",
                style = DeltaType.Caption.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier
                    .centeredAt(cx, cy)
                    .clip(CircleShape)
                    .background(DeltaColors.Purple)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        // Session toast capsule centered at (midX, screen.maxY − 22).
        val message = ui.message
        if (message != null && !ui.menuVisible) {
            Text(
                message,
                style = DeltaType.Footnote.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                modifier = Modifier
                    .centeredAt(widthPx / 2f, screen.maxY - with(density) { 22.dp.toPx() })
                    .clip(CircleShape)
                    .background(Color(0xD92B2B2B))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // Controller badge when touch controls are hidden by a connected gamepad.
        if (!controllerVisible && !ui.menuVisible) {
            Column(
                Modifier.centeredAt(widthPx / 2f, heightPx * 0.85f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.SportsEsports, null, tint = DeltaColors.Purple, modifier = Modifier.size(34.dp))
                Text(
                    ui.gamepadName ?: "Controller connected",
                    style = DeltaType.Footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                )
                Button(
                    onClick = { openMenu() },
                    colors = ButtonDefaults.buttonColors(containerColor = DeltaColors.Purple),
                ) { Text("Delta Menu", fontSize = 15.sp) }
            }
        }

        if (ui.menuVisible) {
            PauseMenu(
                rom = rom,
                session = session,
                ui = ui,
                portrait = portrait,
                activeSkinId = skin.optionId,
                onResume = { closeMenu() },
                onExit = {
                    ui.menuVisible = false
                    navigator.exitGame()
                },
            )
        }
    }
}

/** Frozen last frame drawn at the screen rects while paused, so the whole box can blur. */
@Composable
private fun FrozenScreens(frame: ImageBitmap, quads: List<ScreenQuad>, filter: VideoFilter) {
    val density = LocalDensity.current
    for (q in quads) {
        val src = q.source
        val d = q.dest
        Box(
            Modifier
                .offset { IntOffset(d.x.toInt(), d.y.toInt()) }
                .size(with(density) { d.width.toDp() }, with(density) { d.height.toDp() })
                .clip(RectangleShape),
        ) {
            if (src == null) {
                Image(
                    frame, null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    filterQuality = if (filter == VideoFilter.SMOOTH_BILINEAR) FilterQuality.Low else FilterQuality.None,
                )
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    drawImage(
                        frame,
                        srcOffset = IntOffset(src.x.toInt(), src.y.toInt()),
                        srcSize = IntSize(src.width.toInt(), src.height.toInt()),
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        filterQuality = FilterQuality.None,
                    )
                }
            }
        }
    }
}

private fun defaultSource(system: GameSystem): Pair<Float, Float> = when (system) {
    GameSystem.GBA -> 240f to 160f
    GameSystem.GB, GameSystem.GBC, GameSystem.GAME_GEAR -> 160f to 144f
    GameSystem.NDS -> 256f to 384f
    GameSystem.NES -> 256f to 240f
    GameSystem.SNES -> 256f to 224f
    GameSystem.N64 -> 320f to 240f
    GameSystem.GENESIS, GameSystem.MASTER_SYSTEM -> 320f to 224f
    GameSystem.CHIP8 -> 128f to 64f
}

private fun isSilenced(context: Context, prefs: AppPreferences): Boolean {
    if (!prefs.respectSilentMode) return false
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return am.ringerMode != AudioManager.RINGER_MODE_NORMAL
}
