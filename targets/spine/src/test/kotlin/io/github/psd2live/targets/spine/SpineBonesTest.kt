package io.github.psd2live.targets.spine

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.*

/**
 * Evaluates rigs made of rotation deformers (pivots keyed on at most one parameter) and meshes under them,
 * by the editor's documented rotation rules: angle is the keyed angle plus the base angle; the origin goes
 * through the parent, angles and scales add; a parent's vertical flip or negative scale adds a half turn; a
 * deformer's own flips mirror its children's coordinates only.
 */
internal object RotationEvaluator : GeometryEvaluator {
	private class Fr(val x: Double, val y: Double, val angle: Double, val scale: Double, val fx: Boolean, val fy: Boolean) {
		fun apply(px: Double, py: Double): DoubleArray {
			val lx = (if (fx) -px else px) * scale; val ly = (if (fy) -py else py) * scale
			val r = Math.toRadians(angle)
			return doubleArrayOf(x + lx * cos(r) - ly * sin(r), y + lx * sin(r) + ly * cos(r))
		}
	}

	private fun <F> interpolate(grid: KeyGrid<F>, values: Map<String, Float>, ir: RigIR, blend: (F, F, Double) -> F): F {
		if (grid.axes.isEmpty()) return grid.cells.single().form
		val axis = grid.axes.single()
		val p = ir.parameters.first { it.id == axis.parameter }
		val v = (values[p.id] ?: p.default).coerceIn(p.min, p.max)
		val keys = axis.keys.toArray()
		val cell = { i: Int -> grid.cells.first { it.coordinate[0] == i }.form }
		if (v <= keys.first()) return cell(0)
		if (v >= keys.last()) return cell(keys.size - 1)
		val j = (0 until keys.size - 1).first { v <= keys[it + 1] }
		return blend(cell(j), cell(j + 1), ((v - keys[j]) / (keys[j + 1] - keys[j])).toDouble())
	}

	override fun open(ir: RigIR) = object : GeometrySession {
		override fun evaluate(parameters: Map<String, Float>): PoseGeometry {
			val frames = HashMap<String, Fr>()
			fun frame(id: String): Fr = frames.getOrPut(id) {
				val r = ir.deformers.first { it.id == id } as Deformer.Rotation
				val p = interpolate(r.pivot!!, parameters, ir) { a, b, u ->
					Pivot((a.x + (b.x - a.x) * u).toFloat(), (a.y + (b.y - a.y) * u).toFloat(), (a.angle + (b.angle - a.angle) * u).toFloat(), (a.scale + (b.scale - a.scale) * u).toFloat())
				}
				val angle = p.angle.toDouble() + r.baseAngle
				val parent = r.parent?.let(::frame)
				if (parent == null) Fr(p.x.toDouble(), p.y.toDouble(), angle, p.scale.toDouble(), r.flipX, r.flipY)
				else {
					val origin = parent.apply(p.x.toDouble(), p.y.toDouble())
					val half = (if (parent.fy) 180.0 else 0.0) + (if (parent.scale < 0) 180.0 else 0.0)
					Fr(origin[0], origin[1], parent.angle + angle + half, parent.scale * p.scale, r.flipX, r.flipY)
				}
			}
			val positions = ir.meshes.associate { mesh ->
				val rest = mesh.geometry!!.positions.toArray()
				val offsets = mesh.offsets?.let { grid -> interpolate(grid, parameters, ir) { a, b, u ->
					MeshOffsets(Floats.wrap(FloatArray(a.deltas.size) { (a.deltas[it] + (b.deltas[it] - a.deltas[it]) * u).toFloat() }))
				}.deltas.toArray() }
				val local = FloatArray(rest.size) { rest[it] + (offsets?.get(it) ?: 0f) }
				val f = mesh.parent?.let(::frame)
				mesh.id to if (f == null) local else FloatArray(local.size) { i ->
					f.apply(local[i - i % 2].toDouble(), local[i - i % 2 + 1].toDouble())[i % 2].toFloat()
				}
			}
			return PoseGeometry(positions, ir.meshes.associate { it.id to 1f }, ir.meshes.associate { it.id to it.drawOrder })
		}
		override fun close() {}
	}
}

class SpineBonesTest {
	private fun pivots(parameter: String, keys: List<Float>, pivot: (Int) -> Pivot) =
		KeyGrid(listOf(KeyAxis(parameter, Floats.of(keys.toFloatArray()))), keys.indices.map { KeyCell(Ints.values(it), pivot(it)) })

	private fun quad(x: Float, y: Float, w: Float, h: Float) = MeshGeometry(
		Floats.values(x, y, x + w, y, x, y + h, x + w, y + h), Floats.values(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), Ints.values(0, 1, 2, 1, 3, 2))

	/** A three-rotation chain on A, B and C; M2 also has keyforms on B in its rotation's space. */
	private fun chain(flips: Boolean = false) = RigIR(
		canvas = Canvas(200f, 160f),
		parameters = listOf(Parameter("A", "A", -30f, 30f, 0f), Parameter("B", "B", -1f, 1f, 0f), Parameter("C", "C", 0f, 1f, 0f)),
		deformers = listOf(
			Deformer.Rotation("R1", "R1", null, null, 0f, pivots("A", listOf(-30f, 0f, 30f)) { Pivot(100f, 120f, listOf(-25f, 0f, 35f)[it], if (flips) -1.5f else 1f) },
				flipX = flips),
			Deformer.Rotation("R2", "R2", "R1", null, 10f, pivots("B", listOf(-1f, 0f, 1f)) { Pivot(listOf(0f, 0f, 6f)[it], -40f, listOf(-50f, 0f, 40f)[it], 1f) },
				flipY = flips),
			Deformer.Rotation("R3", "R3", "R2", null, -5f, pivots("C", listOf(0f, 1f)) { Pivot(4f, -25f, listOf(0f, 200f)[it], listOf(1f, 0.6f)[it]) },
				flipX = flips),
		),
		meshes = listOf(
			Mesh("M0", "Still", null, geometry = quad(10f, 10f, 20f, 20f), offsets = null, page = 0, drawOrder = 100f),
			Mesh("M1", "One", "R1", geometry = quad(-10f, -40f, 20f, 40f), offsets = null, page = 0, drawOrder = 200f),
			Mesh("M2", "Two", "R2", geometry = quad(-6f, -30f, 12f, 30f), page = 0, drawOrder = 300f,
				offsets = KeyGrid(listOf(KeyAxis("B", Floats.values(-1f, 1f))), listOf(
					KeyCell(Ints.values(0), MeshOffsets(Floats.values(0f, 0f, 0f, 0f, -3f, 2f, 0f, 0f))),
					KeyCell(Ints.values(1), MeshOffsets(Floats.values(0f, 0f, 0f, 0f, 3f, 2f, 0f, 0f)))))),
			Mesh("M3", "Three", "R3", geometry = quad(-4f, -20f, 8f, 20f), offsets = null, page = 0, drawOrder = 400f),
		),
		textures = Textures(pages = listOf(TexturePage(4, 4, Bytes.of(byteArrayOf(7))))),
		clips = listOf(Clip("sway", "Sway", "Idle", "sway", 1f, 30f, true, curves = listOf(
			Curve("A", 0f, -30f, listOf(CurveSegment.Linear(0.5f, 30f), CurveSegment.Linear(1f, -30f))),
			Curve("C", 0f, 0f, listOf(CurveSegment.Linear(1f, 1f)))))),
		physics = Physics(listOf(
			PhysicsGroup("hair", "Hair", listOf(PhysicsInput("A", 100f, PhysicsSource.X, false)),
				listOf(PhysicsOutput("B", 1, 1f, 80f, PhysicsSource.ANGLE, false)),
				listOf(PhysicsSegment(0f, 1f, 1f, 1f), PhysicsSegment(30f, 0.9f, 0.8f, 1.5f)), PhysicsNormalization(-10f, 0f, 10f, -10f, 0f, 10f)),
			PhysicsGroup("skirt", "Skirt", listOf(PhysicsInput("A", 100f, PhysicsSource.X, false)),
				listOf(PhysicsOutput("Missing", 1, 1f, 100f, PhysicsSource.ANGLE, false)),
				listOf(PhysicsSegment(0f, 1f, 1f, 1f), PhysicsSegment(30f, 0.9f, 0.8f, 1.5f)), PhysicsNormalization(-10f, 0f, 10f, -10f, 0f, 10f)),
		)),
	)

	private fun export(ir: RigIR, vararg settings: Pair<String, String>): Pair<Map<String, ByteArray>, ExportReport> {
		val files = LinkedHashMap<String, ByteArray>()
		val report = Compiler.export(SpineTarget(RotationEvaluator), ir, ExportOptions("hero", settings = mapOf(*settings))) { path, bytes -> files[path] = bytes }
		return files to report
	}

	@Suppress("UNCHECKED_CAST")
	private fun json(files: Map<String, ByteArray>) = MiniJson(files.getValue("hero.json").decodeToString()).value() as Map<String, Any?>

	/** Each parameter's track at its normalized value, as a runtime drives the additive animations. */
	private fun tracks(ir: RigIR, values: Map<String, Float>) = values.map { (id, v) ->
		val p = ir.parameters.first { it.id == id }
		"param/$id" to ((v - p.min) / (p.max - p.min)).toDouble()
	}

	/** The largest distance between Spine's pose and the evaluator's, in canvas pixels. */
	private fun error(ir: RigIR, player: SpinePlayer, tracks: List<Pair<String, Double>>, values: Map<String, Float>): Double {
		val want = RotationEvaluator.open(ir).evaluate(values)
		val got = player.pose(tracks)
		var worst = 0.0
		for ((mesh, points) in want.positions) {
			val world = got.getValue(mesh)
			for (i in points.indices step 2) {
				worst = maxOf(worst, abs(world[i] + ir.canvas.width / 2 - points[i]), abs(ir.canvas.height - world[i + 1] - points[i + 1]))
			}
		}
		return worst
	}

	private fun poses(ir: RigIR, random: Random) = List(40) { n ->
		// Single parameters at keys and between them, then every parameter at once.
		if (n < 12) { val p = ir.parameters[n % 3]; mapOf(p.id to p.min + (p.max - p.min) * (n / 3) / 3f) }
		else ir.parameters.associate { it.id to it.min + random.nextFloat() * (it.max - it.min) }
	}

	@Suppress("UNCHECKED_CAST")
	@Test fun rotationChainsBecomeBonesThatCompose() {
		val ir = chain()
		val (files, report) = export(ir)
		val root = json(files)
		val bones = root["bones"] as List<Map<String, Any?>>
		assertEquals(listOf("root", "R1", "R2", "R3"), bones.map { it["name"] })
		assertEquals(listOf(null, "root", "R1", "R2"), bones.map { it["parent"] })
		assertEquals(listOf("root", "R1", "R2", "R3"), (root["slots"] as List<Map<String, Any?>>).map { it["bone"] })
		// Rotations need no deform keys: only M2's own keyforms are baked.
		val animations = root["animations"] as Map<String, Map<String, Any?>>
		assertNull(animations.getValue("param/A")["attachments"])
		assertEquals(setOf("M2"), ((animations.getValue("param/B")["attachments"] as Map<String, Any?>)["default"] as Map<String, Any?>).keys)
		assertEquals(setOf("R1"), (animations.getValue("param/A")["bones"] as Map<String, Any?>).keys)
		val player = SpinePlayer(root)
		val random = Random(3)
		for (values in poses(ir, random)) assertTrue(error(ir, player, tracks(ir, values), values) < 2e-3, "Pose $values")
		// Combined parameters compose through the bones, so nothing is reported.
		assertTrue(report.losses.none { it.feature == Feature.PARAMETER_GRID }, report.losses.toString())
	}

	@Test fun flipsAndNegativeScalesFollowTheEditorsRules() {
		val ir = chain(flips = true)
		val (files, report) = export(ir)
		val player = SpinePlayer(json(files))
		for (values in poses(ir, Random(5))) assertTrue(error(ir, player, tracks(ir, values), values) < 2e-3, "Pose $values")
		assertTrue(report.losses.none { it.feature == Feature.PARAMETER_GRID }, report.losses.toString())
	}

	@Test fun clipsKeyBonesPerFrame() {
		val ir = chain()
		val (files, _) = export(ir, "clip_fps" to "30")
		val player = SpinePlayer(json(files))
		val clip = ir.clips.single()
		for (t in listOf(0f, 0.2f, 0.5f, 0.9f)) {
			assertTrue(error(ir, player, listOf("clip/Sway" to t.toDouble()), ClipSampler.valuesAt(clip, t)) < 0.05, "Time $t")
		}
	}

	@Suppress("UNCHECKED_CAST")
	@Test fun pendulumsDrivingRotationsBecomePhysicsConstraints() {
		val (files, report) = export(chain())
		val physics = json(files)["physics"] as List<Map<String, Any?>>
		// B rotates R2: the hair pendulum's output becomes a constraint on R2 with the second segment's settings.
		assertEquals(1, physics.size)
		val hair = physics.single()
		assertEquals("hair/R2", hair["name"]); assertEquals("R2", hair["bone"]); assertEquals(1.0, hair["rotate"])
		assertEquals(150.0, hair["strength"]); assertEquals(0.9, (hair["damping"] as Number).toDouble(), 1e-6); assertEquals(0.8, (hair["mix"] as Number).toDouble(), 1e-6)
		val losses = report.losses.filter { it.feature == Feature.PHYSICS }.associateBy { it.objectId }
		assertEquals(Handling.APPROXIMATED, losses.getValue("hair").handling)
		assertTrue("not simulated" in losses.getValue("hair").note)
		assertEquals(Handling.DROPPED, losses.getValue("skirt").handling)
	}

	@Test fun bonesHangFromFramesWithoutShear() {
		val random = Random(11)
		repeat(200) {
			fun frame() = BoneFrame(random.nextFloat() * 200 - 100, random.nextFloat() * 200 - 100, random.nextFloat() * 720 - 360,
				(random.nextFloat() * 2 + 0.2f) * (if (random.nextBoolean()) -1 else 1), random.nextBoolean(), random.nextBoolean(), random.nextFloat() * 360 - 180)
			val parent = frame(); val child = frame()
			val composed = parent.world * child.relativeTo(parent).affine()
			val want = child.world
			for ((a, b) in listOf(composed.a to want.a, composed.b to want.b, composed.c to want.c, composed.d to want.d, composed.x to want.x, composed.y to want.y))
				assertEquals(b, a, 1e-3f * maxOf(1f, abs(b)))
		}
	}

	@Suppress("UNCHECKED_CAST")
	@Test fun binarySkeletonsHoldTheSameData() {
		val ir = chain()
		val (jsonFiles, _) = export(ir)
		val (binaryFiles, _) = export(ir, "binary" to "true")
		assertEquals(listOf("hero.skel", "hero.atlas", "hero_0.png"), binaryFiles.keys.toList())
		// Deterministic: the same rig writes the same bytes.
		assertContentEquals(binaryFiles.getValue("hero.skel"), export(ir, "binary" to "true").first.getValue("hero.skel"))
		val fromJson = json(jsonFiles)
		val fromBinary = MiniSkel(binaryFiles.getValue("hero.skel")).read()
		val skeleton = fromJson["skeleton"] as Map<String, Any?>
		assertEquals(skeleton["hash"], (fromBinary["skeleton"] as Map<String, Any?>)["hash"])
		assertEquals("4.2.0", (fromBinary["skeleton"] as Map<String, Any?>)["spine"])
		fun bones(root: Map<String, Any?>) = (root["bones"] as List<Map<String, Any?>>).map { b ->
			listOf(b["name"], b["parent"]) + listOf("x" to 0.0, "y" to 0.0, "rotation" to 0.0, "scaleX" to 1.0, "scaleY" to 1.0, "length" to 0.0)
				.map { (key, default) -> (b[key] as Number?)?.toFloat() ?: default.toFloat() }
		}
		assertEquals(bones(fromJson), bones(fromBinary))
		fun slots(root: Map<String, Any?>) = (root["slots"] as List<Map<String, Any?>>).map { s ->
			listOf(s["name"], s["bone"], s["attachment"], s["color"] ?: "ffffffff", s["blend"] ?: "normal")
		}
		assertEquals(slots(fromJson), slots(fromBinary))
		fun physics(root: Map<String, Any?>) = (root["physics"] as List<Map<String, Any?>>).map { p ->
			listOf(p["name"], p["bone"]) + listOf("rotate", "fps", "inertia", "strength", "damping", "mass", "mix").map { (p[it] as Number).toFloat() }
		}
		assertEquals(physics(fromJson), physics(fromBinary))
		val jsonPlayer = SpinePlayer(fromJson); val binaryPlayer = SpinePlayer(fromBinary)
		val animations = (fromJson["animations"] as Map<String, Any?>).keys
		assertEquals(animations, (fromBinary["animations"] as Map<String, Any?>).keys)
		for (name in animations) for (t in listOf(0.0, 0.3, 0.5, 0.77, 1.0)) {
			val a = jsonPlayer.pose(listOf(name to t)); val b = binaryPlayer.pose(listOf(name to t))
			assertEquals(a.keys, b.keys)
			// JSON numbers parse as doubles of their decimals, binary ones as the floats themselves.
			for ((slot, points) in a) points.indices.forEach { assertEquals(points[it], b.getValue(slot)[it], 1e-4, "$name at $t: $slot") }
		}
		val skins = { root: Map<String, Any?> -> (root["skins"] as List<Map<String, Any?>>).single()["attachments"] as Map<String, Map<String, Map<String, Any?>>> }
		for ((slot, entries) in skins(fromJson)) for ((name, attachment) in entries) {
			val other = skins(fromBinary).getValue(slot).getValue(name)
			for (key in listOf("uvs", "triangles", "vertices")) assertEquals(
				(attachment[key] as List<Number>?)?.map { it.toFloat() }, (other[key] as List<Number>?)?.map { it.toFloat() }, "$slot $key")
		}
	}

	/** Binary meshes store no triangle count; one with more triangles than its vertices allow gets padding. */
	@Test fun binaryMeshesFitTheirTriangles() {
		val doubled = MeshGeometry(Floats.values(0f, 0f, 10f, 0f, 0f, 10f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2, 0, 1, 2, 0, 2, 1, 1, 2, 0, 2, 0, 1))
		val ir = RigIR(canvas = Canvas(20f, 20f), parameters = emptyList(),
			meshes = listOf(Mesh("T", "T", null, geometry = doubled, offsets = null, page = 0), Mesh("Q", "Q", null, geometry = quad(0f, 0f, 5f, 5f), offsets = null, page = 0)),
			textures = Textures(pages = listOf(TexturePage(4, 4, Bytes.of(byteArrayOf(7))))))
		val (files, _) = export(ir, "binary" to "true")
		@Suppress("UNCHECKED_CAST")
		val skins = (MiniSkel(files.getValue("hero.skel")).read()["skins"] as List<Map<String, Any?>>).single()["attachments"] as Map<String, Map<String, Map<String, Any?>>>
		val triangle = skins.getValue("T").getValue("T")
		assertEquals(listOf(0, 1, 2, 0, 1, 2, 0, 2, 1, 1, 2, 0, 2, 0, 1).map { it.toDouble() }, triangle["triangles"])
		// Five triangles need four vertices: one unused copy of the first.
		assertEquals(8, (triangle["vertices"] as List<*>).size); assertEquals(1, triangle["hull"])
		assertEquals(4, skins.getValue("Q").getValue("Q")["hull"])
	}
}
