package com.codescv.delta.skins.procedural

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.codescv.delta.model.GameSystem
import com.codescv.delta.model.VirtualButton
import com.codescv.delta.skins.Box
import com.codescv.delta.skins.ProceduralLayout
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Compose color of a [SkinColor]. */
val SkinColor.color: Color get() = Color(argb.toInt())

/** SwiftUI `.opacity(f)` on a color: multiplies its alpha. */
internal fun SkinColor.opacity(f: Float): Color = color.let { it.copy(alpha = it.alpha * f) }

private val UnitDensity = Density(1f, 1f)

/**
 * Compose `Canvas` port of Delta's `ConsoleShellView` (portrait shell) and the visual half of
 * `VirtualGamepadView` (`DPadControl` / `PressableControl`) for a built-in procedural skin.
 *
 * The game screen rect is cleared to transparent so the GL game view underneath shows through
 * (the black 3-pt inner bezel around it is kept). In landscape only the translucent controls
 * are drawn (the caller provides the black background).
 *
 * @param pressed buttons currently pressed by touches (drawn pressed / D-Pad arrows lit).
 * @param held buttons latched by the pause-menu "Hold Buttons" feature (drawn in `active`).
 * @param fastForwardHeld whether a touch is holding the fast-forward button.
 * @param controlsVisible draw the on-screen controls (false when a gamepad is connected).
 */
@Composable
fun ProceduralSkinArtwork(
    layout: ProceduralLayout,
    optionId: String,
    system: GameSystem,
    pressed: Set<VirtualButton>,
    held: Set<VirtualButton>,
    fastForwardActive: Boolean,
    fastForwardMultiplier: Int,
    modifier: Modifier = Modifier,
    fastForwardHeld: Boolean = false,
    controlsVisible: Boolean = true,
) {
    val l = layout as? ProceduralSkinLayout ?: return
    val skin = remember(optionId, l.skin) {
        SystemSkinCatalog.skin(optionId, l.skin.family) ?: l.skin
    }
    val measurer = rememberTextMeasurer(cacheSize = 32)
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        withTransform({ scale(l.density, l.density, pivot = Offset.Zero) }) {
            val painter = ArtworkPainter(this, measurer)
            if (l.portrait) {
                painter.drawShell(l, skin, system)
                // Punch out the game screen so the video underneath is visible.
                val g = l.viewport.gameRect
                drawRect(Color.Black, Offset(g.x, g.y), Size(g.width, g.height), blendMode = BlendMode.Clear)
            }
            if (controlsVisible) {
                painter.drawControls(
                    l.controls, system, skin, translucent = !l.portrait, pressed = pressed, held = held,
                    fastForwardActive = fastForwardActive, fastForwardMultiplier = fastForwardMultiplier,
                    fastForwardHeld = fastForwardHeld,
                )
            }
        }
    }
}

/** Draws in layout points (the caller applies the density transform). */
private class ArtworkPainter(private val ds: DrawScope, private val measurer: TextMeasurer) {

    // region Text

    private fun measure(text: String, style: TextStyle) = measurer.measure(
        AnnotatedString(text), style, overflow = TextOverflow.Clip, softWrap = false, maxLines = 1,
        density = UnitDensity,
    )

    /** Draws single-line text; [anchor] 0 = leading edge at x, 0.5 = centered on x. */
    fun text(text: String, x: Float, y: Float, style: TextStyle, anchor: Float = 0.5f) {
        val m = measure(text, style)
        ds.drawText(m, topLeft = Offset(x - m.size.width * anchor, y - m.size.height / 2f))
    }

    /** Text centered in [rect], scaled down (to at most 50 %) to fit its width. */
    fun fittedText(text: String, rect: Box, fontSize: Float, color: Color) {
        var style = TextStyle(color = color, fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
        var m = measure(text, style)
        if (m.size.width > rect.width && m.size.width > 0) {
            val f = (rect.width / m.size.width).coerceAtLeast(0.5f)
            style = style.copy(fontSize = (fontSize * f).sp)
            m = measure(text, style)
        }
        ds.drawText(m, topLeft = Offset(rect.midX - m.size.width / 2f, rect.midY - m.size.height / 2f))
    }

    // endregion

    // region Shapes

    private fun Box.offset() = Offset(x, y)
    private fun Box.size() = Size(width, height)

    private fun roundRect(r: Box, radius: Float, color: Color) =
        ds.drawRoundRect(color, r.offset(), r.size(), CornerRadius(clampRadius(r, radius)))

    private fun roundRect(r: Box, radius: Float, brush: Brush) =
        ds.drawRoundRect(brush, r.offset(), r.size(), CornerRadius(clampRadius(r, radius)))

    private fun strokeRoundRect(r: Box, radius: Float, color: Color, width: Float) =
        ds.drawRoundRect(color, r.offset(), r.size(), CornerRadius(clampRadius(r, radius)), style = Stroke(width))

    private fun clampRadius(r: Box, radius: Float) = minOf(radius, r.width / 2f, r.height / 2f).coerceAtLeast(0f)

    private fun oval(r: Box, color: Color) = ds.drawOval(color, r.offset(), r.size())

    private fun dot(x: Float, y: Float, r: Float, color: Color) = ds.drawCircle(color, r, Offset(x, y))

    private fun line(ax: Float, ay: Float, bx: Float, by: Float, color: Color, width: Float = 3f) =
        ds.drawLine(color, Offset(ax, ay), Offset(bx, by), strokeWidth = width, cap = StrokeCap.Round)

    /**
     * Cheap soft drop shadow (SwiftUI `.shadow(color: .black.opacity(alpha), radius:, y:)`):
     * a few inflated, offset translucent copies of the shape.
     */
    private fun shadow(alpha: Float, radius: Float, dy: Float, shape: (inflate: Float, dy: Float, color: Color) -> Unit) {
        if (alpha <= 0f) return
        val steps = 3
        for (i in steps downTo 1) {
            val inflate = radius * i / steps - radius * 0.35f
            shape(inflate, dy, Color.Black.copy(alpha = alpha * 0.32f))
        }
    }

    // endregion

    // region ConsoleShellView

    fun drawShell(l: ProceduralSkinLayout, skin: ProceduralSkin, system: GameSystem) {
        val p = skin.palette
        val family = skin.family
        val viewport = l.viewport
        val controls = l.controls
        val w = l.widthPt
        val h = l.heightPt

        ds.drawRect(
            Brush.verticalGradient(listOf(p.shellTop.color, p.shellBottom.color), startY = 0f, endY = h),
            Offset.Zero, Size(w, h),
        )

        val sheenH = viewport.screenRegion.height * 0.55f
        roundRect(
            Box(6f, 4f, w - 12f, sheenH), 28f,
            Brush.verticalGradient(
                listOf(p.shellHighlight.opacity(0.35f), p.shellHighlight.opacity(0f)), startY = 0f, endY = sheenH,
            ),
        )

        if (skin.drawsConsoleArt) {
            val cr = viewport.controlsRect
            for (side in 0..1) {
                val x = if (side == 0) 0f else w
                val dir = if (side == 0) 1f else -1f
                val grip = Path().apply {
                    moveTo(x, cr.minY + cr.height * 0.35f)
                    quadraticBezierTo(x + dir * w * 0.16f, cr.midY + 60f, x, h)
                }
                ds.drawPath(grip, p.shellShadow.opacity(0.55f), style = Stroke(3f))
            }
        }

        val bezelRect = viewport.bezelRect
        val bezelRadius = if (family == "nes") 6f else 18f
        roundRect(bezelRect, bezelRadius, p.bezel.color)
        strokeRoundRect(bezelRect, bezelRadius, p.shellShadow.opacity(0.8f), 2f)

        if (family == "nds") {
            drawDualScreenHinge(viewport, w, p)
        } else {
            roundRect(viewport.gameRect.inset(-3f, -3f), 3f, Color.Black)
        }

        if (family == "nes") {
            listOf(p.accent, p.accentSecondary).forEachIndexed { k, c ->
                val y = bezelRect.minY + 5f + k * 4f
                ds.drawLine(c.color, Offset(bezelRect.minX + 12f, y), Offset(bezelRect.maxX - 12f, y), strokeWidth = 2f)
            }
        }

        val labelY = viewport.gameRect.maxY + ViewportLayout.BEZEL_PADDING * 0.55f + 4f
        val badgeText = "DELTA · " + skin.badge
        text(
            badgeText, bezelRect.minX + 14f, labelY,
            TextStyle(
                color = p.bezelText.color, fontSize = (if (badgeText.length > 28) 8.5f else 10f).sp,
                fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic,
            ),
            anchor = 0f,
        )

        if (family == "genesis") {
            ds.drawRect(p.accent.opacity(0.9f), Offset(0f, bezelRect.maxY + 8f), Size(w, 3f))
        }

        oval(controls.dpad.inset(-8f, -8f), p.shellShadow.opacity(0.28f))
        faceBounds(controls)?.let { faces ->
            roundRect(faces.inset(-10f, -10f), faces.height, p.shellShadow.opacity(0.28f))
        }

        val led = controls.powerLED
        ds.drawCircle(
            Brush.radialGradient(listOf(p.led.opacity(0.55f), p.led.opacity(0f)), center = Offset(led.x, led.y), radius = 12f),
            radius = 12f, center = Offset(led.x, led.y),
        )
        dot(led.x, led.y, 4f, p.led.color)
        text("POWER", led.x + 26f, led.y, TextStyle(color = p.accent.color, fontSize = 8.sp, fontWeight = FontWeight.Bold))
        text(
            "Δ " + skin.consoleLogo, controls.badgeAnchor.x, controls.badgeAnchor.y,
            TextStyle(
                color = p.accent.color, fontSize = (if (skin.consoleLogo.length > 14) 11f else 13f).sp,
                fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
            ),
        )

        drawSpeaker(controls.speaker, skin.speakerStyle, p)
    }

    /** Nintendo DS: framed top / bottom screens with a hinge bar and hinge rings at the middle. */
    private fun drawDualScreenHinge(viewport: ViewportLayout, canvasWidth: Float, p: SkinPalette) {
        val game = viewport.gameRect
        val midY = game.midY
        val top = Box(game.minX, game.minY, game.width, game.height / 2f)
        val bottom = Box(game.minX, midY, game.width, game.height / 2f)
        for (screen in listOf(top, bottom)) {
            val frame = screen.inset(-3f, -3f)
            roundRect(frame, 3f, Color.Black)
            strokeRoundRect(frame.inset(-2f, -2f), 4f, p.bezelText.opacity(0.35f), 1f)
        }
        val barHeight = 8f
        val bar = Box(0f, midY - barHeight / 2f, canvasWidth, barHeight)
        roundRect(
            bar, barHeight / 2f,
            Brush.verticalGradient(listOf(p.shellHighlight.color, p.shellShadow.color), startY = bar.minY, endY = bar.maxY),
        )
        for (x in listOf(viewport.bezelRect.minX + 6f, viewport.bezelRect.maxX - 6f)) {
            dot(x, midY, 7f, p.shellShadow.color)
            ds.drawCircle(p.accent.color, 5f, Offset(x, midY), style = Stroke(1.5f))
        }
    }

    private fun drawSpeaker(speaker: Box, style: SpeakerStyle, p: SkinPalette) {
        val ink = p.shellShadow.opacity(0.7f)
        when (style) {
            SpeakerStyle.AGB_DIAGONAL -> for (k in 0 until 6) {
                val t = k / 5f
                line(
                    speaker.minX + t * speaker.width * 0.6f, speaker.maxY,
                    speaker.minX + speaker.width * 0.4f + t * speaker.width * 0.6f, speaker.minY, ink,
                )
            }
            SpeakerStyle.SP_DOTS -> for (row in 0 until 4) for (col in 0 until 4) {
                dot(
                    speaker.minX + (col + 0.5f) * speaker.width / 4f,
                    speaker.minY + (row + 0.5f) * speaker.height / 4f, 2.5f, ink,
                )
            }
            SpeakerStyle.NES_HORIZONTAL -> for (k in 0 until 5) {
                val y = speaker.minY + (k + 0.5f) * speaker.height / 5f
                line(speaker.minX, y, speaker.maxX, y, ink, 2.5f)
            }
            SpeakerStyle.SNES_VENT -> for (k in 0 until 6) {
                val x = speaker.minX + (k + 0.5f) * speaker.width / 6f
                line(x, speaker.minY, x, speaker.maxY, ink, 3f)
            }
            SpeakerStyle.N64_PRONG -> {
                val cx = speaker.midX
                val cy = speaker.midY
                val r = min(speaker.width, speaker.height) / 2f
                for (angle in listOf(-90.0, 30.0, 150.0)) {
                    val rad = Math.toRadians(angle)
                    line(cx, cy, cx + cos(rad).toFloat() * r, cy + sin(rad).toFloat() * r, ink, 5f)
                }
                dot(cx, cy, 4f, ink)
            }
            SpeakerStyle.GBC_DOTS -> for (row in 0 until 3) {
                val count = if (row == 1) 4 else 3
                val offset = if (row == 1) 0.5f else 1f
                for (col in 0 until count) {
                    dot(
                        speaker.minX + (col + offset) * speaker.width / 4f,
                        speaker.minY + (row + 0.5f) * speaker.height / 3f, 3f, ink,
                    )
                }
            }
            SpeakerStyle.DS_DUAL_HINGE -> for (row in 0 until 2) for (col in 0 until 5) {
                dot(speaker.minX + (col + 0.5f) * speaker.width / 5f, speaker.midY + (row - 0.5f) * 8f, 1.8f, ink)
            }
            SpeakerStyle.GENESIS_ARC -> for (k in 1..3) {
                val r = k * min(speaker.width, speaker.height) / 3.2f
                ds.drawArc(
                    ink, startAngle = 180f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(speaker.maxX - r, speaker.maxY - r), size = Size(2 * r, 2 * r),
                    style = Stroke(2.5f, cap = StrokeCap.Round),
                )
            }
            SpeakerStyle.CHIP8_GRILLE -> for (row in 0 until 4) for (col in 0 until 4) {
                val cell = speaker.width / 4f
                ds.drawRect(
                    ink,
                    Offset(speaker.minX + col * cell + cell * 0.2f, speaker.minY + row * speaker.height / 4f + cell * 0.2f),
                    Size(cell * 0.6f, cell * 0.6f),
                )
            }
        }
    }

    private fun faceBounds(controls: ConsoleControlsLayout): Box? {
        val rects = controls.faceButtons.values
        if (rects.isEmpty()) return null
        val l = rects.minOf { it.minX }
        val t = rects.minOf { it.minY }
        val r = rects.maxOf { it.maxX }
        val b = rects.maxOf { it.maxY }
        return Box(l, t, r - l, b - t)
    }

    // endregion

    // region VirtualGamepadView (visual controls)

    fun drawControls(
        controls: ConsoleControlsLayout,
        system: GameSystem,
        skin: ProceduralSkin,
        translucent: Boolean,
        pressed: Set<VirtualButton>,
        held: Set<VirtualButton>,
        fastForwardActive: Boolean,
        fastForwardMultiplier: Int,
        fastForwardHeld: Boolean,
    ) {
        val p = skin.palette
        val gamepad = system.gamepadLayout
        drawDPad(controls.dpad, p, translucent, pressed intersect VirtualButton.DPAD)

        for ((button, rect) in controls.faceButtons.entries.sortedBy { it.key.retroId }) {
            val isHeld = button in held
            val custom = skin.buttonFaceColors[button]
            val faceFill = custom ?: p.face
            pressable(
                rect, circle = true, label = gamepad.faceLabels[button] ?: button.name,
                fill = if (isHeld) p.active else faceFill,
                pressedFill = custom?.scaled(0.62f) ?: p.facePressed,
                icon = if (isHeld) p.activeIcon else (custom?.contrastingIcon ?: p.faceIcon),
                stroke = p.faceStroke, translucent = translucent, pressed = button in pressed,
            )
        }

        for ((button, rect) in controls.shoulderButtons.entries.sortedBy { it.key.retroId }) {
            val isHeld = button in held
            pressable(
                rect, circle = false, label = button.name,
                fill = if (isHeld) p.active else p.cap, pressedFill = p.capPressed,
                icon = if (isHeld) p.activeIcon else p.capIcon,
                stroke = p.faceStroke, translucent = translucent, pressed = button in pressed,
            )
        }

        if (gamepad.hasStartSelect) {
            pressable(
                controls.select, circle = false, label = "SELECT", fill = p.cap, pressedFill = p.capPressed,
                icon = p.capIcon, stroke = p.faceStroke, translucent = translucent, fontScale = 0.42f,
                pressed = VirtualButton.SELECT in pressed,
            )
            pressable(
                controls.start, circle = false, label = "START", fill = p.cap, pressedFill = p.capPressed,
                icon = p.capIcon, stroke = p.faceStroke, translucent = translucent, fontScale = 0.42f,
                pressed = VirtualButton.START in pressed,
            )
        }

        pressable(
            controls.fastForward, circle = true,
            label = if (fastForwardActive) "$fastForwardMultiplier×" else "▶▶",
            fill = if (fastForwardActive) p.active else p.cap, pressedFill = p.active,
            icon = if (fastForwardActive) p.activeIcon else p.capIcon,
            stroke = p.faceStroke, translucent = translucent, fontScale = 0.34f, pressed = fastForwardHeld,
        )

        pressable(
            controls.menu, circle = true, label = "MENU", fill = p.cap, pressedFill = p.capPressed,
            icon = p.capIcon, stroke = p.faceStroke, translucent = translucent, fontScale = 0.28f, pressed = false,
        )
    }

    /** `PressableControl`: circle or capsule button with a centered label. */
    private fun pressable(
        rect: Box,
        circle: Boolean,
        label: String,
        fill: SkinColor,
        pressedFill: SkinColor,
        icon: SkinColor,
        stroke: SkinColor,
        translucent: Boolean,
        fontScale: Float = 0.5f,
        pressed: Boolean,
    ) {
        if (rect.width <= 0f || rect.height <= 0f) return
        val fillColor = (if (pressed) pressedFill else fill).opacity(if (translucent) 0.55f else 1f)
        val radius = min(rect.width, rect.height) / 2f
        ds.scale(if (pressed) 0.94f else 1f, pivot = Offset(rect.midX, rect.midY)) {
            if (circle) {
                shadow(if (pressed) 0.1f else 0.35f, if (pressed) 1f else 3f, 2f) { inflate, dy, c ->
                    dot(rect.midX, rect.midY + dy, radius + inflate, c)
                }
                dot(rect.midX, rect.midY, radius, fillColor)
                ds.drawCircle(stroke.color, radius, Offset(rect.midX, rect.midY), style = Stroke(1.5f))
            } else {
                shadow(if (pressed) 0.1f else 0.3f, if (pressed) 1f else 2f, 1f) { inflate, dy, c ->
                    val r = Box(rect.x - inflate, rect.y - inflate + dy, rect.width + 2 * inflate, rect.height + 2 * inflate)
                    roundRect(r, r.height / 2f, c)
                }
                roundRect(rect, radius, fillColor)
                strokeRoundRect(rect, radius, stroke.color, 1f)
            }
            fittedText(label, rect, min(rect.width, rect.height) * fontScale, icon.color)
        }
    }

    /** `DPadControl`: ring, cross, center dimple and four direction arrows. */
    private fun drawDPad(rect: Box, p: SkinPalette, translucent: Boolean, active: Set<VirtualButton>) {
        val s = min(rect.width, rect.height)
        if (s <= 0f) return
        val cx = rect.midX
        val cy = rect.midY
        val arm = s * 0.34f
        dot(cx, cy, s / 2f, p.ring.opacity(if (translucent) 0.25f else 0.55f))

        val vertical = Box(cx - arm / 2f, cy - s * 0.46f, arm, s * 0.92f)
        val horizontal = Box(cx - s * 0.46f, cy - arm / 2f, s * 0.92f, arm)
        val corner = s * 0.05f
        shadow(0.4f, 3f, 2f) { inflate, dy, c ->
            for (b in listOf(vertical, horizontal)) {
                roundRect(Box(b.x - inflate, b.y - inflate + dy, b.width + 2 * inflate, b.height + 2 * inflate), corner + inflate, c)
            }
        }
        val capColor = p.cap.opacity(if (translucent) 0.6f else 1f)
        // One unioned path so translucent arms don't double up in the middle.
        fun rr(b: Box) = Path().apply { addRoundRect(RoundRect(b.x, b.y, b.maxX, b.maxY, CornerRadius(corner))) }
        val cross = Path().apply { op(rr(vertical), rr(horizontal), PathOperation.Union) }
        ds.drawPath(cross, capColor)
        dot(cx, cy, arm * 0.55f / 2f, p.capPressed.opacity(0.35f))

        val triW = arm * 0.34f
        val triH = arm * 0.26f
        val distance = s * 0.33f
        for ((dir, degrees) in listOf(
            VirtualButton.UP to 0f, VirtualButton.RIGHT to 90f, VirtualButton.DOWN to 180f, VirtualButton.LEFT to 270f,
        )) {
            val color = (if (dir in active) p.active else p.capIcon).color
            ds.rotate(degrees, pivot = Offset(cx, cy)) {
                // Up-pointing triangle centered `distance` above the center, rotated into place.
                translate(cx, cy - distance) {
                    val tri = Path().apply {
                        moveTo(0f, -triH / 2f)
                        lineTo(triW / 2f, triH / 2f)
                        lineTo(-triW / 2f, triH / 2f)
                        close()
                    }
                    drawPath(tri, color)
                }
            }
        }
    }

    // endregion
}
