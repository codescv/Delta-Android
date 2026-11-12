package com.codescv.delta.emulation

/** Delta's `FramePacer` (core/emu_util.cc): accumulator-based frame pacing with fast forward. */
class FramePacer(coreFps: Double = 60.0) {
    var coreFps: Double = sanitize(coreFps)
        set(value) {
            field = sanitize(value)
        }

    var speedMultiplier: Int = 1
        set(value) {
            field = value.coerceIn(1, 8)
        }

    val maxFramesPerTick: Int get() = 4 * speedMultiplier

    private var accumulator = 0.0

    fun reset() {
        accumulator = 0.0
    }

    fun framesForElapsed(elapsedSeconds: Double): Int {
        val elapsed = elapsedSeconds.coerceIn(0.0, 0.25)
        accumulator += elapsed * coreFps * speedMultiplier
        var frames = (accumulator + JITTER).toInt()
        frames = frames.coerceIn(0, maxFramesPerTick)
        accumulator -= frames
        accumulator = accumulator.coerceIn(-JITTER, maxFramesPerTick.toDouble())
        return frames
    }

    private companion object {
        const val JITTER = 0.02
        fun sanitize(fps: Double) = if (fps > 1.0 && fps < 1000.0) fps else 60.0
    }
}

enum class FastForwardMode(val raw: String, val title: String) {
    HOLD("hold", "Hold to Fast-Forward"),
    TOGGLE("toggle", "Tap to Toggle");

    companion object {
        fun fromRaw(raw: String?) = entries.firstOrNull { it.raw == raw } ?: TOGGLE
    }
}

/** Delta's `FastForwardController`. */
class FastForwardController(mode: FastForwardMode = FastForwardMode.TOGGLE, multiplier: Int = 2) {
    var mode: FastForwardMode = mode
        private set
    var multiplier: Int = coerce(multiplier)
        private set
    var isActive = false
        private set
    private var touches = 0

    val effectiveMultiplier: Int get() = if (isActive) multiplier else 1

    /** Returns true when the active state changed. */
    fun buttonDown(): Boolean {
        touches++
        if (touches != 1) return false
        return when (mode) {
            FastForwardMode.HOLD -> if (!isActive) { isActive = true; true } else false
            FastForwardMode.TOGGLE -> { isActive = !isActive; true }
        }
    }

    fun buttonUp(): Boolean {
        if (touches == 0) return false
        touches--
        if (touches == 0 && mode == FastForwardMode.HOLD && isActive) {
            isActive = false
            return true
        }
        return false
    }

    fun setActive(active: Boolean) {
        isActive = active
    }

    fun setMode(newMode: FastForwardMode) {
        touches = 0
        if (newMode == FastForwardMode.HOLD) isActive = false
        mode = newMode
    }

    fun setMultiplier(value: Int) {
        multiplier = coerce(value)
    }

    companion object {
        val SUPPORTED_MULTIPLIERS = listOf(2, 3, 4)
        fun coerce(v: Int) = if (v in SUPPORTED_MULTIPLIERS) v else 2
    }
}
