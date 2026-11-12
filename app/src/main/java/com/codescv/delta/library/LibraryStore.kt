package com.codescv.delta.library

import com.codescv.delta.model.GameSystem
import com.codescv.delta.saves.SaveFileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream

@Serializable
data class RecentGame(val romId: String, val lastPlayed: Long)

@Serializable
data class LibraryIndex(
    val entries: List<RomEntry> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val recents: List<RecentGame> = emptyList(),
)

data class LibraryState(
    val entries: List<RomEntry> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val recents: List<RecentGame> = emptyList(),
    val isScanning: Boolean = false,
    /** Bumped whenever a cover image changes so views reload artwork. */
    val coverRevision: Int = 0,
) {
    val favoriteEntries: List<RomEntry> get() = entries.filter { it.id in favorites }

    val continuePlaying: List<RomEntry>
        get() {
            val byId = entries.associateBy { it.id }
            return recents.sortedByDescending { it.lastPlayed }.mapNotNull { byId[it.romId] }
        }

    /** Library page systems present, in Delta page order, keyed by category. */
    val availableSystems: List<GameSystem>
        get() {
            val present = entries.map { it.system.category }.toSet()
            return GameSystem.entries.filter { it in present && it.category == it }
        }
}

/** Delta's `LibraryStore`: ROM index, favorites, recents, imports, covers. */
class LibraryStore(
    val documents: File,
    private val indexFile: File,
    val saves: SaveFileManager,
    val covers: CoverArtFetcher,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    val value: LibraryState get() = _state.value

    val romsDirectory: File get() = File(documents, "ROMs").apply { mkdirs() }

    init {
        load()
    }

    private fun load() {
        val idx = runCatching { json.decodeFromString<LibraryIndex>(indexFile.readText()) }.getOrNull() ?: LibraryIndex()
        _state.value = LibraryState(idx.entries.filter { File(it.path).isFile }, idx.favorites, idx.recents)
    }

    @Synchronized
    private fun persist() {
        val s = _state.value
        runCatching {
            indexFile.parentFile?.mkdirs()
            val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
            tmp.writeText(json.encodeToString(LibraryIndex(s.entries, s.favorites, s.recents)))
            if (!tmp.renameTo(indexFile)) {
                indexFile.delete()
                tmp.renameTo(indexFile)
            }
        }
    }

    private fun mutate(persistAfter: Boolean = true, f: (LibraryState) -> LibraryState) {
        synchronized(this) { _state.value = f(_state.value) }
        if (persistAfter) persist()
    }

    private val scanLock = Any()

    /** Blocking rescan of `Documents/ROMs`; call from a background dispatcher. Concurrent calls serialize. */
    fun rescan(): List<RomEntry> = synchronized(scanLock) { rescanLocked() }

    private fun rescanLocked(): List<RomEntry> {
        mutate(false) { it.copy(isScanning = true) }
        val previous = _state.value.entries.associateBy { it.id }
        val scanned = RomScanner.scan(listOf(romsDirectory), previous).map { e ->
            previous[e.id]?.let { old -> e.copy(customName = old.customName, dateAdded = old.dateAdded) } ?: e
        }
        mutate { s ->
            val ids = scanned.map { it.id }.toSet()
            s.copy(
                entries = scanned,
                favorites = s.favorites.filter { it in ids }.toSet(),
                recents = s.recents.filter { it.romId in ids },
                isScanning = false,
            )
        }
        return scanned
    }

    /**
     * Imports a ROM or `.zip` from a stream (SAF document). Zip archives are extracted keeping
     * the inner ROM file name. Returns imported entries.
     */
    fun import(input: InputStream, displayName: String): List<RomEntry> = synchronized(scanLock) { importLocked(input, displayName) }

    private fun importLocked(input: InputStream, displayName: String): List<RomEntry> {
        val name = File(displayName).name.ifBlank { "game" }
        val imported = if (name.endsWith(".zip", ignoreCase = true)) {
            ZipRomExtractor.extract(input, romsDirectory)
        } else {
            if (GameSystem.detectFileName(name) == null) return emptyList()
            val target = uniqueTarget(name)
            val tmp = File(romsDirectory, ".$name.part")
            tmp.outputStream().use { input.copyTo(it, 64 * 1024) }
            if (tmp.length() == 0L || !tmp.renameTo(target)) {
                tmp.delete()
                return emptyList()
            }
            listOf(target)
        }
        val previous = _state.value.entries.associateBy { it.id }
        val entries = imported.mapNotNull { RomScanner.makeEntry(it) }
        mutate { s ->
            val merged = (s.entries.filter { e -> entries.none { it.id == e.id } } + entries.map { e -> previous[e.id]?.let { e.copy(customName = it.customName) } ?: e })
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            s.copy(entries = merged)
        }
        return entries
    }

    private fun uniqueTarget(name: String): File {
        val f = File(romsDirectory, name)
        return f // Re-importing the same file name replaces it (keeps saves associated).
    }

    fun isFavorite(rom: RomEntry) = rom.id in _state.value.favorites

    fun toggleFavorite(rom: RomEntry) = mutate { s ->
        s.copy(favorites = if (rom.id in s.favorites) s.favorites - rom.id else s.favorites + rom.id)
    }

    fun markPlayed(rom: RomEntry) = mutate { s ->
        val recents = (listOf(RecentGame(rom.id, System.currentTimeMillis())) + s.recents.filter { it.romId != rom.id }).take(12)
        s.copy(recents = recents)
    }

    fun rename(rom: RomEntry, newName: String) = mutate { s ->
        s.copy(entries = s.entries.map { if (it.id == rom.id) it.copy(customName = newName.trim().ifEmpty { null }) else it })
    }

    fun delete(rom: RomEntry, includingSaves: Boolean) {
        if (saves.isRunning(rom)) throw com.codescv.delta.saves.SaveTransferException.gameRunning()
        File(rom.path).delete()
        if (includingSaves) saves.deleteAllData(rom)
        covers.removeCover(rom)
        mutate { s ->
            s.copy(
                entries = s.entries.filter { it.id != rom.id },
                favorites = s.favorites - rom.id,
                recents = s.recents.filter { it.romId != rom.id },
            )
        }
    }

    fun bumpCovers() = mutate(false) { it.copy(coverRevision = it.coverRevision + 1) }

    fun entry(id: String): RomEntry? = _state.value.entries.firstOrNull { it.id == id }

    /** Whether `ROMs/<fileName>` already holds a file of [size] bytes (linked-folder sync). */
    fun hasRomFile(fileName: String, size: Long): Boolean {
        val f = File(romsDirectory, File(fileName).name)
        return f.isFile && (size < 0 || f.length() == size)
    }

    // Cover artwork ----------------------------------------------------------------------------

    /** Stores a user-picked cover (already normalized to PNG) and bumps [LibraryState.coverRevision]. */
    fun storeCustomCover(png: ByteArray, rom: RomEntry): File {
        val f = covers.store(png, rom)
        bumpCovers()
        return f
    }

    /** Removes the current cover (custom or downloaded) so the placeholder or a re-fetch shows. */
    fun resetCover(rom: RomEntry) {
        covers.removeCover(rom)
        bumpCovers()
    }

    /**
     * Downloads covers for [roms] (default: every entry) with at most [parallelism] requests in
     * flight. Bumps [LibraryState.coverRevision] after every stored cover so artwork appears
     * progressively. Returns the number of covers stored. Blocking network: runs on IO.
     */
    suspend fun fetchCovers(
        roms: List<RomEntry> = _state.value.entries,
        force: Boolean = false,
        parallelism: Int = COVER_FETCH_PARALLELISM,
    ): Int = coroutineScope {
        val gate = Semaphore(parallelism.coerceAtLeast(1))
        val stored = AtomicInteger(0)
        roms.distinctBy { it.id }.map { rom ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val hadCover = !force && covers.coverIfExists(rom) != null
                    val file = runCatching { covers.fetchCoverIfNeeded(rom, force) }.getOrNull()
                    // Only count downloads that changed the cover on disk.
                    if (file != null && !hadCover) {
                        stored.incrementAndGet()
                        bumpCovers()
                    }
                }
            }
        }.awaitAll()
        stored.get()
    }

    /** Launches [fetchCovers] on [scope] for entries that have no cover yet. */
    fun fetchMissingCovers(scope: CoroutineScope, roms: List<RomEntry> = _state.value.entries): Job =
        scope.launch { fetchCovers(roms.filter { covers.coverIfExists(it) == null }) }

    companion object {
        const val COVER_FETCH_PARALLELISM = 4

        /** Delta's `LibraryStore.filter`. */
        fun filter(entries: List<RomEntry>, search: String, system: GameSystem?): List<RomEntry> {
            val query = search.trim().lowercase()
            return entries.filter { e ->
                if (system != null && e.system.category != system.category) return@filter false
                if (query.isEmpty()) return@filter true
                e.title.lowercase().contains(query) || e.fileName.lowercase().contains(query) || e.system.shortName.lowercase() == query
            }
        }
    }
}
