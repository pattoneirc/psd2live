package io.github.psd2live.tools

import io.github.psd2live.core.IrGeometryEvaluator
import io.github.psd2live.format.model.*
import java.io.File
import kotlin.test.Test

/**
 * Black-box samples of the engine's warp mapping (lattice point -> parent space), inside and far outside the
 * unit square, for a skewed lattice: the reference the runtime's independent implementation is checked against.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*WarpProbeTool'
 * Writes build/tools/warp-probe/samples.tsv: mode, cols, rows, u, v, x, y.
 */
class WarpProbeTool {
	@Test fun probe() {
		requireTools()
		val lattices = listOf(
			Triple(1, 1, floatArrayOf(0f, 0f, 100f, 10f, 5f, 80f, 120f, 110f)),
			Triple(2, 2, floatArrayOf(0f, 0f, 50f, 5f, 100f, 0f, 2f, 50f, 55f, 60f, 104f, 48f, 0f, 100f, 48f, 95f, 102f, 105f)),
			Triple(3, 2, FloatArray(4 * 3 * 2) { i -> val p = i / 2; val c = p % 4; val r = p / 4; if (i % 2 == 0) c * 30f + r * 4f + (c * r % 3) * 2f else r * 40f + c * 3f - (c % 2) * 5f }),
		)
		val out = StringBuilder()
		val point = FloatArray(2)
		val values = listOf(-3f, -1.5f, -0.6f, -0.2f, -0.01f, 0f, 0.13f, 0.37f, 0.5f, 0.71f, 0.99f, 1f, 1.01f, 1.3f, 2f, 3.5f)
		for ((cols, rows, cp) in lattices) for (bilinear in listOf(true, false)) for (u in values) for (v in values) {
			org.umamo.render.eval.warpApply(cp, cols, rows, bilinear, u, v, point, 0)
			out.append(if (bilinear) "quad" else "tri").append('\t').append(cols).append('\t').append(rows).append('\t')
				.append(u).append('\t').append(v).append('\t').append(point[0]).append('\t').append(point[1]).append('\n')
		}
		File(output("warp-probe"), "samples.tsv").writeText(out.toString())
		File(output("warp-probe"), "lattices.txt").writeText(lattices.joinToString("\n") { (c, r, cp) -> "$c $r ${cp.joinToString(" ")}" })
	}

	/**
	 * A rotation deformer under a root warp: the canvas images of its local origin and unit axes (10 px), for
	 * affine and non-affine lattices, pivots, base and keyed angles and scales.
	 * Writes build/tools/warp-probe/rotation.tsv: lattice, px, py, base, angle, scale, o.x, o.y, x.x, x.y, y.x, y.y.
	 */
	@Test fun rotationInWarp() {
		requireTools()
		val lattices = listOf(
			floatArrayOf(100f, 100f, 300f, 100f, 100f, 300f, 300f, 300f),
			floatArrayOf(100f, 100f, 300f, 100f, 100f, 200f, 300f, 200f),
			floatArrayOf(100f, 100f, 300f, 100f, 200f, 300f, 400f, 300f),
			run { val c = kotlin.math.cos(0.5f); val s = kotlin.math.sin(0.5f); floatArrayOf(200f, 200f, 200f + 200f * c, 200f + 200f * s, 200f - 200f * s, 200f + 200f * c, 200f + 200f * (c - s), 200f + 200f * (s + c)) },
			floatArrayOf(100f, 100f, 300f, 120f, 80f, 300f, 350f, 330f),
		)
		val geometry = MeshGeometry(Floats.values(0f, 0f, 10f, 0f, 0f, 10f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2))
		val out = StringBuilder()
		for ((index, lattice) in lattices.withIndex()) for ((px, py) in listOf(0.5f to 0.5f, 0.2f to 0.7f, 0.9f to 0.1f))
			for (base in listOf(0f, 45f, 90f, -30f)) for (angle in listOf(0f, 20f)) for (scale in listOf(1f, 2f)) {
				val ir = RigIR(Canvas(512f, 512f), emptyList(),
					deformers = listOf(
						Deformer.Warp("W", "W", null, null, 1, 1, true, KeyGrid.single(LatticePoints(Floats.wrap(lattice)))),
						Deformer.Rotation("R", "R", "W", null, base, KeyGrid.single(Pivot(px, py, angle, scale)))),
					meshes = listOf(Mesh("M", "M", "R", geometry = geometry, offsets = null)),
					rootChildren = listOf(ChildRef.MeshRef("M")), renderRoot = RenderGroup(null, 500, listOf(RenderMesh("M"))))
				val p = IrGeometryEvaluator.open(ir).evaluate(emptyMap()).positions.getValue("M")
				out.append(listOf(index, px, py, base, angle, scale, p[0], p[1], p[2] - p[0], p[3] - p[1], p[4] - p[0], p[5] - p[1]).joinToString("\t")).append('\n')
			}
		File(output("warp-probe"), "rotation.tsv").writeText(out.toString())
	}

	/**
	 * Flipped rotation frames at the root, under a turned warp and under a rotation (itself flipped or not).
	 * Writes build/tools/warp-probe/flips.tsv: parent, parent flips, flipX, flipY, base, angle, o.x, o.y, x.x, x.y, y.x, y.y.
	 */
	@Test fun rotationFlips() {
		requireTools()
		val c = kotlin.math.cos(0.5f); val s = kotlin.math.sin(0.5f)
		val lattice = floatArrayOf(200f, 200f, 200f + 200f * c, 200f + 200f * s, 200f - 200f * s, 200f + 200f * c, 200f + 200f * (c - s), 200f + 200f * (s + c))
		val geometry = MeshGeometry(Floats.values(0f, 0f, 10f, 0f, 0f, 10f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2))
		val out = StringBuilder()
		val flips = listOf(false to false, true to false, false to true, true to true)
		for (parent in listOf("none", "warp", "rotation")) for ((pfx, pfy) in if (parent == "rotation") flips else listOf(false to false))
			for ((fx, fy) in flips) for (base in listOf(0f, 45f)) for (angle in listOf(0f, 20f)) {
				val parents = when (parent) {
					"warp" -> listOf(Deformer.Warp("P", "P", null, null, 1, 1, true, KeyGrid.single(LatticePoints(Floats.wrap(lattice)))))
					"rotation" -> listOf(Deformer.Rotation("P", "P", null, null, 0f, KeyGrid.single(Pivot(250f, 250f, 30f, 1.5f)), flipX = pfx, flipY = pfy))
					else -> emptyList()
				}
				val pivot = if (parent == "warp") Pivot(0.5f, 0.5f, angle, 1f) else if (parent == "rotation") Pivot(20f, 10f, angle, 1f) else Pivot(100f, 100f, angle, 1f)
				val ir = RigIR(Canvas(512f, 512f), emptyList(),
					deformers = parents + Deformer.Rotation("R", "R", parents.firstOrNull()?.id, null, base, KeyGrid.single(pivot), flipX = fx, flipY = fy),
					meshes = listOf(Mesh("M", "M", "R", geometry = geometry, offsets = null)),
					rootChildren = listOf(ChildRef.MeshRef("M")), renderRoot = RenderGroup(null, 500, listOf(RenderMesh("M"))))
				val p = IrGeometryEvaluator.open(ir).evaluate(emptyMap()).positions.getValue("M")
				out.append(listOf(parent, "$pfx/$pfy", fx, fy, base, angle, p[0], p[1], p[2] - p[0], p[3] - p[1], p[4] - p[0], p[5] - p[1]).joinToString("\t")).append('\n')
			}
		File(output("warp-probe"), "flips.tsv").writeText(out.toString())
	}

	/**
	 * Blend shapes on a root mesh resting at (1000, 2000), a root warp and a root rotation, with a limit.
	 * Writes build/tools/warp-probe/blend.tsv: S, T, A, then mesh M positions, opacity and draw order, warp
	 * child C positions and opacity, rotation child D positions and opacity.
	 */
	@Test fun blendShapes() {
		requireTools()
		val parameters = listOf(Parameter("S", "S", -1f, 1f, 0f, blend = true), Parameter("T", "T", 0f, 1f, 0f, blend = true), Parameter("A", "A", -1f, 1f, 0f))
		val red = Rgb(0.1f, 0f, 0f); val green = Rgb(0f, 0.1f, 0f); val none = Rgb(0f, 0f, 0f)
		fun unit(i: Int) = Floats.wrap(FloatArray(8) { if (it == i * 2) 1f else 0f })
		val mesh = Mesh("M", "M", null, geometry = MeshGeometry(Floats.wrap(FloatArray(8) { if (it % 2 == 0) 1000f else 2000f }), Floats.wrap(FloatArray(8)), Ints.values(0, 1, 2, 1, 3, 2)),
			offsets = null, drawOrder = 500f, opacity = 0.8f,
			shapes = listOf(
				BlendBinding("S", Floats.values(-1f, 0f, 1f), 1, listOf(MeshShape(unit(0), 10f, 0.1f, red, green), null, MeshShape(unit(1), 20f, -0.2f, none, none))),
				BlendBinding("T", Floats.values(0f, 1f), 0, listOf(null, MeshShape(unit(2), 40f, -0.3f, none, none)),
					listOf(BlendLimit("A", listOf(BlendLimitPoint(-1f, 0f), BlendLimitPoint(0f, 1f), BlendLimitPoint(1f, 0.5f))))),
			))
		val square = floatArrayOf(100f, 100f, 200f, 100f, 100f, 200f, 200f, 200f)
		val warp = Deformer.Warp("W", "W", null, null, 1, 1, true, KeyGrid.single(LatticePoints(Floats.wrap(square))), opacity = 0.9f,
			shapes = listOf(BlendBinding("S", Floats.values(-1f, 0f, 1f), 1, listOf(null, null, LatticeShape(Floats.wrap(FloatArray(8) { if (it % 2 == 0) 10f else 0f }), -0.5f, none, none)))))
		val rotation = Deformer.Rotation("R", "R", null, null, 0f, KeyGrid.single(Pivot(300f, 300f, 0f, 1f)),
			shapes = listOf(BlendBinding("S", Floats.values(-1f, 0f, 1f), 1, listOf(null, null, PivotShape(5f, 0f, 10f, 0.5f, false, false, -0.25f, none, none)))))
		val tri = MeshGeometry(Floats.values(0.5f, 0.5f, 1f, 0.5f, 0.5f, 1f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2))
		val rotTri = MeshGeometry(Floats.values(0f, 0f, 10f, 0f, 0f, 10f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2))
		val ir = RigIR(Canvas(512f, 512f), parameters, deformers = listOf(warp, rotation),
			meshes = listOf(mesh, Mesh("C", "C", "W", geometry = tri, offsets = null), Mesh("D", "D", "R", geometry = rotTri, offsets = null)),
			rootChildren = listOf(ChildRef.MeshRef("M"), ChildRef.MeshRef("C"), ChildRef.MeshRef("D")),
			renderRoot = RenderGroup(null, 500, listOf(RenderMesh("M"), RenderMesh("C"), RenderMesh("D"))))
		val session = IrGeometryEvaluator.open(ir)
		val out = StringBuilder()
		for (sv in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) for (tv in listOf(0f, 0.5f, 1f)) for (av in listOf(-1f, 0f, 0.5f, 1f)) {
			val g = session.evaluate(mapOf("S" to sv, "T" to tv, "A" to av))
			val row = mutableListOf<Any>(sv, tv, av)
			for (id in listOf("M", "C", "D")) {
				row.addAll(g.positions[id]?.toList() ?: listOf("-")); row.add(g.opacity[id] ?: "-")
				if (id == "M") row.add(g.drawOrder[id] ?: "-")
			}
			out.append(row.joinToString("\t")).append('\n')
		}
		File(output("warp-probe"), "blend.tsv").writeText(out.toString())
	}

	/**
	 * Blend shapes over keyform grids on A: a warp shifted ±50 px by A with a shape at S = 1, a rotation moved
	 * ±50 px by A with a shape, and a mesh offset ±50 px by A with shape deltas, opacity keyed on A.
	 * Writes build/tools/warp-probe/blend-grid.tsv: A, S, warp child C, rotation child D, mesh M positions and opacities.
	 */
	@Test fun blendOverGrids() {
		requireTools()
		val parameters = listOf(Parameter("S", "S", 0f, 1f, 0f, blend = true), Parameter("A", "A", -1f, 1f, 0f))
		val axis = listOf(KeyAxis("A", Floats.values(-1f, 1f)))
		val none = Rgb(0f, 0f, 0f)
		val square = floatArrayOf(100f, 100f, 200f, 100f, 100f, 200f, 200f, 200f)
		fun shifted(dx: Float) = LatticePoints(Floats.wrap(FloatArray(8) { square[it] + if (it % 2 == 0) dx else 0f }))
		val opacity = mapOf(Channel.OPACITY to KeyGrid(axis, listOf(KeyCell(Ints.values(0), ChannelValue.Scalar(0.2f) as ChannelValue), KeyCell(Ints.values(1), ChannelValue.Scalar(1f) as ChannelValue))))
		val warp = Deformer.Warp("W", "W", null, null, 1, 1, true, KeyGrid(axis, listOf(KeyCell(Ints.values(0), shifted(-50f)), KeyCell(Ints.values(1), shifted(50f)))),
			channels = opacity,
			shapes = listOf(BlendBinding("S", Floats.values(0f, 1f), 0, listOf(null, LatticeShape(Floats.wrap(FloatArray(8) { square[it] + if (it % 2 == 1) 30f else 0f }), 0.5f, none, none)))))
		val rotation = Deformer.Rotation("R", "R", null, null, 0f, KeyGrid(axis, listOf(KeyCell(Ints.values(0), Pivot(250f, 300f, -10f, 1f)), KeyCell(Ints.values(1), Pivot(350f, 300f, 10f, 1f)))),
			channels = opacity,
			shapes = listOf(BlendBinding("S", Floats.values(0f, 1f), 0, listOf(null, PivotShape(300f, 330f, 20f, 2f, false, false, 0.5f, none, none)))))
		val rest = FloatArray(8) { if (it % 2 == 0) 1000f else 2000f }
		val mesh = Mesh("M", "M", null, geometry = MeshGeometry(Floats.wrap(rest), Floats.wrap(FloatArray(8)), Ints.values(0, 1, 2, 1, 3, 2)),
			offsets = KeyGrid(axis, listOf(KeyCell(Ints.values(0), MeshOffsets(Floats.wrap(FloatArray(8) { if (it % 2 == 0) -50f else 0f }))),
				KeyCell(Ints.values(1), MeshOffsets(Floats.wrap(FloatArray(8) { if (it % 2 == 0) 50f else 0f }))))),
			channels = opacity,
			shapes = listOf(BlendBinding("S", Floats.values(0f, 1f), 0, listOf(null, MeshShape(Floats.wrap(FloatArray(8) { if (it % 2 == 1) 30f else 0f }), 0f, 0.5f, none, none)))))
		val tri = MeshGeometry(Floats.values(0.5f, 0.5f, 1f, 0.5f, 0.5f, 1f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2))
		val rotTri = MeshGeometry(Floats.values(0f, 0f, 10f, 0f, 0f, 10f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2))
		val ir = RigIR(Canvas(512f, 512f), parameters, deformers = listOf(warp, rotation),
			meshes = listOf(Mesh("C", "C", "W", geometry = tri, offsets = null), Mesh("D", "D", "R", geometry = rotTri, offsets = null), mesh),
			rootChildren = listOf(ChildRef.MeshRef("C"), ChildRef.MeshRef("D"), ChildRef.MeshRef("M")),
			renderRoot = RenderGroup(null, 500, listOf(RenderMesh("C"), RenderMesh("D"), RenderMesh("M"))))
		val session = IrGeometryEvaluator.open(ir)
		val out = StringBuilder()
		for (av in listOf(-1f, 0f, 1f)) for (sv in listOf(0f, 0.5f, 1f)) {
			val g = session.evaluate(mapOf("S" to sv, "A" to av))
			val row = mutableListOf<Any>(av, sv)
			for (id in listOf("C", "D", "M")) { row.addAll(g.positions.getValue(id).take(4).toList()); row.add(g.opacity[id] ?: "-") }
			out.append(row.joinToString("\t")).append('\n')
		}
		File(output("warp-probe"), "blend-grid.tsv").writeText(out.toString())
	}

	/**
	 * Rotation blend shapes: a root rotation P and a child rotation R, each with a shape on S, R also with a
	 * limited shape on T. Writes build/tools/warp-probe/blend-rotation.tsv: case, S, T, A, child origin and axes.
	 */
	@Test fun blendRotations() {
		requireTools()
		val parameters = listOf(Parameter("S", "S", -1f, 1f, 0f, blend = true), Parameter("T", "T", 0f, 1f, 0f, blend = true), Parameter("A", "A", 0f, 1f, 0.25f))
		val none = Rgb(0f, 0f, 0f)
		val axis = listOf(KeyAxis("A", Floats.values(0f, 1f)))
		val geometry = MeshGeometry(Floats.values(0f, 0f, 10f, 0f, 0f, 10f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2))
		val out = StringBuilder()
		for (case in 0..3) {
			val parent = Deformer.Rotation("P", "P", null, null, 0f, KeyGrid.single(Pivot(250f, 250f, 30f, 1.5f)),
				shapes = if (case == 1 || case == 3) listOf(BlendBinding("S", Floats.values(-1f, 0f, 1f), 1, listOf(null, null, PivotShape(260f, 240f, 50f, 1f, false, false, 1f, none, none)))) else emptyList())
			val child = Deformer.Rotation("R", "R", "P", null, 0f, KeyGrid(axis, listOf(KeyCell(Ints.values(0), Pivot(20f, 10f, 10f, 1f)), KeyCell(Ints.values(1), Pivot(30f, 10f, 30f, 2f)))),
				shapes = listOf(BlendBinding("S", Floats.values(-1f, 0f, 1f), 1, listOf(PivotShape(0f, 20f, -20f, 0.5f, false, false, 1f, none, none), null, null))) +
					if (case >= 2) listOf(BlendBinding("T", Floats.values(0f, 1f), 0, listOf(null, PivotShape(10f, 0f, 40f, 1.5f, false, false, 1f, none, none)),
						listOf(BlendLimit("A", listOf(BlendLimitPoint(-1f, 0.2f), BlendLimitPoint(0f, 1f), BlendLimitPoint(1f, 0.5f)))))) else emptyList())
			val ir = RigIR(Canvas(512f, 512f), parameters, deformers = listOf(parent, child),
				meshes = listOf(Mesh("M", "M", "R", geometry = geometry, offsets = null)),
				rootChildren = listOf(ChildRef.MeshRef("M")), renderRoot = RenderGroup(null, 500, listOf(RenderMesh("M"))))
			val session = IrGeometryEvaluator.open(ir)
			for (sv in listOf(-1f, -0.5f, 0f, 1f)) for (tv in listOf(0f, 1f)) for (av in listOf(0f, 0.25f, 1f)) {
				val p = session.evaluate(mapOf("S" to sv, "T" to tv, "A" to av)).positions.getValue("M")
				out.append(listOf(case, sv, tv, av, p[0], p[1], p[2] - p[0], p[3] - p[1], p[4] - p[0], p[5] - p[1]).joinToString("\t")).append('\n')
			}
		}
		File(output("warp-probe"), "blend-rotation.tsv").writeText(out.toString())
	}

	/**
	 * Sparse keyform grids: a root mesh resting at (1000, 2000) whose vertex i is offset by 1 in x only in
	 * cell i, and a root warp whose lattice cell i is shifted by (100 i, 0), each with one cell missing.
	 * Writes build/tools/warp-probe/sparse.tsv: object, missing cell, a, b, then the positions.
	 */
	@Test fun sparseGrids() {
		requireTools()
		val parameters = listOf(Parameter("A", "A", 0f, 1f, 0f), Parameter("B", "B", 0f, 1f, 0f))
		val axes = listOf(KeyAxis("A", Floats.values(0f, 1f)), KeyAxis("B", Floats.values(0f, 1f)))
		val coordinates = listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)
		val out = StringBuilder()
		val values = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
		for (missing in 0..3) {
			val present = coordinates.indices.filter { it != missing }
			val geometry = MeshGeometry(Floats.wrap(FloatArray(8) { if (it % 2 == 0) 1000f else 2000f }), Floats.wrap(FloatArray(8)), Ints.values(0, 1, 2, 1, 3, 2))
			val mesh = Mesh("M", "M", null, geometry = geometry, offsets = KeyGrid(axes, present.map { i ->
				KeyCell(Ints.values(coordinates[i].first, coordinates[i].second), MeshOffsets(Floats.wrap(FloatArray(8) { if (it == i * 2) 1f else 0f })))
			}))
			val square = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 10f, 10f)
			val warp = Deformer.Warp("W", "W", null, null, 1, 1, true, KeyGrid(axes, present.map { i ->
				KeyCell(Ints.values(coordinates[i].first, coordinates[i].second), LatticePoints(Floats.wrap(FloatArray(8) { square[it] + if (it % 2 == 0) 100f * (i + 1) else 0f })))
			}))
			val child = Mesh("C", "C", "W", geometry = MeshGeometry(Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2)), offsets = null)
			val ir = RigIR(Canvas(512f, 512f), parameters, deformers = listOf(warp), meshes = listOf(mesh, child),
				rootChildren = listOf(ChildRef.MeshRef("M"), ChildRef.MeshRef("C")), renderRoot = RenderGroup(null, 500, listOf(RenderMesh("M"), RenderMesh("C"))))
			val session = IrGeometryEvaluator.open(ir)
			for (a in values) for (b in values) {
				val g = session.evaluate(mapOf("A" to a, "B" to b))
				out.append(listOf("mesh", missing, a, b).joinToString("\t")).append('\t').append(g.positions["M"]?.joinToString("\t") ?: "-").append('\n')
				out.append(listOf("warp", missing, a, b).joinToString("\t")).append('\t').append(g.positions["C"]?.joinToString("\t") ?: "-").append('\n')
			}
		}
		File(output("warp-probe"), "sparse.tsv").writeText(out.toString())
	}
}
