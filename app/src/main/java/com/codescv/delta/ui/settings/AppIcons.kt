package com.codescv.delta.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** `DeltaIconSection` (Patron sections renamed per design §12.7). */
enum class DeltaIconSection(val title: String) {
    GLASS("Glass"),
    MODERN("Modern"),
    CLASSIC("Classic"),
    EXCLUSIVE("Exclusive"),
    BUTTON_PACK("Button Pack"),
}

/** One alternate launcher icon = one `<activity-alias>` named [aliasClassName]. */
data class DeltaAppIcon(
    val name: String,
    val imageName: String,
    val section: DeltaIconSection,
    val accentHex: Long,
    val credit: String? = null,
) {
    /** Fully-qualified alias class (`com.codescv.delta.icon.<imageName>`). */
    val aliasClassName: String get() = AppIcons.ALIAS_PREFIX + imageName

    /** Launcher icon resource name (`@mipmap/ic_launcher_<imagename>`). */
    val resourceName: String get() = "ic_launcher_" + imageName.lowercase()
}

/** All 20 Delta 2.0.5 app icons (ui_spec §6.8) and the activity-alias switcher. */
object AppIcons {
    const val ALIAS_PREFIX = "com.codescv.delta.icon."
    const val DEFAULT_ICON = "ModernIcon"
    const val UPDATE_MESSAGE = "Icon will update on your launcher shortly"

    private const val MANTIA = "Louie Mantia"

    val all: List<DeltaAppIcon> = listOf(
        DeltaAppIcon("Default", "AppIcon", DeltaIconSection.GLASS, 0x8A2BE2),
        DeltaAppIcon("Delta", "ModernIcon", DeltaIconSection.MODERN, 0x7C3AED),
        DeltaAppIcon("Photo Editor", "DeltaRed", DeltaIconSection.MODERN, 0xEF4444),
        DeltaAppIcon("GBA4iOS", "GBA4iOS", DeltaIconSection.MODERN, 0x6D28D9),
        DeltaAppIcon("Broken", "BrokenIcon", DeltaIconSection.CLASSIC, 0x4C1D95),
        DeltaAppIcon("Original", "OriginalIcon", DeltaIconSection.CLASSIC, 0x9333EA),
        DeltaAppIcon("Delta Gold", "DeltaGold", DeltaIconSection.EXCLUSIVE, 0xF59E0B),
        DeltaAppIcon("Halogenide", "Halogenide", DeltaIconSection.EXCLUSIVE, 0x3B82F6, "Sebastiaan de With (Halide)"),
        DeltaAppIcon("Ketchup", "KetchupIcon", DeltaIconSection.EXCLUSIVE, 0xDC2626, "Ben McCarthy"),
        DeltaAppIcon("Microchip", "MicrochipIcon", DeltaIconSection.EXCLUSIVE, 0x10B981, "Sean Fletcher"),
        DeltaAppIcon("NES", "NESIcon", DeltaIconSection.BUTTON_PACK, 0xE11D48, MANTIA),
        DeltaAppIcon("SNES", "SNESIcon", DeltaIconSection.BUTTON_PACK, 0x7C3AED, MANTIA),
        DeltaAppIcon("N64", "N64Icon", DeltaIconSection.BUTTON_PACK, 0x2563EB, MANTIA),
        DeltaAppIcon("GBC", "GBCIcon", DeltaIconSection.BUTTON_PACK, 0x6D28D9, MANTIA),
        DeltaAppIcon("GBC (Dandelion)", "GBCIcon_Dandelion", DeltaIconSection.BUTTON_PACK, 0xEAB308, MANTIA),
        DeltaAppIcon("GBC (Berry)", "GBCIcon_Berry", DeltaIconSection.BUTTON_PACK, 0xEC4899, MANTIA),
        DeltaAppIcon("GBC (Teal)", "GBCIcon_Teal", DeltaIconSection.BUTTON_PACK, 0x0D9488, MANTIA),
        DeltaAppIcon("GBC (Kiwi)", "GBCIcon_Kiwi", DeltaIconSection.BUTTON_PACK, 0x84CC16, MANTIA),
        DeltaAppIcon("Game Boy", "GBIcon", DeltaIconSection.BUTTON_PACK, 0x64748B, MANTIA),
        DeltaAppIcon("GBA", "GBAIcon", DeltaIconSection.BUTTON_PACK, 0x4F46E5, MANTIA),
    )

    fun bySection(): List<Pair<DeltaIconSection, List<DeltaAppIcon>>> =
        DeltaIconSection.entries.map { s -> s to all.filter { it.section == s } }

    fun find(imageName: String?): DeltaAppIcon = all.firstOrNull { it.imageName == imageName } ?: all.first { it.imageName == DEFAULT_ICON }

    /** Desired enabled state of every alias when [selected] is the chosen icon. */
    fun aliasStates(selected: String): Map<String, Boolean> {
        val chosen = find(selected).imageName
        return all.associate { it.aliasClassName to (it.imageName == chosen) }
    }

    /**
     * Switches the launcher icon: enables the chosen alias first (so the launcher never loses
     * its entry), then disables all the others, all with `DONT_KILL_APP`.
     */
    fun apply(context: Context, selected: String) {
        val pm = context.packageManager
        val states = aliasStates(selected).entries.sortedByDescending { it.value }
        for ((alias, enabled) in states) {
            val component = ComponentName(context.packageName, alias)
            val target = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            runCatching {
                if (pm.getComponentEnabledSetting(component) != target) {
                    pm.setComponentEnabledSetting(component, target, PackageManager.DONT_KILL_APP)
                }
            }
        }
    }
}
