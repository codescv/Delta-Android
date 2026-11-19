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
    fun cardinalDirectionsProduceDistinctVectorsAndCenterLockedTransform() {
        val up = DPadState.fromButtons(setOf(VirtualButton.UP))
        val down = DPadState.fromButtons(setOf(VirtualButton.DOWN))
        val left = DPadState.fromButtons(setOf(VirtualButton.LEFT))
        val right = DPadState.fromButtons(setOf(VirtualButton.RIGHT))

        val vectors = listOf(up, down, left, right).map { it.targetX to it.targetY }
        assertEquals(4, vectors.toSet().size)
        assertTrue(up.targetY < 0f && up.targetX == 0f)
        assertTrue(down.targetY > 0f && down.targetX == 0f)
        assertTrue(left.targetX < 0f && left.targetY == 0f)
        assertTrue(right.targetX > 0f && right.targetY == 0f)

        // Default tilt stays center-locked (zero translation drift) so D-Pad stays inside cutout.
        for (state in listOf(up, down, left, right)) {
            val t = DPadFeedback.computeTilt(frame, state.targetX, state.targetY)
            assertEquals(0f, t.translateX, 1e-5f)
            assertEquals(0f, t.translateY, 1e-5f)
            assertEquals(frame.midX, t.pivotX, 1e-5f)
            assertEquals(frame.midY, t.pivotY, 1e-5f)
            assertEquals(1f, t.magnitude, 1e-4f)
        }
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
        assertNotEquals(upRight.targetX, downLeft.targetX)
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
