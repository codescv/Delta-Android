package com.codescv.delta.ui.settings

import com.codescv.delta.input.GamepadMapping
import com.codescv.delta.model.VirtualButton
import com.codescv.delta.settings.ExperimentalFeature
import com.codescv.delta.settings.ExperimentalFeatures
import com.codescv.delta.settings.PreferencesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SettingsDataTest {
    private fun tempStore(): Pair<File, PreferencesStore> {
        val dir = Files.createTempDirectory("prefs").toFile()
        val file = File(dir, "preferences.json")
        return file to PreferencesStore(file)
    }

    @Test
    fun experimentalListHasFifteenFeaturesInDeclarationOrder() {
        val keys = ExperimentalFeatures.defaults().map { it.key }
        assertEquals(
            listOf(
                "airPlaySkins", "showStatusBar", "toastNotifications", "reviewSaveStates", "repairDatabase",
                "skinDebugging", "reverseScreens", "showTouches", "metal", "Lu", "showWhatsNew",
                "screenshotsAlbum", "operatorDevice", "libraryExport", "customFilters",
            ),
            keys,
        )
    }

    @Test
    fun experimentalToggleAndOptionPersistAndMerge() {
        val (file, store) = tempStore()
        assertTrue(store.value.isExperimentalEnabled("toastNotifications"))
        store.setExperimental("toastNotifications", false)
        store.setExperimental("showTouches", true)
        store.setExperimentalOption("toastNotifications", "duration", SettingsData.nextOptionValue("duration", "1.5s")!!)

        val reloaded = PreferencesStore(file).value
        assertFalse(reloaded.isExperimentalEnabled("toastNotifications"))
        assertTrue(reloaded.isExperimentalEnabled("showTouches"))
        assertEquals("2.0s", reloaded.experimentalOption("toastNotifications", "duration"))
        assertEquals(15, reloaded.experimentalFeatures.size)

        // Merge: unknown saved features are dropped, missing ones come back with defaults, order is canonical.
        val merged = ExperimentalFeatures.merge(
            listOf(
                ExperimentalFeature("bogus", "Bogus", "x", isEnabled = true),
                ExperimentalFeature("customFilters", "Custom Filters", "s", isEnabled = false, options = mapOf("shader" to "LCD", "extra" to "1")),
            ),
        )
        assertEquals(ExperimentalFeatures.defaults().map { it.key }, merged.map { it.key })
        val cf = merged.first { it.key == "customFilters" }
        assertFalse(cf.isEnabled)
        assertEquals(mapOf("shader" to "LCD"), cf.options)
        assertTrue(merged.first { it.key == "metal" }.isEnabled)
    }

    @Test
    fun experimentalPresentationAdaptsIosOnlyFeatures() {
        val defaults = ExperimentalFeatures.defaults().associateBy { it.key }
        assertEquals("GPU Renderer", SettingsData.present(defaults.getValue("metal")).name)
        assertTrue(SettingsData.present(defaults.getValue("airPlaySkins")).summary.endsWith("(External displays)"))
        assertTrue(SettingsData.detailFooter(defaults.getValue("operatorDevice")).endsWith("Not available on this device"))
        val lu = defaults.getValue("Lu")
        assertEquals(lu.summary + "\n\n" + lu.detailedDescription, SettingsData.detailFooter(lu))
        assertEquals("Show Touches", SettingsData.present(defaults.getValue("showTouches")).name)
    }

    @Test
    fun optionCycling() {
        assertEquals("Off", SettingsData.nextOptionValue("stateSave", "On"))
        assertEquals("On", SettingsData.nextOptionValue("showHitBoxes", "Off"))
        assertEquals("1.0s", SettingsData.nextOptionValue("duration", "3.0s"))
        assertNull(SettingsData.nextOptionValue("shader", "CRT Pixellate"))
    }

    @Test
    fun opacitySnapsToFivePercentSteps() {
        assertEquals(0.15, SettingsData.snapOpacity(0.0f), 1e-9)
        assertEquals(0.70, SettingsData.snapOpacity(0.71f), 1e-9)
        assertEquals(1.0, SettingsData.snapOpacity(1.4f), 1e-9)
        assertEquals("70%", SettingsData.opacityLabel(0.70))
        assertEquals("15%", SettingsData.opacityLabel(0.15))
    }

    @Test
    fun remapSwapsConflictingKeysAndStoresOnlyOverrides() {
        val d = GamepadMapping.defaults
        val aKey = d.getValue(VirtualButton.A)
        val bKey = d.getValue(VirtualButton.B)
        val swapped = SettingsData.remap(d, VirtualButton.A, bKey)
        assertEquals(bKey, swapped[VirtualButton.A])
        assertEquals(aKey, swapped[VirtualButton.B])
        assertEquals(d.size, swapped.values.toSet().size)

        val overrides = SettingsData.overridesFor(swapped, d)
        assertEquals(mapOf("A" to bKey, "B" to aKey), overrides)
        val all = SettingsData.withPlayerOverrides(emptyMap(), 2, overrides)
        assertEquals(swapped, GamepadMapping.forPlayer(2, all))
        assertEquals(d, GamepadMapping.forPlayer(1, all))
        assertTrue(SettingsData.withPlayerOverrides(all, 2, emptyMap()).isEmpty())
    }

    @Test
    fun staticContentMatchesSpec() {
        assertEquals("Delta 2.0.5 (Build 205)", SettingsData.FOOTER)
        assertEquals(listOf("167.86.108.126", "172.104.88.237", "178.62.43.212"), SettingsData.wfcServers.map { it.dns })
        assertEquals("Wiimmfi (167.86.108.126)", SettingsData.wfcTitle("167.86.108.126"))
        assertEquals(7, SettingsData.biosFiles.size)
        assertEquals("16 KB (Optional — Built-in BIOS active)", SettingsData.biosStatus(SettingsData.biosFiles[0], null))
        assertEquals("128/256/512 KB (Optional)", SettingsData.biosStatus(SettingsData.biosFiles[2], null))
        assertEquals("Imported (16 KB)", SettingsData.biosStatus(SettingsData.biosFiles[0], 16384))
        assertEquals(8, SettingsData.contributors.size)
        assertEquals("VBA-NEXT", SettingsData.coreValue(com.codescv.delta.model.GameSystem.GBA))
        assertEquals(1, SettingsData.hapticStrengthIndex("medium"))
        assertEquals(1, SettingsData.hapticStrengthIndex("garbage"))
    }

    @Test
    fun noForbiddenStringsInSettingsSources() {
        val forbidden = Regex("Patreon|Patrons|Become a Patron|RevenueCat|Unlocked|\\bFree\\b")
        val dir = File("src/main/java/com/codescv/delta/ui/settings")
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() +
            listOf(File("../tools/gen_app_icons.py"), File("src/main/AndroidManifest.xml")) +
            File("src/main/res").walkTopDown().filter { it.isFile && it.name.startsWith("ic_launcher_") && it.extension == "xml" }.toList()
        assertTrue(files.isNotEmpty())
        for (f in files) {
            val hit = forbidden.find(f.readText())
            assertNull("forbidden string '${hit?.value}' in ${f.path}", hit)
        }
    }
}
