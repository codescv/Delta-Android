package com.codescv.delta.skins.procedural

import com.codescv.delta.model.DPadGeometry
import com.codescv.delta.model.GamepadLayout
import com.codescv.delta.model.VirtualButton
import com.codescv.delta.skins.Box
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A point in layout points. */
data class Pt(val x: Float, val y: Float)

/**
 * Port of `ConsoleControlsLayout` (`Skins/GBASkin.swift:189-386`): procedural placement and
 * multi-touch hit testing of the D-Pad, face / shoulder buttons, Start / Select, Menu and
 * Fast Forward. All values are in layout points (Android dp).
 */
data class ConsoleControlsLayout(
    val dpad: Box,
    /** Face buttons in insertion order. */
    val faceButtons: Map<VirtualButton, Box>,
    val shoulderButtons: Map<VirtualButton, Box>,
    val start: Box,
    val select: Box,
    val menu: Box,
    val fastForward: Box,
    val powerLED: Pt,
    val badgeAnchor: Pt,
    val speaker: Box,
) {
    val allTouchTargets: List<Box>
        get() = listOf(dpad, start, select, menu, fastForward) + faceButtons.values + shoulderButtons.values

    /** Whether a touch that begins at the point should lock onto the D-Pad. */
    fun touchBeginsInDPad(x: Float, y: Float): Boolean = dpad.inset(-DPAD_LOCK_SLOP, -DPAD_LOCK_SLOP).contains(x, y)

    /** Whether the point hits the Menu button. */
    fun menuContains(x: Float, y: Float): Boolean = menu.inset(-MENU_SLOP, -MENU_SLOP).contains(x, y)

    /** Whether the point lies on (or near) any on-screen control; touches elsewhere pass through. */
    fun hitRegionContains(x: Float, y: Float): Boolean {
        if (touchBeginsInDPad(x, y) || menuContains(x, y)) return true
        val slop = SMALL_BUTTON_SLOP + 8f
        return allTouchTargets.any { it.inset(-slop, -slop).contains(x, y) }
    }

    data class Hit(val buttons: Set<VirtualButton>, val holdFastForward: Boolean)

    /**
     * The buttons pressed by a single touch at the point.
     * @param dpadLocked the touch began on the D-Pad: routed exclusively to it, no outer cutoff.
     */
    fun buttons(x: Float, y: Float, dpadLocked: Boolean, deadZone: Float = DPadGeometry.DEFAULT_DEAD_ZONE): Hit {
        val lx = x - dpad.minX
        val ly = y - dpad.minY
        if (dpadLocked) {
            return Hit(DPadGeometry.directions(lx, ly, dpad.width, dpad.height, deadZone, clampToRadius = false), false)
        }
        val result = LinkedHashSet<VirtualButton>()
        if (dpad.contains(x, y)) {
            result += DPadGeometry.directions(lx, ly, dpad.width, dpad.height, deadZone)
        }
        for ((button, rect) in faceButtons) {
            val radius = min(rect.width, rect.height) / 2f
            val dx = x - rect.midX
            val dy = y - rect.midY
            if (sqrt(dx * dx + dy * dy) <= radius * FACE_HIT_RADIUS_SCALE) result += button
        }
        val slop = SMALL_BUTTON_SLOP
        for ((button, rect) in shoulderButtons) {
            if (rect.inset(-slop, -slop).contains(x, y)) result += button
        }
        if (select.width > 0f && select.inset(-slop, -slop).contains(x, y)) result += VirtualButton.SELECT
        if (start.width > 0f && start.inset(-slop, -slop).contains(x, y)) result += VirtualButton.START
        val holdFastForward = fastForward.inset(-slop, -slop).contains(x, y)
        return Hit(result, holdFastForward)
    }

    companion object {
        /** Extra slop around the D-Pad within which a touch-down locks onto the D-Pad. */
        const val DPAD_LOCK_SLOP = 22f
        /** Face-button hit radius as a multiple of the visual radius (thumb-bridge chords). */
        const val FACE_HIT_RADIUS_SCALE = 1.36f
        /** Hit slop around capsule / small round buttons (L, R, Start, Select, Fast Forward). */
        const val SMALL_BUTTON_SLOP = 10f
        /** Hit slop around the Menu button. */
        const val MENU_SLOP = 6f

        private fun circle(cx: Float, cy: Float, d: Float) = Box(cx - d / 2f, cy - d / 2f, d, d)

        fun compute(rect: Box, gamepad: GamepadLayout, landscape: Boolean): ConsoleControlsLayout {
            val w = rect.width
            val h = rect.height
            val unit = if (landscape) min(h * 0.27f, 150f) else minOf(w * 0.36f, max(h * 0.42f, 1f), 170f)
            val dpadSize = unit
            val button = unit * (if (gamepad.faceButtons.size > 2) 0.40f else 0.46f)
            val smallW = max(unit * 0.42f, 44f)
            val smallH = max(unit * 0.2f, 30f)
            val shoulderW = max(unit * 0.62f, 60f)
            val shoulderH = max(unit * 0.26f, 36f)

            val dpadCenter: Pt
            val faceCenter: Pt
            if (landscape) {
                dpadCenter = Pt(rect.minX + max(w * 0.11f, dpadSize * 0.65f), rect.maxY - h * 0.32f)
                faceCenter = Pt(rect.maxX - max(w * 0.11f, dpadSize * 0.65f), rect.maxY - h * 0.32f)
            } else {
                dpadCenter = Pt(rect.minX + w * 0.25f, rect.minY + h * 0.44f)
                faceCenter = Pt(rect.minX + w * 0.75f, rect.minY + h * 0.44f)
            }
            val dpad = Box(dpadCenter.x - dpadSize / 2f, dpadCenter.y - dpadSize / 2f, dpadSize, dpadSize)

            val faces = LinkedHashMap<VirtualButton, Box>()
            val spread = button * 1.04f
            when (gamepad.faceButtons.size) {
                4 -> {
                    faces[VirtualButton.X] = circle(faceCenter.x, faceCenter.y - spread, button)
                    faces[VirtualButton.A] = circle(faceCenter.x + spread, faceCenter.y, button)
                    faces[VirtualButton.B] = circle(faceCenter.x, faceCenter.y + spread, button)
                    faces[VirtualButton.Y] = circle(faceCenter.x - spread, faceCenter.y, button)
                }
                3 -> gamepad.faceButtons.forEachIndexed { k, b ->
                    val dx = (k - 1) * button * 1.12f
                    val dy = (1 - k) * button * 0.28f
                    faces[b] = circle(faceCenter.x + dx, faceCenter.y + dy, button)
                }
                else -> {
                    val angle = 25.0 * Math.PI / 180.0
                    val half = button * 0.66f
                    val dx = cos(angle).toFloat() * half
                    val dy = sin(angle).toFloat() * half
                    faces[VirtualButton.B] = circle(faceCenter.x - dx, faceCenter.y + dy, button)
                    faces[VirtualButton.A] = circle(faceCenter.x + dx, faceCenter.y - dy, button)
                }
            }

            val shoulders = LinkedHashMap<VirtualButton, Box>()
            val shoulderY = if (landscape) rect.minY + h * 0.06f else rect.minY + 8f
            if (gamepad.shoulderButtons.isNotEmpty()) {
                val l = Box(rect.minX + 12f, shoulderY, shoulderW, shoulderH)
                val r = Box(rect.maxX - 12f - shoulderW, shoulderY, shoulderW, shoulderH)
                shoulders[VirtualButton.L] = l
                shoulders[VirtualButton.R] = r
                if (VirtualButton.L2 in gamepad.shoulderButtons) {
                    shoulders[VirtualButton.L2] = l.copy(x = l.x + shoulderW + 8f)
                    shoulders[VirtualButton.R2] = r.copy(x = r.x - (shoulderW + 8f))
                }
            }

            val bottomRowY = if (landscape) rect.maxY - smallH - 14f else rect.minY + h * 0.80f
            val select = Box(rect.midX - smallW - 8f, bottomRowY, smallW, smallH)
            val start = Box(rect.midX + 8f, bottomRowY, smallW, smallH)
            val roundSmall = max(unit * 0.26f, 40f)
            val ffCenter = if (landscape) {
                Pt(rect.midX - roundSmall * 0.9f, rect.minY + h * 0.08f + roundSmall / 2f)
            } else {
                val insetX = rect.minX + 12f + shoulderW + 12f + roundSmall / 2f
                var y = shoulderY + shoulderH / 2f
                if (VirtualButton.L2 in gamepad.shoulderButtons) y = shoulderY + shoulderH + 8f + roundSmall / 2f
                Pt(insetX, y)
            }
            val menuCenter = Pt(rect.maxX - (ffCenter.x - rect.minX), ffCenter.y)
            val fastForward = circle(ffCenter.x, ffCenter.y, roundSmall)
            val menu = circle(menuCenter.x, menuCenter.y, roundSmall)

            val ledY = if (landscape) rect.minY + 10f else rect.minY + max(h * 0.13f, 60f)
            val powerLED = Pt(rect.minX + w * 0.75f, ledY)
            val badgeAnchor = Pt(rect.minX + w * 0.25f, ledY)
            val speakerSize = unit * 0.5f
            val speaker = Box(rect.maxX - speakerSize - 18f, rect.maxY - speakerSize - 14f, speakerSize, speakerSize)

            return ConsoleControlsLayout(
                dpad = dpad, faceButtons = faces, shoulderButtons = shoulders, start = start, select = select,
                menu = menu, fastForward = fastForward, powerLED = powerLED, badgeAnchor = badgeAnchor,
                speaker = speaker,
            )
        }
    }
}
