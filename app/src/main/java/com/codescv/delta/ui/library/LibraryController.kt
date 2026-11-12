package com.codescv.delta.ui.library

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.codescv.delta.AppContainer
import com.codescv.delta.library.RomEntry
import com.codescv.delta.model.GameSystem
import com.codescv.delta.saves.SaveFileManager
import com.codescv.delta.saves.SaveStateInfo
import com.codescv.delta.ui.DeltaNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** What the context menu needs to know about a game (read off the main thread). */
data class RomMenuInfo(
    val latestAutoStateId: String?,
    val hasSaveRam: Boolean,
    val latestState: SaveStateInfo?,
)

/**
 * Library actions (Delta `LibraryView` handlers + `LibraryStore` side effects). All file and
 * network I/O runs on [AppContainer.ioScope]; toasts / alerts are delivered on the main thread.
 */
class LibraryController(
    private val context: Context,
    private val container: AppContainer,
    private val onToast: (String) -> Unit,
    private val onAlert: (String) -> Unit,
) {
    private val library get() = container.library
    private val saves get() = container.saves
    private val prefs get() = container.preferences
    private val io: CoroutineScope get() = container.ioScope
    private val resolver get() = context.contentResolver

    private suspend fun toast(text: String) = withContext(Dispatchers.Main) { onToast(text) }
    private suspend fun alert(e: Throwable) = withContext(Dispatchers.Main) { onAlert(e.message ?: e.toString()) }

    private fun background(block: suspend CoroutineScope.() -> Unit): Job = io.launch {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            alert(e)
        }
    }

    // Library ---------------------------------------------------------------------------------

    /** Syncs linked folders, rescans `ROMs/` and (if enabled) fetches missing box art. */
    fun rescan(): Job = background {
        LinkedFolderSync.sync(context, library, prefs.value.linkedFolders, container.root)
        library.rescan()
        autoFetch()
    }

    private fun autoFetch(roms: List<RomEntry>? = null) {
        if (prefs.value.autoFetchBoxArt) library.fetchMissingCovers(io, roms ?: library.value.entries)
    }

    fun importGames(uris: List<Uri>): Job = background {
        if (uris.isEmpty()) return@background
        val imported = ArrayList<RomEntry>()
        var firstError: Throwable? = null
        for (uri in uris) {
            try {
                val name = displayName(uri) ?: uri.lastPathSegment ?: "game"
                open(uri).use { imported += library.import(it, name) }
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        toast(LibraryLogic.importedToast(imported.size))
        firstError?.let { if (imported.isEmpty()) alert(it) }
        autoFetch(imported)
    }

    fun importSkin(uri: Uri): Job = background {
        val name = displayName(uri) ?: "skin.deltaskin"
        val skin = open(uri).use { container.skins.importDeltaSkin(it, name, null) }
        val system = GameSystem.entries.firstOrNull { it.skinSystem == skin.skin.skinSystem }?.displayName ?: skin.skin.skinSystem
        toast("Imported ${skin.skin.name} for $system")
    }

    fun linkFolder(uri: Uri): Job = background {
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val value = uri.toString()
        prefs.update { p -> if (value in p.linkedFolders) p else p.copy(linkedFolders = p.linkedFolders + value) }
        val before = library.value.entries.size
        LinkedFolderSync.sync(context, library, listOf(value), container.root)
        library.rescan()
        val added = (library.value.entries.size - before).coerceAtLeast(0)
        toast(LibraryLogic.importedToast(added))
        autoFetch()
    }

    // Playing ---------------------------------------------------------------------------------

    /** Grid tap: resumes the newest auto save when "Resume Where I Left Off" is on. */
    fun play(rom: RomEntry, navigator: DeltaNavigator): Job = io.launch {
        val auto = runCatching { saves.latestAutoState(rom)?.id }.getOrNull()
        launchGame(rom, LibraryLogic.resumeStateForTap(prefs.value.autoResume, auto), navigator)
    }

    /** Continue Playing tap / "Resume Auto Save". */
    fun resume(rom: RomEntry, navigator: DeltaNavigator): Job = io.launch {
        val auto = runCatching { saves.latestAutoState(rom)?.id }.getOrNull()
        launchGame(rom, LibraryLogic.resumeStateForContinue(auto), navigator)
    }

    fun playState(rom: RomEntry, stateId: String?, navigator: DeltaNavigator): Job = io.launch {
        launchGame(rom, stateId, navigator)
    }

    private suspend fun launchGame(rom: RomEntry, stateId: String?, navigator: DeltaNavigator) {
        // DeltaRootView.launch: library.markPlayed(rom) (recents capped at 12).
        runCatching { library.markPlayed(rom) }
        withContext(Dispatchers.Main) { navigator.play(rom, stateId) }
    }

    suspend fun menuInfo(rom: RomEntry): RomMenuInfo = withContext(Dispatchers.IO) {
        runCatching {
            val states = saves.listStates(rom)
            RomMenuInfo(states.firstOrNull { it.isAuto }?.id, saves.hasSaveRam(rom), states.firstOrNull())
        }.getOrDefault(RomMenuInfo(null, false, null))
    }

    // Favorites / rename / delete --------------------------------------------------------------

    fun toggleFavorite(rom: RomEntry): Job = background { library.toggleFavorite(rom) }

    fun rename(rom: RomEntry, name: String): Job = background { library.rename(rom, name) }

    fun delete(rom: RomEntry, includingSaves: Boolean): Job = background { library.delete(rom, includingSaves) }

    // Cover artwork ---------------------------------------------------------------------------

    fun fetchCover(rom: RomEntry): Job {
        onToast("Fetching cover for ${rom.title}…")
        return background { library.fetchCovers(listOf(rom), force = true) }
    }

    fun changeCover(rom: RomEntry, uri: Uri): Job = background {
        val png = normalizedCoverPng(uri)
        library.storeCustomCover(png, rom)
        toast("Cover updated for ${rom.title}")
    }

    fun resetCover(rom: RomEntry): Job = background {
        library.resetCover(rom)
        if (prefs.value.autoFetchBoxArt) library.fetchCovers(listOf(rom), force = true)
    }

    // Saves -----------------------------------------------------------------------------------

    fun importSave(rom: RomEntry, uri: Uri): Job = background {
        val data = readBytes(uri, SaveFileManager.MAX_SAVE_BYTES + 1)
        saves.importSaveRam(data, rom)
        toast("Save imported for ${rom.title}")
    }

    fun exportSave(rom: RomEntry, uri: Uri): Job = background {
        val data = saves.exportSaveRam(rom)
        write(uri, data)
        toast("Exported")
    }

    fun importState(rom: RomEntry, uri: Uri): Job = background {
        val data = readBytes(uri, SaveFileManager.MAX_SAVE_BYTES * 8 + 1)
        val info = saves.importState(data, rom, rom.system.coreId)
        toast("Save State imported into ${info.name ?: "Quick Save"}")
    }

    fun exportState(rom: RomEntry, stateId: String, uri: Uri): Job = background {
        val data = saves.exportState(rom, stateId)
        write(uri, data)
        toast("Exported")
    }

    // Share -----------------------------------------------------------------------------------

    fun shareRom(rom: RomEntry) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", rom.file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, rom.fileName)
                clipData = ClipData.newRawUri(rom.fileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Share ROM…").apply {
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            onAlert(e.message ?: e.toString())
        }
    }

    // SAF helpers -----------------------------------------------------------------------------

    private fun open(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException("Unable to open $uri")

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Reads at most [limit] bytes (enough for the size validators to reject oversized files). */
    private fun readBytes(uri: Uri, limit: Int): ByteArray = open(uri).use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }

    private fun write(uri: Uri, data: ByteArray) {
        val out = resolver.openOutputStream(uri, "wt") ?: resolver.openOutputStream(uri)
            ?: throw java.io.FileNotFoundException("Unable to write $uri")
        out.use { it.write(data) }
    }

    /** Decodes a picked photo (EXIF-rotated on API 28+), fits it in 1024 px and re-encodes as PNG. */
    private fun normalizedCoverPng(uri: Uri): ByteArray {
        val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val (w, h) = info.size.width to info.size.height
                val scale = COVER_MAX_PX.toFloat() / max(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= COVER_MAX_PX) sample *= 2
            val decoded = open(uri).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: throw IllegalArgumentException("The selected image could not be read.")
            val scale = COVER_MAX_PX.toFloat() / max(decoded.width, decoded.height)
            if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true) else decoded
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    companion object {
        const val COVER_MAX_PX = 1024
    }
}
