package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import io.github.psd2live.ui.theme.ToolColors

/**
 * The shared transform box: the rectangle, the rotate stem and its grip, the scale handles, the anchor,
 * and the axis-lock guide. Every control that frames something draws it here, so they look and answer alike.
 *
 * The box is drawn inside the frame it was measured in, so an oriented box is still just a rectangle
 * here — the rotation lives in this one [rotate], not in the geometry drawn inside it. [outline] false
 * leaves the rectangle to a caller that already draws the thing's own outline.
 */
internal fun DrawScope.drawTransformBox(
    frame: TransformFrame,
    hovered: BoundingHandle,
    colors: ToolColors,
    handles: TransformHandles = TransformHandles.ALL,
    axis: String? = null,
    outline: Boolean = true,
) {
    val bounds = frame.bounds
    val u = handles.unit
    rotate(frame.angleDeg, frame.pivot) {
        if (outline) {
            drawRect(colors.accent, Offset(bounds.minX, bounds.minY), Size(bounds.width, bounds.height), style = Stroke(1.2f * u))
        }

        if (handles.rotates) {
            val grip = bounds.rotateGrip(u)
            drawLine(colors.accent.copy(alpha = 0.8f), Offset(bounds.centerX, bounds.minY), grip, 1f * u)
            // Hovered, a handle takes the treatment the mesh vertices already use: a white ring
            // around a filled accent mark. Swapping the fill on a fixed-size shape was too
            // quiet to tell which handle the pointer had actually caught. The turn zones outside the
            // corners light the grip too: they are the same handle.
            if (hovered == BoundingHandle.ROTATE) {
                drawCircle(Color.White, 8.5f * u, grip, style = Stroke(1.8f * u))
                drawCircle(colors.accent, 5.5f * u, grip)
            } else {
                drawCircle(colors.windowBackground, 4f * u, grip)
                drawCircle(colors.accent, 4f * u, grip, style = Stroke(1.5f * u))
            }
        }

        bounds.handlePoints().forEach { (handle, pt) ->
            if (!handle.isCorner && !handles.showsEdge(bounds, handle)) return@forEach
            val isHovered = hovered == handle
            val side = u * when {
                isHovered && handle.isCorner -> 8.5f
                isHovered || handle.isCorner -> 7f
                else -> 5.5f
            }
            if (isHovered) {
                val ring = side + 5f * u
                drawRect(Color.White, pt - Offset(ring * 0.5f, ring * 0.5f), Size(ring, ring), style = Stroke(1.8f * u))
                drawRect(colors.accent, pt - Offset(side * 0.5f, side * 0.5f), Size(side, side))
            } else {
                drawRect(Color.White, pt - Offset(side * 0.5f, side * 0.5f), Size(side, side))
                drawRect(colors.accent, pt - Offset(side * 0.5f, side * 0.5f), Size(side, side), style = Stroke(1f * u))
            }
        }
    }

    // The anchor: a ring with a cross through it, upright whatever the box is turned to. A box too small to grab it
    // shows it only once it has been moved off the pivot, where it still decides how the box turns.
    val anchor = frame.anchor
    if (!handles.reachesAnchor(bounds) && (anchor - frame.pivot).getDistance() < 0.5f) return drawAxisGuide(frame, axis, colors)
    val ring = (if (hovered == BoundingHandle.ANCHOR) 5.5f else 4.5f) * u
    val arm = ring + 3.5f * u
    drawCircle(Color.Black.copy(alpha = 0.45f), ring, anchor, style = Stroke(3f * u))
    drawLine(Color.Black.copy(alpha = 0.45f), anchor - Offset(arm, 0f), anchor + Offset(arm, 0f), 3f * u)
    drawLine(Color.Black.copy(alpha = 0.45f), anchor - Offset(0f, arm), anchor + Offset(0f, arm), 3f * u)
    val mark = if (hovered == BoundingHandle.ANCHOR) Color.White else colors.accent
    drawCircle(mark, ring, anchor, style = Stroke(1.4f * u))
    drawLine(mark, anchor - Offset(arm, 0f), anchor + Offset(arm, 0f), 1.2f * u)
    drawLine(mark, anchor - Offset(0f, arm), anchor + Offset(0f, arm), 1.2f * u)
    if (hovered == BoundingHandle.ANCHOR) drawCircle(colors.accent, 2f * u, anchor)
    drawAxisGuide(frame, axis, colors)
}

private fun DrawScope.drawAxisGuide(frame: TransformFrame, axis: String?, colors: ToolColors) {
    // Axis lock guide. The constraint is a keyboard latch with no persistent on-screen state, so
    // without this the drag just silently refuses one of the two directions.
    if (axis != null) {
        val span = size.width + size.height
        // The pivot, not the box centre: it is already a screen point, while the box is expressed
        // in the frame and would need turning back out of it.
        val center = frame.pivot
        val from = if (axis == "x") Offset(center.x - span, center.y) else Offset(center.x, center.y - span)
        val to = if (axis == "x") Offset(center.x + span, center.y) else Offset(center.x, center.y + span)
        drawLine(colors.warning.copy(alpha = 0.7f), from, to, 1.2f)
    }
}
