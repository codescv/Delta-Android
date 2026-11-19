package com.codescv.delta.ui.game

import android.graphics.Bitmap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.codescv.delta.model.VirtualButton
import com.codescv.delta.skins.Box
import com.codescv.delta.skins.DPadDirection
import com.codescv.delta.skins.DPadFeedback
import com.codescv.delta.skins.DPadState
import com.codescv.delta.skins.DeltaSkin
import com.codescv.delta.skins.DeltaSkinLayout
import com.codescv.delta.skins.LoadedSkin
import com.codescv.delta.skins.SkinAction
import com.codescv.delta.skins.SkinRepository
import com.codescv.delta.ui.theme.DeltaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Rasterized artwork for one laid-out skin representation. */
private class SkinBitmaps(val background: ImageBitmap?, val items: List<ImageBitmap?>)

/**
 * Draws an official / imported `.deltaskin` (DeltaSkinControllerView): background artwork at
 * the controller frame + per-item assets, pressed = brightness −0.18 & scale 0.95, itemless
 * pressed highlight, thumbstick following the thumb, optional hit boxes / touches.
 * Opacity = `controllerOpacity` when the representation is translucent.
 */
@Composable
fun DeltaSkinArtwork(
    repo: SkinRepository,
    loaded: LoadedSkin,
    layout: DeltaSkinLayout,
    pressed: Set<VirtualButton>,
    held: Set<VirtualButton>,
    fastForwardHeld: Boolean,
    analog: Pair<Float, Float>,
    opacity: Float,
    showHitBoxes: Boolean,
    touches: List<Pair<Float, Float>>,
    modifier: Modifier = Modifier,
) {
    val rep = layout.representation
    val cf = layout.controllerFrame
    val bitmaps by produceState<SkinBitmaps?>(null, loaded.source.id, rep, cf.width.toInt(), cf.height.toInt()) {
        value = withContext(Dispatchers.IO) {
            val bg = rep.backgroundAsset?.let { repo.render(loaded, it, cf.width.toInt(), cf.height.toInt()) }
            val items = layout.items.map { placed ->
                val name = placed.item.assetName ?: return@map null
                val (w, h) = if (placed.item.kind == DeltaSkin.ItemKind.THUMBSTICK) {
                    val sz = placed.item.assetSize
                    ((sz?.width ?: 0.25f) * cf.width).toInt() to ((sz?.height ?: 0.25f) * cf.height).toInt()
                } else {
                    placed.frame.width.toInt() to placed.frame.height.toInt()
                }
                repo.render(loaded, name, w, h)?.asImageBitmap()
            }
            SkinBitmaps(bg?.asImageBitmap(), items)
        }
    }
    val alpha = if (rep.translucent) opacity else 1f
    val primaryDpadState = layout.items.firstOrNull { it.item.kind == DeltaSkin.ItemKind.DPAD }
        ?.let { DPadState.fromDeltaItem(it.item, layout.system, pressed, held) }
        ?: DPadState.fromButtons(pressed + held)
    val dpadSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1200f)
    val dpadAnimX by animateFloatAsState(primaryDpadState.targetX, animationSpec = dpadSpring, label = "dpadX")
    val dpadAnimY by animateFloatAsState(primaryDpadState.targetY, animationSpec = dpadSpring, label = "dpadY")
    val upWeight by animateFloatAsState(if (primaryDpadState.up) 1f else 0f, animationSpec = dpadSpring, label = "dpadUp")
    val downWeight by animateFloatAsState(if (primaryDpadState.down) 1f else 0f, animationSpec = dpadSpring, label = "dpadDown")
    val leftWeight by animateFloatAsState(if (primaryDpadState.left) 1f else 0f, animationSpec = dpadSpring, label = "dpadLeft")
    val rightWeight by animateFloatAsState(if (primaryDpadState.right) 1f else 0f, animationSpec = dpadSpring, label = "dpadRight")
    Canvas(modifier.fillMaxSize()) {
        val b = bitmaps
        if (b != null) {
            b.background?.let { img ->
                drawImage(
                    img,
                    dstOffset = IntOffset(cf.x.toInt(), cf.y.toInt()),
                    dstSize = IntSize(cf.width.toInt(), cf.height.toInt()),
                    alpha = alpha,
                    filterQuality = FilterQuality.High,
                )
            }
            layout.items.forEachIndexed { index, placed ->
                val img = b.items.getOrNull(index)
                val f = placed.frame
                if (placed.item.kind == DeltaSkin.ItemKind.THUMBSTICK && img != null) {
                    val radius = minOf(f.width, f.height) / 2f
                    val cx = f.midX + analog.first * radius * 0.6f
                    val cy = f.midY + analog.second * radius * 0.6f
                    drawImage(
                        img,
                        dstOffset = IntOffset((cx - img.width / 2f).toInt(), (cy - img.height / 2f).toInt()),
                        dstSize = IntSize(img.width, img.height),
                        alpha = alpha,
                    )
                } else if (placed.item.kind == DeltaSkin.ItemKind.DPAD) {
                    val tilt = DPadFeedback.computeTilt(f, dpadAnimX, dpadAnimY)
                    if (img != null) {
                        withTransform({
                            translate(tilt.translateX, tilt.translateY)
                            scale(tilt.scaleX, tilt.scaleY, pivot = Offset(tilt.pivotX, tilt.pivotY))
                        }) {
                            drawImage(
                                img,
                                dstOffset = IntOffset(f.x.toInt(), f.y.toInt()),
                                dstSize = IntSize(f.width.toInt(), f.height.toInt()),
                                alpha = alpha,
                                filterQuality = FilterQuality.High,
                            )
                            drawDPadDirectionalFeedback(
                                frame = f,
                                animX = dpadAnimX,
                                animY = dpadAnimY,
                                magnitude = tilt.magnitude,
                                upWeight = upWeight,
                                downWeight = downWeight,
                                leftWeight = leftWeight,
                                rightWeight = rightWeight,
                                onImage = true,
                                alpha = alpha,
                            )
                        }
                    } else {
                        drawDPadDirectionalFeedback(
                            frame = f,
                            animX = dpadAnimX,
                            animY = dpadAnimY,
                            magnitude = tilt.magnitude,
                            upWeight = upWeight,
                            downWeight = downWeight,
                            leftWeight = leftWeight,
                            rightWeight = rightWeight,
                            onImage = false,
                            alpha = alpha,
                        )
                    }
                } else {
                    val isPressed = isPressed(placed.item, layout, pressed, held, fastForwardHeld)
                    if (img != null) {
                        val s = if (isPressed) 0.95f else 1f
                        scale(s, s, pivot = Offset(f.midX, f.midY)) {
                            drawImage(
                                img,
                                dstOffset = IntOffset(f.x.toInt(), f.y.toInt()),
                                dstSize = IntSize(f.width.toInt(), f.height.toInt()),
                                alpha = alpha,
                                colorFilter = if (isPressed) PressedFilter else null,
                                filterQuality = FilterQuality.High,
                            )
                        }
                    } else if (isPressed) {
                        pressHighlight(placed)
                    }
                }
                if (showHitBoxes) {
                    val e = placed.extendedFrame
                    drawRect(DeltaColors.Purple, Offset(e.x, e.y), Size(e.width, e.height), style = Stroke(width = 2f))
                }
            }
        }
        for ((x, y) in touches) {
            drawCircle(DeltaColors.Purple.copy(alpha = 0.45f), radius = 58f, center = Offset(x, y))
            drawCircle(Color.White, radius = 58f, center = Offset(x, y), style = Stroke(width = 5f))
        }
    }
}

/**
 * Directional visual feedback for a `.deltaskin` D-Pad: shades/illuminates only the pressed arm(s),
 * draws a gliding directional glow & rocker rim, and highlights a directional chevron on each
 * active arm so Up / Down / Left / Right / Diagonals are immediately distinguishable.
 */
private fun DrawScope.drawDPadDirectionalFeedback(
    frame: Box,
    animX: Float,
    animY: Float,
    magnitude: Float,
    upWeight: Float,
    downWeight: Float,
    leftWeight: Float,
    rightWeight: Float,
    onImage: Boolean,
    alpha: Float,
) {
    val maxWeight = maxOf(upWeight, downWeight, leftWeight, rightWeight, magnitude)
    if (maxWeight <= 0.01f) return
    val s = minOf(frame.width, frame.height)
    if (s <= 0f) return

    val weights = listOf(
        DPadDirection.UP to upWeight,
        DPadDirection.RIGHT to rightWeight,
        DPadDirection.DOWN to downWeight,
        DPadDirection.LEFT to leftWeight,
    )

    // 1. Per-arm directional depressed shading + subtle edge highlight on each pressed arm.
    for ((dir, weight) in weights) {
        if (weight <= 0.01f) continue
        val arm = DPadFeedback.armBox(frame, dir)
        val r = minOf(arm.width, arm.height) * 0.24f
        val (gradStart, gradEnd) = when (dir) {
            DPadDirection.UP -> Offset(arm.midX, arm.maxY) to Offset(arm.midX, arm.minY)
            DPadDirection.DOWN -> Offset(arm.midX, arm.minY) to Offset(arm.midX, arm.maxY)
            DPadDirection.LEFT -> Offset(arm.maxX, arm.midY) to Offset(arm.minX, arm.midY)
            DPadDirection.RIGHT -> Offset(arm.minX, arm.midY) to Offset(arm.maxX, arm.midY)
        }
        val darkAlpha = (if (onImage) 0.34f else 0.24f) * weight * alpha
        val lightAlpha = (if (onImage) 0.22f else 0.36f) * weight * alpha
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Black.copy(alpha = darkAlpha * 0.35f),
                    Color.Black.copy(alpha = darkAlpha),
                ),
                start = gradStart,
                end = gradEnd,
            ),
            topLeft = Offset(arm.x, arm.y),
            size = Size(arm.width, arm.height),
            cornerRadius = CornerRadius(r, r),
        )
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = lightAlpha * 0.2f),
                    Color.White.copy(alpha = lightAlpha),
                ),
                start = gradStart,
                end = gradEnd,
            ),
            topLeft = Offset(arm.x, arm.y),
            size = Size(arm.width, arm.height),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = (s * 0.022f).coerceAtLeast(2f)),
        )

        // Crisp directional chevron inside the pressed arm.
        val degrees = when (dir) {
            DPadDirection.UP -> 0f
            DPadDirection.RIGHT -> 90f
            DPadDirection.DOWN -> 180f
            DPadDirection.LEFT -> 270f
        }
        val triW = s * 0.115f * (0.9f + 0.18f * weight)
        val triH = s * 0.085f * (0.9f + 0.18f * weight)
        val dist = s * (0.31f + 0.025f * weight)
        rotate(degrees, pivot = Offset(frame.midX, frame.midY)) {
            translate(frame.midX, frame.midY - dist) {
                val tri = Path().apply {
                    moveTo(0f, -triH / 2f)
                    lineTo(triW / 2f, triH / 2f)
                    lineTo(-triW / 2f, triH / 2f)
                    close()
                }
                drawPath(tri, Color.White.copy(alpha = 0.78f * weight * alpha))
            }
        }
    }

    // 2. Smoothly gliding directional radial glow toward (animX, animY) + subtle opposite-side lift.
    if (magnitude > 0.01f) {
        val glowCenter = Offset(
            frame.midX + animX * s * 0.28f,
            frame.midY + animY * s * 0.28f,
        )
        val glowRadius = s * 0.34f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = (if (onImage) 0.24f else 0.34f) * magnitude * alpha),
                    Color.Transparent,
                ),
                center = glowCenter,
                radius = glowRadius,
            ),
            radius = glowRadius,
            center = glowCenter,
        )
        if (onImage) {
            val liftCenter = Offset(
                frame.midX - animX * s * 0.26f,
                frame.midY - animY * s * 0.26f,
            )
            val liftRadius = s * 0.28f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.14f * magnitude * alpha),
                        Color.Transparent,
                    ),
                    center = liftCenter,
                    radius = liftRadius,
                ),
                radius = liftRadius,
                center = liftCenter,
            )
        }
    }
}

/** brightness(−0.18) like SwiftUI `.brightness(-0.18)`. */
private val PressedFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, -0.18f * 255f,
            0f, 1f, 0f, 0f, -0.18f * 255f,
            0f, 0f, 1f, 0f, -0.18f * 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

private fun DrawScope.pressHighlight(placed: DeltaSkinLayout.PlacedItem) {
    val f = placed.frame
    val round = kotlin.math.abs(f.width - f.height) < f.width * 0.2f
    if (round) {
        drawCircle(Color.White.copy(alpha = 0.28f), radius = minOf(f.width, f.height) / 2f, center = Offset(f.midX, f.midY))
    } else {
        val r = minOf(f.width, f.height) * 0.35f
        drawRoundRect(Color.White.copy(alpha = 0.24f), Offset(f.x, f.y), Size(f.width, f.height), CornerRadius(r, r))
    }
}

private fun isPressed(
    item: DeltaSkin.Item,
    layout: DeltaSkinLayout,
    pressed: Set<VirtualButton>,
    held: Set<VirtualButton>,
    fastForwardHeld: Boolean,
): Boolean = when (item.kind) {
    DeltaSkin.ItemKind.BUTTONS -> item.inputs.any { input ->
        when (val a = DeltaSkin.action(input, layout.system)) {
            is SkinAction.Button -> a.button in pressed || a.button in held
            SkinAction.FastForward -> fastForwardHeld
            else -> false
        }
    }
    DeltaSkin.ItemKind.DPAD -> (pressed intersect VirtualButton.DPAD).isNotEmpty()
    else -> false
}

/** Keeps a reference so unused-import lint stays quiet for Bitmap in signatures. */
@Suppress("unused")
private typealias PlatformBitmap = Bitmap
