package com.codescv.delta.input

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.codescv.delta.skins.GamepadTouchMapper
import com.codescv.delta.skins.GamepadTouchTracker
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Single full-bleed transparent View that owns all on-screen controller touches
 * (Delta's `MultiTouchGamepadView`): per-pointerId tracking via [GamepadTouchTracker], presses on
 * touch-down, B→A slides, chords and D-Pad lock-on. Touches outside every control pass through.
 */
@SuppressLint("ViewConstructor")
class MultiTouchGamepadSurface(context: Context) : View(context) {
    val tracker = GamepadTouchTracker(EmptyMapper)

    var mapper: GamepadTouchMapper
        get() = tracker.mapper
        set(value) {
            if (tracker.mapper !== value) {
                tracker.releaseAll()
                tracker.mapper = value
            }
        }

    init {
        isClickable = false
        isFocusable = false
        setBackgroundColor(0)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val x = event.getX(i)
                val y = event.getY(i)
                // Unclaimed touches (game screen area) are ignored but still consumed so a
                // second thumb landing on a button is delivered to this view.
                if (tracker.claims(x, y)) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    tracker.pointerDown(event.getPointerId(i), x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    tracker.pointerMove(event.getPointerId(i), event.getX(i), event.getY(i))
                }
                tracker.commitMoves()
            }
            MotionEvent.ACTION_POINTER_UP -> tracker.pointerUp(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_UP -> {
                tracker.pointerUp(event.getPointerId(event.actionIndex))
                tracker.releaseAll()
            }
            MotionEvent.ACTION_CANCEL -> tracker.releaseAll()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        tracker.releaseAll()
        super.onDetachedFromWindow()
    }

    private object EmptyMapper : GamepadTouchMapper {
        override fun lockTarget(x: Float, y: Float): Int? = null
        override fun sample(x: Float, y: Float, locked: Int?) = com.codescv.delta.skins.GamepadTouchSample()
        override fun claims(x: Float, y: Float) = false
    }
}

/**
 * Button haptics (`VibrationEffect` CLICK/TICK scaled by strength) and the optional button click
 * sound (SoundPool, generated 20 ms tick at volume 0.3, respecting silent mode).
 */
class HapticsController(private val context: Context, private val host: View) {
    var enabled = true
    var thumbstickEnabled = true
    var clickSound = false
    /** `light` | `medium` | `strong`. */
    var strength = "medium"

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var soundPool: SoundPool? = null
    private var clickId = 0

    private fun amplitude(): Int = when (strength) {
        "light" -> 64
        "strong" -> 255
        else -> 140
    }

    fun perform(kind: GamepadTouchTracker.HapticKind) {
        if (kind == GamepadTouchTracker.HapticKind.PRESS && clickSound) playClick()
        if (!enabled) return
        if (kind == GamepadTouchTracker.HapticKind.MOVE && !thumbstickEnabled) return
        val v = vibrator
        if (v == null || !v.hasVibrator()) {
            host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            return
        }
        val effect = if (v.hasAmplitudeControl()) {
            val ms = when (kind) {
                GamepadTouchTracker.HapticKind.PRESS -> 14L
                GamepadTouchTracker.HapticKind.MOVE -> 8L
                GamepadTouchTracker.HapticKind.RELEASE -> 8L
            }
            val amp = if (kind == GamepadTouchTracker.HapticKind.PRESS) amplitude() else (amplitude() * 0.6f).toInt().coerceAtLeast(1)
            VibrationEffect.createOneShot(ms, amp)
        } else if (Build.VERSION.SDK_INT >= 29) {
            VibrationEffect.createPredefined(
                if (kind == GamepadTouchTracker.HapticKind.PRESS) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK,
            )
        } else {
            VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        runCatching { v.vibrate(effect) }
    }

    private fun playClick() {
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val pool = soundPool ?: SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            .build().also { p ->
                soundPool = p
                clickId = p.load(clickWav().absolutePath, 1)
            }
        if (clickId != 0) pool.play(clickId, 0.3f, 0.3f, 1, 0, 1f)
    }

    private fun clickWav(): File {
        val f = File(context.cacheDir, "delta_click.wav")
        if (f.isFile) return f
        val rate = 44100
        val n = rate / 50 // 20 ms
        val pcm = ShortArray(n) { i ->
            val t = i.toDouble() / rate
            (sin(2 * PI * 2200 * t) * exp(-t * 300) * 12000).toInt().toShort()
        }
        val data = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort(it) }
        val out = ByteArrayOutputStream()
        fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
        out.write("RIFF".toByteArray()); out.write(le32(36 + n * 2)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(le32(16)); out.write(le16(1)); out.write(le16(1))
        out.write(le32(rate)); out.write(le32(rate * 2)); out.write(le16(2)); out.write(le16(16))
        out.write("data".toByteArray()); out.write(le32(n * 2)); out.write(data.array())
        f.writeBytes(out.toByteArray())
        return f
    }

    fun release() {
        soundPool?.release()
        soundPool = null
    }
}
