package com.codescv.delta.ui.game

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.codescv.delta.model.VirtualButton
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
                val isPressed = isPressed(placed.item, layout, pressed, held, fastForwardHeld)
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
                } else if (img != null) {
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
