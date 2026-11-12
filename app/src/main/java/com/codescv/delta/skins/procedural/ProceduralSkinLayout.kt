package com.codescv.delta.skins.procedural

import android.content.res.Resources
import com.codescv.delta.model.GameSystem
import com.codescv.delta.skins.Box
import com.codescv.delta.skins.GamepadTouchSample
import com.codescv.delta.skins.ProceduralLayout

/**
 * Placement of a built-in procedural skin in a container: Delta's `ViewportLayout` (game
 * screen / bezel / controls region) plus `ConsoleControlsLayout` (controls), with the
 * `ConsoleControlsLayout: GamepadTouchMapper` touch mapping.
 *
 * Geometry is computed in layout points (dp, the Android equivalent of iOS points) and
 * exposed in view pixels via [density]; touch coordinates passed to the mapper are pixels.
 *
 * - Portrait: painted console shell; game at the top inside the bezel, controls below.
 * - Landscape: game aspect-fit full height, translucent controls over the sides.
 * - NDS: touches on the game rect that aren't on a control drive the touch screen pointer.
 */
class ProceduralSkinLayout(
    val skin: ProceduralSkin,
    val system: GameSystem,
    /** Pixels per layout point. */
    val density: Float,
    /** Container size in points. */
    val widthPt: Float,
    val heightPt: Float,
    /** Viewport in points. */
    val viewport: ViewportLayout,
    /** Controls in points. */
    val controls: ConsoleControlsLayout,
    /** Region the controls were laid out in, in points. */
    val controlsRegion: Box,
) : ProceduralLayout {
    val portrait: Boolean get() = viewport.portrait

    /** Game rect (both DS screens) in pixels. */
    val gameRect: Box = viewport.gameRect.scaled(density, density)

    /** Region below the bezel (portrait) or the safe area (landscape), in pixels. */
    val controlsRect: Box = (if (portrait) viewport.controlsRect else controlsRegion).scaled(density, density)

    override val screens: List<Box> = if (system == GameSystem.NDS) {
        val half = gameRect.height / 2f
        listOf(Box(gameRect.x, gameRect.y, gameRect.width, half), Box(gameRect.x, gameRect.y + half, gameRect.width, half))
    } else {
        listOf(gameRect)
    }

    override val controllerFrame: Box get() = controlsRect

    override val translucent: Boolean get() = !portrait

    val hasTouchScreen: Boolean get() = system == GameSystem.NDS

    override fun lockTarget(x: Float, y: Float): Int? {
        val px = x / density
        val py = y / density
        if (controls.touchBeginsInDPad(px, py)) return LOCK_DPAD
        if (hasTouchScreen && !controls.hitRegionContains(px, py) && viewport.gameRect.contains(px, py)) return LOCK_SCREEN
        return null
    }

    override fun sample(x: Float, y: Float, locked: Int?): GamepadTouchSample {
        val px = x / density
        val py = y / density
        if (locked == LOCK_SCREEN) {
            val g = viewport.gameRect
            val nx = ((px - g.x) / maxOf(g.width, 1f) * 2f - 1f).coerceIn(-1f, 1f)
            val ny = ((py - g.y) / maxOf(g.height, 1f) * 2f - 1f).coerceIn(-1f, 1f)
            return GamepadTouchSample(pointerX = nx, pointerY = ny)
        }
        val hit = controls.buttons(px, py, dpadLocked = locked == LOCK_DPAD)
        return GamepadTouchSample(
            buttons = hit.buttons,
            holdFastForward = hit.holdFastForward,
            menu = locked == null && controls.menuContains(px, py),
        )
    }

    override fun claims(x: Float, y: Float): Boolean {
        val px = x / density
        val py = y / density
        return controls.hitRegionContains(px, py) || (hasTouchScreen && viewport.gameRect.contains(px, py))
    }

    companion object {
        const val LOCK_DPAD = 0
        const val LOCK_SCREEN = 1

        /** The device's display density, or 1 when unavailable (JVM unit tests). */
        fun systemDensity(): Float =
            runCatching { Resources.getSystem().displayMetrics.density }.getOrNull()?.takeIf { it > 0f } ?: 1f

        /**
         * @param width container width in pixels.
         * @param sourceWidth core video width (with [sourceHeight] gives the aspect ratio;
         *   falls back to [GameSystem.defaultAspectRatio]).
         * @param density pixels per point.
         * @param topInset safe-area insets in pixels.
         */
        fun compute(
            skin: ProceduralSkin,
            system: GameSystem,
            width: Float,
            height: Float,
            sourceWidth: Float,
            sourceHeight: Float,
            density: Float = systemDensity(),
            topInset: Float = 0f,
            bottomInset: Float = 0f,
            leftInset: Float = 0f,
            rightInset: Float = 0f,
        ): ProceduralSkinLayout? {
            if (width <= 0f || height <= 0f) return null
            val d = if (density > 0f) density else 1f
            val w = width / d
            val h = height / d
            val insets = LayoutInsets(topInset / d, leftInset / d, bottomInset / d, rightInset / d)
            val aspect = if (sourceWidth > 0f && sourceHeight > 0f) {
                sourceWidth / sourceHeight
            } else {
                system.defaultAspectRatio.toFloat()
            }
            val viewport = ViewportLayout.compute(w, h, insets, aspect, displayScale = d)
            val portrait = w <= h
            val region = if (portrait) {
                viewport.controlsRect
            } else {
                Box(insets.leading, insets.top, w - insets.leading - insets.trailing, h - insets.top - insets.bottom)
            }
            val controls = ConsoleControlsLayout.compute(region, system.gamepadLayout, landscape = !portrait)
            return ProceduralSkinLayout(skin, system, d, w, h, viewport, controls, region)
        }
    }
}
