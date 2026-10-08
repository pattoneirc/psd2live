package io.github.psd2live.tools

import io.github.psd2live.core.ExportService
import io.github.psd2live.core.IrGeometryEvaluator
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.core.SkeletonAutoBuilder
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.runtime.P2lrt
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

	/** The rig as version 2 (rig.p2lrt), version 1 and compressed version 2; the runtime must read all three alike. */
	private fun variants(dir: File, ir: RigIR) {
		File(dir, "rig.p2lrt").writeBytes(P2lrt.write(ir))
		File(dir, "rig.v1.p2lrt").writeBytes(P2lrt.write(ir, P2lrt.Options(version = 1)))
		File(dir, "rig.packed.p2lrt").writeBytes(P2lrt.write(ir, P2lrt.Options(compress = true)))
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
