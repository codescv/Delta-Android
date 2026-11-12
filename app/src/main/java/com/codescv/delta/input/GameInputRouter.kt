package com.codescv.delta.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.codescv.delta.model.VirtualButton
import kotlin.math.abs

/**
 * Routes physical gamepad events from the Activity to the running game (Delta's
 * GameControllerMonitor). Players 1–4 by connection order / Settings assignment.
 */
object GameInputRouter {
    interface Handler {
        /** Joypad mask for libretro port `player - 1`. */
        fun onGamepadMask(player: Int, mask: Int)
        fun onGamepadAnalog(player: Int, stick: Int, x: Float, y: Float)
        fun onGamepadMenu()
        fun onGamepadConnectionChanged() {}
    }

    @Volatile var handler: Handler? = null
    @Volatile var assignments: Map<String, Int> = emptyMap()
    @Volatile var mappings: Map<String, Map<String, Int>> = emptyMap()

    private val masks = IntArray(5)
    private val dpadAxis = IntArray(5)
    private val triggerAxis = IntArray(5)
    private val playerOrder = LinkedHashMap<Int, Int>() // deviceId -> player

    private fun playerFor(device: InputDevice?): Int {
        if (device == null) return 1
        assignments[device.descriptor]?.let { return it.coerceIn(1, 4) }
        return playerOrder.getOrPut(device.id) { (playerOrder.size + 1).coerceAtMost(4) }
    }

    fun deviceRemoved(deviceId: Int) {
        playerOrder.remove(deviceId)
        handler?.onGamepadConnectionChanged()
    }

    fun deviceAdded() {
        handler?.onGamepadConnectionChanged()
    }

    /** Returns true when consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val h = handler ?: return false
        val device = event.device
        if (!Gamepads.isGamepad(device) && !KeyEvent.isGamepadButton(event.keyCode)) return false
        if (event.keyCode in GamepadMapping.menuKeys) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) h.onGamepadMenu()
            return true
        }
        val player = playerFor(device)
        val map = GamepadMapping.forPlayer(player, mappings)
        val button = map.entries.firstOrNull { it.value == event.keyCode }?.key
        val bit = when {
            button != null -> button.mask
            event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL -> 1 shl 14
            event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR -> 1 shl 15
            event.keyCode == KeyEvent.KEYCODE_BACK && Gamepads.isGamepad(device) -> VirtualButton.SELECT.mask
            else -> return false
        }
        masks[player] = when (event.action) {
            KeyEvent.ACTION_DOWN -> masks[player] or bit
            KeyEvent.ACTION_UP -> masks[player] and bit.inv()
            else -> masks[player]
        }
        h.onGamepadMask(player, masks[player] or dpadAxis[player] or triggerAxis[player])
        return true
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        val h = handler ?: return false
        if (!Gamepads.isGamepad(event.device) || event.action != MotionEvent.ACTION_MOVE) return false
        val player = playerFor(event.device)
        val lx = event.getAxisValue(MotionEvent.AXIS_X)
        val ly = event.getAxisValue(MotionEvent.AXIS_Y)
        val rx = event.getAxisValue(MotionEvent.AXIS_Z)
        val ry = event.getAxisValue(MotionEvent.AXIS_RZ)
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        var d = 0
        if (hx < -0.5f || lx < -0.5f) d = d or VirtualButton.LEFT.mask
        if (hx > 0.5f || lx > 0.5f) d = d or VirtualButton.RIGHT.mask
        if (hy < -0.5f || ly < -0.5f) d = d or VirtualButton.UP.mask
        if (hy > 0.5f || ly > 0.5f) d = d or VirtualButton.DOWN.mask
        dpadAxis[player] = d
        var t = 0
        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        if (lt > 0.4f) t = t or VirtualButton.L2.mask
        if (rt > 0.4f) t = t or VirtualButton.R2.mask
        triggerAxis[player] = t
        h.onGamepadMask(player, masks[player] or dpadAxis[player] or triggerAxis[player])
        h.onGamepadAnalog(player, 0, deadZone(lx), deadZone(ly))
        h.onGamepadAnalog(player, 1, deadZone(rx), deadZone(ry))
        return true
    }

    private fun deadZone(v: Float) = if (abs(v) < 0.12f) 0f else v

    fun reset() {
        masks.fill(0)
        dpadAxis.fill(0)
        triggerAxis.fill(0)
    }
}
