package com.codescv.delta.skins

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.codescv.delta.DeltaApplication
import com.codescv.delta.model.GameSystem

/** One selectable controller skin for a skin family (official, built-in variant or imported). */
data class SkinOption(
    /** Stable id stored in `portraitSkinsBySystem` / `landscapeSkinsBySystem`. */
    val id: String,
    val name: String,
    val family: String,
    val kind: Kind,
    /** Short badge text (e.g. "GAME BOY ADVANCE"). */
    val badge: String = "",
) {
    enum class Kind { OFFICIAL, VARIANT, IMPORTED }

    companion object {
        const val OFFICIAL_ID = "official-standard"
    }
}

/**
 * Skin catalog per family: official `Standard <SYS>` first, then built-in procedural variants
 * (`SystemSkinCatalog` port, see [ProceduralSkins]), then imported `.deltaskin`s.
 */
object SkinCatalog {
    /** Families configurable in Settings › Skins, in Delta order. */
    val configurableFamilies = listOf("nes", "snes", "n64", "gbc", "gba", "nds", "genesis")

    fun familyDisplayName(family: String): String = when (family) {
        "nes" -> "Nintendo (NES)"
        "snes" -> "Super Nintendo"
        "n64" -> "Nintendo 64"
        "gbc" -> "Game Boy Color"
        "gba" -> "Game Boy Advance"
        "nds" -> "Nintendo DS"
        "genesis" -> "Sega Genesis"
        else -> family.uppercase()
    }

    fun familyShortName(family: String): String = when (family) {
        "nes" -> "NES"; "snes" -> "SNES"; "n64" -> "N64"; "gbc" -> "GBC"; "gba" -> "GBA"; "nds" -> "DS"; "genesis" -> "MD"
        else -> family.uppercase()
    }

    fun systemFor(family: String): GameSystem = when (family) {
        "nes" -> GameSystem.NES
        "snes" -> GameSystem.SNES
        "n64" -> GameSystem.N64
        "gbc" -> GameSystem.GBC
        "nds" -> GameSystem.NDS
        "genesis" -> GameSystem.GENESIS
        else -> GameSystem.GBA
    }

    fun options(family: String): List<SkinOption> {
        val repo = DeltaApplication.container.skins
        val official = repo.bundled(family)?.let {
            listOf(SkinOption(SkinOption.OFFICIAL_ID, it.skin.name, family, SkinOption.Kind.OFFICIAL))
        } ?: emptyList()
        val imported = repo.imported(family).map {
            SkinOption(it.source.id, it.skin.name, family, SkinOption.Kind.IMPORTED)
        }
        return official + ProceduralSkins.options(family) + imported
    }

    fun option(family: String, id: String?): SkinOption =
        options(family).firstOrNull { it.id == id } ?: options(family).first()
}

/** Built-in procedural skin variants (owned by the `skin-variants` helper). */
object ProceduralSkins {
    fun options(family: String): List<SkinOption> = emptyList()
}

/** Thumbnail of a skin for pickers (Settings › Skins, pause menu "Controller Skin" chips). */
@Composable
fun SkinPreview(option: SkinOption, selected: Boolean, modifier: Modifier = Modifier) {
}
