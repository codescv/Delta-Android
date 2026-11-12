package com.codescv.delta.saves

import com.codescv.delta.library.RomEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class SaveTransferException(message: String) : Exception(message) {
    companion object {
        fun gameRunning() = SaveTransferException("Close the running game before transferring saves.")
        fun emptyFile() = SaveTransferException("The selected file is empty.")
        fun tooLarge(size: Int) = SaveTransferException("The selected file is too large ($size bytes, max 16 MiB).")
        fun zipArchive() = SaveTransferException("That file is a ZIP archive, not a raw save. Extract the .srm/.sav first.")
        fun noSaveYet() = SaveTransferException("This game has no save yet.")
        fun verificationFailed() = SaveTransferException("The save could not be verified after writing.")
        fun invalidState(why: String) = SaveTransferException("Not a valid save state: $why.")
        fun wrongSystem(expected: String, found: String) = SaveTransferException("This save state is for $found, not $expected.")
        fun wrongCore(expected: String, found: String) =
            SaveTransferException("This save state was made with core $found, but this game uses $expected.")
        fun locked() = SaveTransferException("This save state is locked. Unlock it first.")
    }
}

data class SaveStateHeader(
    val coreId: String,
    val systemId: String,
    val romCrc: Long,
    val createdAtSeconds: Double,
)

/** Delta's `SaveStateContainer` ("LMST" v1, little-endian), byte-compatible with iOS. */
object SaveStateContainer {
    val MAGIC = byteArrayOf('L'.code.toByte(), 'M'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte())
    const val VERSION = 1

    fun encode(payload: ByteArray, header: SaveStateHeader): ByteArray {
        val core = header.coreId.toByteArray(Charsets.UTF_8).take(255).toByteArray()
        val system = header.systemId.toByteArray(Charsets.UTF_8).take(255).toByteArray()
        val buf = ByteBuffer.allocate(4 + 4 + 2 + core.size + 2 + system.size + 4 + 8 + 4 + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC)
        buf.putInt(VERSION)
        buf.putShort(core.size.toShort()); buf.put(core)
        buf.putShort(system.size.toShort()); buf.put(system)
        buf.putInt(header.romCrc.toInt())
        buf.putLong(java.lang.Double.doubleToRawLongBits(header.createdAtSeconds))
        buf.putInt(payload.size)
        buf.put(payload)
        return buf.array()
    }

    /** Returns (header or null for a raw core state, payload). */
    fun decode(data: ByteArray): Pair<SaveStateHeader?, ByteArray> {
        if (data.isEmpty()) throw SaveTransferException.emptyFile()
        if (data.size < 4 || !data.copyOfRange(0, 4).contentEquals(MAGIC)) {
            if (data.size >= 4 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte() && data[2] == 0x03.toByte() && data[3] == 0x04.toByte()) {
                throw SaveTransferException.zipArchive()
            }
            return null to data
        }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(4)
        fun need(n: Int) {
            if (buf.remaining() < n) throw SaveTransferException.invalidState("truncated header")
        }
        fun str(): String {
            need(2)
            val n = buf.short.toInt() and 0xFFFF
            need(n)
            val b = ByteArray(n)
            buf.get(b)
            return String(b, Charsets.UTF_8)
        }
        need(4)
        val ver = buf.int
        if (ver != VERSION) throw SaveTransferException.invalidState("unsupported version $ver")
        val core = str()
        val system = str()
        need(4)
        val crc = buf.int.toLong() and 0xFFFFFFFFL
        need(8)
        val time = java.lang.Double.longBitsToDouble(buf.long)
        need(4)
        val length = buf.int
        if (length <= 0 || buf.remaining() != length) throw SaveTransferException.invalidState("payload length mismatch")
        val payload = ByteArray(length)
        buf.get(payload)
        return SaveStateHeader(core, system, crc, time) to payload
    }
}

/** Delta save state kinds (sections in the Save States screen). */
enum class SaveStateType { AUTO, QUICK, GENERAL, LOCKED }

@Serializable
data class SaveStateInfo(
    val id: String,
    val type: SaveStateType,
    val name: String? = null,
    val createdAt: Long,
    val modifiedAt: Long,
    val coreId: String,
    val systemId: String,
) {
    val isLocked: Boolean get() = type == SaveStateType.LOCKED
}

/**
 * Battery saves (`Saves/<system>/<base>.srm`) and save states
 * (`States/<system>/<base>/<id>.state|.png|.json`) for library games.
 */
class SaveFileManager(val root: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }
    private val running = HashSet<String>()

    val savesDirectory get() = File(root, "Saves")
    val statesDirectory get() = File(root, "States")
    val systemDirectory get() = File(root, "System")
    val cheatsDirectory get() = File(root, "Cheats")

    @Synchronized
    fun markRunning(rom: RomEntry, isRunning: Boolean) {
        if (isRunning) running += rom.id else running -= rom.id
    }

    @Synchronized
    fun isRunning(rom: RomEntry) = rom.id in running

    private fun ensureIdle(rom: RomEntry) {
        if (isRunning(rom)) throw SaveTransferException.gameRunning()
    }

    // Battery SRAM -------------------------------------------------------------------------

    fun saveRamFile(rom: RomEntry) = File(File(savesDirectory, rom.systemId), rom.baseName + ".srm")

    fun hasSaveRam(rom: RomEntry) = saveRamFile(rom).isFile

    fun readSaveRam(rom: RomEntry): ByteArray? = saveRamFile(rom).takeIf { it.isFile }?.readBytes()

    fun writeSaveRam(data: ByteArray, rom: RomEntry) = atomicWrite(saveRamFile(rom), data)

    fun importSaveRam(data: ByteArray, rom: RomEntry) {
        ensureIdle(rom)
        validateSaveRam(data)
        writeSaveRam(data, rom)
        if (!(readSaveRam(rom) contentEquals data)) throw SaveTransferException.verificationFailed()
        // A stale auto save would immediately overwrite the imported battery save.
        deleteState(rom, AUTO_ID)
    }

    fun exportSaveRam(rom: RomEntry): ByteArray {
        ensureIdle(rom)
        val data = readSaveRam(rom)
        if (data == null || data.isEmpty()) throw SaveTransferException.noSaveYet()
        return data
    }

    fun suggestedSaveRamFileName(rom: RomEntry, sav: Boolean = true) = rom.baseName + if (sav) ".sav" else ".srm"

    // Save states --------------------------------------------------------------------------

    fun stateDirectory(rom: RomEntry) = File(File(statesDirectory, rom.systemId), rom.baseName)
    fun stateFile(rom: RomEntry, id: String) = File(stateDirectory(rom), "$id.state")
    fun screenshotFile(rom: RomEntry, id: String) = File(stateDirectory(rom), "$id.png")
    private fun metaFile(rom: RomEntry, id: String) = File(stateDirectory(rom), "$id.json")

    fun listStates(rom: RomEntry): List<SaveStateInfo> {
        val dir = stateDirectory(rom)
        val metas = dir.listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return metas.mapNotNull { f ->
            runCatching { json.decodeFromString<SaveStateInfo>(f.readText()) }.getOrNull()
                ?.takeIf { stateFile(rom, it.id).isFile }
        }.sortedByDescending { it.modifiedAt }
    }

    fun state(rom: RomEntry, id: String): SaveStateInfo? =
        runCatching { json.decodeFromString<SaveStateInfo>(metaFile(rom, id).readText()) }.getOrNull()

    /** Sections in Delta order: Auto Save, Quick Save, General, Locked. */
    fun sections(rom: RomEntry): Map<SaveStateType, List<SaveStateInfo>> {
        val all = listStates(rom)
        return SaveStateType.entries.associateWith { t -> all.filter { it.type == t } }
    }

    fun writeState(
        rom: RomEntry,
        payload: ByteArray,
        header: SaveStateHeader,
        screenshotPng: ByteArray?,
        type: SaveStateType,
        id: String = idFor(type),
        name: String? = null,
    ): SaveStateInfo {
        val existing = state(rom, id)
        if (existing?.isLocked == true) throw SaveTransferException.locked()
        val now = System.currentTimeMillis()
        atomicWrite(stateFile(rom, id), SaveStateContainer.encode(payload, header))
        val shot = screenshotFile(rom, id)
        if (screenshotPng != null) atomicWrite(shot, screenshotPng) else shot.delete()
        val info = SaveStateInfo(
            id = id,
            type = type,
            name = name ?: existing?.name,
            createdAt = existing?.createdAt ?: now,
            modifiedAt = now,
            coreId = header.coreId,
            systemId = header.systemId,
        )
        atomicWrite(metaFile(rom, id), json.encodeToString(info).toByteArray())
        return info
    }

    fun readState(rom: RomEntry, id: String): Pair<SaveStateHeader?, ByteArray> {
        val f = stateFile(rom, id)
        if (!f.isFile) throw SaveTransferException.noSaveYet()
        return SaveStateContainer.decode(f.readBytes())
    }

    fun rename(rom: RomEntry, id: String, name: String?): SaveStateInfo? {
        val info = state(rom, id) ?: return null
        val updated = info.copy(name = name?.trim()?.takeIf { it.isNotEmpty() })
        atomicWrite(metaFile(rom, id), json.encodeToString(updated).toByteArray())
        return updated
    }

    fun setLocked(rom: RomEntry, id: String, locked: Boolean): SaveStateInfo? {
        val info = state(rom, id) ?: return null
        val type = if (locked) SaveStateType.LOCKED else SaveStateType.GENERAL
        val updated = info.copy(type = type)
        atomicWrite(metaFile(rom, id), json.encodeToString(updated).toByteArray())
        return updated
    }

    fun deleteState(rom: RomEntry, id: String, force: Boolean = true): Boolean {
        val info = state(rom, id)
        if (!force && info?.isLocked == true) throw SaveTransferException.locked()
        val a = stateFile(rom, id).delete()
        screenshotFile(rom, id).delete()
        metaFile(rom, id).delete()
        return a
    }

    fun latestScreenshot(rom: RomEntry): File? =
        listStates(rom).firstOrNull { screenshotFile(rom, it.id).isFile }?.let { screenshotFile(rom, it.id) }

    fun exportState(rom: RomEntry, id: String): ByteArray {
        val f = stateFile(rom, id)
        if (!f.isFile || f.length() == 0L) throw SaveTransferException.noSaveYet()
        return f.readBytes()
    }

    fun importState(data: ByteArray, rom: RomEntry, expectedCoreId: String?, name: String? = null): SaveStateInfo {
        if (data.size > MAX_SAVE_BYTES * 8) throw SaveTransferException.tooLarge(data.size)
        val (header, payload) = SaveStateContainer.decode(data)
        if (header != null) {
            if (header.systemId != rom.systemId) throw SaveTransferException.wrongSystem(rom.systemId, header.systemId)
            if (expectedCoreId != null && header.coreId != expectedCoreId) throw SaveTransferException.wrongCore(expectedCoreId, header.coreId)
        }
        val normalized = header ?: SaveStateHeader(expectedCoreId ?: "unknown", rom.systemId, rom.crc32 ?: 0, System.currentTimeMillis() / 1000.0)
        val info = writeState(rom, payload, normalized, null, SaveStateType.GENERAL, name = name ?: "Imported")
        if (!stateFile(rom, info.id).isFile) throw SaveTransferException.verificationFailed()
        return info
    }

    fun deleteAllData(rom: RomEntry) {
        saveRamFile(rom).delete()
        stateDirectory(rom).deleteRecursively()
    }

    companion object {
        const val AUTO_ID = "auto"
        const val QUICK_ID = "quick"
        const val MAX_SAVE_BYTES = 16 * 1024 * 1024

        fun idFor(type: SaveStateType): String = when (type) {
            SaveStateType.AUTO -> AUTO_ID
            SaveStateType.QUICK -> QUICK_ID
            else -> UUID.randomUUID().toString()
        }

        fun validateSaveRam(data: ByteArray) {
            if (data.isEmpty()) throw SaveTransferException.emptyFile()
            if (data.size > MAX_SAVE_BYTES) throw SaveTransferException.tooLarge(data.size)
            if (data.size >= 4 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte() && data[2] == 0x03.toByte() && data[3] == 0x04.toByte()) {
                throw SaveTransferException.zipArchive()
            }
        }

        fun atomicWrite(target: File, data: ByteArray) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, ".${target.name}.tmp")
            tmp.outputStream().use { out ->
                out.write(data)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw java.io.IOException("cannot write ${target.path}")
            }
        }
    }
}
