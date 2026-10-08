package io.github.psd2live.tools

import io.github.psd2live.core.ExportService
import io.github.psd2live.core.IrGeometryEvaluator
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.core.SkeletonAutoBuilder
import io.github.psd2live.core.sim.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.runtime.P2lrt
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test

/**
 * Reference poses for the Rust runtime (runtime/): random rigs that each exercise a few primitives (warps,
 * rotations, nesting, sparse grids, blend shapes, glue, opacity and draw-order channels, parts), plus the
 * samples (and the project PSD2LIVE_RUNTIME_PROJECT names), evaluated by the editor at random poses within each range.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*RuntimeConformanceTool'
 * Writes build/tools/runtime-conformance/<case>/{rig.p2lrt, rig.v1.p2lrt, rig.packed.p2lrt, poses.bin, expected.bin}; compare with
 * `cargo run --release --bin p2lrt-conformance -- build/tools/runtime-conformance` in runtime/.
 */
class RuntimeConformanceTool {
	@Test fun generate() {
		requireTools()
		val root = output("runtime-conformance").apply { deleteRecursively(); mkdirs() }
		val cases = LinkedHashMap<String, RigIR>()
		for (kind in Kind.entries) repeat(8) { seed -> cases["${kind.name.lowercase()}-$seed"] = RandomRig(kind, Random(kind.ordinal * 1000 + seed)).build() }
		for (sample in listOf("tml", "ds")) {
			val plain = PSD2LivePipeline().buildPreview(File("examples/$sample/psd-input/$sample.psd").toPath())
			val skeletal = PSD2LivePipeline().buildPreview(plain.analysis, plain.config.copy(
				rigEdits = plain.config.rigEdits.copy(skeleton = SkeletonAutoBuilder.build(plain.analysis, plain.rig))))
			cases["$sample-plain"] = RigIrCompiler.compile(plain)
			cases["$sample-skeleton"] = RigIrCompiler.compile(skeletal)
		}
		System.getenv("PSD2LIVE_RUNTIME_PROJECT")?.let(::File)?.takeIf { it.isFile }?.let {
			cases["project"] = RigIrCompiler.compile(kotlinx.coroutines.runBlocking { ExportService.load(it.toPath()) })
		}
		for ((name, ir) in cases) write(File(root, name).apply { mkdirs() }, ir, Random(name.hashCode()))
		// The skeleton samples baked as finely as sampling allows: a reference for how close advanced mode's arcs
		// come to the continuous skin between the default bake's keys (p2lrt-conformance --advanced).
		for (sample in listOf("tml", "ds")) {
			val plain = PSD2LivePipeline().buildPreview(File("examples/$sample/psd-input/$sample.psd").toPath())
			val spec = SkeletonAutoBuilder.build(plain.analysis, plain.rig)
			val fine = spec.copy(sampling = io.github.psd2live.core.SkeletonSampling(tolerancePx = 0.25f, minimumStepDegrees = 2.5f, maxMeshKeyforms = 1200))
			val skeletal = PSD2LivePipeline().buildPreview(plain.analysis, plain.config.copy(rigEdits = plain.config.rigEdits.copy(skeleton = fine)))
			File(root, "$sample-skeleton/rig.fine.p2lrt").writeBytes(P2lrt.write(RigIrCompiler.compile(skeletal)))
		}
		println("Wrote ${cases.size} cases to $root")
	}

	/**
	 * Physics traces: random pendulum groups and the samples' own, driven by smooth random inputs at uneven
	 * frame times through the editor's physics. Writes build/tools/runtime-physics/<case>/{rig.p2lrt, trace.bin}:
	 * u32 steps, u32 parameters, then per step f32 dt, the values before the step, the values after it.
	 */
	@Test fun physics() {
		requireTools()
		val root = output("runtime-physics").apply { deleteRecursively(); mkdirs() }
		val cases = LinkedHashMap<String, RigIR>()
		repeat(12) { seed -> cases["random-$seed"] = randomPhysics(Random(seed)) }
		for (sample in listOf("tml", "ds")) {
			val ir = RigIrCompiler.compile(PSD2LivePipeline().buildPreview(File("examples/$sample/psd-input/$sample.psd").toPath()))
			cases[sample] = ir.copy(physics = ir.physics.copy(fps = 30f))
			cases["$sample-unlimited"] = ir.copy(physics = ir.physics.copy(fps = null))
		}
		for ((name, ir) in cases) {
			val dir = File(root, name).apply { mkdirs() }
			val bare = ir.copy(deformers = emptyList(), meshes = emptyList(), parts = emptyList(), glues = emptyList(), rootChildren = emptyList(),
				renderRoot = RenderGroup(null, RigIR.DEFAULT_DRAW_ORDER, emptyList()), textures = Textures(), clips = emptyList())
			variants(dir, bare)
			trace(dir, bare, Random(name.hashCode()))
		}
		println("Wrote ${cases.size} physics cases to $root")
	}

	private fun randomPhysics(random: Random): RigIR {
		fun f(min: Float, max: Float) = min + random.nextFloat() * (max - min)
		val parameters = (0 until 6).map { i -> val range = listOf(1f, 10f, 30f).random(random); Parameter("P$i", "P$i", -range, range, if (i == 5) range * 0.2f else 0f) }
		val sources = listOf(PhysicsSource.X, PhysicsSource.ANGLE)
		val groups = (0 until random.nextInt(1, 4)).map { g ->
			val segments = (0 until random.nextInt(1, 4)).map { PhysicsSegment(f(3f, 15f), f(0.8f, 1f), f(0.6f, 1f), f(0.5f, 2f)) }
			val inputs = parameters.take(3).shuffled(random).take(random.nextInt(1, 3)).map { PhysicsInput(it.id, f(10f, 100f), sources.random(random), random.nextInt(4) == 0) }
			val outputs = parameters.drop(3).shuffled(random).take(random.nextInt(1, 3)).map {
				PhysicsOutput(it.id, random.nextInt(1, segments.size + 1), f(0.5f, 20f), f(50f, 100f), sources.random(random), random.nextInt(4) == 0)
			}
			PhysicsGroup("G$g", "G$g", inputs, outputs, segments, PhysicsNormalization(-10f, 0f, 10f, -10f, 0f, 10f))
		}.distinctBy { it.outputs.map(PhysicsOutput::parameter).toSet() }
		val fps = listOf(30f, 60f, null).random(random)
		return RigIR(Canvas(100f, 100f), parameters, physics = Physics(groups, fps))
	}

	/**
	 * Simulation traces: random cloth sheets (pinned along the top, random stiffness) driven through the editor's
	 * XPBD solver by goals and pins that sway, turn and jump, with and without a moving capsule collider. Writes
	 * build/tools/runtime-sim/<case>/trace.bin, read by `p2lrt-conformance --sim`: the scene's arrays, the rest
	 * positions, then per frame the inputs and the positions the solver reached.
	 */
	@Test fun simulation() {
		requireTools()
		val root = output("runtime-sim").apply { deleteRecursively(); mkdirs() }
		repeat(12) { seed -> simTrace(File(root, "sheet-$seed").apply { mkdirs() }, Random(seed + 7000), collide = seed % 2 == 1) }
		println("Wrote 12 simulation cases to $root")
	}

	/**
	 * A baked simulation end to end: tml's back hair (top tenth pinned) baked and exported with its live scene, and
	 * the editor's scene on the unbaked rig driven through head and body motion. Writes
	 * build/tools/runtime-sim-rig/back-hair/{rig.p2lrt, poses.bin, positions.bin}, read by `p2lrt-conformance --sim-rig`.
	 */
	@Test fun simulationRig() {
		requireTools()
		val dir = output("runtime-sim-rig/back-hair").apply { deleteRecursively(); mkdirs() }
		val initial = PSD2LivePipeline().buildPreview(File("examples/tml/psd-input/tml.psd").toPath())
		val puppet = initial.rig.puppet
		val layers = initial.analysis.layers.associateBy { it.source.id.raw }
		val back = puppet.drawables.first { d ->
			d.mesh != null && layers[initial.rig.layerIdByDrawableId[d.id.raw] ?: d.id.raw]?.semantic?.tag == io.github.psd2live.core.SemanticTag.BACK_HAIR
		}
		val world = org.umamo.render.eval.CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions.getValue(back.id)
		val ys = (1 until world.size step 2).map { world[it] }
		val top = ys.max(); val bottom = ys.min()
		val pin = org.umamo.runtime.model.VertexGroup("pin", back.id, org.umamo.runtime.model.VertexGroupKind.PIN,
			FloatArray(world.size / 2) { if (world[it * 2 + 1] > top - (top - bottom) * 0.1f) 1f else 0f })
		val grouped = initial.config.rigEdits.copy(authoringJournal = initial.config.rigEdits.authoringJournal + io.github.psd2live.core.VertexGroupJournal.encode(pin))
		val withSim = SimAuthoring.put(grouped, grouped.applyTo(initial.baseRig.puppet), RigSimEdit("back", "Back hair", SimKind.HAIR, listOf(back.id.raw),
			inputs = RigSimEdit.defaultInputs(puppet.parameters.mapTo(HashSet()) { it.id.raw }, SimKind.HAIR)))
		val baked = SimAuthoring.withBake(withSim, "back", SimAuthoring.bake(withSim, initial.baseRig.puppet, "back"))
		val preview = PSD2LivePipeline().buildPreview(initial.analysis, initial.config.copy(rigEdits = baked))
		val ir = RigIrCompiler.compile(preview, simulations = true)
		require(ir.advanced.simulations.isNotEmpty()) { "The simulation did not export" }
		File(dir, "rig.p2lrt").writeBytes(P2lrt.write(ir))

		// The editor's scene, as its preview plays it: on the rig without the bake, calibrated, at rest at the first pose.
		val unbaked = SimAuthoring.unbakedModel(baked, initial.baseRig.puppet, "back")
		val scene = SimScene.build(unbaked, baked.simEdits.single()).also { it.calibrate(unbaked) }
		val frames = 180
		val poses = (0 until frames).map { frame ->
			val t = frame / 60f
			mapOf("ParamAngleX" to 25f * kotlin.math.sin(t * 2.3f), "ParamAngleZ" to 15f * kotlin.math.sin(t * 1.4f),
				"ParamBodyAngleX" to 8f * kotlin.math.sin(t * 3.1f), "ParamBodyAngleZ" to (if (frame in 60..75) 10f else 0f))
				.filterKeys { id -> ir.parameters.any { it.id == id } }
		}
		val poseBuffer = ByteBuffer.allocate(8 + frames * ir.parameters.size * 4).order(ByteOrder.LITTLE_ENDIAN)
		poseBuffer.putInt(frames); poseBuffer.putInt(ir.parameters.size)
		for (pose in poses) for (p in ir.parameters) poseBuffer.putFloat(pose[p.id] ?: p.default)
		File(dir, "poses.bin").writeBytes(poseBuffer.array())
		val n = scene.state.count
		val positions = ByteBuffer.allocate(8 + frames * n * 8).order(ByteOrder.LITTLE_ENDIAN)
		positions.putInt(frames); positions.putInt(n)
		for ((frame, pose) in poses.withIndex()) {
			val values = pose.mapKeys { org.umamo.runtime.model.ParameterId(it.key) }
			if (frame == 0) scene.reset(unbaked, values) else scene.drive(unbaked, values, 1f / 60f)
			for (i in 0 until n) { positions.putFloat(scene.state.x[i]); positions.putFloat(scene.state.y[i]) }
		}
		File(dir, "positions.bin").writeBytes(positions.array())
		println("Wrote the back hair simulation, $n particles over $frames frames, to $dir")
	}

	private fun simTrace(dir: File, random: Random, collide: Boolean) {
		val sim = run {
			val columns = random.nextInt(2, 6); val rows = random.nextInt(3, 7)
			val n = columns * rows
			val rest = FloatArray(n * 2) { if (it % 2 == 0) (it / 2 % columns) * 20f + random.nextFloat() * 4f else -(it / 2 / columns) * 22f + random.nextFloat() * 4f }
			val state = SimState(n)
			for (i in 0 until n) { state.invMass[i] = 0.5f + random.nextFloat(); state.damping[i] = random.nextFloat() * 2f; state.windFactor[i] = random.nextFloat()
				state.goalOffsetX[i] = random.nextFloat() - 0.5f; state.goalOffsetY[i] = random.nextFloat() - 0.5f }
			val pin = FloatArray(n) { if (it < columns) (if (random.nextBoolean()) 1f else 0.6f) else 0f }
			val goal = FloatArray(n) { if (random.nextInt(3) == 0) Float.POSITIVE_INFINITY else 0.002f + random.nextFloat() * 0.05f }
			val ea = ArrayList<Int>(); val eb = ArrayList<Int>()
			val ta = ArrayList<Int>(); val tb = ArrayList<Int>(); val tc = ArrayList<Int>()
			for (r in 0 until rows) for (c in 0 until columns) {
				val i = r * columns + c
				if (c + 1 < columns) { ea += i; eb += i + 1 }
				if (r + 1 < rows) { ea += i; eb += i + columns }
				if (c + 1 < columns && r + 1 < rows) { ta += i; tb += i + columns; tc += i + 1; ta += i + 1; tb += i + columns; tc += i + columns + 1 }
			}
			fun len(a: Int, b: Int) = kotlin.math.hypot(rest[a * 2] - rest[b * 2], rest[a * 2 + 1] - rest[b * 2 + 1])
			val stretch = DistanceConstraints(ea.toIntArray(), eb.toIntArray(), FloatArray(ea.size) { len(ea[it], eb[it]) },
				FloatArray(ea.size) { 1e-9f * 10f.pow(random.nextFloat() * 4f) }, FloatArray(ea.size) { 1e-4f + random.nextFloat() * 1e-3f })
			val triangles = TriangleConstraints(ta.toIntArray(), tb.toIntArray(), tc.toIntArray(),
				FloatArray(ta.size) { if (random.nextBoolean()) Float.POSITIVE_INFINITY else random.nextFloat() * 0.5f })
			val bends = BendConstraints(IntArray(ta.size / 2) { it * 2 }, IntArray(ta.size / 2) { it * 2 + 1 }, FloatArray(ta.size / 2) { random.nextFloat() * 1e-3f })
			val welds = if (n > 4) WeldConstraints(intArrayOf(n - 1), intArrayOf(n - 2), floatArrayOf(0.5f), floatArrayOf(0.5f), floatArrayOf(1e-4f)) else WeldConstraints.Empty
			val longRange = LongRangeConstraints(IntArray(n - columns) { columns + it }, IntArray(n - columns) { (columns + it) % columns },
				FloatArray(n - columns) { len(columns + it, (columns + it) % columns) * 1.1f })
			val settings = SimSettings(gravityY = -980f, windX = random.nextFloat() * 200f, substeps = random.nextInt(4, 17), pinCompliance = 1e-4f)
			state.reset(rest)
			XpbdSolver(state, stretch, triangles, bends, welds, longRange, pin, goal, settings) to rest
		}
		val (solver, rest) = sim
		val s = solver.state
		val n = s.count
		val out = java.io.DataOutputStream(File(dir, "trace.bin").outputStream().buffered())
		fun le(v: Int) = out.writeInt(Integer.reverseBytes(v))
		fun f(v: Float) = le(java.lang.Float.floatToRawIntBits(v))
		fun floats(v: FloatArray) { le(v.size); v.forEach(::f) }
		fun ints(v: IntArray) { le(v.size); v.forEach(::le) }
		with(solver) {
			floats(s.invMass); floats(s.damping); floats(s.windFactor); floats(pinWeight); floats(goalCompliance); floats(s.goalOffsetX); floats(s.goalOffsetY)
			ints(stretch.a); ints(stretch.b); floats(stretch.rest); floats(stretch.compliance); floats(stretch.compressionCompliance)
			ints(triangles.a); ints(triangles.b); ints(triangles.c); floats(triangles.areaCompliance)
			ints(bends.t1); ints(bends.t2); floats(bends.compliance)
			ints(welds.a); ints(welds.b); floats(welds.weightA); floats(welds.weightB); floats(welds.compliance)
			ints(longRange.particle); ints(longRange.root); floats(longRange.maxDistance)
			le(settings.substeps); f(settings.gravityX); f(settings.gravityY); f(settings.windX); f(settings.windY); f(settings.pinCompliance)
		}
		floats(rest)
		val frames = 240
		le(frames)
		var time = 0f
		for (frame in 0 until frames) {
			val dt = 1f / 60f
			time += dt
			val angle = 0.4f * kotlin.math.sin(time * 2.1f)
			val dx = 30f * kotlin.math.sin(time * 3.3f) + if (frame in 100..110) 40f else 0f
			val dy = 15f * kotlin.math.sin(time * 1.7f)
			for (i in 0 until n) {
				val x = rest[i * 2]; val y = rest[i * 2 + 1]
				val rx = x * kotlin.math.cos(angle) - y * kotlin.math.sin(angle) + dx
				val ry = x * kotlin.math.sin(angle) + y * kotlin.math.cos(angle) + dy
				s.goalX[i] = rx; s.goalY[i] = ry; s.anchorX[i] = rx; s.anchorY[i] = ry
			}
			s.frameAngle = angle
			s.colliders = if (!collide) emptyList() else listOf(PlacedCollider(
				10f + 20f * kotlin.math.sin(time * 2f), -60f, 30f, -90f + 10f * kotlin.math.cos(time), 18f, 12f, 0.3f))
			f(dt); floats(s.goalX); floats(s.goalY); floats(s.anchorX); floats(s.anchorY); f(s.frameAngle)
			le(s.colliders.size); s.colliders.forEach { f(it.ax); f(it.ay); f(it.bx); f(it.by); f(it.radiusA); f(it.radiusB); f(it.friction) }
			solver.step(dt)
			floats(s.x); floats(s.y)
		}
		out.close()
	}

	private fun trace(dir: File, ir: RigIR, random: Random) {
		val settings = ir.physics.groups.map { g ->
			fun type(s: PhysicsSource) = if (s == PhysicsSource.ANGLE) io.github.psd2live.core.PhysicsSourceType.ANGLE else io.github.psd2live.core.PhysicsSourceType.X
			io.github.psd2live.core.RigPhysicsEdit(g.id, g.name,
				g.inputs.map { io.github.psd2live.core.PhysicsInput(it.parameter, it.weight, type(it.source), it.reflect) },
				g.outputs.map { io.github.psd2live.core.PhysicsOutput(it.parameter, it.vertex, it.scale, it.weight, type(it.source), it.reflect) },
				g.segments.map { io.github.psd2live.core.PhysicsSegment(it.length, it.mobility, it.delay, it.acceleration) },
				g.normalization.let { io.github.psd2live.core.PhysicsNormalization(it.positionMin, it.positionDefault, it.positionMax, it.angleMin, it.angleDefault, it.angleMax) })
		}
		val ranges = ir.parameters.associate { it.id to io.github.psd2live.core.PhysicsEngine.Range(it.min, it.max, it.default) }
		val engine = io.github.psd2live.core.PhysicsEngine(settings, ranges, ir.physics.fps)
		val inputs = ir.physics.groups.flatMap { g -> g.inputs.map { it.parameter } }.toSet()
		// Each input follows a sum of two sines with random phases; some frames are long or repeated.
		val waves = inputs.associateWith { FloatArray(4) { random.nextFloat() * 6f } }
		val steps = 400
		val buffer = ByteBuffer.allocate(8 + steps * (4 + ir.parameters.size * 8)).order(ByteOrder.LITTLE_ENDIAN)
		buffer.putInt(steps); buffer.putInt(ir.parameters.size)
		var time = 0f
		for (step in 0 until steps) {
			val dt = when (random.nextInt(20)) { 0 -> 0.1f; 1 -> 0.004f; else -> 1f / 60f + (random.nextFloat() - 0.5f) * 0.006f }
			time += dt
			val before = ir.parameters.associate { p ->
				val w = waves[p.id]
				p.id to if (w == null) p.default else (p.default + (p.max - p.min) * 0.45f * (kotlin.math.sin(time * (1f + w[0]) + w[1]) * 0.7f + kotlin.math.sin(time * (3f + w[2]) + w[3]) * 0.3f)).coerceIn(p.min, p.max)
			}
			val after = before + engine.step(before, dt)
			buffer.putFloat(dt)
			ir.parameters.forEach { buffer.putFloat(before.getValue(it.id)) }
			ir.parameters.forEach { buffer.putFloat(after.getValue(it.id)) }
		}
		File(dir, "trace.bin").writeBytes(buffer.array())
	}

	/** The rig as version 2 (rig.p2lrt), version 1 and version 2 deflated and zstd-packed; the runtime must read them alike. */
	private fun variants(dir: File, ir: RigIR) {
		File(dir, "rig.p2lrt").writeBytes(P2lrt.write(ir))
		File(dir, "rig.v1.p2lrt").writeBytes(P2lrt.write(ir, P2lrt.Options(version = 1)))
		File(dir, "rig.packed.p2lrt").writeBytes(P2lrt.write(ir, P2lrt.Options(compression = P2lrt.Compression.DEFLATE)))
		File(dir, "rig.zstd.p2lrt").writeBytes(P2lrt.write(ir, P2lrt.Options(compression = P2lrt.Compression.ZSTD)))
	}

	private fun write(dir: File, ir: RigIR, random: Random) {
		variants(dir, ir)
		val poses = ArrayList<FloatArray>()
		poses += FloatArray(ir.parameters.size) { ir.parameters[it].default }
		repeat(40) {
			poses += FloatArray(ir.parameters.size) { i ->
				val p = ir.parameters[i]; val range = p.max - p.min
				// Inside the range, as the editor clamps values before evaluating; some at the default or an end.
				when (random.nextInt(8)) { 0 -> p.default; 1 -> p.min; 2 -> p.max; else -> p.min + random.nextFloat() * range }
			}
		}
		val session = IrGeometryEvaluator.open(ir)
		val expected = DataOutputStream(File(dir, "expected.bin").outputStream().buffered())
		val header = ByteBuffer.allocate(8 + poses.size * ir.parameters.size * 4).order(ByteOrder.LITTLE_ENDIAN)
		header.putInt(poses.size); header.putInt(ir.parameters.size)
		for (pose in poses) {
			pose.forEach(header::putFloat)
			val geometry = session.evaluate(ir.parameters.indices.associate { ir.parameters[it].id to pose[it] })
			for (mesh in ir.meshes) {
				val points = geometry.positions[mesh.id]
				val buffer = ByteBuffer.allocate(12 + (points?.size ?: 0) * 4).order(ByteOrder.LITTLE_ENDIAN)
				buffer.putFloat(geometry.opacity[mesh.id] ?: Float.NaN); buffer.putFloat(geometry.drawOrder[mesh.id] ?: Float.NaN)
				buffer.putInt(points?.size ?: -1); points?.forEach(buffer::putFloat)
				expected.write(buffer.array())
			}
		}
		expected.close(); session.close()
		File(dir, "poses.bin").writeBytes(header.array())
	}

	private enum class Kind { WARP, ROTATION, ROTATION_IN_WARP, WARP_IN_ROTATION, MIXED, SPARSE, BLEND, GLUE, CHANNELS, PARTS }

	private class RandomRig(private val kind: Kind, private val random: Random) {
		private val parameters = listOf(
			Parameter("A", "A", -1f, 1f, 0f), Parameter("B", "B", 0f, 1f, 0.25f), Parameter("C", "C", -30f, 30f, 0f, repeat = kind == Kind.MIXED),
			Parameter("S", "S", -1f, 1f, 0f, blend = true), Parameter("T", "T", 0f, 1f, 0f, blend = true),
		)
		private val normal = parameters.filter { !it.blend }
		private val deformers = ArrayList<Deformer>()
		private val meshes = ArrayList<Mesh>()
		private val glues = ArrayList<Glue>()

		private fun f(min: Float, max: Float) = min + random.nextFloat() * (max - min)

		/** Coordinates of a point in [parent]'s space: canvas px, warp 0..1 or rotation-local px. */
		private fun point(parent: Deformer?): Pair<Float, Float> = when (parent) {
			null -> f(100f, 400f) to f(100f, 400f)
			is Deformer.Warp -> f(-0.1f, 1.1f) to f(-0.1f, 1.1f)
			is Deformer.Rotation -> f(-80f, 80f) to f(-80f, 80f)
		}
		private fun scale(parent: Deformer?) = if (parent is Deformer.Warp) 0.08f else 25f

		private fun axes(): List<KeyAxis> {
			val count = random.nextInt(if (kind == Kind.SPARSE) 2 else 0, 3).coerceAtMost(normal.size)
			return normal.shuffled(random).take(count).map { p ->
				val keys = if (random.nextBoolean()) listOf(p.min, p.max) else listOf(p.min, (p.min + p.max) / 2 + f(-0.2f, 0.2f) * (p.max - p.min), p.max)
				KeyAxis(p.id, Floats.values(*keys.toFloatArray()))
			}
		}

		private fun <F> grid(form: () -> F): KeyGrid<F> {
			val axes = axes()
			val coordinates = axes.fold(listOf(emptyList<Int>())) { acc, axis -> acc.flatMap { c -> (0 until axis.keys.size).map { c + it } } }
			val kept = if (kind == Kind.SPARSE && coordinates.size > 2) coordinates.filter { it.all { i -> i == 0 } || random.nextInt(3) > 0 } else coordinates
			return KeyGrid(axes, kept.map { KeyCell(Ints.values(*it.toIntArray()), form()) })
		}

		private fun channels(vararg allowed: Channel): Channels {
			if (kind != Kind.CHANNELS && kind != Kind.PARTS && kind != Kind.MIXED) return emptyMap()
			return allowed.filter { random.nextBoolean() }.associateWith { channel ->
				grid {
					when (channel) {
						Channel.DRAW_ORDER -> ChannelValue.Scalar(f(300f, 700f).toInt().toFloat())
						Channel.OPACITY, Channel.GLUE_INTENSITY -> ChannelValue.Scalar(f(0f, 1f))
						Channel.FLIP_X, Channel.FLIP_Y -> ChannelValue.Flag(random.nextBoolean())
						else -> ChannelValue.Color(Rgb(f(0f, 1f), f(0f, 1f), f(0f, 1f)))
					} as ChannelValue
				}
			}
		}

		private fun limits(): List<BlendLimit> = if (random.nextBoolean()) emptyList() else
			listOf(BlendLimit(normal.random(random).id, listOf(BlendLimitPoint(-1f, f(0f, 1f)), BlendLimitPoint(0f, 1f), BlendLimitPoint(1f, f(0f, 1f)))))

		private fun <S> blends(shape: () -> S): List<BlendBinding<S>> {
			if (kind != Kind.BLEND && kind != Kind.MIXED) return emptyList()
			return parameters.filter { it.blend && random.nextBoolean() }.map { p ->
				val keys = if (p.min < 0f) listOf(-1f, 0f, 1f) else listOf(0f, 1f)
				val neutral = keys.indexOf(0f)
				BlendBinding(p.id, Floats.values(*keys.toFloatArray()), neutral, keys.indices.map { if (it == neutral || random.nextInt(4) == 0) null else shape() }, limits())
			}
		}

		private fun warp(parent: Deformer?): Deformer.Warp {
			val columns = random.nextInt(1, 4); val rows = random.nextInt(1, 4)
			val (x0, y0) = point(parent); val size = scale(parent) * f(4f, 10f)
			val base = FloatArray((columns + 1) * (rows + 1) * 2) { i ->
				val p = i / 2; val c = p % (columns + 1); val r = p / (columns + 1)
				if (i % 2 == 0) x0 + size * c / columns + f(-0.1f, 0.1f) * size else y0 + size * r / rows + f(-0.1f, 0.1f) * size
			}
			val jitter = { amount: Float -> Floats.wrap(FloatArray(base.size) { base[it] + f(-amount, amount) * size }) }
			return Deformer.Warp("W${deformers.size}", "W", parent?.id, null, rows, columns, random.nextBoolean(),
				grid { LatticePoints(jitter(0.15f)) }, channels(Channel.OPACITY, Channel.MULTIPLY_COLOR),
				opacity = if (random.nextInt(4) == 0) f(0.3f, 1f) else 1f,
				shapes = blends { LatticeShape(Floats.wrap(FloatArray(base.size) { f(-0.1f, 0.1f) * size }), f(-0.3f, 0.3f), Rgb(0f, 0f, 0f), Rgb(0f, 0f, 0f)) })
		}

		private fun rotation(parent: Deformer?): Deformer.Rotation {
			val (x, y) = point(parent)
			val angleRange = if (random.nextBoolean()) 40f else 180f
			return Deformer.Rotation("R${deformers.size}", "R", parent?.id, null, listOf(0f, 0f, 90f, -30f, 45f).random(random),
				grid { Pivot(x + f(-0.2f, 0.2f) * scale(parent), y + f(-0.2f, 0.2f) * scale(parent), f(-angleRange, angleRange), f(0.6f, 1.4f)) },
				channels(Channel.OPACITY, Channel.FLIP_X, Channel.FLIP_Y),
				opacity = if (random.nextInt(4) == 0) f(0.3f, 1f) else 1f,
				flipX = kind == Kind.MIXED && random.nextInt(4) == 0, flipY = kind == Kind.MIXED && random.nextInt(4) == 0,
				shapes = blends { PivotShape(f(-5f, 5f), f(-5f, 5f), f(-20f, 20f), f(-0.2f, 0.2f), false, false, f(-0.3f, 0.3f), Rgb(0f, 0f, 0f), Rgb(0f, 0f, 0f)) })
		}

		private fun deformerChain(): Deformer? {
			val pattern = when (kind) {
				Kind.WARP -> List(random.nextInt(1, 4)) { 'w' }
				Kind.ROTATION -> List(random.nextInt(1, 4)) { 'r' }
				Kind.ROTATION_IN_WARP -> List(random.nextInt(1, 3)) { 'w' } + 'r' + List(random.nextInt(0, 2)) { 'r' }
				Kind.WARP_IN_ROTATION -> listOf('r') + List(random.nextInt(1, 3)) { 'w' }
				Kind.MIXED, Kind.BLEND -> List(random.nextInt(0, 5)) { if (random.nextBoolean()) 'w' else 'r' }
				else -> List(random.nextInt(0, 3)) { if (random.nextBoolean()) 'w' else 'r' }
			}
			// Branch from an existing deformer at times so siblings share parents.
			var parent: Deformer? = if (deformers.isNotEmpty() && random.nextBoolean()) deformers.random(random) else null
			for (c in pattern) { val d = if (c == 'w') warp(parent) else rotation(parent); deformers += d; parent = d }
			return parent
		}

		private fun mesh(parent: Deformer?): Mesh {
			val (x0, y0) = point(parent); val size = scale(parent) * f(1f, 4f)
			val positions = FloatArray(12) { i -> val p = i / 2; if (i % 2 == 0) x0 + size * (p % 3) / 2f + f(-0.1f, 0.1f) * size else y0 + size * (p / 3) + f(-0.1f, 0.1f) * size }
			val geometry = MeshGeometry(Floats.wrap(positions), Floats.wrap(FloatArray(12) { random.nextFloat() }), Ints.values(0, 1, 3, 1, 4, 3, 1, 2, 4, 2, 5, 4))
			return Mesh("M${meshes.size}", "M", parent?.id, geometry = geometry,
				offsets = grid { MeshOffsets(Floats.wrap(FloatArray(12) { f(-0.2f, 0.2f) * size })) },
				channels = channels(Channel.OPACITY, Channel.DRAW_ORDER, Channel.SCREEN_COLOR),
				drawOrder = f(300f, 700f).toInt().toFloat(), opacity = if (random.nextInt(4) == 0) f(0.3f, 1f) else 1f,
				shapes = blends { MeshShape(Floats.wrap(FloatArray(12) { f(-0.2f, 0.2f) * size }), f(-50f, 50f), f(-0.3f, 0.3f), Rgb(0f, 0f, 0f), Rgb(0f, 0f, 0f)) })
		}

		fun build(): RigIR {
			repeat(random.nextInt(2, 5)) { meshes += mesh(deformerChain()) }
			if (kind == Kind.GLUE || kind == Kind.MIXED) repeat(random.nextInt(1, 3)) {
				val a = meshes.random(random); val b = (meshes - a).random(random)
				glues += Glue("G${glues.size}", a.id, b.id, List(random.nextInt(1, 4)) { GluePair(random.nextInt(6), random.nextInt(6), f(0f, 1f), f(0f, 1f)) }
					.distinctBy { it.a to it.b }, channels(Channel.GLUE_INTENSITY), intensity = f(0.2f, 1f))
			}
			val parts = ArrayList<Part>()
			val rootChildren = ArrayList<ChildRef>()
			if (kind == Kind.PARTS || kind == Kind.CHANNELS) {
				// Two levels of parts, opacity channels and static opacity on some.
				val inner = Part("P1", "Inner", meshes.drop(1).map { ChildRef.MeshRef(it.id) }, channels = channels(Channel.OPACITY, Channel.DRAW_ORDER))
				val outer = Part("P0", "Outer", listOf(ChildRef.MeshRef(meshes[0].id), ChildRef.PartRef(inner.id)), channels = channels(Channel.OPACITY))
				parts += outer; parts += inner; rootChildren += ChildRef.PartRef(outer.id)
			} else meshes.forEach { rootChildren += ChildRef.MeshRef(it.id) }
			return RigIR(Canvas(512f, 512f), parameters, parts = parts, rootChildren = rootChildren, deformers = deformers, meshes = meshes, glues = glues,
				renderRoot = RenderGroup(null, RigIR.DEFAULT_DRAW_ORDER, meshes.map { RenderMesh(it.id) }))
		}
	}
}
