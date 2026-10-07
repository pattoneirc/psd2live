package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import java.awt.Cursor
import kotlin.test.*

class TransformBoxTest {
    private val box = BoundingBox(100f, 100f, 200f, 160f)
    private val frame = TransformFrame(box, Offset(150f, 130f), 0f)

    private fun near(expected: Offset, actual: Offset, tolerance: Float = 1e-3f) =
        assertTrue((expected - actual).getDistance() < tolerance, "expected $expected, was $actual")

    @Test fun cornersTurnFromJustOutsideAndScaleOnTheHandle() {
        assertEquals(BoundingHandle.BOTTOM_RIGHT, transformHandleAt(Offset(203f, 163f), frame))
        // Outside the box, past the corner handle: the turn zone.
        assertEquals(BoundingHandle.ROTATE, transformHandleAt(Offset(214f, 172f), frame))
        assertEquals(BoundingHandle.ROTATE, transformHandleAt(Offset(88f, 92f), frame))
        // The zone under the pointer names its corner, for the hover to light.
        assertEquals(Offset(200f, 160f), turnCornerAt(Offset(214f, 172f), frame))
        assertNull(turnCornerAt(Offset(214f, 172f), frame, TransformHandles(rotates = false)))
        assertNull(turnCornerAt(Offset(188f, 150f), frame))
        // Inside the box near a corner is still a move, never a turn.
        assertEquals(BoundingHandle.BODY, transformHandleAt(Offset(188f, 150f), frame))
        // Far from every corner, outside: nothing.
        assertEquals(BoundingHandle.NONE, transformHandleAt(Offset(150f, 200f), frame))
        // A box that cannot turn has no turn zones and no grip.
        val upright = TransformHandles(rotates = false)
        assertEquals(BoundingHandle.NONE, transformHandleAt(Offset(214f, 172f), frame, upright))
        assertEquals(BoundingHandle.NONE, transformHandleAt(box.rotateHandlePos, frame, upright))
        // The zones turn with the box.
        val turned = frame.copy(angleDeg = 90f)
        val corner = Offset(214f, 172f).rotateAbout(turned.pivot, 90f)
        assertEquals(BoundingHandle.ROTATE, transformHandleAt(corner, turned))
    }

    @Test fun theAnchorStartsOnThePivotAndIsGrabbedFirst() {
        near(frame.pivot, frame.anchor)
        assertEquals(BoundingHandle.ANCHOR, transformHandleAt(Offset(152f, 131f), frame))
        // Moved onto a corner, the anchor is still the one grabbed there.
        val cornered = frame.withAnchor(Offset(1f, 1f))
        near(Offset(200f, 160f), cornered.anchor)
        assertEquals(BoundingHandle.ANCHOR, transformHandleAt(Offset(200f, 160f), cornered))
    }

    @Test fun aDraggedAnchorSnapsToTheBoxAndBackToThePivot() {
        assertNull(frame.anchorDraggedTo(Offset(153f, 128f)))
        assertEquals(Offset(1f, 0f), frame.anchorDraggedTo(Offset(196f, 103f)))
        assertEquals(Offset(0.5f, 1f), frame.anchorDraggedTo(Offset(147f, 164f)))
        // Away from every snap it stays where it was dropped, even outside the box.
        val free = assertNotNull(frame.anchorDraggedTo(Offset(240f, 130f)))
        near(Offset(240f, 130f), frame.withAnchor(free).anchor)
    }

    @Test fun aTurnHoldsTheAnchorStillAndCarriesTheBoxAround() {
        val anchored = frame.withAnchor(Offset(0f, 0f))
        val press = anchored.bounds.rotateHandlePos
        val drag = TransformDrag(BoundingHandle.ROTATE, anchored.bounds, anchored.pivot, anchored.angleDeg, press, anchored.anchor)
        val result = drag.apply(press.rotateAbout(anchored.anchor, 90f), null, shift = false, alt = false)
        assertEquals(90f, result.frameAngle, 1e-3f)
        near(Offset(100f, 100f), result.destination(Offset(100f, 100f)))
        near(Offset(100f, 200f), result.destination(Offset(200f, 100f)))
        // The box the drag shows is the box of the turned points, read in the turned frame about the pressed pivot.
        val after = TransformFrame(result.bounds, anchored.pivot, result.frameAngle)
        listOf(Offset(100f, 100f), Offset(200f, 100f), Offset(200f, 160f), Offset(100f, 160f)).forEach { corner ->
            val turned = result.destination(corner).intoTransformFrame(after.pivot, after.angleDeg)
            assertTrue(turned.x in after.bounds.minX - 1e-2f..after.bounds.maxX + 1e-2f && turned.y in after.bounds.minY - 1e-2f..after.bounds.maxY + 1e-2f)
        }
        // And the anchor, a point of that box, has not moved.
        near(anchored.anchor, after.withAnchor(Offset(0f, 0f)).anchor, 1e-2f)
    }

    @Test fun shiftStepsATurnBy15Degrees() {
        assertEquals(30f, turnDegrees(Offset.Zero, Offset(10f, 0f), Offset(10f, 6.5f), snap = true), 1e-3f)
        assertEquals(0f, turnDegrees(Offset.Zero, Offset(10f, 0f), Offset(10f, 1f), snap = true), 1e-3f)
    }

    @Test fun altScalesAboutTheAnchorAndPlainScaleKeepsTheOppositeSide() {
        val plain = TransformDrag(BoundingHandle.RIGHT, box, frame.pivot, 0f, Offset(200f, 130f))
            .apply(Offset(250f, 130f), null, shift = false, alt = false)
        assertEquals(BoundingBox(100f, 100f, 250f, 160f), plain.bounds)
        val anchored = frame.withAnchor(Offset(0.25f, 0.5f))
        val alt = TransformDrag(BoundingHandle.RIGHT, box, anchored.pivot, 0f, Offset(200f, 130f), anchored.anchor)
            .apply(Offset(275f, 130f), null, shift = false, alt = true)
        // The anchor at x = 125 stays; the right edge went from 75 past it to 150, so the box doubles about it.
        assertEquals(BoundingBox(75f, 100f, 275f, 160f), alt.bounds)
        near(Offset(125f, 130f), alt.destination(Offset(125f, 130f)))
        // Never through the anchor into a mirror image.
        val crossed = TransformDrag(BoundingHandle.RIGHT, box, anchored.pivot, 0f, Offset(200f, 130f), anchored.anchor)
            .apply(Offset(0f, 130f), null, shift = false, alt = true)
        assertTrue(crossed.bounds.width > 0f)
    }

    @Test fun resizeCursorsFollowTheTurn() {
        fun type(handle: BoundingHandle, angle: Float) = CanvasCursors.transform(handle, angle).type
        assertEquals(Cursor.E_RESIZE_CURSOR, type(BoundingHandle.RIGHT, 0f))
        assertEquals(Cursor.N_RESIZE_CURSOR, type(BoundingHandle.RIGHT, 90f))
        assertEquals(Cursor.NW_RESIZE_CURSOR, type(BoundingHandle.RIGHT, 45f))
        assertEquals(Cursor.NE_RESIZE_CURSOR, type(BoundingHandle.TOP_LEFT, 90f))
        assertEquals(Cursor.MOVE_CURSOR, type(BoundingHandle.BODY, 30f))
        // Turning keeps the plain arrow; the box lights the zone instead.
        assertEquals(Cursor.DEFAULT_CURSOR, type(BoundingHandle.ROTATE, 30f))
    }
}
