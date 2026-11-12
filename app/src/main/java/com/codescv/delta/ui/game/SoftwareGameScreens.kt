package com.codescv.delta.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.codescv.delta.emulation.EmulatorSession
import com.codescv.delta.emulation.ScreenQuad
import com.codescv.delta.settings.VideoFilter

/**
 * Software renderer used when the experimental "GPU Renderer" (key `metal`) is off: draws the
 * latest core frame with Compose at each screen rect, redrawn whenever [frameTick] changes.
 */
@Composable
fun SoftwareGameScreens(session: EmulatorSession?, quads: List<ScreenQuad>, filter: VideoFilter, frameTick: Int) {
    Canvas(Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        frameTick // subscribe to new frames
        val s = session ?: return@Canvas
        s.withFrame { bmp ->
            if (bmp == null || bmp.isRecycled) return@withFrame
            val img = bmp.asImageBitmap()
            val quality = if (filter == VideoFilter.SMOOTH_BILINEAR) FilterQuality.Low else FilterQuality.None
            for (q in quads) {
                val src = q.source
                drawImage(
                    img,
                    srcOffset = IntOffset(src?.x?.toInt() ?: 0, src?.y?.toInt() ?: 0),
                    srcSize = IntSize(src?.width?.toInt() ?: bmp.width, src?.height?.toInt() ?: bmp.height),
                    dstOffset = IntOffset(q.dest.x.toInt(), q.dest.y.toInt()),
                    dstSize = IntSize(q.dest.width.toInt(), q.dest.height.toInt()),
                    filterQuality = quality,
                )
            }
        }
    }
}
