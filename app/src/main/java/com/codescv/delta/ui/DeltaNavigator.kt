package com.codescv.delta.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.codescv.delta.library.RomEntry

/** What the root shows (Delta's DeltaRootView: Library root + Settings sheet + Game cover). */
sealed interface DeltaRoute {
    data object Library : DeltaRoute

    /** A running game; [resumeStateId] = save state to load on start (e.g. `auto1`). */
    data class Game(val rom: RomEntry, val resumeStateId: String?) : DeltaRoute
}

/**
 * Root navigation contract shared by every screen. Screens never navigate on their own; they
 * call these functions.
 */
@Stable
class DeltaNavigator {
    var route: DeltaRoute by mutableStateOf(DeltaRoute.Library)
        private set
    var settingsVisible: Boolean by mutableStateOf(false)
        private set

    /** Error shown as the "Cannot Start Game" alert. */
    var launchError: String? by mutableStateOf(null)

    /** Launches [rom]; no-op if a game is already running (DeltaRootView.launch). */
    fun play(rom: RomEntry, resumeStateId: String? = null) {
        if (route is DeltaRoute.Game) return
        route = DeltaRoute.Game(rom, resumeStateId)
    }

    fun exitGame() {
        route = DeltaRoute.Library
    }

    fun openSettings() {
        settingsVisible = true
    }

    fun closeSettings() {
        settingsVisible = false
    }
}
