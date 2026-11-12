package com.codescv.delta.saves

import com.codescv.delta.library.RomEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveFileManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val rom by lazy { RomEntry(File(tmp.root, "ROMs/Advance Wars (USA) (Rev 1).gba").absolutePath, "gba", 4 * 1024 * 1024, 0x26FD0FC9L) }
    private val header = SaveStateHeader("gba", "gba", 0x26FD0FC9L, 1_700_000_000.0)

    @Test
    fun containerRoundTripIsByteCompatible() {
        val payload = ByteArray(1000) { (it * 7).toByte() }
        val encoded = SaveStateContainer.encode(payload, header)
        assertArrayEquals("LMST".toByteArray(), encoded.copyOfRange(0, 4))
        assertEquals(1, encoded[4].toInt())
        val (h, p) = SaveStateContainer.decode(encoded)
        assertEquals(header, h)
        assertArrayEquals(payload, p)
        // Raw core states pass through without a header.
        val (h2, p2) = SaveStateContainer.decode(payload)
        assertNull(h2)
        assertArrayEquals(payload, p2)
    }

    @Test(expected = SaveTransferException::class)
    fun truncatedContainerRejected() {
        val encoded = SaveStateContainer.encode(ByteArray(10), header)
        SaveStateContainer.decode(encoded.copyOf(encoded.size - 3))
    }

    @Test
    fun sectionsAutoQuickGeneralLocked() {
        val m = SaveFileManager(tmp.root)
        m.writeState(rom, byteArrayOf(1), header, byteArrayOf(9), SaveStateType.AUTO)
        m.writeState(rom, byteArrayOf(2), header, null, SaveStateType.QUICK)
        val g1 = m.writeState(rom, byteArrayOf(3), header, null, SaveStateType.GENERAL)
        val g2 = m.writeState(rom, byteArrayOf(4), header, null, SaveStateType.GENERAL, name = "Boss")
        m.setLocked(rom, g2.id, true)
        val s = m.sections(rom)
        assertEquals(1, s[SaveStateType.AUTO]!!.size)
        assertEquals(1, s[SaveStateType.QUICK]!!.size)
        assertEquals(listOf(g1.id), s[SaveStateType.GENERAL]!!.map { it.id })
        assertEquals(listOf("Boss"), s[SaveStateType.LOCKED]!!.map { it.name })
        assertTrue(m.screenshotFile(rom, SaveFileManager.AUTO_ID).isFile)
        // Auto save is overwritten in place.
        m.writeState(rom, byteArrayOf(5), header, null, SaveStateType.AUTO)
        assertEquals(1, m.sections(rom)[SaveStateType.AUTO]!!.size)
        assertArrayEquals(byteArrayOf(5), m.readState(rom, SaveFileManager.AUTO_ID).second)
    }

    @Test
    fun lockedStatesCannotBeOverwrittenOrDeletedWithoutForce() {
        val m = SaveFileManager(tmp.root)
        val g = m.writeState(rom, byteArrayOf(3), header, null, SaveStateType.GENERAL)
        m.setLocked(rom, g.id, true)
        try {
            m.writeState(rom, byteArrayOf(4), header, null, SaveStateType.GENERAL, id = g.id)
            throw AssertionError("expected locked")
        } catch (e: SaveTransferException) {
            assertTrue(e.message!!.contains("locked"))
        }
        try {
            m.deleteState(rom, g.id, force = false)
            throw AssertionError("expected locked")
        } catch (e: SaveTransferException) {
            // expected
        }
        m.setLocked(rom, g.id, false)
        assertTrue(m.deleteState(rom, g.id, force = false))
        assertTrue(m.listStates(rom).isEmpty())
    }

    @Test
    fun renameExportImport() {
        val m = SaveFileManager(tmp.root)
        val g = m.writeState(rom, byteArrayOf(1, 2, 3), header, null, SaveStateType.GENERAL)
        assertEquals("Before Boss", m.rename(rom, g.id, "  Before Boss ")!!.name)
        val exported = m.exportState(rom, g.id)
        val imported = m.importState(exported, rom, expectedCoreId = "gba")
        assertArrayEquals(byteArrayOf(1, 2, 3), m.readState(rom, imported.id).second)
        try {
            m.importState(SaveStateContainer.encode(byteArrayOf(1), header.copy(systemId = "nes")), rom, "gba")
            throw AssertionError("expected wrong system")
        } catch (e: SaveTransferException) {
            assertTrue(e.message!!.contains("nes"))
        }
    }

    @Test
    fun saveRamImportExportValidation() {
        val m = SaveFileManager(tmp.root)
        try {
            m.exportSaveRam(rom)
            throw AssertionError()
        } catch (e: SaveTransferException) {
            assertEquals("This game has no save yet.", e.message)
        }
        val sav = ByteArray(32 * 1024) { 0x5A }
        m.importSaveRam(sav, rom)
        assertArrayEquals(sav, m.exportSaveRam(rom))
        assertEquals("Advance Wars (USA) (Rev 1).sav", m.suggestedSaveRamFileName(rom))
        assertTrue(m.saveRamFile(rom).path.endsWith("Saves/gba/Advance Wars (USA) (Rev 1).srm"))
        for (bad in listOf(ByteArray(0), byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0))) {
            try {
                m.importSaveRam(bad, rom)
                throw AssertionError()
            } catch (e: SaveTransferException) {
                // expected
            }
        }
        m.markRunning(rom, true)
        try {
            m.exportSaveRam(rom)
            throw AssertionError()
        } catch (e: SaveTransferException) {
            assertTrue(e.message!!.startsWith("Close the running game"))
        }
        m.markRunning(rom, false)
        m.deleteAllData(rom)
        assertFalse(m.hasSaveRam(rom))
    }
}
