package com.codescv.delta.ui.library

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.codescv.delta.library.RomEntry
import com.codescv.delta.model.GameSystem
import com.codescv.delta.skins.SkinRepository
import com.codescv.delta.ui.theme.DeltaColors
import com.codescv.delta.ui.theme.DeltaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal const val BOX_ART_GLASS_ASSET = "file:///android_asset/DeltaAssets/BoxArtGlass.png"
internal val ToastBackground = Color(0xE6333336)
private val ThumbnailPlaceholder = Color(0x26EBEBF5)

/** Glass circular top-bar button (gear / plus), 44 dp. */
@Composable
fun LibraryGlassCircle(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(DeltaColors.GlassFill)
            .border(1.dp, DeltaColors.GlassStroke, CircleShape)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/** Square box art card with a centered title (Delta `GameCollectionViewCell`). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryBoxArtCard(
    rom: RomEntry,
    cover: File?,
    coverRevision: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.BottomCenter) {
            if (cover != null) CoverArtwork(cover, coverRevision) else BoxArtGlassPlaceholder()
        }
        Text(
            rom.title,
            style = DeltaType.Subheadline,
            color = DeltaColors.SecondaryText,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CoverArtwork(cover: File, coverRevision: Int) {
    val context = LocalContext.current
    val request = remember(cover.path, coverRevision) {
        ImageRequest.Builder(context)
            .data(cover)
            .memoryCacheKey("${cover.path}#$coverRevision")
            .crossfade(false)
            .build()
    }
    val painter = rememberAsyncImagePainter(request)
    val state = painter.state
    val shape = RoundedCornerShape(12.dp)
    if (state !is AsyncImagePainter.State.Success) BoxArtGlassPlaceholder()
    val intrinsic = painter.intrinsicSize
    val ratio = if (state is AsyncImagePainter.State.Success && intrinsic.width > 0 && intrinsic.height > 0) {
        intrinsic.width / intrinsic.height
    } else {
        null
    }
    val sized = when {
        ratio == null -> Modifier.fillMaxSize()
        ratio >= 1f -> Modifier.fillMaxWidth().aspectRatio(ratio)
        else -> Modifier.fillMaxSize().aspectRatio(ratio, matchHeightConstraintsFirst = true)
    }
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = if (ratio == null) sized else sized.clip(shape).border(0.5.dp, DeltaColors.BoxArtStroke, shape),
    )
}

/** Delta's missing-artwork tile: `#474747` rounded square with the BoxArtGlass Δ glyph padded 18. */
@Composable
fun BoxArtGlassPlaceholder() {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(DeltaColors.BoxArtGlassFill)
            .border(0.5.dp, DeltaColors.BoxArtGlassStroke, shape),
    ) {
        AsyncImage(
            model = BOX_ART_GLASS_ASSET,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(18.dp),
        )
    }
}

/** Continue Playing card: latest save-state screenshot (128×72, r10) + title. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContinueCard(rom: RomEntry, thumbnail: File?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .width(128.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .size(128.dp, 72.dp)
                .clip(shape)
                .border(0.5.dp, DeltaColors.GlassStroke, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(ThumbnailPlaceholder), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Photo, contentDescription = null, tint = DeltaColors.SystemGray)
                }
            }
        }
        Text(
            rom.title,
            style = DeltaType.Caption.copy(fontWeight = FontWeight.SemiBold),
            color = DeltaColors.CardTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Floating `[ ★ | 🎮 … ]` capsule that jumps between pages. */
@Composable
fun PageSwitcher(pages: List<LibraryPage>, current: LibraryPage?, onSelect: (LibraryPage) -> Unit) {
    val icons = rememberControllerIcons(pages)
    Row(
        Modifier
            .shadow(8.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(CircleShape)
            .background(DeltaColors.Capsule)
            .border(1.dp, DeltaColors.GlassStroke, CircleShape)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (page in pages) {
            val tint = if (page == current) Color.White else DeltaColors.InactiveIcon
            Box(
                Modifier
                    .sizeIn(minWidth = 24.dp, minHeight = 28.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(page) }
                    .semantics { contentDescription = page.title },
                contentAlignment = Alignment.Center,
            ) {
                when (page) {
                    LibraryPage.Favorites -> Icon(Icons.Filled.Star, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
                    is LibraryPage.System -> {
                        val bmp = icons[page.system.category]
                        if (bmp != null) {
                            Image(bmp, contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = Modifier.height(18.dp))
                        } else {
                            Text(
                                page.system.shortName,
                                color = tint,
                                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Black),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Process-wide cache of rendered `ControllerIcon_*.pdf` template images, keyed `asset@px`. */
private object ControllerIconCache {
    val bitmaps = ConcurrentHashMap<String, ImageBitmap>()

    fun get(context: Context, asset: String, heightPx: Int): ImageBitmap? {
        val key = "$asset@$heightPx"
        bitmaps[key]?.let { return it }
        val bmp: Bitmap = SkinRepository.renderAssetPdf(context, asset, heightPx) ?: return null
        return bmp.asImageBitmap().also { bitmaps[key] = it }
    }
}

@Composable
private fun rememberControllerIcons(pages: List<LibraryPage>): Map<GameSystem, ImageBitmap> {
    val context = LocalContext.current
    val heightPx = with(LocalDensity.current) { 18.dp.roundToPx() }
    val systems = pages.filterIsInstance<LibraryPage.System>().map { it.system.category }
    val icons by produceState(emptyMap<GameSystem, ImageBitmap>(), systems, heightPx) {
        value = withContext(Dispatchers.IO) {
            systems.mapNotNull { s ->
                val asset = LibraryLogic.controllerIconAsset(s) ?: return@mapNotNull null
                runCatching { ControllerIconCache.get(context.applicationContext, asset, heightPx) }.getOrNull()?.let { s to it }
            }.toMap()
        }
    }
    return icons
}

/** Floating 52 dp search capsule. */
@Composable
fun LibrarySearchBar(
    text: String,
    onTextChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .shadow(8.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
            .clip(CircleShape)
            .background(DeltaColors.Capsule)
            .border(1.dp, DeltaColors.GlassStroke, CircleShape)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = DeltaColors.CardTitle, modifier = Modifier.size(22.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text("Search", color = DeltaColors.InactiveIcon, fontSize = 19.sp, maxLines = 1)
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 19.sp),
                cursorBrush = SolidColor(DeltaColors.Purple),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
        if (text.isNotEmpty()) {
            Box(
                Modifier
                    .size(28.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClear,
                    )
                    .semantics { contentDescription = "Clear Search" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Cancel, contentDescription = null, tint = DeltaColors.InactiveIcon, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Toast capsule (`.footnote semibold`, padding h14 v8). */
@Composable
fun LibraryToast(text: String) {
    Text(
        text,
        style = DeltaType.Footnote.copy(fontWeight = FontWeight.SemiBold),
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(CircleShape)
            .background(ToastBackground)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
