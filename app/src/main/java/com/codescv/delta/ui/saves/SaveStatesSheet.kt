package com.codescv.delta.ui.saves

import androidx.compose.runtime.Composable
import com.codescv.delta.emulation.EmulatorSession
import com.codescv.delta.library.RomEntry

/** How the save-state list is used. */
enum class SaveStatesMode { SAVE, LOAD, MANAGE }

/**
 * Save States manager (design §5.4): Auto Save / Quick Save / General / Locked sections with
 * thumbnails and Load · Rename · Preview · Lock · Export · Import · Delete actions.
 * Used by the pause menu (SAVE / LOAD with a running [session]) and by the Library context
 * menu "View Save States…" (MANAGE, [session] = null; loading launches the game via [onLaunch]).
 * Owned by `coder`.
 */
@Composable
fun SaveStatesSheet(
    rom: RomEntry,
    mode: SaveStatesMode,
    session: EmulatorSession?,
    onDismiss: () -> Unit,
    onLaunch: (stateId: String) -> Unit = {},
) {
}
