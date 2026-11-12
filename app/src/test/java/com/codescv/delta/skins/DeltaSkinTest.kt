package com.codescv.delta.skins

import com.codescv.delta.TestAssets
import com.codescv.delta.model.GameSystem
import com.codescv.delta.model.VirtualButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeltaSkinTest {
    private val families = listOf("gba", "gbc", "nes", "snes", "n64", "nds", "genesis")

    private fun bundled(family: String) = DeltaSkin.parse(family, TestAssets.text("ControllerSkins/${family}_info.json"))

    @Test
    fun allOfficialSkinsParseWithPortraitAndLandscape() {
        for (family in families) {
            val skin = bundled(family)
            assertTrue("$family name", skin.name.isNotBlank())
            assertTrue("$family identifier", skin.identifier.startsWith("com.delta."))
            val portrait = assertNotNull(skin.representation(portrait = true)).let { skin.representation(true)!! }
            val landscape = skin.representation(portrait = false)!!
            for (rep in listOf(portrait, landscape)) {
                assertTrue("$family mapping", rep.mappingSize.width > 0 && rep.mappingSize.height > 0)
                assertTrue("$family items", rep.items.isNotEmpty())
                assertNotNull("$family background", rep.backgroundAsset)
                // Every referenced asset ships in the APK assets.
                val bg = rep.backgroundAsset!!
                assertTrue("$family bg asset $bg", File(TestAssets.assetsDir, "ControllerSkins/${family}_${bg}").isFile)
                for (item in rep.items) {
                    item.assetName?.let { a ->
                        assertTrue("$family item asset $a", File(TestAssets.assetsDir, "ControllerSkins/${family}_$a").isFile)
                    }
                    assertTrue(item.frame.width > 0 && item.frame.height > 0)
                    // Extended frame always contains the frame.
                    assertTrue(item.extendedFrame.x <= item.frame.x + 1e-5f)
                    assertTrue(item.extendedFrame.maxX >= item.frame.maxX - 1e-5f)
                }
            }
            // Every skin has a menu button.
            assertTrue("$family menu", portrait.items.any { "menu" in it.inputs })
        }
    }

    @Test
    fun gbaPortraitItemsMatchInfoJson() {
        val skin = bundled("gba")
        assertEquals("Standard GBA", skin.name)
        assertEquals("com.delta.gba.standard", skin.identifier)
        val rep = skin.representation(portrait = true, edgeToEdge = false)!!
        assertEquals(320f, rep.mappingSize.width)
        val dpad = rep.items.first { it.kind == DeltaSkin.ItemKind.DPAD }
        // frame x=15,y=61,w=128,h=128 with extendedEdges 15 on every side.
        assertEquals(15f / 320f, dpad.frame.x, 1e-5f)
        assertEquals(128f / 320f, dpad.frame.width, 1e-5f)
        assertEquals(0f, dpad.extendedFrame.x, 1e-5f)
        assertEquals(158f / 320f, dpad.extendedFrame.width, 1e-5f)
        assertEquals(mapOf("up" to "up", "down" to "down", "left" to "left", "right" to "right"), dpad.directions)
        val a = rep.items.first { it.inputs == listOf("a") }
        // Item overrides right (15); left falls back to the representation default (7).
        assertEquals((56f + 15f + 7f) / 320f, a.extendedFrame.width, 1e-5f)
        // B has no override: 7 on every side.
        assertEquals((56f + 14f) / 240f, rep.items.first { it.inputs == listOf("b") }.extendedFrame.height, 1e-5f)
        assertEquals("iphone_a.pdf", a.assetName)
    }

    @Test
    fun representationPrefersEdgeToEdge() {
        val skin = bundled("gba")
        val e2e = skin.representations["edgeToEdge"]?.get("portrait")
        if (e2e != null) assertEquals(e2e, skin.representation(true, edgeToEdge = true))
        assertEquals(skin.representations["standard"]?.get("portrait"), skin.representation(true, edgeToEdge = false))
    }

    @Test
    fun ndsSkinHasTouchScreenAndTwoScreens() {
        val skin = bundled("nds")
        val rep = skin.representation(true)!!
        assertTrue(rep.items.any { it.kind == DeltaSkin.ItemKind.TOUCH_SCREEN })
        assertEquals(2, rep.screens.size)
    }

    @Test
    fun n64SkinHasThumbstick() {
        val rep = bundled("n64").representation(true)!!
        assertTrue(rep.items.any { it.kind == DeltaSkin.ItemKind.THUMBSTICK })
    }

    @Test
    fun invalidJsonThrows() {
        try {
            DeltaSkin.parse("gba", "{nope")
            throw AssertionError("expected exception")
        } catch (e: DeltaSkin.ParseException) {
            // expected
        }
        try {
            DeltaSkin.parse("gba", """{"name":"x"}""")
            throw AssertionError("expected exception")
        } catch (e: DeltaSkin.ParseException) {
            // expected
        }
    }

    @Test
    fun actionMappingPerSystem() {
        assertEquals(SkinAction.Button(VirtualButton.A), DeltaSkin.action("a", GameSystem.GBA))
        assertEquals(SkinAction.Menu, DeltaSkin.action("menu", GameSystem.GBA))
        assertEquals(SkinAction.Button(VirtualButton.Y), DeltaSkin.action("a", GameSystem.GENESIS))
        assertEquals(SkinAction.Button(VirtualButton.A), DeltaSkin.action("c", GameSystem.GENESIS))
        assertEquals(SkinAction.Button(VirtualButton.B), DeltaSkin.action("a", GameSystem.N64))
        assertEquals(SkinAction.Button(VirtualButton.L2), DeltaSkin.action("z", GameSystem.N64))
        assertEquals(SkinAction.ToggleFastForward, DeltaSkin.action("toggleFastForward", GameSystem.SNES))
        assertEquals(SkinAction.QuickSave, DeltaSkin.action("quickSave", GameSystem.NES))
        assertEquals(SkinAction.Ignored, DeltaSkin.action("bogus", GameSystem.NES))
    }

    @Test
    fun portraitLayoutPinsSkinToBottomAndCentersGame() {
        val skin = bundled("gba")
        val layout = DeltaSkinLayout.compute(skin, GameSystem.GBA, 1080f, 2400f, 240f, 160f)!!
        val rep = layout.representation
        val expectedHeight = 1080f / rep.mappingSize.width * rep.mappingSize.height
        assertEquals(1080f, layout.controllerFrame.width, 0.01f)
        assertEquals(2400f - expectedHeight, layout.controllerFrame.y, 0.5f)
        if (!rep.hasControllerPlacedScreens) {
            val screen = layout.screens.single().frame
            // 3:2 aspect fit in the area above the skin.
            assertEquals(1.5f, screen.width / screen.height, 0.02f)
            assertTrue(screen.maxY <= layout.controllerFrame.y + 1f)
        } else {
            val screen = layout.screens.first().frame
            assertTrue(screen.width > 0 && screen.height > 0)
        }
    }

    @Test
    fun landscapeLayoutAspectFitsSkin() {
        val skin = bundled("gba")
        val layout = DeltaSkinLayout.compute(skin, GameSystem.GBA, 2400f, 1080f, 240f, 160f)!!
        val rep = layout.representation
        val aspect = rep.mappingSize.width / rep.mappingSize.height
        assertEquals(aspect, layout.controllerFrame.width / layout.controllerFrame.height, 0.01f)
        assertTrue(layout.controllerFrame.width <= 2400.5f && layout.controllerFrame.height <= 1080.5f)
        assertTrue(layout.screens.isNotEmpty())
    }

    @Test
    fun itemsAreDenormalizedIntoControllerFrame() {
        val skin = bundled("gba")
        val layout = DeltaSkinLayout.compute(skin, GameSystem.GBA, 1080f, 2400f, 240f, 160f)!!
        for (p in layout.items) {
            assertEquals(layout.controllerFrame.x + p.item.frame.x * layout.controllerFrame.width, p.frame.x, 0.01f)
            assertEquals(layout.controllerFrame.y + p.item.frame.y * layout.controllerFrame.height, p.frame.y, 0.01f)
        }
    }

    @Test
    fun hiddenControllerGivesFullScreenGame() {
        val layout = DeltaSkinLayout.compute(bundled("gba"), GameSystem.GBA, 1080f, 2400f, 240f, 160f, controllerVisible = false)!!
        assertTrue(layout.items.isEmpty())
        assertEquals(1080f, layout.screens.single().frame.width, 1f)
    }

    @Test
    fun aspectFitHelper() {
        val r = DeltaSkinLayout.aspectFit(SizeF(4f, 3f), Box(0f, 0f, 400f, 600f))
        assertEquals(400f, r.width, 0.01f)
        assertEquals(300f, r.height, 0.01f)
        assertEquals(150f, r.y, 0.01f)
        assertNull(DeltaSkinLayout.compute(bundled("gba"), GameSystem.GBA, 0f, 0f, 240f, 160f))
        assertFalse(Box(0f, 0f, 10f, 10f).contains(11f, 5f))
    }
}
