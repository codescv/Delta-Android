package com.codescv.delta.skins.procedural

import com.codescv.delta.skins.Box
import kotlin.math.floor
import kotlin.math.max

/** Safe-area insets in layout points. */
data class LayoutInsets(val top: Float = 0f, val leading: Float = 0f, val bottom: Float = 0f, val trailing: Float = 0f) {
    companion object {
        val ZERO = LayoutInsets()
    }
}

/**
 * Port of Delta's `ViewportLayout` (`Layout/ViewportLayout.swift`): where the game screen,
 * its bezel and the controls region sit for the procedural console shell. All values are in
 * layout points (Android dp).
 */
data class ViewportLayout(
    val portrait: Boolean,
    val gameRect: Box,
    val bezelRect: Box,
    val screenRegion: Box,
    val controlsRect: Box,
) {
    companion object {
        const val SHELL_MARGIN = 14f
        const val BEZEL_PADDING = 14f
        const val BEZEL_LABEL_STRIP = 18f
        const val MAX_PORTRAIT_SCREEN_FRACTION = 0.56f

        /**
         * @param aspectRatio game width / height (falls back to 4:3 when degenerate).
         * @param displayScale pixels per point, used to snap the game rect to whole pixels.
         */
        fun compute(
            width: Float,
            height: Float,
            safeArea: LayoutInsets,
            aspectRatio: Float,
            displayScale: Float = 3f,
        ): ViewportLayout {
            val scale = max(displayScale, 1f)
            val aspect = if (aspectRatio > 0.05f) aspectRatio else 4f / 3f
            fun snap(v: Float) = floor(v * scale) / scale

            if (width <= height) {
                val top = safeArea.top
                val availableWidth = width - 2 * (SHELL_MARGIN + BEZEL_PADDING)
                var gameWidth = availableWidth
                var gameHeight = gameWidth / aspect
                val maxGameHeight = max(height, width * 1.4f) * MAX_PORTRAIT_SCREEN_FRACTION - top -
                    2 * (SHELL_MARGIN + BEZEL_PADDING) - BEZEL_LABEL_STRIP
                if (gameHeight > maxGameHeight) {
                    gameHeight = maxGameHeight
                    gameWidth = gameHeight * aspect
                }
                gameWidth = snap(gameWidth)
                gameHeight = snap(gameHeight)
                val originX = snap((width - gameWidth) / 2f)
                val originY = snap(top + SHELL_MARGIN + BEZEL_PADDING)
                val gameRect = Box(originX, originY, gameWidth, gameHeight)
                val bezelRect = Box(
                    SHELL_MARGIN, top + SHELL_MARGIN,
                    width - 2 * SHELL_MARGIN,
                    gameHeight + 2 * BEZEL_PADDING + BEZEL_LABEL_STRIP,
                )
                val screenRegion = Box(0f, 0f, width, bezelRect.maxY + SHELL_MARGIN)
                val controlsRect = Box(
                    0f, screenRegion.maxY, width,
                    max(0f, height - screenRegion.maxY - safeArea.bottom),
                )
                return ViewportLayout(true, gameRect, bezelRect, screenRegion, controlsRect)
            }

            val safe = Box(
                safeArea.leading, safeArea.top,
                width - safeArea.leading - safeArea.trailing,
                height - safeArea.top - safeArea.bottom,
            )
            var w = safe.width
            var h = w / aspect
            if (h > safe.height) {
                h = safe.height
                w = h * aspect
            }
            w = snap(w)
            h = snap(h)
            val gameRect = Box(snap(safe.midX - w / 2f), snap(safe.midY - h / 2f), w, h)
            val full = Box(0f, 0f, width, height)
            return ViewportLayout(false, gameRect, gameRect, full, full)
        }
    }
}
