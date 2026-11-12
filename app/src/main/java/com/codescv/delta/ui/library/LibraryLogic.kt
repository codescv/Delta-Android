package com.codescv.delta.ui.library

import com.codescv.delta.library.LibraryState
import com.codescv.delta.library.LibraryStore
import com.codescv.delta.library.RomEntry
import com.codescv.delta.model.GameSystem

/** A page of the Delta game browser (`LibraryPage` / `GamesViewController`). */
sealed interface LibraryPage {
    val id: String
    val title: String

    data object Favorites : LibraryPage {
        override val id: String get() = "favorites"
        override val title: String get() = "Favorites"
    }

    data class System(val system: GameSystem) : LibraryPage {
        override val id: String get() = system.id
        override val title: String get() = LibraryLogic.title(system)
    }
}

/** Pure library-browser decisions (ui_spec §2.2, §2.5, §2.6), unit-tested on the JVM. */
object LibraryLogic {
    /** Systems shipped by Delta, shown as empty pages before any game is imported. */
    val deltaSystems: List<GameSystem> = listOf(
        GameSystem.NES, GameSystem.SNES, GameSystem.N64, GameSystem.GB, GameSystem.GBA, GameSystem.NDS, GameSystem.GENESIS,
    )

    const val CONTINUE_PLAYING_LIMIT = 12

    /** Delta's page titles for each library category. */
    fun title(system: GameSystem): String = when (system.category) {
        GameSystem.NES -> "Nintendo"
        GameSystem.GB -> "Game Boy Color"
        GameSystem.GENESIS -> "Sega Genesis"
        else -> system.displayName
    }

    /** `[Favorites] + available systems`, or one empty page per Delta system when the library is empty. */
    fun pages(availableSystems: List<GameSystem>): List<LibraryPage> =
        if (availableSystems.isEmpty()) deltaSystems.map { LibraryPage.System(it) }
        else listOf(LibraryPage.Favorites) + availableSystems.map { LibraryPage.System(it) }

    /** Selected page if still present, else the first system page (never Favorites by default), else the first page. */
    fun currentPage(pages: List<LibraryPage>, selectedId: String?): LibraryPage? =
        pages.firstOrNull { it.id == selectedId }
            ?: pages.firstOrNull { it is LibraryPage.System }
            ?: pages.firstOrNull()

    fun isSearching(searchText: String): Boolean = searchText.isNotBlank()

    /** Top-bar title: `Search` while searching, the current page title, else `Delta`. */
    fun topTitle(searchText: String, current: LibraryPage?): String =
        if (isSearching(searchText)) "Search" else current?.title ?: "Delta"

    fun entries(page: LibraryPage, state: LibraryState): List<RomEntry> = when (page) {
        LibraryPage.Favorites -> state.favoriteEntries
        is LibraryPage.System -> LibraryStore.filter(state.entries, "", page.system)
    }

    fun searchResults(state: LibraryState, searchText: String): List<RomEntry> =
        LibraryStore.filter(state.entries, searchText, null)

    fun continuePlaying(state: LibraryState): List<RomEntry> = state.continuePlaying.take(CONTINUE_PLAYING_LIMIT)

    fun showContinuePlaying(state: LibraryState, searchText: String): Boolean =
        !isSearching(searchText) && state.continuePlaying.isNotEmpty()

    fun showPageSwitcher(pages: List<LibraryPage>, searchText: String): Boolean =
        !isSearching(searchText) && pages.size > 1

    /** Grid tap: resume the newest auto save only when "Resume Where I Left Off" is on and one exists. */
    fun resumeStateForTap(autoResume: Boolean, latestAutoStateId: String?): String? =
        if (autoResume) latestAutoStateId else null

    /** Continue Playing tap / "Resume Auto Save": always the newest auto save (if any). */
    fun resumeStateForContinue(latestAutoStateId: String?): String? = latestAutoStateId

    fun emptySubtitle(page: LibraryPage): String =
        if (page is LibraryPage.Favorites) "Long-press a game and choose Add to Favorites."
        else "Tap + to import .zip, .gba, .gb, .gbc, .nes, .sfc, .n64, .nds or .md games."

    fun noResultsMessage(searchText: String): String = "No games match “$searchText”."

    /** `DeltaAssets/ControllerIcon_<NAME>.pdf` for a page system, or null (text fallback). */
    fun controllerIconAsset(system: GameSystem): String? {
        val name = when (system.category) {
            GameSystem.GBA -> "GBA"
            GameSystem.GB -> "GBC"
            GameSystem.NES -> "NES"
            GameSystem.SNES -> "SNES"
            GameSystem.N64 -> "N64"
            GameSystem.NDS -> "DS"
            GameSystem.GENESIS -> "Genesis"
            else -> return null
        }
        return "DeltaAssets/ControllerIcon_$name.pdf"
    }

    fun importedToast(count: Int): String = "Imported $count game(s)"
}
