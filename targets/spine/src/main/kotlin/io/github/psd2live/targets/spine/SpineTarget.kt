package io.github.psd2live.targets.spine

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.format.model.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A Spine 4.2 skeleton: JSON or binary `.skel`, a libGDX-style atlas and its pages.
 *
 * Spine has bones but no parameters. Rotation deformers become bones whose parents mirror the deformer
 * tree (a rotation under a warp hangs from the nearest rotation above it, or the root); every visible mesh
 * is an unweighted mesh attachment in a slot on the bone of its nearest rotation, and what the bone does not
 * carry (warps, mesh keyforms, glue) is baked as deform keys in that bone's space. Then
 * - each parameter becomes one animation, `param/<id>`, one second long, whose time is the parameter's
 *   normalized value (minimum at 0, maximum at 1), keying bone rotation, translation and scale relative to the
 *   setup pose and deform offsets at the parameter's sampled keys. A runtime plays each on its own track with
 *   additive mixing and sets the track time from the parameter, so rotations along a chain compose;
 * - each motion clip becomes one animation sampled at `clip_fps`.
 * What adding up parameters misses is measured with Spine's bone math and reported.
 *
 * Bone and deform keys interpolation can rebuild (deforms within `key_tolerance` pixels, default 0.25) are
 * dropped.
 *
 * Masks become clipping attachments in a slot just before the masked one, ending at it: the outline of a
 * single mask mesh, or the convex hull of several masks' outlines, deformed with them. Spine clips by the
 * polygon rather than the texture's alpha; inverted masks are dropped.
 *
 * Pendulum physics groups whose angle outputs key bone rotations become physics constraints on those bones,
 * with the pendulum's settings mapped approximately; other groups are dropped.
 *
 * Settings: `binary` (default false: JSON), `clip_fps` (default 15), `clips` (default true),
 * `key_tolerance` (default 0.25), `sample_pairs` (default 48).
 */
/**
 * The atlas region covering texture page [index], named like the page's image without its extension: runtimes find
 * it through the atlas, and Spine Editor, which imports data without the atlas, finds `<images>/<region>.png`.
 */
internal fun regionName(options: ExportOptions, index: Int): String = "${options.baseName}_$index"

public class SpineTarget(private val evaluator: GeometryEvaluator) : ExportTarget {
	override val id: String = "spine"
	override val family: TargetFamily = TargetFamily.RIG
	override val description: String = "Spine 4.2 skeleton (JSON or binary + atlas)"
	override val settings: List<TargetSetting> = listOf(
		TargetSetting.Flag("binary", false), TargetSetting.CLIPS, TargetSetting.Number("clip_fps", 15.0, 1.0, 60.0, 1.0, 0),
		TargetSetting.KEY_TOLERANCE, TargetSetting.SAMPLE_PAIRS,
	)
	override val capabilities: CapabilityProfile = CapabilityProfile(
		bones = true, skinning = true, warpLattice = false, parameterGrid = 0, blendShapes = false, timeline = true,
		physics = PhysicsSupport.CONSTRAINT, blendModes = setOf(ColorBlend.NORMAL, ColorBlend.ADD, ColorBlend.MULTIPLY, ColorBlend.SCREEN),
		masks = MaskSupport.POLYGON, keyedDrawOrder = true, glue = false,
	)

	override fun plan(ir: RigIR, options: ExportOptions): LoweredExport {
		require(ir.textures.pages.all { it.png.size > 0 }) { "Texture pages have no pixels" }
		val clipFps = options.float("clip_fps", 15f)
		require(clipFps in 1f..60f) { "clip_fps must be within 1..60" }
		val binary = options.flag("binary", false)
		val losses = ArrayList(CapabilityScan.scan(ir, capabilities, options, defaults = mapOf(
			Feature.PARAMETERS to Handling.BAKED, Feature.WARP_LATTICE to Handling.BAKED, Feature.BLEND_SHAPES to Handling.BAKED, Feature.GLUE to Handling.BAKED)))
		val inverted = ir.meshes.filter { it.invertMask && it.maskedBy.isNotEmpty() }.map { it.id }.toSet()
		losses.replaceAll {
			when {
				it.feature != Feature.MASK -> it
				it.objectId in inverted -> it.copy(handling = Handling.DROPPED, note = "Spine clipping cannot invert a mask")
				else -> it.copy(handling = Handling.APPROXIMATED, note = "The mask clips by its outline polygon, not its texture alpha")
			}
		}
		val meshes = ir.meshes.filter { it.visible && it.geometry != null }
		val skeleton = evaluator.open(ir).use { session -> SpineBuilder(ir, options, meshes, session, clipFps, losses).build() }
		val skeletonFile = if (binary) "${options.baseName}.skel" else "${options.baseName}.json"
		val skeletonBytes = if (binary) SkeletonBinaryWriter.write(skeleton) else SkeletonJsonWriter.write(skeleton).encodeToByteArray()
		val pageNames = ir.textures.pages.indices.map { "${regionName(options, it)}.png" }
		val atlas = buildString {
			ir.textures.pages.forEachIndexed { index, page ->
				if (index > 0) append('\n')
				append(pageNames[index]).append('\n')
				append("\tsize: ${page.width}, ${page.height}\n\tfilter: Linear, Linear\n")
				append(regionName(options, index)).append("\n\tbounds: 0, 0, ${page.width}, ${page.height}\n")
			}
		}
		return object : LoweredExport {
			override val losses: List<LossEntry> = losses
			override fun write(sink: OutputSink) {
				sink.write(skeletonFile, skeletonBytes)
				sink.write("${options.baseName}.atlas", atlas.encodeToByteArray())
				ir.textures.pages.forEachIndexed { index, page -> sink.write(pageNames[index], page.png.shared()) }
			}
		}
	}
}

/** Lowers one rig to a [Skeleton], appending what it approximates or drops to [losses]. */
private class SpineBuilder(
	private val ir: RigIR, private val options: ExportOptions, meshes: List<Mesh>, private val session: GeometrySession,
	private val clipFps: Float, private val losses: MutableList<LossEntry>,
) {
	private val bake = ParameterBake(ir, session, linkedPairs = false)
	private val rest = bake.rest
	private val tolerance = options.float("key_tolerance", 0.25f)
	/** Bone keys are dropped within the deform tolerance at the canvas diagonal: radians and scale factors. */
	private val boneTolerance = tolerance / maxOf(1f, kotlin.math.hypot(ir.canvas.width, ir.canvas.height))
	// Skeleton space: origin at the canvas' bottom center, y up, in pixels.
	private val cx = ir.canvas.width / 2f
	private val bottom = ir.canvas.height
	private fun spine(points: FloatArray) = FloatArray(points.size) { if (it % 2 == 0) points[it] - cx else bottom - points[it] }

	private val ordered = meshes.withIndex().sortedWith(compareBy({ rest.drawOrder[it.value.id] ?: it.value.drawOrder }, { it.index })).map { it.value }
	private val deformers = ir.deformers.associateBy { it.id }
	private val frames = DeformerFrames(ir)
	private val restFrames = frames.at(emptyMap())
	private val axisFrames = bake.axes.associateWith { axis -> axis.xKeys.indices.map { frames.at(axis.pose(it, 0)) } }
	private val clipPoses: List<Pair<String, List<Pair<Float, Map<String, Float>>>>> = if (!options.flag("clips", true)) emptyList() else {
		val used = HashSet<String>()
		ir.clips.map { clip ->
			var name = "clip/${clip.name}"; var n = 2
			while (!used.add(name)) name = "clip/${clip.name} ${n++}"
			val times = ClipSampler.frameTimes(clip, clipFps).let { if (clip.loop) it + clip.duration else it }
			name to times.map { t -> t to ClipSampler.valuesAt(clip, if (clip.loop && t >= clip.duration) 0f else t) }
		}
	}
	private val clipFrames = clipPoses.map { (_, poses) -> poses.map { (_, values) -> frames.at(values) } }

	/** Rotations that stay a usable frame (nonzero, finite scale) at every sampled pose become bones. */
	private val boneDeformers: List<Deformer.Rotation> = frames.ordered.filterIsInstance<Deformer.Rotation>().filter { r ->
		(sequenceOf(restFrames) + axisFrames.values.asSequence().flatten() + clipFrames.asSequence().flatten()).all { f ->
			val frame = f[r.id] as Frame.Rotation
			abs(frame.scale) > 1e-4f && frame.x.isFinite() && frame.y.isFinite() && frame.angle.isFinite() && frame.scale.isFinite()
		}.also { valid ->
			if (!valid) losses += LossEntry(r.id, Feature.BONES, Handling.BAKED, note = "The rotation's scale reaches zero; it is baked into the bone above")
		}
	}
	private val boneIds = boneDeformers.map { it.id }.toSet()

	/** The nearest rotation at or above [id] that is a bone, or null for the root. */
	private fun boneDeformerOf(id: String?): String? {
		var current = id
		val seen = HashSet<String>()
		while (current != null && seen.add(current)) {
			if (current in boneIds) return current
			current = deformers[current]?.parent
		}
		return null
	}

	private val boneNames: List<String> = buildList {
		add("root")
		val taken = hashSetOf("root")
		for (r in boneDeformers) { var name = r.id; var n = 2; while (!taken.add(name)) name = "${r.id} ${n++}"; add(name) }
	}
	/** Bone index per bone deformer id; 0 is the root. */
	private val boneIndex = boneDeformers.withIndex().associate { it.value.id to it.index + 1 }
	private val parentBone = boneDeformers.associate { it.id to boneDeformerOf(it.parent) }
	/** Bones whose frame is not a plain rotation of their parent bone's: their sampled angle is unwrapped. */
	private val wrapped = boneDeformers.filter { it.parent != parentBone[it.id] }.map { it.id }.toSet()
	private val meshBone = ordered.associate { it.id to boneDeformerOf(it.parent) }

	init {
		for (r in boneDeformers) {
			var current = r.parent
			while (current != null && current != parentBone[r.id]) {
				if (deformers[current] is Deformer.Warp) {
					losses += LossEntry(r.id, Feature.BONES, Handling.APPROXIMATED,
						note = "Rotation under a warp: its bone is keyed from the sampled frame; combined with other parameters its pivot does not follow the warp")
					break
				}
				current = deformers[current]?.parent
			}
		}
	}

	/** Each bone's turn (pointing it at the content it carries) and length, from the rest pose. */
	private val turns = HashMap<String, Float>()
	private val lengths = HashMap<String, Float>()

	init {
		for (r in boneDeformers) {
			val frame = boneFrame(r.id, restFrames, turn = 0f)
			val inverse = frame.world.inverse()
			val points = ordered.filter { meshBone[it.id] == r.id }.map { inverse.map(spine(rest.positions.getValue(it.id))) }
			var sx = 0.0; var sy = 0.0; var count = 0
			for (p in points) for (i in p.indices step 2) { sx += p[i]; sy += p[i + 1]; count++ }
			val turn = if (count > 0 && kotlin.math.hypot(sx / count, sy / count) > 1.0) round(Math.toDegrees(kotlin.math.atan2(sy, sx)).toFloat(), 100f) else 0f
			turns[r.id] = turn
			val c = kotlin.math.cos(Math.toRadians(turn.toDouble())).toFloat(); val s = kotlin.math.sin(Math.toRadians(turn.toDouble())).toFloat()
			val reach = points.maxOfOrNull { p -> (p.indices step 2).maxOfOrNull { p[it] * c + p[it + 1] * s } ?: 0f } ?: 0f
			lengths[r.id] = if (count > 0) round(maxOf(0f, reach), 100f) else r.handleLength ?: 0f
		}
	}

	private fun round(value: Float, scale: Float) = (value * scale).roundToInt() / scale

	private fun boneFrame(id: String?, at: Map<String, Frame>, turn: Float? = null): BoneFrame {
		if (id == null) return BoneFrame.Root
		val f = at.getValue(id) as Frame.Rotation
		return BoneFrame(f.x - cx, bottom - f.y, f.angle, f.scale, f.flipX, f.flipY, turn ?: turns.getValue(id))
	}

	private fun locals(at: Map<String, Frame>): List<BoneLocal> =
		boneDeformers.map { r -> boneFrame(r.id, at).relativeTo(boneFrame(parentBone[r.id], at)) }

	private val setup = locals(restFrames)

	/** Vertices of [mesh] at a pose, in its bone's space. */
	private fun meshLocal(mesh: String, pose: PoseGeometry, at: Map<String, Frame>): FloatArray {
		val points = spine(pose.positions[mesh] ?: rest.positions.getValue(mesh))
		val bone = meshBone[mesh] ?: return points
		return boneFrame(bone, at).world.inverse().map(points)
	}

	private val restLocal = ordered.associate { it.id to meshLocal(it.id, rest, restFrames) }

	private val clips = ordered.filter { it.maskedBy.isNotEmpty() && !it.invertMask }.mapNotNull { mesh ->
		Clipping.polygon(mesh.maskedBy.mapNotNull { id -> ir.meshes.firstOrNull { it.id == id } }, rest)?.let { mesh.id to it }
	}.toMap()
	/** A clipping polygon sits on its masks' bone when they share one, else on the root. */
	private val clipBone = clips.mapValues { (_, clip) -> clip.points.map { meshBone[it.first] }.distinct().singleOrNull() }

	private fun clipLocal(mesh: String, pose: PoseGeometry, at: Map<String, Frame>): FloatArray {
		val points = spine(clips.getValue(mesh).positions(pose, rest))
		val bone = clipBone[mesh] ?: return points
		return boneFrame(bone, at).world.inverse().map(points)
	}

	private val restClip = clips.keys.associateWith { clipLocal(it, rest, restFrames) }

	/** Slot names in setup order: each clipped mesh follows its clipping slot. */
	private fun slotsOf(meshOrder: List<String>) = meshOrder.flatMap { id -> if (id in clips) listOf("clip/$id", id) else listOf(id) }
	private val slotNames = slotsOf(ordered.map { it.id })
	private val slotIndex = slotNames.withIndex().associate { it.value to it.index }

	private fun color(mesh: Mesh, opacity: Float) =
		(channel(mesh.multiply.red) shl 24) or (channel(mesh.multiply.green) shl 16) or (channel(mesh.multiply.blue) shl 8) or channel(opacity)
	private fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255f).roundToInt()

	/** Parameters whose animation keys a bone's rotation, and the bones; for physics. */
	private val rotatedBy = HashMap<String, MutableSet<Int>>()
	private val deformedBy = HashSet<String>()

	fun build(): Skeleton {
		clips.forEach { (mesh, clip) ->
			if (clip.hull) losses += LossEntry(mesh, Feature.MASK, Handling.APPROXIMATED, note = "Several masks or outlines clip by their convex hull")
		}
		ordered.filter { it.screen != Rgb.Black }.forEach {
			losses += LossEntry(it.id, Feature.BLEND_MODE, Handling.DROPPED, note = "Screen color is not written")
		}
		var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
		for (mesh in ordered) {
			val points = spine(rest.positions.getValue(mesh.id))
			for (i in points.indices step 2) {
				minX = minOf(minX, points[i]); maxX = maxOf(maxX, points[i]); minY = minOf(minY, points[i + 1]); maxY = maxOf(maxY, points[i + 1])
			}
		}
		if (ordered.isEmpty()) { minX = 0f; minY = 0f; maxX = 0f; maxY = 0f }

		val bones = listOf(SkBone("root", null, 0f, 0f, 0f, 1f, 1f, 0f)) + boneDeformers.mapIndexed { i, r ->
			val local = setup[i]
			SkBone(boneNames[i + 1], boneIndex[parentBone[r.id]] ?: 0, local.x, local.y, local.rotation, local.scaleX, local.scaleY, lengths.getValue(r.id))
		}
		fun boneOf(id: String?) = id?.let { boneIndex.getValue(it) } ?: 0
		val slots = ordered.flatMap { mesh ->
			listOfNotNull(
				clips[mesh.id]?.let { SkSlot("clip/${mesh.id}", boneOf(clipBone[mesh.id]), SkeletonJsonWriter.WHITE, "clip/${mesh.id}", SkBlend.NORMAL) },
				SkSlot(mesh.id, boneOf(meshBone[mesh.id]), color(mesh, rest.opacity[mesh.id] ?: mesh.opacity), mesh.id, blend(mesh.blend)))
		}
		val attachments = ordered.map { mesh ->
			val geometry = mesh.geometry!!
			SkAttachment.Mesh(slotIndex.getValue(mesh.id), mesh.id, regionName(options, ir.textures.bindings[mesh.id] ?: mesh.page),
				geometry.uvs.toArray(), geometry.indices.toArray(), restLocal.getValue(mesh.id))
		} + clips.keys.map { mesh -> SkAttachment.Clipping(slotIndex.getValue("clip/$mesh"), "clip/$mesh", slotIndex.getValue(mesh), restClip.getValue(mesh)) }

		val animations = ArrayList<SkAnimation>()
		for (axis in bake.axes) {
			val p = axis.x
			val samples = axis.xKeys.indices.map { xi -> Sample((axis.xKeys[xi] - p.min) / (p.max - p.min), bake.samples.getValue(axis)[xi][0], axisFrames.getValue(axis)[xi]) }
			val animation = animation("param/${p.id}", samples)
			animation.bones.filter { it.rotate != null }.forEach { rotatedBy.getOrPut(p.id) { HashSet() } += it.bone }
			if (animation.deforms.isNotEmpty()) deformedBy += p.id
			animations += animation
		}
		clipPoses.forEachIndexed { c, (name, poses) ->
			animations += animation(name, poses.mapIndexed { i, (t, values) -> Sample(t, session.evaluate(values), clipFrames[c][i]) })
		}
		losses += crossTermLosses(options.int("sample_pairs", 48))
		return Skeleton(
			hash = ContentHash.of(ir.meshes.map { it.id }, ir.parameters.map { it.id }).take(16),
			x = minX, y = minY, width = maxX - minX, height = maxY - minY, fps = clipFps, images = "./",
			bones = bones, slots = slots, physics = physics(), attachments = attachments, animations = animations,
		)
	}

	private class Sample(val time: Float, val pose: PoseGeometry, val frames: Map<String, Frame>)

	private fun wrap(degrees: Float) = degrees - 360f * kotlin.math.round(degrees / 360f)

	/** A bone's keyed values at a sample, relative to its setup: rotation offset, translation offset, scale factor. */
	private fun boneDelta(i: Int, local: BoneLocal): FloatArray {
		val s = setup[i]
		val rotation = (local.rotation - s.rotation).let { if (boneDeformers[i].id in wrapped) wrap(it) else it }
		return floatArrayOf(rotation, local.x - s.x, local.y - s.y, local.scaleX / s.scaleX, local.scaleY / s.scaleY)
	}

	/** A deform key: the offsets trimmed to their nonzero span, or none at all (the setup pose). */
	private fun deformKey(time: Float, offsets: FloatArray): DeformKey {
		val first = offsets.indexOfFirst { abs(it) >= 5e-4f }
		if (first < 0) return DeformKey(time, 0, FloatArray(0))
		val last = offsets.indexOfLast { abs(it) >= 5e-4f }
		// Thousandths of a pixel are below any visible difference and keep the file small.
		return DeformKey(time, first, FloatArray(last + 1 - first) { (offsets[first + it] * 1000f).roundToInt() / 1000f })
	}

	private fun deformTimeline(slot: String, offsets: List<Pair<Float, FloatArray>>): DeformTimeline? {
		if (offsets.none { (_, values) -> values.any { abs(it) >= 5e-4f } }) return null
		val kept = KeyReduction.reduce(offsets.map { it.first }, offsets.map { it.second }, tolerance)
		return DeformTimeline(slotIndex.getValue(slot), slot, kept.map { offsets[it] }.map { (time, values) -> deformKey(time, values) })
	}

	/** An animation from samples: bone transforms, deform, slot alpha and draw order where they change. */
	private fun animation(name: String, samples: List<Sample>): SkAnimation {
		val times = samples.map { it.time }
		val sampledLocals = samples.map { locals(it.frames) }
		val boneTimelines = boneDeformers.indices.mapNotNull { i ->
			val deltas = sampledLocals.map { boneDelta(i, it[i]) }
			fun track(from: Int, to: Int, neutral: Float, epsilon: Float, tolerance: Float): List<Int>? {
				if (deltas.all { d -> (from until to).all { abs(d[it] - neutral) <= epsilon } }) return null
				return KeyReduction.reduce(times, deltas.map { it.copyOfRange(from, to) }, tolerance)
			}
			val rotate = track(0, 1, 0f, 1e-3f, Math.toDegrees(boneTolerance.toDouble()).toFloat())?.map { Key1(times[it], deltas[it][0]) }
			val translate = track(1, 3, 0f, 1e-4f, tolerance)?.map { Key2(times[it], deltas[it][1], deltas[it][2]) }
			val scale = track(3, 5, 1f, 1e-5f, boneTolerance)?.map { Key2(times[it], deltas[it][3], deltas[it][4]) }
			if (rotate == null && translate == null && scale == null) null else BoneTimelines(i + 1, rotate, translate, scale)
		}
		val deforms = ArrayList<DeformTimeline>()
		val slotColors = ArrayList<Pair<Int, List<SlotColorKey>>>()
		for (mesh in ordered) {
			val restPoints = restLocal.getValue(mesh.id)
			deformTimeline(mesh.id, samples.map { s ->
				val posed = meshLocal(mesh.id, s.pose, s.frames)
				s.time to FloatArray(posed.size) { posed[it] - restPoints[it] }
			})?.let { deforms += it }
			val restOpacity = rest.opacity[mesh.id] ?: mesh.opacity
			if (samples.any { abs((it.pose.opacity[mesh.id] ?: restOpacity) - restOpacity) > 1e-3f })
				slotColors += slotIndex.getValue(mesh.id) to samples.map { SlotColorKey(it.time, color(mesh, it.pose.opacity[mesh.id] ?: restOpacity)) }
		}
		for (mesh in clips.keys) {
			val restPoints = restClip.getValue(mesh)
			deformTimeline("clip/$mesh", samples.map { s ->
				val posed = clipLocal(mesh, s.pose, s.frames)
				s.time to FloatArray(posed.size) { posed[it] - restPoints[it] }
			})?.let { deforms += it }
		}
		deforms.sortBy { it.slot }
		slotColors.sortBy { it.first }
		val orders = samples.map { s ->
			s.time to slotsOf(ordered.withIndex().sortedWith(compareBy({ s.pose.drawOrder[it.value.id] ?: rest.drawOrder[it.value.id] ?: it.value.drawOrder }, { it.index }))
				.map { it.value.id })
		}
		val drawOrder = if (orders.none { (_, order) -> order != slotNames }) null else orders.map { (time, order) ->
			val moved = order.withIndex().filter { (index, id) -> slotIndex.getValue(id) != index }.sortedBy { slotIndex.getValue(it.value) }
			DrawOrderKey(time, moved.map { (index, id) -> slotIndex.getValue(id) to index - slotIndex.getValue(id) })
		}
		return SkAnimation(name, slotColors, boneTimelines, deforms, drawOrder)
	}

	/**
	 * The largest deviation, per mesh, between the rig and Spine adding up the parameter animations (bone
	 * values and deform offsets summed, bones composed), over [count] poses combining two parameters at sampled
	 * keys. Meshes within half a pixel are left out.
	 */
	private fun crossTermLosses(count: Int): List<LossEntry> {
		val axes = bake.axes
		if (axes.size < 2 || count <= 0) return emptyList()
		var seed = 0x2545F491L
		fun next(bound: Int): Int { seed = seed * 6364136223846793005L + 1442695040888963407L; return ((seed ushr 33) % bound).toInt() }
		val sampledLocals = axes.associateWith { axis -> axisFrames.getValue(axis).map { locals(it) } }
		val worst = HashMap<String, Float>()
		repeat(count) {
			val a = axes[next(axes.size)]
			val b = axes[next(axes.size)].let { if (it === a) axes[(axes.indexOf(a) + 1) % axes.size] else it }
			val picks = listOf(a, b).map { axis -> Triple(axis, next(axis.xKeys.size), next(axis.yKeys.size)) }
			val actual = session.evaluate(picks.fold(emptyMap()) { pose, (axis, xi, yi) -> pose + axis.pose(xi, yi) })
			// Spine's additive mix: setup plus each track's offset; scale adds (factor - 1) times the setup scale.
			val world = ArrayList<Affine>(boneDeformers.size + 1).apply { add(Affine.Identity) }
			boneDeformers.forEachIndexed { i, r ->
				val s = setup[i]
				var x = s.x; var y = s.y; var rotation = s.rotation; var scaleX = s.scaleX; var scaleY = s.scaleY
				for ((axis, xi, _) in picks) {
					val d = boneDelta(i, sampledLocals.getValue(axis)[xi][i])
					rotation += d[0]; x += d[1]; y += d[2]; scaleX += (d[3] - 1f) * s.scaleX; scaleY += (d[4] - 1f) * s.scaleY
				}
				world += world[boneIndex[parentBone[r.id]] ?: 0] * Affine.local(x, y, rotation, scaleX, scaleY)
			}
			for (mesh in ordered) {
				val evaluated = actual.positions[mesh.id] ?: continue
				val local = restLocal.getValue(mesh.id).copyOf()
				for ((axis, xi, yi) in picks) {
					val posed = meshLocal(mesh.id, bake.samples.getValue(axis)[xi][yi], axisFrames.getValue(axis)[xi])
					val restPoints = restLocal.getValue(mesh.id)
					for (i in local.indices) local[i] += posed[i] - restPoints[i]
				}
				val placed = world[meshBone[mesh.id]?.let { boneIndex.getValue(it) } ?: 0].map(local)
				var error = 0f
				for (i in placed.indices step 2) {
					error = maxOf(error, abs(placed[i] + cx - evaluated[i]), abs(bottom - placed[i + 1] - evaluated[i + 1]))
				}
				worst[mesh.id] = maxOf(worst[mesh.id] ?: 0f, error)
			}
		}
		return worst.filterValues { it > 0.5f }.toSortedMap().map { (mesh, error) ->
			LossEntry(mesh, Feature.PARAMETER_GRID, Handling.APPROXIMATED, error,
				"Parameters combining on this mesh are added up per parameter (max ${"%.1f".format(error)} px at sampled poses)")
		}
	}

	/**
	 * Physics constraints: a pendulum output keying a parameter whose animation rotates bones puts a rotating
	 * constraint on each of those bones. Spine simulates from the bone's own motion; the pendulum's settings map
	 * approximately (inertia full, strength 100 × acceleration, damping ← mobility, mix ← output weight).
	 */
	private fun physics(): List<SkPhysics> {
		val constrained = LinkedHashMap<Int, SkPhysics>()
		val fps = (ir.physics.fps ?: 60f).roundToInt().coerceIn(1, 255)
		for (group in ir.physics.groups) {
			val mapped = ArrayList<String>()
			val unmapped = ArrayList<String>()
			for (output in group.outputs) {
				val bones = if (output.source == PhysicsSource.ANGLE) rotatedBy[output.parameter].orEmpty().sorted() else emptyList()
				if (bones.isEmpty()) { unmapped += output.parameter; continue }
				mapped += output.parameter
				val segment = group.segments.getOrNull(output.vertex) ?: group.segments.lastOrNull()
				for (bone in bones) if (bone !in constrained) constrained[bone] = SkPhysics(
					name = "${group.id}/${boneNames[bone]}", bone = bone, rotate = 1f, fps = fps, inertia = 1f,
					strength = round((100f * (segment?.acceleration ?: 1f)).coerceIn(1f, 1000f), 1000f),
					damping = round((segment?.mobility ?: 1f).coerceIn(0f, 1f), 1000f), mass = 1f,
					mix = round((output.weight / 100f).coerceIn(0f, 1f), 1000f))
			}
			losses += if (mapped.isEmpty()) LossEntry(group.id, Feature.PHYSICS, Handling.DROPPED, note = "No angle output of the pendulum keys a bone's rotation")
			else LossEntry(group.id, Feature.PHYSICS, Handling.APPROXIMATED, note = buildString {
				append("Becomes physics constraints on the bones that ${mapped.joinToString()} rotate; Spine simulates from the bones' own motion with approximated settings")
				val deformed = mapped.filter { it in deformedBy }
				if (deformed.isNotEmpty()) append("; deformation keyed by ${deformed.joinToString()} is not simulated")
				if (unmapped.isNotEmpty()) append("; outputs ${unmapped.joinToString()} are dropped")
			})
		}
		return constrained.values.toList()
	}

	private fun blend(mode: ColorBlend) = when (mode) {
		ColorBlend.ADD, ColorBlend.ADD_GLOW, ColorBlend.ADD_PREMULTIPLIED -> SkBlend.ADDITIVE
		ColorBlend.MULTIPLY, ColorBlend.MULTIPLY_PREMULTIPLIED -> SkBlend.MULTIPLY
		ColorBlend.SCREEN -> SkBlend.SCREEN
		else -> SkBlend.NORMAL
	}
}
