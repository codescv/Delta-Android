package com.codescv.delta.skins

import com.codescv.delta.model.GameSystem
import com.codescv.delta.model.VirtualButton
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Cardinal directions of a D-Pad for directional visual press feedback. */
enum class DPadDirection { UP, DOWN, LEFT, RIGHT }

/**
 * Active directional state of a D-Pad, resolved from either raw [VirtualButton]s
 * or a `.deltaskin` [DeltaSkin.Item]'s directional input map.
 */
data class DPadState(
    val up: Boolean = false,
    val down: Boolean = false,
    val left: Boolean = false,
    val right: Boolean = false,
) {
    val isPressed: Boolean get() = up || down || left || right

    val activeDirections: Set<DPadDirection>
        get() = buildSet(4) {
            if (up) add(DPadDirection.UP)
            if (down) add(DPadDirection.DOWN)
            if (left) add(DPadDirection.LEFT)
            if (right) add(DPadDirection.RIGHT)
        }

    /** Normalized horizontal vector in [-1, 1] (-1 = left, +1 = right). */
    val targetX: Float
        get() {
            val rx = (if (right) 1f else 0f) - (if (left) 1f else 0f)
            val ry = (if (down) 1f else 0f) - (if (up) 1f else 0f)
            return if (rx != 0f && ry != 0f) rx * INV_SQRT2 else rx
        }

    /** Normalized vertical vector in [-1, 1] (-1 = up, +1 = down). */
    val targetY: Float
        get() {
            val rx = (if (right) 1f else 0f) - (if (left) 1f else 0f)
            val ry = (if (down) 1f else 0f) - (if (up) 1f else 0f)
            return if (rx != 0f && ry != 0f) ry * INV_SQRT2 else ry
        }

    companion object {
        private const val INV_SQRT2 = 0.70710677f

        val NEUTRAL = DPadState()

        fun fromButtons(buttons: Set<VirtualButton>): DPadState = DPadState(
            up = VirtualButton.UP in buttons,
            down = VirtualButton.DOWN in buttons,
            left = VirtualButton.LEFT in buttons,
            right = VirtualButton.RIGHT in buttons,
        )

        fun fromDeltaItem(
            item: DeltaSkin.Item,
            system: GameSystem,
            pressed: Set<VirtualButton>,
            held: Set<VirtualButton>,
        ): DPadState {
            if (item.kind != DeltaSkin.ItemKind.DPAD) return NEUTRAL
            fun dirActive(key: String, fallback: VirtualButton): Boolean {
                val input = item.directions[key]
                val button = if (input != null) {
                    (DeltaSkin.action(input, system) as? SkinAction.Button)?.button
                } else {
                    fallback
                }
                return button != null && (button in pressed || button in held)
            }
            return DPadState(
                up = dirActive("up", VirtualButton.UP),
                down = dirActive("down", VirtualButton.DOWN),
                left = dirActive("left", VirtualButton.LEFT),
                right = dirActive("right", VirtualButton.RIGHT),
            )
        }
    }
}

/**
 * Directional 2D rocker transform for a pressed D-Pad: translates slightly toward the pressed
 * direction and foreshortens along the pressed axis around a pivot near the opposite edge.
 */
data class DPadTiltTransform(
    val translateX: Float,
    val translateY: Float,
    val scaleX: Float,
    val scaleY: Float,
    val pivotX: Float,
    val pivotY: Float,
    val magnitude: Float,
)

object DPadFeedback {
    /**
     * Computes a directional rocker transform from animated normalized vector `(animX, animY)`.
     * Neutral `(0, 0)` yields identity transform (`translate = 0, scale = 1, magnitude = 0`).
     */
    fun computeTilt(
        frame: Box,
        animX: Float,
        animY: Float,
        shiftFraction: Float = 0.042f,
        compressFraction: Float = 0.065f,
    ): DPadTiltTransform {
        val cx = animX.coerceIn(-1f, 1f)
        val cy = animY.coerceIn(-1f, 1f)
        val mag = hypot(cx, cy).coerceIn(0f, 1f)
        return DPadTiltTransform(
            translateX = cx * frame.width * shiftFraction,
            translateY = cy * frame.height * shiftFraction,
            scaleX = 1f - abs(cx) * compressFraction,
            scaleY = 1f - abs(cy) * compressFraction,
            pivotX = frame.midX - cx * frame.width * 0.35f,
            pivotY = frame.midY - cy * frame.height * 0.35f,
            magnitude = mag,
        )
    }

    /**
     * Returns the bounding box of one arm (`UP`, `DOWN`, `LEFT`, `RIGHT`) of a cross D-Pad
     * inside [frame]. Used for per-direction highlights and shading overlays.
     */
    fun armBox(
        frame: Box,
        direction: DPadDirection,
        armWidthFraction: Float = 0.34f,
        armLengthFraction: Float = 0.36f,
        outerInsetFraction: Float = 0.04f,
    ): Box {
        val s = min(frame.width, frame.height)
        val armW = s * armWidthFraction
        val armL = s * armLengthFraction
        val inset = s * outerInsetFraction
        val cx = frame.midX
        val cy = frame.midY
        return when (direction) {
            DPadDirection.UP -> Box(cx - armW / 2f, frame.minY + inset, armW, armL)
            DPadDirection.DOWN -> Box(cx - armW / 2f, frame.maxY - inset - armL, armW, armL)
            DPadDirection.LEFT -> Box(frame.minX + inset, cy - armW / 2f, armL, armW)
            DPadDirection.RIGHT -> Box(frame.maxX - inset - armL, cy - armW / 2f, armL, armW)
        }
    }
}
