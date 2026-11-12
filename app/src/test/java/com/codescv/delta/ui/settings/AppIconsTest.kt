package com.codescv.delta.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppIconsTest {
    private val expectedOrder = listOf(
        "AppIcon", "ModernIcon", "DeltaRed", "GBA4iOS", "BrokenIcon", "OriginalIcon",
        "DeltaGold", "Halogenide", "KetchupIcon", "MicrochipIcon",
        "NESIcon", "SNESIcon", "N64Icon", "GBCIcon", "GBCIcon_Dandelion", "GBCIcon_Berry",
        "GBCIcon_Teal", "GBCIcon_Kiwi", "GBIcon", "GBAIcon",
    )

    @Test
    fun tableHasAllTwentyIconsInDeltaOrder() {
        assertEquals(20, AppIcons.all.size)
        assertEquals(expectedOrder, AppIcons.all.map { it.imageName })
        assertEquals(20, AppIcons.all.map { it.aliasClassName }.toSet().size)
        assertEquals(20, AppIcons.all.map { it.resourceName }.toSet().size)
    }

    @Test
    fun sectionsAreRenamedAndOrdered() {
        assertEquals(
            listOf("Glass", "Modern", "Classic", "Exclusive", "Button Pack"),
            AppIcons.bySection().map { it.first.title },
        )
        assertEquals(listOf(1, 3, 2, 4, 10), AppIcons.bySection().map { it.second.size })
        assertTrue(AppIcons.all.filter { it.section == DeltaIconSection.BUTTON_PACK }.all { it.credit == "Louie Mantia" })
        assertEquals("Sebastiaan de With (Halide)", AppIcons.find("Halogenide").credit)
        assertEquals(0x7C3AEDL, AppIcons.find("ModernIcon").accentHex)
        assertEquals(0x8A2BE2L, AppIcons.find("AppIcon").accentHex)
    }

    @Test
    fun defaultAndUnknownResolveToModernIcon() {
        assertEquals("ModernIcon", AppIcons.DEFAULT_ICON)
        assertEquals("ModernIcon", AppIcons.find(null).imageName)
        assertEquals("ModernIcon", AppIcons.find("Nope").imageName)
    }

    @Test
    fun aliasStatesEnableExactlyOne() {
        val states = AppIcons.aliasStates("GBCIcon_Kiwi")
        assertEquals(20, states.size)
        assertEquals(listOf("com.codescv.delta.icon.GBCIcon_Kiwi"), states.filterValues { it }.keys.toList())
        assertEquals(listOf("com.codescv.delta.icon.ModernIcon"), AppIcons.aliasStates("bogus").filterValues { it }.keys.toList())
    }

    @Test
    fun manifestDeclaresOneLauncherAliasPerIcon() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val aliasBlocks = Regex("<activity-alias(.*?)</activity-alias>", RegexOption.DOT_MATCHES_ALL).findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals(20, aliasBlocks.size)
        val enabled = mutableListOf<String>()
        for (icon in AppIcons.all) {
            val block = aliasBlocks.firstOrNull { it.contains("android:name=\".icon.${icon.imageName}\"") }
            assertNotNull("missing alias for ${icon.imageName}", block)
            block!!
            assertTrue(block.contains("@mipmap/${icon.resourceName}\""))
            assertTrue(block.contains("android:targetActivity=\".MainActivity\""))
            assertTrue(block.contains("android.intent.category.LAUNCHER"))
            if (block.contains("android:enabled=\"true\"")) enabled += icon.imageName
        }
        assertEquals(listOf("ModernIcon"), enabled)
        // The LAUNCHER filter lives only on the aliases.
        val mainActivity = Regex("<activity\\s(.*?)</activity>", RegexOption.DOT_MATCHES_ALL).find(manifest)!!.groupValues[1]
        assertFalse(mainActivity.contains("android.intent.category.LAUNCHER"))
    }

    @Test
    fun launcherIconResourcesExist() {
        val res = File("src/main/res")
        for (icon in AppIcons.all) {
            assertTrue(icon.resourceName, File(res, "mipmap-anydpi-v26/${icon.resourceName}.xml").isFile)
            for (density in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) {
                assertTrue("$density/${icon.resourceName}", File(res, "mipmap-$density/${icon.resourceName}.png").isFile)
            }
        }
    }
}
