package com.codescv.delta.emulation

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.codescv.delta.cheats.CheatCode
import com.codescv.delta.library.RomEntry
import com.codescv.delta.model.GameSystem
import com.codescv.delta.model.VirtualButton
import com.codescv.delta.saves.SaveFileManager
import com.codescv.delta.saves.SaveStateHeader
import com.codescv.delta.saves.SaveStateInfo
import com.codescv.delta.saves.SaveStateType
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One running game: the native core, a dedicated emulation thread (16 MB stack for melonDS /
 * mupen64plus), a low-latency AudioTrack (MODE_STREAM) pulling from the native resampler,
 * battery-save persistence and save states with live screenshots. Port of iOS
 * `EmulatorSession` + `EmulationLoop` + `AudioOutput`.
 */
class EmulatorSession(
    val rom: RomEntry,
    private val saves: SaveFileManager,
    private val systemDirectory: File,
    private val cacheDirectory: File,
    private val coreOptions: Map<String, String> = emptyMap(),
    fastForwardMode: FastForwardMode = FastForwardMode.TOGGLE,
    fastForwardMultiplier: Int = 2,
) {
    val system: GameSystem = rom.system
    val coreId: String = system.coreId

    interface Listener {
        fun onFrame() {}
        fun onMessage(text: String) {}
        fun onFastForwardChanged(active: Boolean, multiplier: Int) {}
        fun onError(text: String) {}
    }

    @Volatile var listener: Listener? = null
    @Volatile var toastsEnabled = true

    private var handle = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    // Threads -----------------------------------------------------------------------------
    private val running = AtomicBoolean(false)
    @Volatile private var paused = false
    @Volatile private var suspended = false
    private var emuThread: Thread? = null
    private var audioThread: Thread? = null
    private val coreLock = ReentrantLock()
    /** Guards `nativeRenderAudio` vs `nativeDestroy` so the audio thread never touches a freed Session. */
    private val audioLock = Any()

    // Video ----------------------------------------------------------------------------------
    private val frameLock = Any()
    private var frameBuffer: ByteBuffer? = null
    private var frameBitmap: Bitmap? = null
    @Volatile var frameWidth = 0
        private set
    @Volatile var frameHeight = 0
        private set
    @Volatile private var lastSerial = 0L
    var baseWidth = 0
        private set
    var baseHeight = 0
        private set
    var aspectRatio = 0.0
        private set
    var coreFps = 60.0
        private set
    var libraryName = ""
        private set
    var libraryVersion = ""
        private set

    // Audio ----------------------------------------------------------------------------------
    @Volatile var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (handle != 0L) NativeBridge.nativeSetAudioVolume(handle, field)
        }
    @Volatile var muted: Boolean = false
        set(value) {
            field = value
            if (handle != 0L) NativeBridge.nativeSetAudioMuted(handle, value)
        }

    // Input ----------------------------------------------------------------------------------
    @Volatile private var touchMask = 0
    @Volatile private var hardwareMask = 0
    private val held = LinkedHashSet<VirtualButton>()
    val heldButtons: Set<VirtualButton> get() = synchronized(held) { held.toSet() }

    val fastForward = FastForwardController(fastForwardMode, fastForwardMultiplier)
    @Volatile private var speedMultiplier = 1

    // Saves ----------------------------------------------------------------------------------
    private var lastPersistedSram: ByteArray? = null
    private var lastSramCheck = 0L

    val isRunning: Boolean get() = running.get()
    val isPaused: Boolean get() = paused

    /** Creates the core, loads the ROM / SRAM / optional save state, starts threads. */
    fun start(resumeStateId: String? = null, cheats: List<CheatCode> = emptyList()) {
        NativeBridge.ensureLoaded()
        systemDirectory.mkdirs()
        cacheDirectory.mkdirs()
        val saveDir = File(saves.savesDirectory, rom.systemId).apply { mkdirs() }
        // Directories are injected before retro_init (Android has no /tmp).
        handle = NativeBridge.nativeCreate(coreId, systemDirectory.absolutePath, saveDir.absolutePath, cacheDirectory.absolutePath)
        for ((k, v) in defaultCoreOptions(system) + coreOptions) NativeBridge.nativeSetOption(handle, k, v)
        val err = NativeBridge.nativeLoadGame(handle, rom.path)
        if (err != null) {
            NativeBridge.nativeDestroy(handle)
            handle = 0
            throw IllegalStateException("Cannot start ${rom.title}: $err")
        }
        val info = NativeBridge.nativeCoreInfo(handle)
        libraryName = info[0] ?: ""
        libraryVersion = info[1] ?: ""
        refreshGeometry()
        saves.readSaveRam(rom)?.let { sram ->
            if (NativeBridge.nativeLoadSaveRam(handle, sram) == null) lastPersistedSram = sram
        }
        if (resumeStateId != null) loadState(resumeStateId, quiet = true)
        applyCheats(cheats, quiet = true)
        NativeBridge.nativeSetAudioVolume(handle, volume)
        NativeBridge.nativeSetAudioMuted(handle, muted)
        saves.markRunning(rom, true)
        applyFastForward(notify = false)
        running.set(true)
        startEmulationThread()
        startAudioThread()
    }

    private fun refreshGeometry() {
        val g = NativeBridge.nativeVideoGeometry(handle)
        val t = NativeBridge.nativeTiming(handle)
        baseWidth = g[0]
        baseHeight = g[1]
        aspectRatio = t[0]
        coreFps = t[1]
        val maxW = maxOf(g[2], g[0], 1)
        val maxH = maxOf(g[3], g[1], 1)
        synchronized(frameLock) {
            if (frameBuffer == null || frameBuffer!!.capacity() < maxW * maxH * 4) {
                frameBuffer = ByteBuffer.allocateDirect(maxW * maxH * 4).order(ByteOrder.nativeOrder())
            }
        }
    }

    private fun startEmulationThread() {
        val t = Thread(null, ::emulationLoop, "DeltaEmulation", 16L * 1024 * 1024)
        t.priority = Thread.MAX_PRIORITY
        emuThread = t
        t.start()
    }

    private fun emulationLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: Throwable) {
        }
        val pacer = FramePacer(coreFps)
        var last = System.nanoTime()
        var statsStart = last
        var statsFrames = 0L
        val period = 1.0 / maxOf(coreFps, 1.0)
        while (running.get()) {
            val now = System.nanoTime()
            val elapsed = (now - last) / 1e9
            last = now
            if (paused || suspended) {
                pacer.reset()
                Thread.sleep(16)
                continue
            }
            val speed = speedMultiplier
            if (pacer.speedMultiplier != speed) pacer.speedMultiplier = speed
            val frames = pacer.framesForElapsed(elapsed)
            if (frames > 0) {
                coreLock.withLock {
                    if (handle != 0L) NativeBridge.nativeRunFrames(handle, frames, speed > 1)
                }
                publishFrame()
                maybePersistSram(now)
                statsFrames += frames
                if (now - statsStart > 5_000_000_000L) {
                    Log.i(TAG, "emulation %.1f fps (x%d)".format(statsFrames * 1e9 / (now - statsStart), speed))
                    statsStart = now
                    statsFrames = 0
                }
            }
            val spent = (System.nanoTime() - now) / 1e9
            val sleep = maxOf(0.001, period / maxOf(speed, 1) - spent).coerceAtMost(period)
            val ms = (sleep * 1000).toLong()
            val ns = ((sleep * 1e9).toLong() % 1_000_000).toInt()
            Thread.sleep(ms, ns)
        }
    }

    private fun publishFrame() {
        val h = handle
        if (h == 0L) return
        val serial = NativeBridge.nativeVideoSerial(h)
        if (serial == lastSerial) return
        lastSerial = serial
        synchronized(frameLock) {
            val buf = frameBuffer ?: return
            buf.clear()
            val packed = NativeBridge.nativeCopyFrame(h, buf)
            if (packed == 0) return
            val w = packed ushr 16
            val hh = packed and 0xFFFF
            var bmp = frameBitmap
            if (bmp == null || bmp.width != w || bmp.height != hh) {
                bmp = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888)
                frameBitmap = bmp
            }
            buf.position(0)
            buf.limit(w * hh * 4)
            bmp!!.copyPixelsFromBuffer(buf)
            frameWidth = w
            frameHeight = hh
        }
        listener?.onFrame()
    }

    /** Runs [block] under the frame lock with the latest frame bitmap. */
    fun <T> withFrame(block: (Bitmap?) -> T): T = synchronized(frameLock) { block(frameBitmap) }

    /** Copy of the latest frame (for screenshots / save state thumbnails). */
    fun snapshotFrame(): Bitmap? = synchronized(frameLock) { frameBitmap?.copy(Bitmap.Config.ARGB_8888, false) }

    private fun startAudioThread() {
        val t = Thread(null, ::audioLoop, "DeltaAudio", 512L * 1024)
        audioThread = t
        t.start()
    }

    /** Device output rate (AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE), fallback 48 kHz. */
    @Volatile var outputSampleRate: Int = 48000

    private fun audioLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
        }
        val rate = outputSampleRate.takeIf { it in 8000..192000 } ?: 48000
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val tenMsBytes = rate / 100 * 4
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(maxOf(minBuf, 3 * tenMsBytes))
                .build()
        } catch (e: Throwable) {
            Log.w(TAG, "AudioTrack unavailable", e)
            return
        }
        // 5 ms chunks pulled from the native resampler (+-0.5 % fill-rate control).
        val frames = maxOf(64, rate / 200)
        val buffer = ShortArray(frames * 2)
        val silence = ShortArray(frames * 2)
        try {
            track.play()
            while (running.get()) {
                val h = handle
                if (h == 0L || paused || suspended) {
                    track.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                    continue
                }
                val n = synchronized(audioLock) {
                    val live = handle
                    if (live != 0L) NativeBridge.nativeRenderAudio(live, buffer, frames, rate.toDouble()) else 0
                }
                if (n > 0) track.write(buffer, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                else track.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "audio loop failed", e)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    // Lifecycle ----------------------------------------------------------------------------

    fun pause() {
        paused = true
        persistSramIfChanged()
    }

    fun resume() {
        paused = false
    }

    /** App went to background: auto save + SRAM. */
    fun suspend() {
        suspended = true
        persistSramIfChanged(force = true)
        saveState(SaveStateType.AUTO, quiet = true)
    }

    fun resumeFromSuspend() {
        suspended = false
    }

    /** Stops threads, writes SRAM + auto save, destroys the core. */
    fun stop(writeAutoSave: Boolean = true) {
        if (handle == 0L) return
        paused = true
        persistSramIfChanged(force = true)
        if (writeAutoSave) saveState(SaveStateType.AUTO, quiet = true)
        running.set(false)
        emuThread?.join(1500)
        audioThread?.join(1500)
        emuThread = null
        audioThread = null
        synchronized(audioLock) {
            coreLock.withLock {
                NativeBridge.nativeDestroy(handle)
                handle = 0
            }
        }
        saves.markRunning(rom, false)
    }

    fun reset() {
        coreLock.withLock { if (handle != 0L) NativeBridge.nativeReset(handle) }
        message("Restarted ${rom.title}")
    }

    // Input --------------------------------------------------------------------------------

    fun setTouchButtons(buttons: Set<VirtualButton>) {
        touchMask = VirtualButton.mask(buttons)
        pushMask()
    }

    fun setHardwareMask(mask: Int) {
        hardwareMask = mask
        pushMask()
    }

    /** Physical gamepad for [player] (1..4) → libretro port player-1. */
    fun setPlayerMask(player: Int, mask: Int) {
        if (player <= 1) {
            setHardwareMask(mask)
        } else {
            val h = handle
            if (h != 0L && player <= 4) NativeBridge.nativeSetJoypadMask(h, player - 1, mask)
        }
    }

    fun setPlayerAnalog(player: Int, stick: Int, x: Float, y: Float) {
        val h = handle
        if (h == 0L || player !in 1..4) return
        NativeBridge.nativeSetAnalog(h, player - 1, stick, (x.coerceIn(-1f, 1f) * 32767).toInt(), (y.coerceIn(-1f, 1f) * 32767).toInt())
    }

    fun toggleHeldButton(button: VirtualButton) {
        synchronized(held) { if (!held.remove(button)) held += button }
        pushMask()
    }

    fun releaseHeldButtons() {
        synchronized(held) { held.clear() }
        pushMask()
    }

    private fun pushMask() {
        val h = handle
        if (h == 0L) return
        val heldMask = synchronized(held) { VirtualButton.mask(held) }
        NativeBridge.nativeSetJoypadMask(h, 0, touchMask or hardwareMask or heldMask)
    }

    /** Analog stick in -1..1 (x right, y down). */
    fun setAnalog(stick: Int, x: Float, y: Float) {
        val h = handle
        if (h == 0L) return
        NativeBridge.nativeSetAnalog(h, 0, stick, (x.coerceIn(-1f, 1f) * 32767).toInt(), (y.coerceIn(-1f, 1f) * 32767).toInt())
    }

    /** Touch-screen pointer in normalized -1..1 coordinates; null releases. */
    fun setPointer(x: Float?, y: Float?) {
        val h = handle
        if (h == 0L) return
        if (x == null || y == null) {
            NativeBridge.nativeSetPointer(h, false, 0, 0)
        } else {
            NativeBridge.nativeSetPointer(h, true, (x.coerceIn(-1f, 1f) * 32767).toInt(), (y.coerceIn(-1f, 1f) * 32767).toInt())
        }
    }

    // Fast forward -------------------------------------------------------------------------

    fun fastForwardDown() {
        if (fastForward.buttonDown()) applyFastForward()
    }

    fun fastForwardUp() {
        if (fastForward.buttonUp()) applyFastForward()
    }

    fun setFastForwardActive(active: Boolean) {
        fastForward.setActive(active)
        applyFastForward()
    }

    fun configureFastForward(mode: FastForwardMode, multiplier: Int) {
        fastForward.setMode(mode)
        fastForward.setMultiplier(multiplier)
        applyFastForward()
    }

    private fun applyFastForward(notify: Boolean = true) {
        speedMultiplier = fastForward.effectiveMultiplier
        if (notify) {
            val active = fastForward.isActive
            val mult = fastForward.multiplier
            mainHandler.post { listener?.onFastForwardChanged(active, mult) }
        }
    }

    // Cheats -------------------------------------------------------------------------------

    fun applyCheats(cheats: List<CheatCode>, quiet: Boolean = false) {
        coreLock.withLock {
            val h = handle
            if (h == 0L) return
            NativeBridge.nativeClearCheats(h)
            cheats.forEachIndexed { index, cheat ->
                if (cheat.isEnabled) NativeBridge.nativeSetCheat(h, index, true, cheat.code)
            }
        }
        if (!quiet) message("Updated ${cheats.count { it.isEnabled }} active cheat(s)")
    }

    fun setCoreOption(key: String, value: String) {
        val h = handle
        if (h != 0L) NativeBridge.nativeSetOption(h, key, value)
    }

    fun coreOptionsJson(): String = if (handle != 0L) NativeBridge.nativeCoreOptionsJson(handle) else "[]"

    fun setReverseScreens(reversed: Boolean) {
        setCoreOption("delta_ds_reverse_screens", if (reversed) "enabled" else "disabled")
        message(if (reversed) "Reversed DS screens" else "Standard DS screens")
    }

    // Saves --------------------------------------------------------------------------------

    private fun maybePersistSram(nowNanos: Long) {
        if (nowNanos - lastSramCheck < SRAM_INTERVAL_NS) return
        lastSramCheck = nowNanos
        persistSramIfChanged()
    }

    fun persistSramIfChanged(force: Boolean = false) {
        val sram = coreLock.withLock { if (handle != 0L) NativeBridge.nativeGetSaveRam(handle) else null } ?: return
        if (!force && lastPersistedSram != null && sram.contentEquals(lastPersistedSram)) return
        try {
            saves.writeSaveRam(sram, rom)
            lastPersistedSram = sram
        } catch (e: Exception) {
            message("Could not write save: ${e.message}")
        }
    }

    fun saveState(type: SaveStateType, id: String? = null, name: String? = null, quiet: Boolean = false): SaveStateInfo? {
        return try {
            val payload = coreLock.withLock { if (handle != 0L) NativeBridge.nativeSaveState(handle) else null }
                ?: throw IllegalStateException("core does not support save states")
            val header = SaveStateHeader(coreId, rom.systemId, rom.crc32 ?: 0, System.currentTimeMillis() / 1000.0)
            val info = saves.writeState(rom, payload, header, screenshotPng(320), type, id = id, name = name)
            if (!quiet) message("Saved State")
            info
        } catch (e: Exception) {
            if (!quiet) message("Save failed: ${e.message}")
            null
        }
    }

    fun loadState(id: String, quiet: Boolean = false): Boolean {
        return try {
            val (header, payload) = saves.readState(rom, id)
            if (header != null && header.coreId != coreId) {
                throw IllegalStateException("This save state was made with core ${header.coreId}, but this game uses $coreId.")
            }
            val err = coreLock.withLock { NativeBridge.nativeLoadState(handle, payload) }
            if (err != null) throw IllegalStateException(err)
            if (!quiet) message("Loaded State")
            true
        } catch (e: Exception) {
            if (!quiet) message("Load failed: ${e.message}")
            false
        }
    }

    fun screenshotPng(minWidth: Int = 0): ByteArray? {
        val frame = snapshotFrame() ?: return null
        val scaled = if (minWidth > frame.width) {
            val scale = (minWidth + frame.width - 1) / frame.width
            Bitmap.createScaledBitmap(frame, frame.width * scale, frame.height * scale, false)
        } else {
            frame
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun message(text: String) {
        if (!toastsEnabled) return
        mainHandler.post { listener?.onMessage(text) }
    }

    companion object {
        private const val TAG = "DeltaSession"
        private const val SRAM_INTERVAL_NS = 10_000_000_000L

        /** Delta iOS applies no core option overrides; cores run with their own defaults. */
        @Suppress("UNUSED_PARAMETER")
        fun defaultCoreOptions(system: GameSystem): Map<String, String> = emptyMap()
    }
}
