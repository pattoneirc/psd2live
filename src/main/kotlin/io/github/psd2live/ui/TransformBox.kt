package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.*

/**
 * The transform box, shared by every control that edits something by framing it: the Transform tool's box
 * on the edit canvas, the create ghost of a Warp or an imported layer, and the selected tiles of the atlas.
 *
 * Nothing here touches the editor, the viewport or Compose state — a box is a value and a drag is a
 * function of the pointer, so the whole thing is testable without a running canvas.
 */

internal enum class BoundingHandle {
    NONE, BODY, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    TOP, BOTTOM, LEFT, RIGHT, ROTATE,
    /** The anchor a rotation turns about and an Alt scale grows from; dragging it moves only the anchor. */
    ANCHOR,
}

internal val BoundingHandle.isCorner get() = this == BoundingHandle.TOP_LEFT || this == BoundingHandle.TOP_RIGHT ||
    this == BoundingHandle.BOTTOM_LEFT || this == BoundingHandle.BOTTOM_RIGHT

internal val BoundingHandle.scales get() = isCorner || this == BoundingHandle.TOP || this == BoundingHandle.BOTTOM ||
    this == BoundingHandle.LEFT || this == BoundingHandle.RIGHT

internal data class BoundingBox(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    val centerX get() = (minX + maxX) * 0.5f
    val centerY get() = (minY + maxY) * 0.5f
    val width get() = maxX - minX
    val height get() = maxY - minY
    val rotateHandlePos get() = rotateGrip(1f)

    /** The rotate grip off the middle of the top edge, [unit] scaling its reach as it scales every handle. */
    fun rotateGrip(unit: Float) = Offset(centerX, minY - 24f * unit)

    /** The point at ([uv].x, [uv].y) of the box, 0 at its min edge and 1 at its max one. */
    fun at(uv: Offset) = Offset(minX + uv.x * width, minY + uv.y * height)

    /** Where [p] sits in the box, as [at] reads it; an axis the box has no extent along reads as its middle. */
    fun uvOf(p: Offset) = Offset(
        if (width > 1e-4f) (p.x - minX) / width else 0.5f,
        if (height > 1e-4f) (p.y - minY) / height else 0.5f,
    )

    /** The four corners in TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT order, then the four edge midpoints. */
    fun handlePoints(): List<Pair<BoundingHandle, Offset>> = listOf(
        BoundingHandle.TOP_LEFT to Offset(minX, minY),
        BoundingHandle.TOP_RIGHT to Offset(maxX, minY),
        BoundingHandle.BOTTOM_LEFT to Offset(minX, maxY),
        BoundingHandle.BOTTOM_RIGHT to Offset(maxX, maxY),
        BoundingHandle.TOP to Offset(centerX, minY),
        BoundingHandle.BOTTOM to Offset(centerX, maxY),
        BoundingHandle.LEFT to Offset(minX, centerY),
        BoundingHandle.RIGHT to Offset(maxX, centerY),
    )

    fun contains(p: Offset) = p.x in minX..maxX && p.y in minY..maxY
}

/**
 * Which handles a control offers. Every box has its corners, its body and its anchor; [edges] adds the four
 * one-axis scale handles and [rotates] the rotate grip and the turn zones just outside each corner. [unit]
 * scales every radius, so a view sized in dp draws and grabs the same box the edit canvas does in pixels.
 */
internal data class TransformHandles(val edges: Boolean = true, val rotates: Boolean = true, val unit: Float = 1f) {
    companion object {
        val ALL = TransformHandles()
    }
}

/**
 * Turns a pointer position into the transform frame's own coordinates, and back.
 *
 * The box is always axis-aligned *here*, which is the whole point of the frame: an oriented selection
 * box needs no oriented-rectangle maths anywhere, only this one pair of conversions at the edges.
 */
internal fun Offset.intoTransformFrame(pivot: Offset, angleDeg: Float): Offset = rotateAbout(pivot, -angleDeg)

internal fun Offset.outOfTransformFrame(pivot: Offset, angleDeg: Float): Offset = rotateAbout(pivot, angleDeg)

/** Turns a delta about the origin, for rotating a pointer's travel into the frame. */
internal fun Offset.rotateVector(angleDeg: Float): Offset {
    if (angleDeg == 0f) return this
    val radians = angleDeg * PI.toFloat() / 180f
    val c = cos(radians)
    val s = sin(radians)
    return Offset(x * c - y * s, x * s + y * c)
}

internal fun Offset.rotateAbout(pivot: Offset, angleDeg: Float): Offset {
    if (angleDeg == 0f) return this
    val radians = angleDeg * PI.toFloat() / 180f
    val c = cos(radians)
    val s = sin(radians)
    val d = this - pivot
    return pivot + Offset(d.x * c - d.y * s, d.x * s + d.y * c)
}

/**
 * The transform frame: its box, the screen point it is oriented about, that orientation, and the anchor.
 *
 * [bounds] is in frame coordinates — axis-aligned *inside* the frame — so every consumer of a box (the
 * overlay, the handle hit test, the drag math) needs no oriented-rectangle case at all.
 *
 * [anchor] is a screen point, the one a rotation turns about and an Alt scale grows from. It starts on
 * [pivot]; a control that lets the anchor be moved keeps it as a point *of the box* ([anchorUv]), so it rides
 * along through every move, scale and turn of the box instead of staying behind on the screen.
 */
internal data class TransformFrame(val bounds: BoundingBox, val pivot: Offset, val angleDeg: Float, val anchor: Offset = pivot) {
    /** [anchor] as a point of the box, for [withAnchor]. */
    val anchorUv: Offset get() = bounds.uvOf(anchor.intoTransformFrame(pivot, angleDeg))

    /** This frame with its anchor at [uv] of its box; null leaves it on the pivot. */
    fun withAnchor(uv: Offset?): TransformFrame =
        if (uv == null) copy(anchor = pivot) else copy(anchor = bounds.at(uv).outOfTransformFrame(pivot, angleDeg))

    /**
     * Where an anchor dragged to the screen point [pointer] lands, as a point of the box: on the centre, a corner or
     * an edge's middle when it comes within reach of one, and back on the pivot (null) within reach of that.
     */
    fun anchorDraggedTo(pointer: Offset, unit: Float = 1f): Offset? {
        val local = pointer.intoTransformFrame(pivot, angleDeg)
        val reach = ANCHOR_SNAP * unit
        if ((local - pivot).getDistance() <= reach) return null
        val snaps = bounds.handlePoints().map { it.second } + Offset(bounds.centerX, bounds.centerY)
        val near = snaps.minBy { (it - local).getDistance() }
        return bounds.uvOf(if ((near - local).getDistance() <= reach) near else local)
    }

    private companion object {
        const val ANCHOR_SNAP = 8f
    }
}

/**
 * The centroid a selection is framed and turned about.
 *
 * Shared by [frameOf] and the numeric Precise Transform panel, so the box and the panel cannot
 * disagree about the centre: they are the same function, not two that happen to agree.
 *
 * A centroid rather than the hull's centre, because a rotation leaves the centroid where it is — an
 * oriented frame turns in place instead of swinging as its bounding hull changes shape.
 */
internal fun selectionPivot(points: List<Offset>): Offset =
    Offset(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat())

/**
 * The box hugging [indices] of [points], axis-aligned inside a frame turned by [angleDeg] about their
 * centroid. Null when nothing is selected, and null when what is selected spans nothing — a single point
 * — which is the only guard the overlay and the hit test need: no frame draws no box and grabs no handle.
 */
internal fun frameOf(points: List<Offset>, indices: Set<Int>, angleDeg: Float): TransformFrame? {
    val chosen = indices.filter { it in points.indices }.map { points[it] }
    if (chosen.isEmpty()) return null
    val pivot = selectionPivot(chosen)
    val local = chosen.map { it.intoTransformFrame(pivot, angleDeg) }
    val box = BoundingBox(local.minOf { it.x }, local.minOf { it.y }, local.maxOf { it.x }, local.maxOf { it.y })
    // A box with no extent is one point, or several sitting on the same spot: eight scale handles stacked
    // on the point and a rotate grip hanging over it, none of which can do anything. No frame at all is the
    // honest answer — the point is still drawn as selected and still dragged directly.
    if (box.width <= 0f && box.height <= 0f) return null
    return TransformFrame(box, pivot, angleDeg)
}

/** Whether the box shows the edge handle [handle]: a box too short to hold one between its corners does not. */
internal fun TransformHandles.showsEdge(bounds: BoundingBox, handle: BoundingHandle) = edges && when (handle) {
    BoundingHandle.TOP, BoundingHandle.BOTTOM -> bounds.width >= 20f * unit
    BoundingHandle.LEFT, BoundingHandle.RIGHT -> bounds.height >= 20f * unit
    else -> false
}

/**
 * Whether the anchor can be grabbed: on a box too small for it to sit apart from the body, a press on the middle
 * has to move the box, so the anchor stays where it is until the view is zoomed in.
 */
internal fun TransformHandles.reachesAnchor(bounds: BoundingBox) = bounds.width >= 40f * unit && bounds.height >= 40f * unit

/** How far outside a corner a press still turns the box. */
private const val ROTATE_REACH = 24f

/**
 * The handle under [pos], a point in frame coordinates: the anchor, the rotate grip, a corner, an edge, and last
 * the turn zone just outside a corner - outside the box only, so it never takes a press that could scale or move.
 */
private fun hitBoundingHandle(pos: Offset, frame: TransformFrame, handles: TransformHandles): BoundingHandle {
    val bounds = frame.bounds
    val u = handles.unit
    // The anchor first: it sits on the pivot by default, and once it is dragged onto a handle it has to be
    // possible to drag it off again. The ring of the handle around it still reaches the handle.
    if (handles.reachesAnchor(bounds) && (pos - frame.anchor.intoTransformFrame(frame.pivot, frame.angleDeg)).getDistance() <= 9f * u) {
        return BoundingHandle.ANCHOR
    }
    if (handles.rotates && (pos - bounds.rotateGrip(u)).getDistance() <= 9f * u) return BoundingHandle.ROTATE
    val points = bounds.handlePoints()
    for ((handle, at) in points.take(4)) if ((pos - at).getDistance() <= 8f * u) return handle
    for ((handle, at) in points.drop(4)) if (handles.showsEdge(bounds, handle) && (pos - at).getDistance() <= 7f * u) return handle
    if (handles.rotates && turnCorner(pos, bounds, u) != null) return BoundingHandle.ROTATE
    return BoundingHandle.NONE
}

/** The corner, in frame coordinates, whose turn zone the frame point [pos] is in: outside the box, near the corner. */
private fun turnCorner(pos: Offset, bounds: BoundingBox, unit: Float): Offset? {
    if (bounds.contains(pos)) return null
    return bounds.handlePoints().take(4).map { it.second }.firstOrNull { (pos - it).getDistance() <= ROTATE_REACH * unit }
}

/**
 * The corner whose turn zone the screen point [pointer] is in, in frame coordinates, so the zone can light up
 * under the pointer; null outside every zone and on a box that does not turn.
 */
internal fun turnCornerAt(pointer: Offset, frame: TransformFrame, handles: TransformHandles = TransformHandles.ALL): Offset? =
    if (!handles.rotates) null else turnCorner(pointer.intoTransformFrame(frame.pivot, frame.angleDeg), frame.bounds, handles.unit)

/**
 * The handle ring only — BODY is never reported.
 *
 * The point tools need the ring *before* their element pick and the box body *after* it, so the two
 * cannot be one call there.
 */
internal fun transformRingAt(pos: Offset, frame: TransformFrame, handles: TransformHandles = TransformHandles.ALL): BoundingHandle =
    hitBoundingHandle(pos.intoTransformFrame(frame.pivot, frame.angleDeg), frame, handles)

/**
 * The ring, then BODY for a point anywhere inside the rectangle.
 *
 * [hitBoundingHandle] answers NONE for every point inside the rectangle — it only knows the ring of
 * handles — so a body move has to be resolved here. The object tools want both answers at once, and
 * want them *before* any artwork hit test: a multi-object selection has holes in its box, and a click
 * in one of them is a move, not a re-pick.
 */
internal fun transformHandleAt(pos: Offset, frame: TransformFrame, handles: TransformHandles = TransformHandles.ALL): BoundingHandle {
    val local = pos.intoTransformFrame(frame.pivot, frame.angleDeg)
    val handle = hitBoundingHandle(local, frame, handles)
    if (handle != BoundingHandle.NONE) return handle
    return if (frame.bounds.contains(local)) BoundingHandle.BODY else BoundingHandle.NONE
}

/** What a drag did: where the box and the frame angle ended up, and where each point belongs now. */
internal sealed interface TransformDragResult {
    val bounds: BoundingBox
    val frameAngle: Float

    /** Where a point that was at [point] when the drag started should be now, in screen coordinates. */
    fun destination(point: Offset): Offset
}

/**
 * A body move. The box travels in the frame's coordinates while the points travel by the raw screen
 * delta — the asymmetry is deliberate: the artwork follows the pointer, the box follows the frame.
 */
internal data class TranslateDrag(
    override val bounds: BoundingBox,
    override val frameAngle: Float,
    private val delta: Offset,
) : TransformDragResult {
    override fun destination(point: Offset) = point + delta
}

/**
 * A rotation about [pivot] (the frame's anchor). The frame carries the orientation, so the box inside it
 * never changes shape and turns with the pointer without resizing; it only slides when the anchor is off
 * the frame's own pivot.
 */
internal data class RotateDrag(
    override val bounds: BoundingBox,
    override val frameAngle: Float,
    private val pivot: Offset,
    private val deltaDeg: Float,
) : TransformDragResult {
    override fun destination(point: Offset) = point.rotateAbout(pivot, deltaDeg)
}

/**
 * A scale about one of the eight handles, from [pressBounds] to [bounds].
 *
 * Points are read in the frame and written back out of it, which is what makes dragging a corner of a
 * rotated box stretch along the box rather than along the screen.
 */
internal data class ScaleDrag(
    override val bounds: BoundingBox,
    override val frameAngle: Float,
    private val pressBounds: BoundingBox,
    private val pivot: Offset,
    private val pressAngle: Float,
) : TransformDragResult {
    private val w0 = pressBounds.width.coerceAtLeast(1f)
    private val h0 = pressBounds.height.coerceAtLeast(1f)

    override fun destination(point: Offset): Offset {
        val p0 = point.intoTransformFrame(pivot, pressAngle)
        val u = (p0.x - pressBounds.minX) / w0
        val v = (p0.y - pressBounds.minY) / h0
        return Offset(bounds.minX + u * bounds.width, bounds.minY + v * bounds.height)
            .outOfTransformFrame(pivot, pressAngle)
    }
}

/**
 * One live box drag.
 *
 * Everything it needs is frozen at press, so it is a pure value: the pointer and the live modifiers
 * are the only inputs, and the same instance can be re-applied on every move. A rotation turns about
 * [anchor], and so does an Alt scale; a plain scale keeps the side opposite the grabbed handle.
 */
internal class TransformDrag(
    val handle: BoundingHandle,
    val bounds: BoundingBox,
    val pivot: Offset,
    val angleDeg: Float,
    val press: Offset,
    val anchor: Offset = pivot,
) {
    fun apply(pointer: Offset, axis: String?, shift: Boolean, alt: Boolean): TransformDragResult = when (handle) {
        // The axis lock is a screen-space promise — "move horizontally" has to mean the screen's
        // horizontal whatever the frame is turned to — so it constrains the pointer delta before that
        // delta is rotated into the frame. A rotation is measured about the anchor and never sees a
        // delta at all, so the lock cannot reach it.
        BoundingHandle.ROTATE -> rotate(pointer, shift)
        BoundingHandle.BODY -> translate(axisLocked(pointer, axis))
        else -> scale(axisLocked(pointer, axis), shift, alt)
    }

    private fun axisLocked(pointer: Offset, axis: String?) = Offset(
        if (axis == "y") 0f else pointer.x - press.x,
        if (axis == "x") 0f else pointer.y - press.y,
    )

    private fun translate(screenDelta: Offset): TransformDragResult {
        val frameDelta = screenDelta.rotateVector(-angleDeg)
        return TranslateDrag(
            BoundingBox(
                bounds.minX + frameDelta.x, bounds.minY + frameDelta.y,
                bounds.maxX + frameDelta.x, bounds.maxY + frameDelta.y,
            ),
            angleDeg,
            screenDelta,
        )
    }

    private fun rotate(pointer: Offset, shift: Boolean): TransformDragResult {
        // Measured about the anchor in screen space, and accumulated onto the angle the frame already had.
        val deltaDeg = turnDegrees(anchor, press, pointer, shift)
        val angle = angleDeg + deltaDeg
        // The frame stays oriented about the pivot it was pressed with, so a turn about any other point moves
        // the box inside it: by how much the anchor's offset from the pivot reads differently in the new frame.
        val arm = anchor - pivot
        val slide = arm.rotateVector(-angle) - arm.rotateVector(-angleDeg)
        return RotateDrag(
            BoundingBox(bounds.minX + slide.x, bounds.minY + slide.y, bounds.maxX + slide.x, bounds.maxY + slide.y),
            angle, anchor, deltaDeg,
        )
    }

    private fun scale(screenDelta: Offset, shift: Boolean, alt: Boolean): TransformDragResult {
        val d = screenDelta.rotateVector(-angleDeg)
        // Which side of the box the handle moves along each axis: -1 the min side, 1 the max one, 0 neither.
        val sideX = when (handle) {
            BoundingHandle.LEFT, BoundingHandle.TOP_LEFT, BoundingHandle.BOTTOM_LEFT -> -1
            BoundingHandle.RIGHT, BoundingHandle.TOP_RIGHT, BoundingHandle.BOTTOM_RIGHT -> 1
            else -> 0
        }
        val sideY = when (handle) {
            BoundingHandle.TOP, BoundingHandle.TOP_LEFT, BoundingHandle.TOP_RIGHT -> -1
            BoundingHandle.BOTTOM, BoundingHandle.BOTTOM_LEFT, BoundingHandle.BOTTOM_RIGHT -> 1
            else -> 0
        }
        // The point that stays: the opposite side, or with Alt the anchor.
        val fixed = anchor.intoTransformFrame(pivot, angleDeg)
        val fixX = if (alt) fixed.x else if (sideX > 0) bounds.minX else bounds.maxX
        val fixY = if (alt) fixed.y else if (sideY > 0) bounds.minY else bounds.maxY
        fun factor(edge: Float, delta: Float, fix: Float) = if (abs(edge - fix) < 1e-3f) 1f else (edge + delta - fix) / (edge - fix)
        var sx = if (sideX == 0) 1f else factor(if (sideX > 0) bounds.maxX else bounds.minX, d.x, fixX)
        var sy = if (sideY == 0) 1f else factor(if (sideY > 0) bounds.maxY else bounds.minY, d.y, fixY)
        if (shift && handle.isCorner) { sx = maxOf(sx, sy); sy = sx }
        // A drag that crosses its own anchor would otherwise hand the box a negative extent, which the
        // scale below turns into mirrored artwork. Clamping to a few pixels keeps the flip out of the
        // drag entirely.
        if (sideX != 0) sx = sx.coerceAtLeast(MIN_SIZE / bounds.width.coerceAtLeast(1e-3f))
        if (sideY != 0) sy = sy.coerceAtLeast(MIN_SIZE / bounds.height.coerceAtLeast(1e-3f))
        return ScaleDrag(
            BoundingBox(
                fixX + (bounds.minX - fixX) * sx, fixY + (bounds.minY - fixY) * sy,
                fixX + (bounds.maxX - fixX) * sx, fixY + (bounds.maxY - fixY) * sy,
            ),
            angleDeg,
            bounds,
            pivot,
            angleDeg,
        )
    }

    private companion object {
        const val MIN_SIZE = 4f
    }
}

/**
 * How far the pointer has turned about [centre] since [press], in degrees: measured from the press, so
 * grabbing a grip anywhere never jumps. With [snap] the turn steps by 15°.
 */
internal fun turnDegrees(centre: Offset, press: Offset, pointer: Offset, snap: Boolean): Float {
    val angle0 = atan2(press.y - centre.y, press.x - centre.x)
    val angle1 = atan2(pointer.y - centre.y, pointer.x - centre.x)
    var delta = angle1 - angle0
    if (snap) delta = (delta / (PI.toFloat() / 12)).roundToInt() * (PI.toFloat() / 12)
    return Math.toDegrees(delta.toDouble()).toFloat()
}
