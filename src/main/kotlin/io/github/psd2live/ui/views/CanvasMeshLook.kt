package io.github.psd2live.ui.views

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/*
 * The canvas's one look for geometry being edited: mesh vertices, warp control points, the wires between them.
 * Every mode and tool draws its editable points and edges through these, so a vertex looks and answers the same
 * wherever it is, and each colour keeps one meaning:
 *
 * - structure: the light neutral [MeshLook.Structure] - or, while several meshes are held at once, each mesh's own
 *   colour, so they never read as one;
 * - selected: the accent, larger, cased in white over a dark rim, so it holds on art of the accent's own hue;
 * - hovered: a white ring round the point (a white fill when it is not selected), the thing a click would take;
 * - reach: a ring in the tool's colour round the point, from a hairline close in to a wide firm ring at a full
 *   dab - the brush's hover preview, and its falloff while a stroke is in hand;
 * - data (weights, glue sides) washes the artwork under the wires, it does not recolour the structure.
 *
 * What a point is shows in its shape, not its colour: a mesh vertex is round, a warp control point square, a
 * glued point a diamond. Points appear only where they can be picked; the passive mesh channel draws wires alone.
 */
internal object MeshLook {
	/** The structure's tone when one mesh (or one lattice) is edited. */
	val Structure = Color(0xFFE6EBF2)

	/** The dark edge under every wire and point, so they read over light and dark art alike. */
	val Halo = Color(0xFF0C0D10).copy(alpha = 0.55f)

	const val WIRE = 1f
	const val WIRE_SELECTED = 2.2f
	const val WIRE_ALPHA = 0.62f
	const val HALO_EXTRA = 1.6f

	const val POINT = 2.4f
	const val POINT_SELECTED = 3.6f
	const val POINT_HOVERED = 3.2f
	const val HOVER_RING = 7.4f
	const val HALO_RIM = 1.3f
	/** The white casing of a selected point, between its dark rim and its accent core. */
	const val CASING = 1.4f

	/** The reach ring's radius just inside the brush's edge and at a full dab. */
	const val REACH_MIN = 3.2f
	const val REACH_MAX = 11f

	/** The tone brushes that move points ring their reach in. */
	val Reach = Color(0xFFFF5A4E)
}

internal enum class HandleShape { ROUND, SQUARE }

/** Wires as point pairs ([PointMode.Lines]): the halo, then the line. [width] is the line's own. */
internal fun DrawScope.drawMeshWires(segments: List<Offset>, tone: Color, width: Float = MeshLook.WIRE, alpha: Float = MeshLook.WIRE_ALPHA) {
	if (segments.isEmpty()) return
	drawPoints(segments, PointMode.Lines, MeshLook.Halo, width + MeshLook.HALO_EXTRA)
	drawPoints(segments, PointMode.Lines, tone.copy(alpha = tone.alpha * alpha), width)
}

/** The selected wires: the accent cased in white over the halo, so they hold on art of the accent's hue. */
internal fun DrawScope.drawSelectedWires(segments: List<Offset>, accent: Color) {
	if (segments.isEmpty()) return
	drawPoints(segments, PointMode.Lines, MeshLook.Halo, MeshLook.WIRE_SELECTED + 2.6f)
	drawPoints(segments, PointMode.Lines, Color.White, MeshLook.WIRE_SELECTED + 1.2f)
	drawPoints(segments, PointMode.Lines, accent, MeshLook.WIRE_SELECTED)
}

/** One editable point in the state it is in. [tone] is the structure's; [accent] the selection's. */
internal fun DrawScope.drawMeshHandle(
	center: Offset,
	tone: Color,
	accent: Color,
	selected: Boolean = false,
	hovered: Boolean = false,
	shape: HandleShape = HandleShape.ROUND,
) {
	val r = when {
		selected -> MeshLook.POINT_SELECTED
		hovered -> MeshLook.POINT_HOVERED
		else -> MeshLook.POINT
	}
	if (hovered) {
		drawCircle(MeshLook.Halo, MeshLook.HOVER_RING, center, style = Stroke(3.4f))
		drawCircle(Color.White, MeshLook.HOVER_RING, center, style = Stroke(1.6f))
	}
	val fill = when {
		selected -> accent
		hovered -> Color.White
		else -> tone
	}
	// Dark rim, then (selected) a white casing, then the core: three bands no art can swallow all of.
	val layers = buildList {
		add(MeshLook.Halo.copy(alpha = 0.8f) to r + MeshLook.HALO_RIM + if (selected) MeshLook.CASING else 0f)
		if (selected) add(Color.White to r + MeshLook.CASING)
		add(fill to r)
	}
	for ((color, radius) in layers) when (shape) {
		HandleShape.ROUND -> drawCircle(color, radius, center)
		HandleShape.SQUARE -> drawRect(color, Offset(center.x - radius, center.y - radius), Size(radius * 2f, radius * 2f))
	}
}

/** How far a brush would move the point at [center], [reach] 0..1: a ring in [tint] that grows and firms with it. */
internal fun DrawScope.drawReachRing(center: Offset, reach: Float, tint: Color) {
	if (reach <= 0.001f) return
	// Size carries the reach, so a falloff reads as rings swelling towards the centre; weight and opacity follow.
	val r = MeshLook.REACH_MIN + reach * (MeshLook.REACH_MAX - MeshLook.REACH_MIN)
	val width = 1f + reach * 1.2f
	drawCircle(Color.Black.copy(alpha = 0.25f + 0.35f * reach), r, center, style = Stroke(width + 1.6f))
	drawCircle(tint.copy(alpha = 0.45f + 0.55f * reach), r, center, style = Stroke(width))
}

/** A filled triangle of the face selection, in the accent. */
internal fun DrawScope.drawSelectedFace(a: Offset, b: Offset, c: Offset, accent: Color) {
	drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close() }, accent.copy(alpha = 0.24f))
}
