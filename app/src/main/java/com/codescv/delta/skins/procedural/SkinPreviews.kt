package com.codescv.delta.skins.procedural

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codescv.delta.DeltaApplication
import com.codescv.delta.model.VirtualButton
import com.codescv.delta.skins.DeltaSkin
import com.codescv.delta.skins.DeltaSkinLayout
import com.codescv.delta.skins.Box as SkinBox
import com.codescv.delta.skins.SkinCatalog
import com.codescv.delta.skins.SkinOption
import com.codescv.delta.ui.theme.DeltaColors
import com.codescv.delta.ui.theme.DeltaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Card size of `DeltaSkinPreview` (64 × 56 pt). */
private val CardWidth = 64.dp
private val CardHeight = 56.dp
private val CardShape = RoundedCornerShape(10.dp)

/** `DeltaSkinPreview` layout: card + one-line `.caption2` name (max width 84). */
@Composable
internal fun SkinPreviewFrame(
    name: String,
    selected: Boolean,
    modifier: Modifier,
    card: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.semantics { contentDescription = name },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(CardWidth, CardHeight)
                .then(if (selected) Modifier.border(3.dp, DeltaColors.Purple, CardShape) else Modifier),
        ) {
            card()
        }
        Text(
            name,
            style = DeltaType.Caption2,
            color = LocalContentColor.current,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 84.dp),
        )
    }
}

/** `DeltaSkinPreview`: miniature console card for a built-in procedural skin (ui_spec §3.2). */
@Composable
fun VariantSkinPreview(skin: ProceduralSkin, selected: Boolean, modifier: Modifier = Modifier) {
    SkinPreviewFrame(skin.displayName, selected, modifier) {
        Canvas(Modifier.size(CardWidth, CardHeight)) {
            scale(density, density, pivot = Offset.Zero) { drawMiniConsole(skin) }
        }
    }
}

/** Draws the 64 × 56 pt mini console in points. */
private fun DrawScope.drawMiniConsole(skin: ProceduralSkin) {
    val p = skin.palette
    val w = 64f
    val h = 56f
    val cx = w / 2f
    val cy = h / 2f
    fun rr(x: Float, y: Float, rw: Float, rh: Float, r: Float, c: Color) =
        drawRoundRect(c, Offset(x, y), Size(rw, rh), CornerRadius(minOf(r, rw / 2f, rh / 2f)))
    fun centered(ox: Float, oy: Float, rw: Float, rh: Float, r: Float, c: Color) =
        rr(cx + ox - rw / 2f, cy + oy - rh / 2f, rw, rh, r, c)

    drawRoundRect(
        Brush.verticalGradient(listOf(p.shellTop.color, p.shellBottom.color), startY = 0f, endY = h),
        Offset.Zero, Size(w, h), CornerRadius(10f),
    )

    if (skin.family == "nds") {
        // VStack(spacing 2) { 30×12, 44×2 capsule, 30×12 } offset y −10 → total height 28.
        val top = cy - 10f - 14f
        rr(cx - 15f, top, 30f, 12f, 2f, p.bezel.color)
        rr(cx - 22f, top + 14f, 44f, 2f, 1f, p.shellShadow.color)
        rr(cx - 15f, top + 18f, 30f, 12f, 2f, p.bezel.color)
    } else {
        centered(0f, -8f, 34f, 22f, 3f, p.bezel.color)
        if (skin.family == "nes") centered(0f, -17f, 30f, 1.5f, 0f, p.accent.color)
    }

    // HStack(spacing 14) { plus glyph, face dots } offset y 15.
    val system = SkinCatalog.systemFor(skin.family)
    val fourButtons = system.gamepadLayout.faceButtons.size >= 3
    val plusW = 9f
    val rowY = cy + 15f
    val faceOrder: List<VirtualButton>
    val dotD: Float
    val dotSpacing: Float
    if (fourButtons) {
        faceOrder = if (skin.family == "genesis") {
            listOf(VirtualButton.Y, VirtualButton.B, VirtualButton.A)
        } else {
            listOf(VirtualButton.Y, VirtualButton.X, VirtualButton.B, VirtualButton.A)
        }
        dotD = 5.5f
        dotSpacing = 1.5f
    } else {
        faceOrder = listOf(VirtualButton.B, VirtualButton.A)
        dotD = 7f
        dotSpacing = 2f
    }
    val facesW = faceOrder.size * dotD + (faceOrder.size - 1) * dotSpacing
    val rowW = plusW + 14f + facesW
    val plusCx = cx - rowW / 2f + plusW / 2f
    val bar = 2.4f
    rr(plusCx - plusW / 2f, rowY - bar / 2f, plusW, bar, 0.6f, p.cap.color)
    rr(plusCx - bar / 2f, rowY - plusW / 2f, bar, plusW, 0.6f, p.cap.color)
    var x = cx - rowW / 2f + plusW + 14f
    faceOrder.forEachIndexed { i, b ->
        val dy = if (!fourButtons && i == 1) -3f else 0f
        drawCircle(skin.faceColor(b).color, dotD / 2f, Offset(x + dotD / 2f, rowY + dy))
        x += dotD + dotSpacing
    }

    drawCircle(p.led.color, 2f, Offset(cx + 24f, cy + 4f))
}

/** Rasterized official / imported skin thumbnails keyed by `family|id|w×h`. */
private val renderedPreviewCache = LruCache<String, ImageBitmap>(48)

/**
 * Official / imported `.deltaskin` thumbnail: the portrait background artwork aspect-fit in the
 * same 64 × 56 card, rendered off the main thread and cached.
 */
@Composable
fun RenderedSkinPreview(option: SkinOption, selected: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val wPx = with(density) { CardWidth.roundToPx() }
    val hPx = with(density) { CardHeight.roundToPx() }
    val key = "${option.family}|${option.id}|${wPx}x$hPx"
    val image by produceState(renderedPreviewCache.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { renderPreview(option, wPx, hPx) }?.also { renderedPreviewCache.put(key, it) }
        }
    }
    SkinPreviewFrame(option.name, selected, modifier) {
        Box(
            Modifier
                .size(CardWidth, CardHeight)
                .clip(CardShape)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            image?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(CardWidth, CardHeight))
            }
        }
    }
}

/**
 * Port of `DeltaSkinRenderer.preview`, framed on the portrait controller: black fill,
 * `#383838` placeholder for controller-placed screens, the background artwork, then every item
 * asset at its frame (thumbstick assets sized by `assetSize`, centered), composited on ARGB_8888.
 */
private fun renderPreview(option: SkinOption, width: Int, height: Int): ImageBitmap? = runCatching {
    val repo = DeltaApplication.container.skins
    val loaded = when (option.kind) {
        SkinOption.Kind.IMPORTED -> repo.imported(option.family).firstOrNull { it.source.id == option.id }
        else -> repo.bundled(option.family)
    } ?: return null
    val rep = loaded.skin.representation(portrait = true) ?: loaded.skin.representation(portrait = false) ?: return null
    val fit = DeltaSkinLayout.aspectFit(rep.mappingSize, SkinBox(0f, 0f, width.toFloat(), height.toFloat()))
    val w = fit.width.toInt().coerceAtLeast(1)
    val h = fit.height.toInt().coerceAtLeast(1)
    val cf = SkinBox(0f, 0f, w.toFloat(), h.toFloat())
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawColor(android.graphics.Color.BLACK)
    val screenPaint = android.graphics.Paint().apply { color = 0xFF383838.toInt() }
    for (screen in rep.screens) {
        val o = screen.outputFrame ?: continue
        if (!screen.controllerPlacement) continue
        val r = DeltaSkinLayout.denormalize(o, cf)
        canvas.drawRect(r.minX, r.minY, r.maxX, r.maxY, screenPaint)
    }
    val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        alpha = if (rep.translucent) (0.7f * 255).toInt() else 255
    }
    fun draw(name: String, r: SkinBox) {
        val bw = r.width.toInt()
        val bh = r.height.toInt()
        if (bw <= 0 || bh <= 0) return
        val bmp = repo.render(loaded, name, bw, bh) ?: return
        canvas.drawBitmap(bmp, null, android.graphics.RectF(r.minX, r.minY, r.maxX, r.maxY), paint)
    }
    rep.backgroundAsset?.let { draw(it, cf) }
    for (item in rep.items) {
        val name = item.assetName ?: continue
        var r = DeltaSkinLayout.denormalize(item.frame, cf)
        val size = item.assetSize
        if (item.kind == DeltaSkin.ItemKind.THUMBSTICK && size != null) {
            val tw = size.width * cf.width
            val th = size.height * cf.height
            r = SkinBox(r.midX - tw / 2f, r.midY - th / 2f, tw, th)
        }
        draw(name, r)
    }
    out.asImageBitmap()
}.getOrNull()
