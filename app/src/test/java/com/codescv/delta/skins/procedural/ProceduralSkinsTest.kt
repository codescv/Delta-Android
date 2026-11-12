package com.codescv.delta.skins.procedural

import com.codescv.delta.model.GameSystem
import com.codescv.delta.model.VirtualButton
import com.codescv.delta.skins.Box
import com.codescv.delta.skins.GamepadTouchTracker
import com.codescv.delta.skins.ProceduralSkins
import com.codescv.delta.skins.SkinOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProceduralSkinsTest {

    // region Catalog

    @Test
    fun catalogCountsPerFamily() {
        val expected = mapOf("gba" to 9, "nes" to 3, "snes" to 3, "n64" to 4, "gbc" to 6, "nds" to 4, "genesis" to 3, "chip8" to 2)
        for ((family, count) in expected) {
            val options = ProceduralSkins.options(family)
            assertEquals(family, count, options.size)
            assertTrue(options.all { it.kind == SkinOption.Kind.VARIANT && it.family == family })
            assertEquals(family, options.size, options.map { it.id }.toSet().size)
        }
        assertTrue(ProceduralSkins.options("unknown").isEmpty())
    }

    @Test
    fun gbaIdsNamesAndBadgesMatchIos() {
        val options = ProceduralSkins.options("gba")
        assertEquals(
            listOf(
                "gba-atomicPurple", "gba-indigo", "gba-glacierClear", "gba-spiceOrange", "gba-platinumSP",
                "gba-famicom", "gba-emerald", "gba-matteMidnight", "gba-classicGlass",
            ),
            options.map { it.id },
        )
        val byId = options.associateBy { it.id }
        assertEquals("Atomic Purple", byId.getValue("gba-atomicPurple").name)
        assertEquals("Classic Glass", byId.getValue("gba-classicGlass").name)
        assertEquals("GBA SP", byId.getValue("gba-platinumSP").badge)
        assertEquals("Platinum SP", byId.getValue("gba-platinumSP").name)
        assertEquals("GAME BOY ADVANCE", byId.getValue("gba-emerald").badge)
        assertFalse(SystemSkinCatalog.skin("gba-classicGlass", "gba")!!.drawsConsoleArt)
        assertEquals(SpeakerStyle.SP_DOTS, SystemSkinCatalog.skin("gba-platinumSP", "gba")!!.speakerStyle)
    }

    @Test
    fun otherFamiliesIdsMatchIos() {
        assertEquals(listOf("nes-standard", "nes-famicom", "nes-midnight"), ProceduralSkins.options("nes").map { it.id })
        assertEquals(listOf("Standard NES", "Famicom Red & Gold", "NES Midnight"), ProceduralSkins.options("nes").map { it.name })
        assertEquals(listOf("snes-standard", "snes-super-famicom", "snes-atomic-purple"), ProceduralSkins.options("snes").map { it.id })
        assertEquals(
            listOf("n64-charcoal", "n64-funtastic-grape", "n64-jungle-green", "n64-ice-blue"),
            ProceduralSkins.options("n64").map { it.id },
        )
        assertEquals(
            listOf("gbc-grape", "gbc-atomic-purple", "gbc-teal", "gbc-dandelion", "gbc-berry", "gb-dmg"),
            ProceduralSkins.options("gbc").map { it.id },
        )
        assertEquals(
            listOf("nds-pink", "nds-polar-white", "nds-cobalt-black", "nds-dsi-matte"),
            ProceduralSkins.options("nds").map { it.id },
        )
        assertEquals(
            listOf("genesis-standard", "genesis-megadrive-jp", "genesis-nomad"),
            ProceduralSkins.options("genesis").map { it.id },
        )
        assertEquals(listOf("chip8-cosmac", "chip8-amber"), ProceduralSkins.options("chip8").map { it.id })
        assertEquals("SEGA GENESIS · 16-BIT", ProceduralSkins.options("genesis").first().badge)
        assertEquals("GAME BOY DMG-01", ProceduralSkins.options("gbc").last().badge)
    }

    @Test
    fun paletteAndFaceColors() {
        val sfc = SystemSkinCatalog.skin("snes-super-famicom", "snes")!!
        assertEquals(0xFFD7263DL, sfc.faceColor(VirtualButton.A).argb)
        assertEquals(0xFFF2C12EL, sfc.faceColor(VirtualButton.B).argb)
        assertEquals(0xFF2B59C3L, sfc.faceColor(VirtualButton.X).argb)
        assertEquals(0xFF2E9E4FL, sfc.faceColor(VirtualButton.Y).argb)
        val n64 = SystemSkinCatalog.skin("n64-ice-blue", "n64")!!
        assertEquals(0xFF1F4FC4L, n64.faceColor(VirtualButton.A).argb)
        assertEquals(0xFFFFC72CL, n64.faceColor(VirtualButton.Y).argb)
        val purple = SystemSkinCatalog.skin("gba-atomicPurple", "gba")!!.palette
        assertEquals(0xE68A2BE2L, purple.shellTop.argb)
        assertEquals(0xFF3A3542L, purple.track.argb)
        // No custom face colors → palette face.
        val gbc = SystemSkinCatalog.skin("gbc-teal", "gbc")!!
        assertEquals(gbc.palette.face, gbc.faceColor(VirtualButton.A))
    }

    @Test
    fun skinColorHelpers() {
        val c = SkinColor(0xFF808080)
        assertEquals(1f, c.alpha, 1e-6f)
        assertEquals(0x80 / 255f, c.red, 1e-6f)
        assertEquals(0xFF404040L, c.scaled(0.5f).argb)
        assertEquals(0xFF1A1A1EL, SkinColor(0xFFFFFFFF).contrastingIcon.argb)
        assertEquals(0xFFF7F7FAL, SkinColor(0xFF000000).contrastingIcon.argb)
    }

    @Test
    fun skinResolutionFallsBackToFamilyDefault() {
        assertEquals("gba-indigo", ProceduralSkins.skin("gba-indigo", GameSystem.GBA)!!.id)
        assertEquals("gba-atomicPurple", ProceduralSkins.skin("official-standard", GameSystem.GBA)!!.id)
        assertEquals("gbc-grape", ProceduralSkins.skin(null, GameSystem.GB)!!.id)
        assertEquals("genesis-standard", ProceduralSkins.skin(null, GameSystem.GAME_GEAR)!!.id)
        assertEquals("chip8-cosmac", ProceduralSkins.skin(null, GameSystem.CHIP8)!!.id)
        assertTrue(ProceduralSkins.isVariant("nes-midnight", "nes"))
        assertFalse(ProceduralSkins.isVariant("nes-midnight", "snes"))
    }

    // endregion

    // region Layout geometry

    private fun layout(
        system: GameSystem,
        w: Float,
        h: Float,
        sw: Float = 240f,
        sh: Float = 160f,
        density: Float = 2.625f,
        id: String = "x",
    ) = ProceduralSkins.layout(id, system, w, h, sw, sh, density = density) as ProceduralSkinLayout

    private fun assertInside(outer: Box, inner: Box, msg: String, eps: Float = 0.5f) {
        assertTrue(
            "$msg: $inner not inside $outer",
            inner.minX >= outer.minX - eps && inner.minY >= outer.minY - eps &&
                inner.maxX <= outer.maxX + eps && inner.maxY <= outer.maxY + eps,
        )
    }

    @Test
    fun emptyContainerHasNoLayout() {
        assertNull(ProceduralSkins.layout("gba-indigo", GameSystem.GBA, 0f, 100f, 240f, 160f, density = 1f))
    }

    @Test
    fun portraitGeometryIsSane() {
        val systems = listOf(
            GameSystem.GBA to (240f to 160f), GameSystem.GBC to (160f to 144f), GameSystem.NES to (256f to 240f),
            GameSystem.SNES to (256f to 224f), GameSystem.N64 to (320f to 240f), GameSystem.NDS to (256f to 384f),
            GameSystem.GENESIS to (320f to 224f), GameSystem.CHIP8 to (64f to 32f),
        )
        for ((system, src) in systems) {
            val l = layout(system, 1080f, 2400f, src.first, src.second)
            assertTrue(l.portrait)
            assertFalse(l.translucent)
            val bounds = Box(0f, 0f, 1080f, 2400f)
            for (s in l.screens) assertInside(bounds, s, "$system screen")
            // Game keeps its aspect ratio.
            val g = l.gameRect
            assertEquals("$system aspect", src.first / src.second, g.width / g.height, 0.02f)
            // Game sits above the controller frame.
            assertTrue("$system game above controls", g.maxY <= l.controllerFrame.minY)
            assertInside(bounds, l.controllerFrame, "$system controller")
            // Controls inside the controller frame (in points).
            val frame = l.viewport.controlsRect
            val c = l.controls
            for (r in c.allTouchTargets) assertInside(frame, r, "$system control")
            assertInside(frame, c.speaker, "$system speaker")
            // Bezel contains the game.
            assertInside(l.viewport.bezelRect, l.viewport.gameRect, "$system bezel")
        }
    }

    @Test
    fun ndsHasTwoStackedScreens() {
        val l = layout(GameSystem.NDS, 1080f, 2400f, 256f, 384f)
        assertEquals(2, l.screens.size)
        val (top, bottom) = l.screens
        assertEquals(top.maxY, bottom.minY, 0.01f)
        assertEquals(top.width, bottom.width, 0.01f)
        assertEquals(256f / 192f, top.width / top.height, 0.02f)
        assertEquals(1, layout(GameSystem.GBA, 1080f, 2400f).screens.size)
    }

    @Test
    fun landscapeGeometryIsSane() {
        val l = layout(GameSystem.GBA, 2400f, 1080f)
        assertFalse(l.portrait)
        assertTrue(l.translucent)
        val g = l.gameRect
        assertEquals(1080f, g.height, 1f) // full height
        assertEquals(1200f, g.midX, 1f)
        val bounds = Box(0f, 0f, 2400f, 1080f)
        assertEquals(bounds, l.controllerFrame)
        for (r in l.controls.allTouchTargets) assertInside(Box(0f, 0f, 2400f / 2.625f, 1080f / 2.625f), r, "landscape control")
        // D-Pad on the left, face buttons on the right.
        assertTrue(l.controls.dpad.midX < l.widthPt / 2f)
        assertTrue(l.controls.faceButtons.values.all { it.midX > l.widthPt / 2f })
    }

    @Test
    fun controlsLayoutMatchesIosFormulas() {
        val rect = Box(0f, 300f, 400f, 500f)
        val c = ConsoleControlsLayout.compute(rect, GameSystem.SNES.gamepadLayout, landscape = false)
        val unit = minOf(400f * 0.36f, 500f * 0.42f, 170f) // 144
        assertEquals(unit, c.dpad.width, 1e-3f)
        assertEquals(100f, c.dpad.midX, 1e-3f)
        assertEquals(300f + 500f * 0.44f, c.dpad.midY, 1e-3f)
        val button = unit * 0.40f
        assertEquals(button, c.faceButtons.getValue(VirtualButton.A).width, 1e-3f)
        assertEquals(300f + button * 1.04f, c.faceButtons.getValue(VirtualButton.A).midX, 1e-3f)
        assertEquals(setOf(VirtualButton.L, VirtualButton.R), c.shoulderButtons.keys)
        // Menu mirrors fast-forward horizontally.
        assertEquals(rect.maxX - (c.fastForward.midX - rect.minX), c.menu.midX, 1e-3f)
        assertEquals(c.fastForward.midY, c.menu.midY, 1e-3f)
        // Genesis: three faces in a rising row.
        val g = ConsoleControlsLayout.compute(rect, GameSystem.GENESIS.gamepadLayout, landscape = false)
        val (y, b, a) = listOf(VirtualButton.Y, VirtualButton.B, VirtualButton.A).map { g.faceButtons.getValue(it) }
        assertTrue(y.midX < b.midX && b.midX < a.midX)
        assertTrue(y.midY > b.midY && b.midY > a.midY)
        // GBA: B lower-left of A.
        val gba = ConsoleControlsLayout.compute(rect, GameSystem.GBA.gamepadLayout, landscape = false)
        assertTrue(gba.faceButtons.getValue(VirtualButton.B).midX < gba.faceButtons.getValue(VirtualButton.A).midX)
        assertTrue(gba.faceButtons.getValue(VirtualButton.B).midY > gba.faceButtons.getValue(VirtualButton.A).midY)
    }

    // endregion

    // region Touch mapping

    private val density = 2.625f

    private fun ProceduralSkinLayout.px(b: Box) = Pair(b.midX * density, b.midY * density)

    @Test
    fun faceButtonsAndThumbBridgeChord() {
        val l = layout(GameSystem.GBA, 1080f, 2400f)
        val a = l.controls.faceButtons.getValue(VirtualButton.A)
        val b = l.controls.faceButtons.getValue(VirtualButton.B)
        val (ax, ay) = l.px(a)
        assertNull(l.lockTarget(ax, ay))
        assertEquals(setOf(VirtualButton.A), l.sample(ax, ay, null).buttons)
        val (bx, by) = l.px(b)
        assertEquals(setOf(VirtualButton.B), l.sample(bx, by, null).buttons)
        // Midpoint between A and B presses both (hit radius × 1.36).
        val mx = (ax + bx) / 2f
        val my = (ay + by) / 2f
        assertEquals(setOf(VirtualButton.A, VirtualButton.B), l.sample(mx, my, null).buttons)
        assertTrue(l.claims(mx, my))
    }

    @Test
    fun dpadLockKeepsSteeringOutsideThePad() {
        val l = layout(GameSystem.GBA, 1080f, 2400f)
        val d = l.controls.dpad
        // Touch-down just outside the pad but within the 22 pt slop locks onto the D-Pad.
        val downX = (d.minX - 10f) * density
        val downY = d.midY * density
        assertEquals(ProceduralSkinLayout.LOCK_DPAD, l.lockTarget(downX, downY))
        assertEquals(setOf(VirtualButton.LEFT), l.sample(downX, downY, ProceduralSkinLayout.LOCK_DPAD).buttons)
        // Unlocked, the same point is outside the pad → nothing.
        assertTrue(l.sample(downX, downY, null).buttons.isEmpty())
        // Locked touch slides far right (over the face buttons) and keeps steering, no face press.
        val farX = (l.controls.faceButtons.getValue(VirtualButton.A).midX) * density
        val s = l.sample(farX, downY, ProceduralSkinLayout.LOCK_DPAD)
        assertEquals(setOf(VirtualButton.RIGHT), s.buttons)
        assertFalse(s.menu)
        // Up-right diagonal.
        val diag = l.sample((d.midX + d.width * 0.3f) * density, (d.midY - d.height * 0.3f) * density, null)
        assertEquals(setOf(VirtualButton.UP, VirtualButton.RIGHT), diag.buttons)
        // Dead zone at the center.
        assertTrue(l.sample(d.midX * density, d.midY * density, ProceduralSkinLayout.LOCK_DPAD).buttons.isEmpty())
    }

    @Test
    fun menuFastForwardStartSelectAndShoulders() {
        val l = layout(GameSystem.SNES, 1080f, 2400f, 256f, 224f)
        val c = l.controls
        val (mx, my) = l.px(c.menu)
        assertNull(l.lockTarget(mx, my))
        assertTrue(l.sample(mx, my, null).menu)
        assertFalse(l.sample(mx, my, ProceduralSkinLayout.LOCK_DPAD).menu)
        val (fx, fy) = l.px(c.fastForward)
        val ff = l.sample(fx, fy, null)
        assertTrue(ff.holdFastForward)
        assertFalse(ff.menu)
        assertEquals(setOf(VirtualButton.START), l.sample(l.px(c.start).first, l.px(c.start).second, null).buttons)
        assertEquals(setOf(VirtualButton.SELECT), l.sample(l.px(c.select).first, l.px(c.select).second, null).buttons)
        val lb = c.shoulderButtons.getValue(VirtualButton.L)
        assertEquals(setOf(VirtualButton.L), l.sample(l.px(lb).first, l.px(lb).second, null).buttons)
        // Four-face diamond.
        for (b in listOf(VirtualButton.A, VirtualButton.B, VirtualButton.X, VirtualButton.Y)) {
            val (x, y) = l.px(c.faceButtons.getValue(b))
            assertEquals(setOf(b), l.sample(x, y, null).buttons)
        }
    }

    @Test
    fun touchesAwayFromControlsPassThrough() {
        val l = layout(GameSystem.GBA, 1080f, 2400f)
        val g = l.gameRect
        assertFalse(l.claims(g.midX, g.midY))
        assertNull(l.lockTarget(g.midX, g.midY))
        assertTrue(l.sample(g.midX, g.midY, null).buttons.isEmpty())
    }

    @Test
    fun ndsGameRectIsATouchScreen() {
        val l = layout(GameSystem.NDS, 1080f, 2400f, 256f, 384f)
        val bottom = l.screens[1]
        val x = bottom.midX
        val y = bottom.midY
        assertTrue(l.claims(x, y))
        assertEquals(ProceduralSkinLayout.LOCK_SCREEN, l.lockTarget(x, y))
        val s = l.sample(x, y, ProceduralSkinLayout.LOCK_SCREEN)
        assertEquals(0f, s.pointerX!!, 0.01f)
        assertEquals(0.5f, s.pointerY!!, 0.01f)
        assertTrue(s.buttons.isEmpty())
        // Dragging off the screen clamps.
        val off = l.sample(0f, 2400f, ProceduralSkinLayout.LOCK_SCREEN)
        assertEquals(-1f, off.pointerX!!, 1e-6f)
        assertEquals(1f, off.pointerY!!, 1e-6f)
    }

    @Test
    fun ndsReverseScreensSwapsSourcesAndPointer() {
        val normal = layout(GameSystem.NDS, 1080f, 2400f, 256f, 384f)
        assertEquals(listOf(Box(0f, 0f, 256f, 192f), Box(0f, 192f, 256f, 192f)), normal.screenSources)
        val rev = ProceduralSkins.layout(
            "nds-pink", GameSystem.NDS, 1080f, 2400f, 256f, 384f, density = density, reverseScreens = true,
        ) as ProceduralSkinLayout
        assertEquals(listOf(Box(0f, 192f, 256f, 192f), Box(0f, 0f, 256f, 192f)), rev.screenSources)
        assertEquals(normal.screens, rev.screens)
        // The touch screen is now displayed on top: its center maps to the core bottom-half center.
        val top = rev.screens[0]
        val s = rev.sample(top.midX, top.midY, ProceduralSkinLayout.LOCK_SCREEN)
        assertEquals(0.5f, s.pointerY!!, 0.01f)
        val bottom = rev.screens[1]
        assertEquals(-0.5f, rev.sample(bottom.midX, bottom.midY, ProceduralSkinLayout.LOCK_SCREEN).pointerY!!, 0.01f)
        assertEquals(listOf(Box(0f, 0f, 240f, 160f)), layout(GameSystem.GBA, 1080f, 2400f).screenSources)
    }

    @Test
    fun trackerIntegrationMenuAndSlide() {
        val l = layout(GameSystem.GBA, 1080f, 2400f)
        val tracker = GamepadTouchTracker(l)
        var buttons: Set<VirtualButton> = emptySet()
        var menus = 0
        var ff = false
        tracker.listener = object : GamepadTouchTracker.Listener {
            override fun onButtonsChanged(newButtons: Set<VirtualButton>) { buttons = newButtons }
            override fun onMenu() { menus++ }
            override fun onFastForwardHold(held: Boolean) { ff = held }
        }
        val c = l.controls
        val (mx, my) = l.px(c.menu)
        tracker.pointerDown(1, mx, my)
        assertEquals(1, menus)
        assertTrue(buttons.isEmpty())
        tracker.pointerUp(1)
        // B → A slide without lifting.
        val (bx, by) = l.px(c.faceButtons.getValue(VirtualButton.B))
        val (ax, ay) = l.px(c.faceButtons.getValue(VirtualButton.A))
        tracker.pointerDown(2, bx, by)
        assertEquals(setOf(VirtualButton.B), buttons)
        tracker.pointerMove(2, ax, ay)
        tracker.commitMoves()
        assertEquals(setOf(VirtualButton.A), buttons)
        // Second finger holds fast-forward.
        val (fx, fy) = l.px(c.fastForward)
        tracker.pointerDown(3, fx, fy)
        assertTrue(ff)
        tracker.releaseAll()
        assertTrue(buttons.isEmpty())
        assertFalse(ff)
    }

    // endregion
}
