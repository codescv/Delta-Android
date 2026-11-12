package com.codescv.delta.ui.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.codescv.delta.library.LibraryStore
import com.codescv.delta.library.RomScanner
import com.codescv.delta.model.GameSystem
import java.io.File

/**
 * "Link ROM Folder…": walks persisted SAF trees with [DocumentFile] and copies supported ROMs
 * (and `.zip` archives, extracted) into `ROMs/`. A ledger (`.linked-folders.ledger`) of
 * `uri|size|lastModified` keys keeps unchanged files from being copied again on every rescan
 * (and keeps games the user deleted from coming back until the source file changes).
 * Blocking: call from a background dispatcher.
 */
object LinkedFolderSync {
    private const val LEDGER = ".linked-folders.ledger"

    fun sync(context: Context, library: LibraryStore, treeUris: List<String>, root: File): Int {
        if (treeUris.isEmpty()) return 0
        val ledgerFile = File(root, LEDGER)
        val ledger = runCatching { ledgerFile.readLines().toMutableSet() }.getOrDefault(mutableSetOf())
        val sizeBefore = ledger.size
        var copied = 0
        for (tree in treeUris) {
            val dir = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(tree)) }.getOrNull() ?: continue
            if (!dir.canRead()) continue
            walk(dir, depth = 0) { doc ->
                val name = doc.name ?: return@walk
                val size = doc.length()
                val key = "${doc.uri}|$size|${doc.lastModified()}"
                if (key in ledger) return@walk
                val isZip = name.endsWith(".zip", ignoreCase = true)
                if (!isZip && library.hasRomFile(name, size)) {
                    ledger += key
                    return@walk
                }
                val ok = runCatching {
                    context.contentResolver.openInputStream(doc.uri)?.use { library.import(it, name) }
                }.getOrNull()
                if (ok != null) {
                    ledger += key
                    copied += ok.size
                }
            }
        }
        if (ledger.size != sizeBefore) {
            runCatching {
                val tmp = File(root, "$LEDGER.tmp")
                tmp.writeText(ledger.joinToString("\n"))
                if (!tmp.renameTo(ledgerFile)) {
                    ledgerFile.delete()
                    tmp.renameTo(ledgerFile)
                }
            }
        }
        return copied
    }

    private fun walk(dir: DocumentFile, depth: Int, onFile: (DocumentFile) -> Unit) {
        if (depth > 8) return
        for (child in runCatching { dir.listFiles() }.getOrDefault(emptyArray())) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            if (child.isDirectory) {
                if (name !in RomScanner.skippedFolders) walk(child, depth + 1, onFile)
            } else if (isImportable(name)) {
                onFile(child)
            }
        }
    }

    fun isImportable(name: String): Boolean =
        name.endsWith(".zip", ignoreCase = true) || GameSystem.detectFileName(name) != null
}
