package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import io.github.psd2live.ui.theme.ToolColors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The shared transform box: the rectangle, the rotate stem and its grip, the scale handles, the anchor,
 * and the axis-lock guide. Every control that frames something draws it here, so they look and answer alike.
 *
 * The box is drawn inside the frame it was measured in, so an oriented box is still just a rectangle
 * here — the rotation lives in this one [rotate], not in the geometry drawn inside it. [outline] false
 * leaves the rectangle to a caller that already draws the thing's own outline. [pointer], the screen point the
 * pointer is at, lights the turn zone of the corner it is over.
 */
internal fun DrawScope.drawTransformBox(
    frame: TransformFrame,
    hovered: BoundingHandle,
    colors: ToolColors,
    handles: TransformHandles = TransformHandles.ALL,
    axis: String? = null,
    outline: Boolean = true,
    pointer: Offset? = null,
) {
    val bounds = frame.bounds
    val u = handles.unit
    val turnCorner = if (hovered == BoundingHandle.ROTATE) pointer?.let { turnCornerAt(it, frame, handles) } else null
    rotate(frame.angleDeg, frame.pivot) {
        if (outline) {
            drawRect(colors.accent, Offset(bounds.minX, bounds.minY), Size(bounds.width, bounds.height), style = Stroke(1.2f * u))
        }

        if (handles.rotates) {
            val grip = bounds.rotateGrip(u)
            drawLine(colors.accent.copy(alpha = 0.8f), Offset(bounds.centerX, bounds.minY), grip, 1f * u)
            // Hovered, a handle takes the treatment the mesh vertices already use: a white ring
            // around a filled accent mark. Swapping the fill on a fixed-size shape was too
            // quiet to tell which handle the pointer had actually caught. A turn zone lights its own corner instead.
            if (hovered == BoundingHandle.ROTATE && turnCorner == null) {
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

        turnCorner?.let { drawTurnHint(it, Offset(bounds.centerX, bounds.centerY), u, colors) }
    }

    // The anchor: a ring with a cross through it, upright whatever the box is turned to. A box too small to grab it
    // shows it only once it has been moved off the pivot, where it still decides how the box turns.
    val anchor = frame.anchor
    if (!handles.reachesAnchor(bounds) && (anchor - frame.pivot).getDistance() < 0.5f) return drawAxisGuide(frame, axis, colors)
    drawAnchor(anchor, hovered == BoundingHandle.ANCHOR, u, colors)
    drawAxisGuide(frame, axis, colors)
}

/**
 * The anchor: a ring with a cross through it, in three layers - a dark halo, a white outline and the accent core - so
 * it reads over light, dark and busy artwork alike. Hovered, it grows and fills its middle.
 */
private fun DrawScope.drawAnchor(at: Offset, hovered: Boolean, u: Float, colors: ToolColors) {
    val ring = (if (hovered) 8f else 7f) * u
    val arm = ring + 5f * u
    fun layer(color: Color, width: Float) {
        drawCircle(color, ring, at, style = Stroke(width))
        drawLine(color, at - Offset(arm, 0f), at - Offset(ring, 0f), width, StrokeCap.Round)
        drawLine(color, at + Offset(ring, 0f), at + Offset(arm, 0f), width, StrokeCap.Round)
        drawLine(color, at - Offset(0f, arm), at - Offset(0f, ring), width, StrokeCap.Round)
        drawLine(color, at + Offset(0f, ring), at + Offset(0f, arm), width, StrokeCap.Round)
    }
    drawCircle(colors.windowBackground.copy(alpha = 0.35f), ring, at)
    layer(Color.Black.copy(alpha = 0.5f), 5.5f * u)
    layer(Color.White, 3.4f * u)
    layer(colors.accent, 1.6f * u)
    val dot = (if (hovered) 3.2f else 2.2f) * u
    drawCircle(Color.White, dot + 1.2f * u, at)
    drawCircle(colors.accent, dot, at)
}

/**
 * The turn zone under the pointer, lit: an arc around the outside of [corner] (frame coordinates, away from
 * [centre]) with an arrowhead at each end, drawn in the handles' white-ring-and-accent style.
 */
private fun DrawScope.drawTurnHint(corner: Offset, centre: Offset, u: Float, colors: ToolColors) {
    val radius = 19f * u
    val outward = atan2(corner.y - centre.y, corner.x - centre.x) * 180f / PI.toFloat()
    val sweep = 100f
    val start = outward - sweep / 2f
    val topLeft = corner - Offset(radius, radius)
    val size = Size(radius * 2f, radius * 2f)
    /** The arrowhead at [deg] on the arc, pointing on along it - clockwise when [clockwise]. */
    fun head(deg: Float, clockwise: Boolean): Path {
        val r = deg * PI.toFloat() / 180f
        val radial = Offset(cos(r), sin(r))
        val tangent = Offset(-sin(r), cos(r)) * (if (clockwise) 1f else -1f)
        val at = corner + radial * radius
        return Path().apply {
            (at + tangent * (6.5f * u)).let { moveTo(it.x, it.y) }
            (at - tangent * (1.5f * u) + radial * (5f * u)).let { lineTo(it.x, it.y) }
            (at - tangent * (1.5f * u) - radial * (5f * u)).let { lineTo(it.x, it.y) }
            close()
        }
    }
    val heads = listOf(head(start, clockwise = false), head(start + sweep, clockwise = true))
    drawArc(Color.Black.copy(alpha = 0.45f), start, sweep, false, topLeft, size, style = Stroke(5.5f * u, cap = StrokeCap.Round))
    heads.forEach { drawPath(it, Color.Black.copy(alpha = 0.45f), style = Stroke(3f * u)) }
    drawArc(Color.White, start, sweep, false, topLeft, size, style = Stroke(3.6f * u, cap = StrokeCap.Round))
    heads.forEach { drawPath(it, Color.White, style = Stroke(1.8f * u)); drawPath(it, Color.White) }
    drawArc(colors.accent, start, sweep, false, topLeft, size, style = Stroke(1.8f * u, cap = StrokeCap.Round))
    heads.forEach { drawPath(it, colors.accent) }
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
