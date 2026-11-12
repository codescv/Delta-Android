package com.codescv.delta.input

import android.view.KeyEvent
import com.codescv.delta.model.VirtualButton

/** Default physical-gamepad bindings (design §4) and per-player overrides from preferences. */
object GamepadMapping {
    /** RetroPad button -> Android keycode (Nintendo-style positional layout like Delta/MFi). */
    val defaults: Map<VirtualButton, Int> = linkedMapOf(
        VirtualButton.A to KeyEvent.KEYCODE_BUTTON_B,
        VirtualButton.B to KeyEvent.KEYCODE_BUTTON_A,
        VirtualButton.X to KeyEvent.KEYCODE_BUTTON_Y,
        VirtualButton.Y to KeyEvent.KEYCODE_BUTTON_X,
        VirtualButton.L to KeyEvent.KEYCODE_BUTTON_L1,
        VirtualButton.R to KeyEvent.KEYCODE_BUTTON_R1,
        VirtualButton.L2 to KeyEvent.KEYCODE_BUTTON_L2,
        VirtualButton.R2 to KeyEvent.KEYCODE_BUTTON_R2,
        VirtualButton.START to KeyEvent.KEYCODE_BUTTON_START,
        VirtualButton.SELECT to KeyEvent.KEYCODE_BUTTON_SELECT,
        VirtualButton.UP to KeyEvent.KEYCODE_DPAD_UP,
        VirtualButton.DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
        VirtualButton.LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
        VirtualButton.RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
    )

    /** Keys that open the Delta pause menu. */
    val menuKeys = setOf(KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_MENU)

    /** Effective mapping for [player] (1..4) given `AppPreferences.gamepadMappings`. */
    fun forPlayer(player: Int, overrides: Map<String, Map<String, Int>>): Map<VirtualButton, Int> {
        val custom = overrides[player.toString()] ?: return defaults
        return defaults.mapValues { (button, key) -> custom[button.name] ?: key }
    }

    fun keyLabel(keyCode: Int): String = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ')
}
