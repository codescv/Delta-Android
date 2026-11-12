package com.codescv.delta.skins

import com.codescv.delta.TestAssets
import com.codescv.delta.model.DPadGeometry
import com.codescv.delta.model.GameSystem
import com.codescv.delta.model.VirtualButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MultiTouchTest {
    private lateinit var layout: DeltaSkinLayout
    private lateinit var tracker: GamepadTouchTracker
    private val events = mutableListOf<String>()
    private var lastButtons: Set<VirtualButton> = emptySet()
    private var menuCount = 0

    private fun item(input: String) = layout.items.first { input in it.item.inputs }
    private val dpad get() = layout.items.first { it.item.kind == DeltaSkin.ItemKind.DPAD }

    @Before
    fun setUp() {
        val skin = DeltaSkin.parse("gba", TestAssets.text("ControllerSkins/gba_info.json"))
        layout = DeltaSkinLayout.compute(skin, GameSystem.GBA, 1080f, 2400f, 240f, 160f)!!
        tracker = GamepadTouchTracker(layout)
        tracker.listener = object : GamepadTouchTracker.Listener {
            override fun onButtonsChanged(buttons: Set<VirtualButton>) {
                lastButtons = buttons
                events += "buttons:" + buttons.map { it.name }.sorted().joinToString(",")
            }
            override fun onMenu() { menuCount++ }
            override fun onHaptic(kind: GamepadTouchTracker.HapticKind) { events += "haptic:$kind" }
        }
    }

    @Test
    fun tapRegistersOnTouchDown() {
        val a = item("a").frame
        tracker.pointerDown(0, a.midX, a.midY)
        assertEquals(setOf(VirtualButton.A), lastButtons)
        assertTrue(events.contains("haptic:PRESS"))
        tracker.pointerUp(0)
        assertEquals(emptySet<VirtualButton>(), lastButtons)
        assertTrue(events.contains("haptic:RELEASE"))
    }

    @Test
    fun slideFromBToAWithoutLifting() {
        val b = item("b").frame
        val a = item("a").frame
        tracker.pointerDown(7, b.midX, b.midY)
        assertEquals(setOf(VirtualButton.B), lastButtons)
        tracker.pointerMove(7, a.midX, a.midY)
        tracker.commitMoves()
        assertEquals(setOf(VirtualButton.A), lastButtons)
    }

    @Test
    fun chordBetweenAAndBPressesBoth() {
        val a = item("a")
        val b = item("b")
        // Point inside both extended frames, if they overlap; otherwise two thumbs.
        val overlap = a.extendedFrame.intersection(b.extendedFrame)
        if (overlap.area > 0f) {
            tracker.pointerDown(1, overlap.midX, overlap.midY)
        } else {
            tracker.pointerDown(1, a.frame.midX, a.frame.midY)
            tracker.pointerDown(2, b.frame.midX, b.frame.midY)
        }
        assertEquals(setOf(VirtualButton.A, VirtualButton.B), lastButtons)
    }

    @Test
    fun twoThumbsDpadPlusButton() {
        val d = dpad.frame
        val a = item("a").frame
        tracker.pointerDown(1, d.midX, d.y + d.height * 0.05f) // up
        tracker.pointerDown(2, a.midX, a.midY)
        assertEquals(setOf(VirtualButton.UP, VirtualButton.A), lastButtons)
        tracker.pointerUp(2)
        assertEquals(setOf(VirtualButton.UP), lastButtons)
    }

    @Test
    fun dpadLockOnKeepsSteeringOutsideFrame() {
        val d = dpad.frame
        tracker.pointerDown(3, d.midX + d.width * 0.4f, d.midY) // right
        assertEquals(setOf(VirtualButton.RIGHT), lastButtons)
        // Slide far to the right (over the B button area): still the D-Pad, still RIGHT.
        tracker.pointerMove(3, item("b").frame.midX, d.midY)
        tracker.commitMoves()
        assertEquals(setOf(VirtualButton.RIGHT), lastButtons)
        // Roll to up-left while locked.
        tracker.pointerMove(3, d.x - 50f, d.y - 50f)
        tracker.commitMoves()
        assertEquals(setOf(VirtualButton.UP, VirtualButton.LEFT), lastButtons)
        assertTrue(events.contains("haptic:MOVE"))
    }

    @Test
    fun dpadEightWaySectorsAndDeadZone() {
        val size = 100f
        fun at(dx: Float, dy: Float) = DPadGeometry.directions(50f + dx, 50f + dy, size, size)
        assertEquals(emptySet<VirtualButton>(), at(0f, 0f))
        assertEquals(emptySet<VirtualButton>(), at(4f, 0f)) // inside 0.10 * 50 = 5
        assertEquals(setOf(VirtualButton.RIGHT), at(6f, 0f))
        assertEquals(setOf(VirtualButton.RIGHT), at(40f, 0f))
        assertEquals(setOf(VirtualButton.UP), at(0f, -40f))
        assertEquals(setOf(VirtualButton.DOWN), at(0f, 40f))
        assertEquals(setOf(VirtualButton.LEFT), at(-40f, 0f))
        assertEquals(setOf(VirtualButton.UP, VirtualButton.RIGHT), at(30f, -30f))
        assertEquals(setOf(VirtualButton.UP, VirtualButton.LEFT), at(-30f, -30f))
        assertEquals(setOf(VirtualButton.DOWN, VirtualButton.LEFT), at(-30f, 30f))
        assertEquals(setOf(VirtualButton.DOWN, VirtualButton.RIGHT), at(30f, 30f))
        // Outside the ring: nothing when clamped, held when locked.
        assertEquals(emptySet<VirtualButton>(), at(80f, 0f))
        assertEquals(setOf(VirtualButton.RIGHT), DPadGeometry.directions(130f, 50f, size, size, clampToRadius = false))
        // Sector boundary at 22.5 degrees.
        assertEquals(setOf(VirtualButton.RIGHT), at(40f, -16f)) // ~21.8 deg
        assertEquals(setOf(VirtualButton.UP, VirtualButton.RIGHT), at(40f, -17f)) // ~23 deg
    }

    @Test
    fun menuButtonOpensMenuAndIsNotAJoypadInput() {
        val m = item("menu").frame
        tracker.pointerDown(9, m.midX, m.midY)
        assertEquals(1, menuCount)
        assertEquals(emptySet<VirtualButton>(), lastButtons)
        assertEquals(0, tracker.activePointerCount)
    }

    @Test
    fun touchesOutsideControlsAreNotClaimed() {
        assertFalse(tracker.claims(540f, 100f)) // game screen area
        val a = item("a").frame
        assertTrue(tracker.claims(a.midX, a.midY))
    }

    @Test
    fun startSelectSuppressedWhenUnsupported() {
        tracker.hasStartSelect = false
        val s = item("start").frame
        tracker.pointerDown(4, s.midX, s.midY)
        assertFalse(VirtualButton.START in lastButtons)
    }

    @Test
    fun releaseAllClearsEverything() {
        val a = item("a").frame
        val d = dpad.frame
        tracker.pointerDown(1, a.midX, a.midY)
        tracker.pointerDown(2, d.midX, d.y)
        tracker.releaseAll()
        assertEquals(emptySet<VirtualButton>(), lastButtons)
    }

    @Test
    fun hapticPolicy() {
        val h = GamepadTouchTracker.Companion
        assertEquals(GamepadTouchTracker.HapticKind.PRESS, h.haptics(emptySet(), setOf(VirtualButton.A), false, false))
        assertEquals(GamepadTouchTracker.HapticKind.PRESS, h.haptics(emptySet(), setOf(VirtualButton.UP), false, false))
        assertEquals(GamepadTouchTracker.HapticKind.MOVE, h.haptics(setOf(VirtualButton.UP), setOf(VirtualButton.LEFT), false, false))
        assertEquals(GamepadTouchTracker.HapticKind.RELEASE, h.haptics(setOf(VirtualButton.A), emptySet(), false, false))
        assertEquals(null, h.haptics(setOf(VirtualButton.UP), emptySet(), false, false))
    }

    @Test
    fun ndsTouchScreenProducesPointer() {
        val skin = DeltaSkin.parse("nds", TestAssets.text("ControllerSkins/nds_info.json"))
        val l = DeltaSkinLayout.compute(skin, GameSystem.NDS, 1080f, 2400f, 256f, 384f)!!
        val touch = l.items.first { it.item.kind == DeltaSkin.ItemKind.TOUCH_SCREEN }
        val s = l.sample(touch.frame.midX, touch.frame.midY, l.lockTarget(touch.frame.midX, touch.frame.midY))
        assertTrue(s.pointerX != null && s.pointerY != null)
        assertTrue(s.pointerX!! in -1f..1f && s.pointerY!! in -1f..1f)
    }

    @Test
    fun n64ThumbstickProducesAnalog() {
        val skin = DeltaSkin.parse("n64", TestAssets.text("ControllerSkins/n64_info.json"))
        val l = DeltaSkinLayout.compute(skin, GameSystem.N64, 1080f, 2400f, 320f, 240f)!!
        val stick = l.items.first { it.item.kind == DeltaSkin.ItemKind.THUMBSTICK }
        val lock = l.lockTarget(stick.frame.midX, stick.frame.midY)
        val s = l.sample(stick.frame.maxX + 500f, stick.frame.midY, lock)
        assertEquals(1f, s.analogX!!, 0.01f)
        assertEquals(0f, s.analogY!!, 0.01f)
    }
}
