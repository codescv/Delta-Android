package com.codescv.delta.emulation

import java.nio.ByteBuffer

/**
 * JNI surface of `libdelta_core.so` (app/src/main/cpp/jni/delta_jni.cpp), the Android
 * counterpart of iOS Delta's `LREmulatorCore` Objective-C++ bridge.
 *
 * All natives are registered in `JNI_OnLoad`; handles are opaque native `Session*` pointers.
 */
object NativeBridge {
    @Volatile
    private var loaded = false

    /** Loads the engine. On the JVM (unit tests) pass the absolute host library path. */
    @Synchronized
    fun ensureLoaded(hostLibraryPath: String? = null) {
        if (loaded) return
        if (hostLibraryPath != null) System.load(hostLibraryPath) else System.loadLibrary("delta_core")
        loaded = true
    }

    val isLoaded: Boolean get() = loaded

    // Discovery & lifecycle -----------------------------------------------------------------
    /** Flattened triples of (coreId, displayName, systemId). */
    @JvmStatic external fun nativeBuiltinCores(): Array<String>
    @JvmStatic external fun nativeCreate(coreId: String): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    /** [libraryName, libraryVersion, validExtensions]. */
    @JvmStatic external fun nativeCoreInfo(handle: Long): Array<String?>
    @JvmStatic external fun nativeSetDirectories(handle: Long, systemDir: String, saveDir: String)
    @JvmStatic external fun nativeSetOption(handle: Long, key: String, value: String)
    @JvmStatic external fun nativeGetOption(handle: Long, key: String): String
    @JvmStatic external fun nativeCoreOptionsJson(handle: Long): String
    /** Returns null on success, otherwise the error message. */
    @JvmStatic external fun nativeLoadGame(handle: Long, path: String): String?
    @JvmStatic external fun nativeLoadGameData(handle: Long, data: ByteArray, virtualPath: String): String?
    @JvmStatic external fun nativeUnloadGame(handle: Long)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeGameLoaded(handle: Long): Boolean
    @JvmStatic external fun nativeRunFrames(handle: Long, count: Int, fastForward: Boolean)
    @JvmStatic external fun nativeFramesRun(handle: Long): Long

    // Input ----------------------------------------------------------------------------------
    @JvmStatic external fun nativeSetJoypadMask(handle: Long, port: Int, mask: Int)
    @JvmStatic external fun nativeJoypadMask(handle: Long, port: Int): Int
    @JvmStatic external fun nativeSetAnalog(handle: Long, port: Int, index: Int, x: Int, y: Int)
    @JvmStatic external fun nativeSetPointer(handle: Long, pressed: Boolean, x: Int, y: Int)
    @JvmStatic external fun nativeClearCheats(handle: Long)
    @JvmStatic external fun nativeSetCheat(handle: Long, index: Int, enabled: Boolean, code: String)

    // Video ----------------------------------------------------------------------------------
    /** [baseWidth, baseHeight, maxWidth, maxHeight, rotation, pixelFormat]. */
    @JvmStatic external fun nativeVideoGeometry(handle: Long): IntArray
    /** [aspectRatio, fps, sampleRate]. */
    @JvmStatic external fun nativeTiming(handle: Long): DoubleArray
    @JvmStatic external fun nativeVideoSerial(handle: Long): Long
    /** Copies the latest RGBA8888 frame; returns (width shl 16) or height, or 0. */
    @JvmStatic external fun nativeCopyFrame(handle: Long, directBuffer: ByteBuffer): Int

    // Audio ----------------------------------------------------------------------------------
    @JvmStatic external fun nativeRenderAudio(handle: Long, interleaved: FloatArray, frames: Int, outputRate: Double): Int
    @JvmStatic external fun nativeSetAudioMuted(handle: Long, muted: Boolean)
    @JvmStatic external fun nativeSetAudioVolume(handle: Long, volume: Float)
    @JvmStatic external fun nativeQueuedAudioFrames(handle: Long): Int
    @JvmStatic external fun nativeTotalAudioFramesWritten(handle: Long): Long

    // Saves ----------------------------------------------------------------------------------
    @JvmStatic external fun nativeSaveState(handle: Long): ByteArray?
    @JvmStatic external fun nativeLoadState(handle: Long, data: ByteArray): String?
    @JvmStatic external fun nativeSaveRamSize(handle: Long): Int
    @JvmStatic external fun nativeGetSaveRam(handle: Long): ByteArray?
    @JvmStatic external fun nativeLoadSaveRam(handle: Long, data: ByteArray): String?

    // Utilities ------------------------------------------------------------------------------
    @JvmStatic external fun nativeCrc32(data: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun nativeCrc32Update(crc: Int, data: ByteArray, offset: Int, length: Int): Int
}
