package com.codescv.delta.skins

import com.codescv.delta.model.DPadGeometry
import com.codescv.delta.model.GameSystem
import com.codescv.delta.model.VirtualButton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Plain float rectangle (android.graphics.RectF is unavailable in JVM unit tests). */
data class Box(val x: Float, val y: Float, val width: Float, val height: Float) {
    val minX get() = x
    val minY get() = y
    val maxX get() = x + width
    val maxY get() = y + height
    val midX get() = x + width / 2f
    val midY get() = y + height / 2f
    val area get() = if (width <= 0f || height <= 0f) 0f else width * height

    fun contains(px: Float, py: Float): Boolean = px >= x && px <= maxX && py >= y && py <= maxY

    fun inset(dx: Float, dy: Float) = Box(x + dx, y + dy, width - 2 * dx, height - 2 * dy)

    fun scaled(sx: Float, sy: Float) = Box(x * sx, y * sy, width * sx, height * sy)

    fun intersection(o: Box): Box {
        val l = max(x, o.x)
        val t = max(y, o.y)
        val r = min(maxX, o.maxX)
        val b = min(maxY, o.maxY)
        return if (r <= l || b <= t) Box(0f, 0f, 0f, 0f) else Box(l, t, r - l, b - t)
    }

    fun intersects(o: Box) = intersection(o).area > 0f

    /** CGRect.integral equivalent. */
    fun integral(): Box {
        val l = kotlin.math.floor(x)
        val t = kotlin.math.floor(y)
        val r = kotlin.math.ceil(maxX)
        val b = kotlin.math.ceil(maxY)
        return Box(l, t, r - l, b - t)
    }

    companion object {
        val ZERO = Box(0f, 0f, 0f, 0f)
    }
}

data class SizeF(val width: Float, val height: Float)

/**
 * A parsed official Delta controller skin (`info.json` + PDF/PNG assets), ported from
 * DeltaCore's `ControllerSkin`. Item and screen frames are normalized (0..1) to `mappingSize`.
 */
data class DeltaSkin(
    val skinSystem: String,
    val name: String,
    val identifier: String,
    val gameTypeIdentifier: String?,
    val debug: Boolean,
    /** `iphone` representations keyed by display type (`edgeToEdge`, `standard`) then orientation. */
    val representations: Map<String, Map<String, Representation>>,
) {
    data class Screen(
        /** Region of the core's video output shown by this screen, in source pixels. */
        val inputFrame: Box?,
        /** Output frame (normalized when [controllerPlacement]). */
        val outputFrame: Box?,
        val controllerPlacement: Boolean,
    )

    enum class ItemKind { BUTTONS, DPAD, THUMBSTICK, TOUCH_SCREEN }

    data class Item(
        val kind: ItemKind,
        val inputs: List<String>,
        val directions: Map<String, String>,
        val frame: Box,
        val extendedFrame: Box,
        val assetName: String?,
        val assetSize: SizeF?,
    )

    data class Representation(
        val backgroundAsset: String?,
        val mappingSize: SizeF,
        val items: List<Item>,
        val screens: List<Screen>,
        val translucent: Boolean,
    ) {
        val hasControllerPlacedScreens: Boolean
            get() = screens.any { it.controllerPlacement && it.outputFrame != null }
    }

    /** The iPhone representation for an orientation, preferring edge-to-edge. */
    fun representation(portrait: Boolean, edgeToEdge: Boolean = true): Representation? {
        val orientation = if (portrait) "portrait" else "landscape"
        val order = if (edgeToEdge) listOf("edgeToEdge", "standard") else listOf("standard", "edgeToEdge")
        for (displayType in order) {
            representations[displayType]?.get(orientation)?.let { return it }
        }
        return null
    }

    val supportsPortrait get() = representation(true) != null
    val supportsLandscape get() = representation(false) != null

    class ParseException(message: String) : Exception(message)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(skinSystem: String, infoJson: String): DeltaSkin {
            val root = try {
                json.parseToJsonElement(infoJson) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: throw ParseException("invalid JSON")
            val name = root.string("name") ?: "Standard"
            val identifier = root.string("identifier") ?: "com.delta.$skinSystem.standard"
            val reps = root["representations"] as? JsonObject ?: throw ParseException("missing representations")
            val iphone = reps["iphone"] as? JsonObject ?: throw ParseException("missing iphone representations")
            val parsed = LinkedHashMap<String, Map<String, Representation>>()
            for ((displayType, value) in iphone) {
                val orientations = value as? JsonObject ?: continue
                val byOrientation = LinkedHashMap<String, Representation>()
                for ((orientation, repValue) in orientations) {
                    val dict = repValue as? JsonObject ?: continue
                    parseRepresentation(dict)?.let { byOrientation[orientation] = it }
                }
                if (byOrientation.isNotEmpty()) parsed[displayType] = byOrientation
            }
            if (parsed.isEmpty()) throw ParseException("missing representations")
            return DeltaSkin(
                skinSystem = skinSystem,
                name = name,
                identifier = identifier,
                gameTypeIdentifier = root.string("gameTypeIdentifier"),
                debug = (root["debug"] as? JsonPrimitive)?.booleanOrNull ?: false,
                representations = parsed,
            )
        }

        /** Maps a Delta `gameTypeIdentifier` (e.g. `com.rileytestut.delta.game.gba`) to a skin family. */
        fun skinSystemForGameType(gameType: String?): String? = when (gameType?.substringAfterLast('.')) {
            "gba" -> "gba"
            "gbc", "gb" -> "gbc"
            "nes" -> "nes"
            "snes" -> "snes"
            "n64" -> "n64"
            "ds", "nds" -> "nds"
            "genesis", "md", "sms", "gg" -> "genesis"
            else -> null
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun number(e: JsonElement?): Float? = (e as? JsonPrimitive)?.doubleOrNull?.toFloat()

        private fun rect(e: JsonElement?): Box? {
            val d = e as? JsonObject ?: return null
            val x = number(d["x"]) ?: return null
            val y = number(d["y"]) ?: return null
            val w = number(d["width"]) ?: return null
            val h = number(d["height"]) ?: return null
            return Box(x, y, w, h)
        }

        private class Edges(e: JsonElement?) {
            val d = e as? JsonObject
            val top = number(d?.get("top"))
            val bottom = number(d?.get("bottom"))
            val left = number(d?.get("left"))
            val right = number(d?.get("right"))
        }

        private fun parseRepresentation(dict: JsonObject): Representation? {
            val mapping = dict["mappingSize"] as? JsonObject ?: return null
            val mw = number(mapping["width"]) ?: return null
            val mh = number(mapping["height"]) ?: return null
            if (mw <= 0f || mh <= 0f) return null
            val nx = 1f / mw
            val ny = 1f / mh
            val assets = dict["assets"] as? JsonObject
            val background = assets?.string("resizable") ?: assets?.string("large")
                ?: assets?.string("medium") ?: assets?.string("small")
            val defaultEdges = Edges(dict["extendedEdges"])

            val items = ArrayList<Item>()
            for (itemEl in dict["items"] as? JsonArray ?: JsonArray(emptyList())) {
                val itemDict = itemEl as? JsonObject ?: continue
                val frame = rect(itemDict["frame"]) ?: continue
                val override = Edges(itemDict["extendedEdges"])
                val top = override.top ?: defaultEdges.top ?: 0f
                val bottom = override.bottom ?: defaultEdges.bottom ?: 0f
                val left = override.left ?: defaultEdges.left ?: 0f
                val right = override.right ?: defaultEdges.right ?: 0f
                val extended = Box(frame.x - left, frame.y - top, frame.width + left + right, frame.height + top + bottom)

                var kind = ItemKind.BUTTONS
                var inputs: List<String> = emptyList()
                var directions: Map<String, String> = emptyMap()
                when (val raw = itemDict["inputs"]) {
                    is JsonArray -> inputs = raw.mapNotNull { (it as? JsonPrimitive)?.content }
                    is JsonObject -> {
                        val map = raw.mapValues { it.value.jsonPrimitive.content }
                        directions = map
                        kind = if (map["x"] != null && map["y"] != null) {
                            ItemKind.TOUCH_SCREEN
                        } else if (itemDict["thumbstick"] != null) {
                            ItemKind.THUMBSTICK
                        } else {
                            ItemKind.DPAD
                        }
                    }
                    else -> continue
                }

                val asset = (itemDict["asset"] as? JsonObject) ?: (itemDict["thumbstick"] as? JsonObject)
                var assetName: String? = null
                var assetSize: SizeF? = null
                if (asset != null) {
                    assetName = asset.string("name")
                    val w = number(asset["width"])
                    val h = number(asset["height"])
                    if (w != null && h != null) assetSize = SizeF(w / mw, h / mh)
                }
                items += Item(kind, inputs, directions, frame.scaled(nx, ny), extended.scaled(nx, ny), assetName, assetSize)
            }

            val screens = ArrayList<Screen>()
            val gameFrame = rect(dict["gameScreenFrame"])
            if (gameFrame != null) {
                screens += Screen(null, gameFrame.scaled(nx, ny), true)
            } else {
                for (screenEl in dict["screens"] as? JsonArray ?: JsonArray(emptyList())) {
                    val s = screenEl as? JsonObject ?: continue
                    val input = rect(s["inputFrame"])
                    val output = rect(s["outputFrame"])
                    val placement = s.string("placement")
                    val controller = placement?.let { it == "controller" } ?: (output != null)
                    screens += Screen(input, if (controller) output?.scaled(nx, ny) else output, controller)
                }
            }
            return Representation(
                backgroundAsset = background,
                mappingSize = SizeF(mw, mh),
                items = items,
                screens = screens,
                translucent = (dict["translucent"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }

        /** What a skin input name does for [system]'s core. */
        fun action(input: String, system: GameSystem): SkinAction {
            when (input) {
                "menu" -> return SkinAction.Menu
                "fastForward" -> return SkinAction.FastForward
                "toggleFastForward" -> return SkinAction.ToggleFastForward
                "quickSave" -> return SkinAction.QuickSave
                "quickLoad" -> return SkinAction.QuickLoad
                "up" -> return SkinAction.Button(VirtualButton.UP)
                "down" -> return SkinAction.Button(VirtualButton.DOWN)
                "left" -> return SkinAction.Button(VirtualButton.LEFT)
                "right" -> return SkinAction.Button(VirtualButton.RIGHT)
                "start" -> return SkinAction.Button(VirtualButton.START)
                "select" -> return SkinAction.Button(VirtualButton.SELECT)
            }
            return when (system.category) {
                GameSystem.GENESIS -> when (input) {
                    // Genesis A/B/C sit on RetroPad Y/B/A.
                    "a" -> SkinAction.Button(VirtualButton.Y)
                    "b" -> SkinAction.Button(VirtualButton.B)
                    "c" -> SkinAction.Button(VirtualButton.A)
                    "x" -> SkinAction.Button(VirtualButton.L)
                    "y" -> SkinAction.Button(VirtualButton.X)
                    "z" -> SkinAction.Button(VirtualButton.R)
                    "mode" -> SkinAction.Button(VirtualButton.SELECT)
                    else -> SkinAction.Ignored
                }
                GameSystem.N64 -> when (input) {
                    // parallel-n64 RetroPad mapping: A=B, B=Y, Z=L2, R=R2, C-buttons on X/A/L/R.
                    "a" -> SkinAction.Button(VirtualButton.B)
                    "b" -> SkinAction.Button(VirtualButton.Y)
                    "z" -> SkinAction.Button(VirtualButton.L2)
                    "r" -> SkinAction.Button(VirtualButton.R2)
                    "l" -> SkinAction.Button(VirtualButton.L)
                    "cUp" -> SkinAction.Button(VirtualButton.X)
                    "cDown" -> SkinAction.Button(VirtualButton.A)
                    "cLeft" -> SkinAction.Button(VirtualButton.L)
                    "cRight" -> SkinAction.Button(VirtualButton.R)
                    else -> SkinAction.Ignored
                }
                else -> when (input) {
                    "a" -> SkinAction.Button(VirtualButton.A)
                    "b" -> SkinAction.Button(VirtualButton.B)
                    "x" -> SkinAction.Button(VirtualButton.X)
                    "y" -> SkinAction.Button(VirtualButton.Y)
                    "l" -> SkinAction.Button(VirtualButton.L)
                    "r" -> SkinAction.Button(VirtualButton.R)
                    else -> SkinAction.Ignored
                }
            }
        }
    }
}

sealed interface SkinAction {
    data class Button(val button: VirtualButton) : SkinAction
    data object Menu : SkinAction
    data object FastForward : SkinAction
    data object ToggleFastForward : SkinAction
    data object QuickSave : SkinAction
    data object QuickLoad : SkinAction
    data object Ignored : SkinAction
}

/** What a single touch does on an on-screen controller. */
data class GamepadTouchSample(
    val buttons: Set<VirtualButton> = emptySet(),
    val holdFastForward: Boolean = false,
    val toggleFastForward: Boolean = false,
    val menu: Boolean = false,
    val quickSave: Boolean = false,
    val quickLoad: Boolean = false,
    /** Analog deflection in -1..1 (x right, y down). */
    val analogX: Float? = null,
    val analogY: Float? = null,
    /** Touch-screen pointer in normalized core output coordinates (-1..1). */
    val pointerX: Float? = null,
    val pointerY: Float? = null,
)

/** Maps touches on an on-screen controller to inputs. */
interface GamepadTouchMapper {
    /** The directional control a touch beginning at the point locks onto, if any. */
    fun lockTarget(x: Float, y: Float): Int?
    fun sample(x: Float, y: Float, locked: Int?): GamepadTouchSample
    fun claims(x: Float, y: Float): Boolean
}

/** Absolute placement of a skin representation in a container (view pixels). */
data class DeltaSkinLayout(
    val system: GameSystem,
    val skin: DeltaSkin,
    val representation: DeltaSkin.Representation,
    val controllerFrame: Box,
    val screens: List<PlacedScreen>,
    val items: List<PlacedItem>,
    val sourceSize: SizeF,
) : GamepadTouchMapper {
    data class PlacedItem(val item: DeltaSkin.Item, val frame: Box, val extendedFrame: Box)
    data class PlacedScreen(val frame: Box, val inputFrame: Box?)

    override fun lockTarget(x: Float, y: Float): Int? {
        items.forEachIndexed { index, placed ->
            if (placed.item.kind != DeltaSkin.ItemKind.BUTTONS && placed.extendedFrame.contains(x, y)) return index
        }
        return null
    }

    override fun sample(x: Float, y: Float, locked: Int?): GamepadTouchSample {
        val acc = SampleAccumulator()
        if (locked != null && locked in items.indices) {
            apply(items[locked], x, y, acc)
            return acc.build()
        }
        for (placed in items) {
            if (placed.extendedFrame.contains(x, y)) apply(placed, x, y, acc)
        }
        return acc.build()
    }

    override fun claims(x: Float, y: Float): Boolean =
        items.any { it.extendedFrame.inset(-6f, -6f).contains(x, y) }

    private class SampleAccumulator {
        val buttons = LinkedHashSet<VirtualButton>()
        var holdFF = false
        var toggleFF = false
        var menu = false
        var quickSave = false
        var quickLoad = false
        var ax: Float? = null
        var ay: Float? = null
        var px: Float? = null
        var py: Float? = null
        fun build() = GamepadTouchSample(buttons, holdFF, toggleFF, menu, quickSave, quickLoad, ax, ay, px, py)
    }

    private fun apply(placed: PlacedItem, x: Float, y: Float, acc: SampleAccumulator) {
        val item = placed.item
        val frame = placed.frame
        when (item.kind) {
            DeltaSkin.ItemKind.BUTTONS -> for (input in item.inputs) {
                when (val a = DeltaSkin.action(input, system)) {
                    is SkinAction.Button -> acc.buttons += a.button
                    SkinAction.Menu -> acc.menu = true
                    SkinAction.FastForward -> acc.holdFF = true
                    SkinAction.ToggleFastForward -> acc.toggleFF = true
                    SkinAction.QuickSave -> acc.quickSave = true
                    SkinAction.QuickLoad -> acc.quickLoad = true
                    SkinAction.Ignored -> Unit
                }
            }
            DeltaSkin.ItemKind.DPAD -> {
                val dirs = DPadGeometry.directions(x - frame.x, y - frame.y, frame.width, frame.height, clampToRadius = false)
                for (d in dirs) {
                    val key = when (d) {
                        VirtualButton.UP -> "up"
                        VirtualButton.DOWN -> "down"
                        VirtualButton.LEFT -> "left"
                        else -> "right"
                    }
                    val input = item.directions[key] ?: continue
                    val a = DeltaSkin.action(input, system)
                    if (a is SkinAction.Button) acc.buttons += a.button
                }
            }
            DeltaSkin.ItemKind.THUMBSTICK -> {
                val radius = max(min(frame.width, frame.height) / 2f, 1f)
                var dx = (x - frame.midX) / radius
                var dy = (y - frame.midY) / radius
                val length = sqrt(dx * dx + dy * dy)
                if (length > 1f) {
                    dx /= length
                    dy /= length
                }
                acc.ax = dx
                acc.ay = dy
            }
            DeltaSkin.ItemKind.TOUCH_SCREEN -> {
                val (px, py) = pointer(x, y, frame)
                acc.px = px
                acc.py = py
            }
        }
    }

    /** Converts a point in a touch-screen item to normalized (-1..1) core output coordinates. */
    fun pointer(x: Float, y: Float, itemFrame: Box): Pair<Float, Float> {
        val screen = screens.maxByOrNull { it.frame.intersection(itemFrame).area }
        val target = if (screen != null && screen.frame.intersects(itemFrame)) screen.frame else itemFrame
        val input = screen?.inputFrame ?: Box(0f, 0f, sourceSize.width, sourceSize.height)
        val fx = ((x - target.x) / max(target.width, 1f)).coerceIn(0f, 1f)
        val fy = ((y - target.y) / max(target.height, 1f)).coerceIn(0f, 1f)
        val sx = input.x + fx * input.width
        val sy = input.y + fy * input.height
        return Pair(sx / max(sourceSize.width, 1f) * 2f - 1f, sy / max(sourceSize.height, 1f) * 2f - 1f)
    }

    companion object {
        /**
         * Lays out [skin] like DeltaCore's `GameViewController.viewDidLayoutSubviews`:
         * - Portrait without controller-placed screens: skin pinned to the bottom edge at full
         *   width; the game is aspect-fit and centered in the area above it.
         * - Otherwise (landscape, DS): skin aspect-fit in the container; screens placed by their
         *   normalized `outputFrame` inside the skin, or aspect-fit in the container.
         */
        fun compute(
            skin: DeltaSkin,
            system: GameSystem,
            containerWidth: Float,
            containerHeight: Float,
            sourceWidth: Float,
            sourceHeight: Float,
            topInset: Float = 0f,
            controllerVisible: Boolean = true,
        ): DeltaSkinLayout? {
            val portrait = containerWidth <= containerHeight
            if (containerWidth <= 0f || containerHeight <= 0f) return null
            val rep = skin.representation(portrait) ?: return null
            val bounds = Box(0f, 0f, containerWidth, containerHeight)
            val source = if (sourceWidth > 0 && sourceHeight > 0) SizeF(sourceWidth, sourceHeight) else SizeF(4f, 3f)

            val controllerFrame: Box
            var available: Box
            if (!controllerVisible) {
                controllerFrame = Box(0f, containerHeight, containerWidth, 0f)
                available = bounds
            } else if (portrait && !rep.hasControllerPlacedScreens) {
                val height = (containerWidth / rep.mappingSize.width) * rep.mappingSize.height
                controllerFrame = Box(0f, containerHeight - height, containerWidth, height)
                available = Box(0f, 0f, containerWidth, max(0f, containerHeight - height))
            } else {
                controllerFrame = aspectFit(rep.mappingSize, bounds)
                available = bounds
            }
            if (portrait && topInset > 0f && aspectFit(source, available).y < topInset &&
                available.height - topInset > available.width * 0.5f
            ) {
                available = Box(available.x, available.y + topInset, available.width, available.height - topInset)
            }

            val screens = ArrayList<PlacedScreen>()
            if (controllerVisible && rep.screens.isNotEmpty()) {
                for (screen in rep.screens) {
                    val output = screen.outputFrame
                    val frame = if (output != null && screen.controllerPlacement) {
                        denormalize(output, controllerFrame)
                    } else {
                        val aspect = screen.inputFrame?.let { SizeF(it.width, it.height) } ?: source
                        aspectFit(aspect, available)
                    }
                    screens += PlacedScreen(frame.integral(), screen.inputFrame)
                }
            } else {
                screens += PlacedScreen(aspectFit(source, available).integral(), null)
            }

            val items = if (controllerVisible) {
                rep.items.map { PlacedItem(it, denormalize(it.frame, controllerFrame), denormalize(it.extendedFrame, controllerFrame)) }
            } else {
                emptyList()
            }
            return DeltaSkinLayout(system, skin, rep, controllerFrame, screens, items, source)
        }

        fun aspectFit(aspect: SizeF, rect: Box): Box {
            if (aspect.width <= 0 || aspect.height <= 0 || rect.width <= 0 || rect.height <= 0) return rect
            val scale = min(rect.width / aspect.width, rect.height / aspect.height)
            val w = aspect.width * scale
            val h = aspect.height * scale
            return Box(rect.midX - w / 2f, rect.midY - h / 2f, w, h)
        }

        fun denormalize(r: Box, frame: Box) = Box(
            frame.x + r.x * frame.width,
            frame.y + r.y * frame.height,
            r.width * frame.width,
            r.height * frame.height,
        )
    }
}
