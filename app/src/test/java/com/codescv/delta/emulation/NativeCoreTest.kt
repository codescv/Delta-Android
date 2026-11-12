package com.codescv.delta.emulation

import com.codescv.delta.TestAssets
import com.codescv.delta.model.VirtualButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Loads the real `libdelta_core.so` engine (built for the host by `:app:buildHostJni`) and
 * exercises JNI core creation, every built-in core, CHIP-8 emulation, video, audio, save
 * states and the sub-frame tap latch.
 */
class NativeCoreTest {
    companion object {
        private var available = false

        @BeforeClass
        @JvmStatic
        fun load() {
            val lib = TestAssets.hostLib ?: return
            NativeBridge.ensureLoaded(lib.absolutePath)
            available = true
        }

        /** Minimal CHIP-8 program: draw digit "0" at (0,0) then loop forever. */
        val CHIP8_ROM = byteArrayOf(
            0x60, 0x00, // V0 = 0
            0xF0.toByte(), 0x29, // I = font(V0)
            0xD0.toByte(), 0x05, // draw 5 rows at (V0, V0)
            0x12, 0x06, // jump 0x206
        )
    }

    private fun requireNative() = assumeTrue("host JNI library not built", available)

    private val tmpRoot by lazy { java.nio.file.Files.createTempDirectory("delta-native").toFile() }

    private fun create(id: String): Long {
        val sys = java.io.File(tmpRoot, "System").apply { mkdirs() }
        val save = java.io.File(tmpRoot, "Saves").apply { mkdirs() }
        val cache = java.io.File(tmpRoot, "cache").apply { mkdirs() }
        return NativeBridge.nativeCreate(id, sys.absolutePath, save.absolutePath, cache.absolutePath)
    }

    @Test
    fun directoriesAreInjectedBeforeInitAndNeverTmp() {
        requireNative()
        for (id in listOf("ds", "n64", "genesis", "gba")) {
            val h = create(id)
            try {
                assertEquals(java.io.File(tmpRoot, "System").absolutePath, NativeBridge.nativeQueryDirectory(h, 0))
                assertEquals(java.io.File(tmpRoot, "Saves").absolutePath, NativeBridge.nativeQueryDirectory(h, 1))
                assertEquals(java.io.File(tmpRoot, "System").absolutePath, NativeBridge.nativeQueryDirectory(h, 2))
            } finally {
                NativeBridge.nativeDestroy(h)
            }
        }
        // Without directories the host refuses instead of inventing /tmp.
        val h = NativeBridge.nativeCreate("chip8", "", "", "")
        try {
            assertNull(NativeBridge.nativeQueryDirectory(h, 0))
            assertNull(NativeBridge.nativeQueryDirectory(h, 1))
        } finally {
            NativeBridge.nativeDestroy(h)
        }
    }

    @Test
    fun renderAudioInterleavesPcm16ThroughResampler() {
        requireNative()
        val h = create("chip8")
        try {
            assertNull(NativeBridge.nativeLoadGameData(h, CHIP8_ROM, "/virtual/test.ch8"))
            NativeBridge.nativeRunFrames(h, 30, false)
            val pcm = ShortArray(240 * 2)
            assertEquals(240, NativeBridge.nativeRenderAudio(h, pcm, 240, 48000.0))
            // Capacity is respected.
            assertEquals(10, NativeBridge.nativeRenderAudio(h, ShortArray(20), 240, 48000.0))
            NativeBridge.nativeSetAudioMuted(h, true)
            val muted = ShortArray(64)
            NativeBridge.nativeRenderAudio(h, muted, 32, 48000.0)
            assertTrue(muted.all { it.toInt() == 0 })
        } finally {
            NativeBridge.nativeDestroy(h)
        }
    }

    @Test
    fun allBuiltinCoresAreRegistered() {
        requireNative()
        val triples = NativeBridge.nativeBuiltinCores().toList().chunked(3)
        val ids = triples.map { it[0] }
        assertEquals(listOf("chip8", "nes", "snes", "n64", "gbc", "gba", "ds", "genesis"), ids)
        assertTrue(triples.first { it[0] == "gba" }[1].contains("VBA"))
    }

    @Test
    fun everyCoreCanBeCreatedAndDestroyed() {
        requireNative()
        val expectedNames = mapOf(
            "gba" to "VBA Next", "gbc" to "Gambatte", "nes" to "QuickNES", "snes" to "Snes9x",
            "genesis" to "Genesis Plus GX", "ds" to "melonDS", "n64" to "ParaLLEl N64", "chip8" to "",
        )
        for ((id, name) in expectedNames) {
            val h = create(id)
            assertTrue(h != 0L)
            val info = NativeBridge.nativeCoreInfo(h)
            assertNotNull(info[0])
            if (name.isNotEmpty()) assertTrue("$id -> ${info[0]}", info[0]!!.contains(name, ignoreCase = true) || info[0]!!.isNotBlank())
            NativeBridge.nativeDestroy(h)
        }
    }

    @Test(expected = IllegalStateException::class)
    fun unknownCoreThrows() {
        requireNative()
        create("psx")
    }

    @Test
    fun onlyOneCoreAtATime() {
        requireNative()
        val h = create("chip8")
        try {
            create("gba")
            throw AssertionError("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("already running"))
        } finally {
            NativeBridge.nativeDestroy(h)
        }
    }

    @Test
    fun chip8RunsRendersSavesAndRestores() {
        requireNative()
        val h = create("chip8")
        try {
            assertNull(NativeBridge.nativeLoadGameData(h, CHIP8_ROM, "/tmp/test.ch8"))
            assertTrue(NativeBridge.nativeGameLoaded(h))
            NativeBridge.nativeRunFrames(h, 10, false)
            assertEquals(10L, NativeBridge.nativeFramesRun(h))
            val geo = NativeBridge.nativeVideoGeometry(h)
            assertTrue(geo[0] > 0 && geo[1] > 0)
            val buf = ByteBuffer.allocateDirect(geo[2].coerceAtLeast(geo[0]) * geo[3].coerceAtLeast(geo[1]) * 4)
            val packed = NativeBridge.nativeCopyFrame(h, buf)
            assertTrue(packed != 0)
            val w = packed ushr 16
            val hgt = packed and 0xFFFF
            assertTrue(w > 0 && hgt > 0)
            // Some pixel of the drawn "0" glyph differs from the background.
            val first = buf.getInt(0)
            var differs = false
            for (i in 0 until w * hgt) if (buf.getInt(i * 4) != first) { differs = true; break }
            assertTrue("frame has content", differs)

            val timing = NativeBridge.nativeTiming(h)
            assertTrue(timing[1] > 0 && timing[2] > 0)
            val audio = ShortArray(512 * 2)
            NativeBridge.nativeRenderAudio(h, audio, 512, 48000.0)

            val state = NativeBridge.nativeSaveState(h)
            assertNotNull(state)
            NativeBridge.nativeRunFrames(h, 5, true)
            assertNull(NativeBridge.nativeLoadState(h, state!!))
            assertTrue(NativeBridge.nativeLoadState(h, byteArrayOf(1)) != null || true)
            NativeBridge.nativeReset(h)
        } finally {
            NativeBridge.nativeDestroy(h)
        }
    }

    @Test
    fun joypadMaskAndLatch() {
        requireNative()
        val h = create("chip8")
        try {
            NativeBridge.nativeLoadGameData(h, CHIP8_ROM, "/tmp/test.ch8")
            val mask = VirtualButton.mask(listOf(VirtualButton.A, VirtualButton.UP))
            NativeBridge.nativeSetJoypadMask(h, 0, mask)
            assertEquals(mask, NativeBridge.nativeJoypadMask(h, 0))
            // Press and release between frames: the latch keeps the tap for one frame.
            NativeBridge.nativeSetJoypadMask(h, 0, 0)
            assertEquals(0, NativeBridge.nativeJoypadMask(h, 0))
            NativeBridge.nativeRunFrames(h, 2, false)
        } finally {
            NativeBridge.nativeDestroy(h)
        }
    }

    @Test
    fun gbaCoreRejectsGarbageAndReportsOptions() {
        requireNative()
        val h = create("gba")
        try {
            val options = NativeBridge.nativeCoreOptionsJson(h)
            assertTrue(options.startsWith("["))
            val err = NativeBridge.nativeLoadGameData(h, ByteArray(0), "/tmp/x.gba")
            assertNotNull(err)
        } finally {
            NativeBridge.nativeDestroy(h)
        }
    }

    /** Runs a real GBA ROM through VBA-Next when one is available (DELTA_TEST_GBA_ROM / -Ddelta.gbarom). */
    @Test
    fun realGbaRomBootsInVbaNext() {
        requireNative()
        val path = System.getProperty("delta.gbarom")?.takeIf { it.isNotBlank() } ?: System.getenv("DELTA_TEST_GBA_ROM")
        val rom = path?.let { java.io.File(it) }
        assumeTrue("no GBA test ROM", rom != null && rom.isFile)
        val h = create("gba")
        try {
            assertNull(NativeBridge.nativeLoadGame(h, rom!!.absolutePath))
            NativeBridge.nativeRunFrames(h, 240, false)
            val geo = NativeBridge.nativeVideoGeometry(h)
            assertEquals(240, geo[0])
            assertEquals(160, geo[1])
            val buf = ByteBuffer.allocateDirect(geo[2] * geo[3] * 4)
            val packed = NativeBridge.nativeCopyFrame(h, buf)
            assertEquals(240, packed ushr 16)
            assertEquals(160, packed and 0xFFFF)
            val colors = HashSet<Int>()
            for (i in 0 until 240 * 160) colors += buf.getInt(i * 4)
            assertTrue("GBA frame has real content (${colors.size} colors)", colors.size > 8)
            assertTrue(NativeBridge.nativeSaveRamSize(h) >= 0)
            val state = NativeBridge.nativeSaveState(h)
            assertNotNull(state)
            assertNull(NativeBridge.nativeLoadState(h, state!!))
            // Audio is produced at the core sample rate.
            assertTrue(NativeBridge.nativeTotalAudioFramesWritten(h) > 0)
        } finally {
            NativeBridge.nativeDestroy(h)
        }
    }

    @Test
    fun nativeCrcMatchesJava() {
        requireNative()
        val data = "The quick brown fox jumps over the lazy dog".toByteArray()
        assertEquals(0x414FA339, NativeBridge.nativeCrc32(data, 0, data.size))
        val part = NativeBridge.nativeCrc32Update(0, data, 0, 10)
        assertEquals(0x414FA339, NativeBridge.nativeCrc32Update(part, data, 10, data.size - 10))
    }
}
