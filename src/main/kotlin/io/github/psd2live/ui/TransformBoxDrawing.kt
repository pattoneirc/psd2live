package io.github.psd2live.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
 * pointer is at, lights the turn zone of the corner it is over; [active] says [hovered] is held by a live drag.
 *
 * Every part sits on a dark halo and wears white and the accent, so the box reads over light, dark and busy
 * artwork alike; a lit handle swaps the two and grows a size.
 */
internal fun DrawScope.drawTransformBox(
    frame: TransformFrame,
    hovered: BoundingHandle,
    colors: ToolColors,
    handles: TransformHandles = TransformHandles.ALL,
    axis: String? = null,
    outline: Boolean = true,
    pointer: Offset? = null,
    active: Boolean = false,
) {
    val bounds = frame.bounds
    val u = handles.unit
    val turnCorner = if (hovered == BoundingHandle.ROTATE) pointer?.let { turnCornerAt(it, frame, handles) } else null
    // While a handle is held the others step back, so the one doing the work is the one that reads.
    fun alphaOf(handle: BoundingHandle, lit: Boolean) = if (lit || !active || handle == hovered) 1f else 0.45f
    rotate(frame.angleDeg, frame.pivot) {
        if (outline) {
            val topLeft = Offset(bounds.minX, bounds.minY)
            val size = Size(bounds.width, bounds.height)
            drawRect(HALO, topLeft, size, style = Stroke(2.8f * u))
            drawRect(colors.accent, topLeft, size, style = Stroke(1.3f * u))
        }

        if (handles.rotates) {
            val grip = bounds.rotateGrip(u)
            // A turn zone lights its own corner, not the grip.
            val lit = hovered == BoundingHandle.ROTATE && turnCorner == null
            val alpha = alphaOf(BoundingHandle.ROTATE, lit)
            val stemFrom = Offset(bounds.centerX, bounds.minY)
            val stemTo = grip + Offset(0f, 6f * u)
            drawLine(HALO.copy(alpha = HALO.alpha * alpha), stemFrom, stemTo, 3f * u, StrokeCap.Round)
            drawLine(colors.accent.copy(alpha = alpha), stemFrom, stemTo, 1.3f * u, StrokeCap.Round)
            drawRotateGrip(grip, lit, alpha, u, colors)
        }

        bounds.handlePoints().forEach { (handle, pt) ->
            if (!handle.isCorner && !handles.showsEdge(bounds, handle)) return@forEach
            val lit = hovered == handle
            val grow = if (lit) 1.25f else 1f
            // Corners are rounded squares; edges are capsules lying along their edge.
            val extent = when (handle) {
                BoundingHandle.TOP, BoundingHandle.BOTTOM -> Size(14f * u, 5.5f * u)
                BoundingHandle.LEFT, BoundingHandle.RIGHT -> Size(5.5f * u, 14f * u)
                else -> Size(8.5f * u, 8.5f * u)
            } * grow
            val radius = if (handle.isCorner) 2.2f * u * grow else minOf(extent.width, extent.height) / 2f
            drawHandleShape(pt, extent, radius, lit, alphaOf(handle, lit), u, colors)
        }

        turnCorner?.let { drawTurnHint(it, Offset(bounds.centerX, bounds.centerY), u, colors) }
    }

    // A turn in progress: a dashed spoke from the anchor out to the pointer, the line the box turns with.
    if (active && hovered == BoundingHandle.ROTATE && pointer != null) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(5f * u, 4f * u))
        drawLine(HALO, frame.anchor, pointer, 2.8f * u, pathEffect = dash)
        drawLine(colors.accent, frame.anchor, pointer, 1.2f * u, pathEffect = dash)
    }

    // The anchor: a ring with a cross through it, upright whatever the box is turned to. A box too small to grab it
    // shows it only once it has been moved off the pivot, where it still decides how the box turns.
    val anchor = frame.anchor
    if (handles.reachesAnchor(bounds) || (anchor - frame.pivot).getDistance() >= 0.5f) {
        drawAnchor(anchor, hovered == BoundingHandle.ANCHOR, u, colors)
    }
    drawAxisGuide(frame, axis, colors)
}

/** The dark band every part of the box sits on. */
private val HALO = Color.Black.copy(alpha = 0.3f)

/**
 * One scale handle at [at]: a soft shadow, then a white body with an accent border - or, [lit], an accent body with a
 * white border - faded to [alpha].
 */
private fun DrawScope.drawHandleShape(at: Offset, extent: Size, radius: Float, lit: Boolean, alpha: Float, u: Float, colors: ToolColors) {
    val topLeft = at - Offset(extent.width / 2f, extent.height / 2f)
    val shadow = 1.6f * u
    drawRoundRect(Color.Black.copy(alpha = 0.35f * alpha), topLeft - Offset(shadow, shadow - 0.8f * u),
        Size(extent.width + shadow * 2f, extent.height + shadow * 2f), CornerRadius(radius + shadow))
    drawRoundRect((if (lit) colors.accent else Color.White).copy(alpha = alpha), topLeft, extent, CornerRadius(radius))
    drawRoundRect((if (lit) Color.White else colors.accent).copy(alpha = alpha), topLeft, extent, CornerRadius(radius), style = Stroke(1.4f * u))
}

/** The rotate grip: a round button holding a small turning arrow; lit, it fills with the accent and the arrow turns white. */
private fun DrawScope.drawRotateGrip(at: Offset, lit: Boolean, alpha: Float, u: Float, colors: ToolColors) {
    val r = (if (lit) 7.5f else 6.5f) * u
    drawCircle(Color.Black.copy(alpha = 0.35f * alpha), r + 1.6f * u, at + Offset(0f, 0.8f * u))
    drawCircle((if (lit) colors.accent else Color.White).copy(alpha = alpha), r, at)
    drawCircle((if (lit) Color.White else colors.accent).copy(alpha = alpha), r, at, style = Stroke(1.4f * u))
    // Three quarters of a circle, clockwise from the top right, with an arrowhead where it ends.
    val glyph = (if (lit) Color.White else colors.accent).copy(alpha = alpha)
    val g = r * 0.48f
    drawArc(glyph, -60f, 270f, false, at - Offset(g, g), Size(g * 2f, g * 2f), style = Stroke(1.3f * u, cap = StrokeCap.Round))
    val end = (210f * PI / 180f).toFloat()
    val out = Offset(cos(end), sin(end))
    val along = Offset(-sin(end), cos(end))
    val tip = at + out * g
    drawPath(Path().apply {
        (tip + along * (2.6f * u)).let { moveTo(it.x, it.y) }
        (tip - along * (0.6f * u) + out * (2.2f * u)).let { lineTo(it.x, it.y) }
        (tip - along * (0.6f * u) - out * (2.2f * u)).let { lineTo(it.x, it.y) }
        close()
    }, glyph)
}

/**
 * The anchor: a ring with a cross through it, in three layers - a dark halo, a white outline and the accent core.
 * Hovered, it grows and its centre swells.
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
 * [centre]) with an arrowhead at each end, in the same halo, white and accent as the handles.
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
    // Each layer strokes the heads with round joins as wide as its arc, so arc and heads read as one shape.
    fun layer(color: Color, width: Float) {
        drawArc(color, start, sweep, false, topLeft, size, style = Stroke(width, cap = StrokeCap.Round))
        heads.forEach { drawPath(it, color); drawPath(it, color, style = Stroke(width - 1.8f * u, join = StrokeJoin.Round)) }
    }
    layer(HALO, 5.8f * u)
    layer(Color.White, 3.8f * u)
    layer(colors.accent, 1.9f * u)
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
