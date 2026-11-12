package com.codescv.delta.skins

import com.codescv.delta.model.VirtualButton

/**
 * Platform-independent core of Delta's `MultiTouchGamepadView`: tracks every active pointer
 * and recomputes the union of pressed inputs on each touch event, so presses register on
 * touch-down, a thumb can slide between buttons (B -> A) without lifting, a thumb resting
 * between two face buttons presses both (A+B chord), and a touch that starts on the D-Pad
 * keeps steering it after sliding outside (lock-on).
 *
 * `MultiTouchGamepadSurface` (Android View) feeds MotionEvents into this class.
 */
class GamepadTouchTracker(
    var mapper: GamepadTouchMapper,
    var hasStartSelect: Boolean = true,
) {
    interface Listener {
        fun onButtonsChanged(buttons: Set<VirtualButton>) {}
        fun onFastForwardHold(held: Boolean) {}
        fun onToggleFastForward() {}
        fun onMenu() {}
        fun onQuickSave() {}
        fun onQuickLoad() {}
        fun onAnalog(x: Float, y: Float) {}
        fun onRightAnalog(x: Float, y: Float) {}
        fun onPointer(x: Float?, y: Float?) {}
        fun onTouchPoints(points: List<Pair<Float, Float>>) {}
        fun onHaptic(kind: HapticKind) {}
    }

    enum class HapticKind { PRESS, MOVE, RELEASE }

    var listener: Listener? = null

    private data class Touch(var x: Float, var y: Float, val lock: Int?)

    private val touches = LinkedHashMap<Int, Touch>()
    private val menuTouches = HashSet<Int>()

    var currentButtons: Set<VirtualButton> = emptySet()
        private set
    private var currentFastForward = false
    private var currentToggle = false
    private var currentQuickSave = false
    private var currentQuickLoad = false
    private var currentAnalog: Pair<Float, Float>? = null
    private var currentRight: Pair<Float, Float> = 0f to 0f
    private var currentPointer: Pair<Float, Float>? = null

    val activePointerCount: Int get() = touches.size

    /** Whether the controller claims a touch at the point (others pass through). */
    fun claims(x: Float, y: Float): Boolean = mapper.claims(x, y)

    fun pointerDown(id: Int, x: Float, y: Float) {
        val lock = mapper.lockTarget(x, y)
        if (lock == null && mapper.sample(x, y, null).menu) {
            menuTouches += id
            recompute()
            listener?.onHaptic(HapticKind.PRESS)
            listener?.onMenu()
            return
        }
        touches[id] = Touch(x, y, lock)
        recompute()
    }

    fun pointerMove(id: Int, x: Float, y: Float) {
        val t = touches[id] ?: return
        t.x = x
        t.y = y
    }

    /** Call after a batch of [pointerMove]s (one MotionEvent.ACTION_MOVE). */
    fun commitMoves() = recompute()

    fun pointerUp(id: Int) {
        touches.remove(id)
        menuTouches.remove(id)
        recompute()
    }

    fun releaseAll() {
        touches.clear()
        menuTouches.clear()
        recompute()
    }

    private fun recompute() {
        val newButtons = LinkedHashSet<VirtualButton>()
        var newFF = false
        var newToggle = false
        var newQuickSave = false
        var newQuickLoad = false
        var newAnalog: Pair<Float, Float>? = null
        var newPointer: Pair<Float, Float>? = null
        var rx = 0f
        var ry = 0f
        for (t in touches.values) {
            val hit = mapper.sample(t.x, t.y, t.lock)
            newButtons += hit.buttons
            newFF = newFF || hit.holdFastForward
            newToggle = newToggle || hit.toggleFastForward
            newQuickSave = newQuickSave || hit.quickSave
            newQuickLoad = newQuickLoad || hit.quickLoad
            if (newAnalog == null && hit.analogX != null && hit.analogY != null) newAnalog = hit.analogX to hit.analogY
            if (newPointer == null && hit.pointerX != null && hit.pointerY != null) newPointer = hit.pointerX to hit.pointerY
            rx += hit.rightX
            ry += hit.rightY
        }
        val newRight = rx.coerceIn(-1f, 1f) to ry.coerceIn(-1f, 1f)
        if (!hasStartSelect) {
            newButtons.remove(VirtualButton.START)
            newButtons.remove(VirtualButton.SELECT)
        }
        listener?.onTouchPoints(touches.values.map { it.x to it.y })

        val oldButtons = currentButtons
        val oldFF = currentFastForward
        val oldToggle = currentToggle
        val oldAnalog = currentAnalog
        val oldPointer = currentPointer
        val oldQuickSave = currentQuickSave
        val oldQuickLoad = currentQuickLoad
        val oldRight = currentRight
        currentRight = newRight
        currentButtons = newButtons
        currentFastForward = newFF
        currentToggle = newToggle
        currentAnalog = newAnalog
        currentPointer = newPointer
        currentQuickSave = newQuickSave
        currentQuickLoad = newQuickLoad

        val l = listener
        if (newButtons != oldButtons) l?.onButtonsChanged(newButtons)
        if (newFF != oldFF) l?.onFastForwardHold(newFF)
        if (newToggle && !oldToggle) l?.onToggleFastForward()
        if (newQuickSave && !oldQuickSave) l?.onQuickSave()
        if (newQuickLoad && !oldQuickLoad) l?.onQuickLoad()
        if (newAnalog != oldAnalog) l?.onAnalog(newAnalog?.first ?: 0f, newAnalog?.second ?: 0f)
        if (newPointer != oldPointer) l?.onPointer(newPointer?.first, newPointer?.second)
        if (newRight != oldRight) l?.onRightAnalog(newRight.first, newRight.second)
        val oldRightActive = oldRight.first != 0f || oldRight.second != 0f
        val newRightActive = newRight.first != 0f || newRight.second != 0f
        haptics(
            oldButtons, newButtons,
            oldFF || oldToggle || oldAnalog != null || oldRightActive,
            newFF || newToggle || newAnalog != null || newRightActive,
        )
            ?.let { l?.onHaptic(it) }
    }

    companion object {
        /** Delta's haptic policy (press on new face button / D-Pad from neutral, tick on roll). */
        fun haptics(
            old: Set<VirtualButton>,
            new: Set<VirtualButton>,
            oldFastForward: Boolean,
            newFastForward: Boolean,
        ): HapticKind? {
            val dpad = VirtualButton.DPAD
            val oldFace = old - dpad
            val newFace = new - dpad
            val oldDPad = old intersect dpad
            val newDPad = new intersect dpad
            val pressed = (newFace - oldFace).isNotEmpty() || (newFastForward && !oldFastForward)
            val released = (oldFace - newFace).isNotEmpty() || (oldFastForward && !newFastForward)
            return when {
                pressed || (oldDPad.isEmpty() && newDPad.isNotEmpty()) -> HapticKind.PRESS
                oldDPad != newDPad && newDPad.isNotEmpty() -> HapticKind.MOVE
                released -> HapticKind.RELEASE
                else -> null
            }
        }
    }
}
