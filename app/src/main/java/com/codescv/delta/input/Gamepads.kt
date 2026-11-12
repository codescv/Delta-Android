package com.codescv.delta.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice

/** Connected physical controllers (Bluetooth/USB gamepads). Owned by `coder`. */
object Gamepads {
    data class ControllerInfo(val deviceId: Int, val descriptor: String, val name: String, val player: Int)

    fun isGamepad(device: InputDevice?): Boolean {
        if (device == null || device.isVirtual) return false
        val s = device.sources
        return (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    /** Connected gamepads with their assigned player (1..4) in connection order. */
    fun connected(context: Context, assignments: Map<String, Int> = emptyMap()): List<ControllerInfo> {
        val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val devices: List<InputDevice> = im.inputDeviceIds.toList()
            .mapNotNull { id -> InputDevice.getDevice(id) }
            .filter { d -> isGamepad(d) }
        var next = 1
        return devices.map { d: InputDevice ->
            val player = assignments[d.descriptor] ?: next
            next = maxOf(next, player) + 1
            ControllerInfo(d.id, d.descriptor, d.name ?: "Game Controller", player.coerceIn(1, 4))
        }
    }
}
