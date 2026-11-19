package com.codescv.delta.skins

import com.codescv.delta.model.GameSystem
import com.codescv.delta.model.VirtualButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DPadFeedbackTest {

    private val frame = Box(20f, 100f, 160f, 160f)

    @Test
    fun cardinalDirectionsProduceDistinctTargetsAndTransforms() {
        val states = listOf(
            DPadState.fromButtons(setOf(VirtualButton.UP)),
            DPadState.fromButtons(setOf(VirtualButton.DOWN)),
            DPadState.fromButtons(setOf(VirtualButton.LEFT)),
            DPadState.fromButtons(setOf(VirtualButton.RIGHT)),
        )
        val transforms = states.map { DPadFeedback.computeTilt(frame, it.targetX, it.targetY) }

        // Every cardinal direction has a unique transform (translate, scale, pivot).
        assertEquals(4, transforms.toSet().size)

        val up = transforms[0]
        val down = transforms[1]
        val left = transforms[2]
        val right = transforms[3]

        assertTrue("UP should shift upward", up.translateY < 0f && up.translateX == 0f)
        assertTrue("DOWN should shift downward", down.translateY > 0f && down.translateX == 0f)
        assertTrue("LEFT should shift left", left.translateX < 0f && left.translateY == 0f)
        assertTrue("RIGHT should shift right", right.translateX > 0f && right.translateY == 0f)

        // Pivot should anchor on the opposite side of the press for realistic rocker tilt.
        assertTrue("UP pivot below center", up.pivotY > frame.midY)
        assertTrue("DOWN pivot above center", down.pivotY < frame.midY)
        assertTrue("LEFT pivot right of center", left.pivotX > frame.midX)
        assertTrue("RIGHT pivot left of center", right.pivotX < frame.midX)

        // Axis compression should differ between vertical and horizontal presses.
        assertTrue(up.scaleY < 1f && up.scaleX == 1f)
        assertTrue(left.scaleX < 1f && left.scaleY == 1f)
        assertEquals(1f, up.magnitude, 1e-4f)
        assertEquals(1f, right.magnitude, 1e-4f)
    }

    @Test
    fun diagonalDirectionsNormalizeMagnitudeAndDifferFromCardinals() {
        val upRight = DPadState.fromButtons(setOf(VirtualButton.UP, VirtualButton.RIGHT))
        val downLeft = DPadState.fromButtons(setOf(VirtualButton.DOWN, VirtualButton.LEFT))

        assertTrue(upRight.targetX > 0.7f && upRight.targetY < -0.7f)
        assertTrue(downLeft.targetX < -0.7f && downLeft.targetY > 0.7f)

        val tUpRight = DPadFeedback.computeTilt(frame, upRight.targetX, upRight.targetY)
        val tDownLeft = DPadFeedback.computeTilt(frame, downLeft.targetX, downLeft.targetY)

        assertEquals(1f, tUpRight.magnitude, 1e-3f)
        assertEquals(1f, tDownLeft.magnitude, 1e-3f)
        assertNotEquals(tUpRight, tDownLeft)
        assertTrue(tUpRight.scaleX < 1f && tUpRight.scaleY < 1f)
        assertEquals(setOf(DPadDirection.UP, DPadDirection.RIGHT), upRight.activeDirections)
    }

    @Test
    fun neutralStateProducesIdentityTransform() {
        val neutral = DPadState.fromButtons(emptySet())
        assertFalse(neutral.isPressed)
        val tilt = DPadFeedback.computeTilt(frame, neutral.targetX, neutral.targetY)
        assertEquals(0f, tilt.translateX, 1e-5f)
        assertEquals(0f, tilt.translateY, 1e-5f)
        assertEquals(1f, tilt.scaleX, 1e-5f)
        assertEquals(1f, tilt.scaleY, 1e-5f)
        assertEquals(frame.midX, tilt.pivotX, 1e-5f)
        assertEquals(frame.midY, tilt.pivotY, 1e-5f)
        assertEquals(0f, tilt.magnitude, 1e-5f)
    }

    @Test
    fun armBoxesAreDisjointAndInsideFrame() {
        val arms = DPadDirection.entries.associateWith { DPadFeedback.armBox(frame, it) }
        for ((_, box) in arms) {
            assertTrue(box.minX >= frame.minX && box.maxX <= frame.maxX)
            assertTrue(box.minY >= frame.minY && box.maxY <= frame.maxY)
        }
        assertTrue(arms[DPadDirection.UP]!!.maxY < arms[DPadDirection.DOWN]!!.minY)
        assertTrue(arms[DPadDirection.LEFT]!!.maxX < arms[DPadDirection.RIGHT]!!.minX)
    }

    @Test
    fun fromDeltaItemResolvesPressedAndHeldDirections() {
        val dpadItem = DeltaSkin.Item(
            kind = DeltaSkin.ItemKind.DPAD,
            inputs = emptyList(),
            directions = mapOf("up" to "up", "down" to "down", "left" to "left", "right" to "right"),
            frame = frame,
            extendedFrame = frame,
            assetName = null,
            assetSize = null,
        )
        val state = DPadState.fromDeltaItem(
            item = dpadItem,
            system = GameSystem.GBA,
            pressed = setOf(VirtualButton.LEFT),
            held = setOf(VirtualButton.UP),
        )
        assertEquals(setOf(DPadDirection.LEFT, DPadDirection.UP), state.activeDirections)
        assertTrue(state.targetX < 0f && state.targetY < 0f)
    }
}
