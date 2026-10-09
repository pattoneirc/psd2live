package io.github.psd2live.targets.runtime

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * `.p2lrt`, the compiled rig the PSD2Live runtime plays. Version 2 (the default) holds the records below in
 * chunks - a string table, one chunk per table, arrays tagged with a codec and four-byte aligned - each
 * 16-byte aligned, CRC-checked and optionally deflated; its layout is `docs/zh/spec/P2LRT_V2.md`. Version 1,
 * still written on request for older players, is one little-endian stream:
 *
 * ```
 * "P2LRT\0\0\0" u32 version
 * canvas      f32 width, height, originX, originY, pixelsPerUnit (-1: none)
 * parameters  u32 n × { str id, str name, f32 min, max, default, u8 flags (1 blend, 2 repeat) }
 * deformers   u32 n × deformer, parents before children
 * parts       u32 n × part
 * meshes      u32 n × mesh
 * glues       u32 n × glue
 * render      group (the draw-order tree)
 * textures    u32 n × { u32 width, height, bytes png }
 * physics     f32 fps (0: runtime default), u32 n × group
 * clips       u32 n × clip
 * roles       u32 n × { str role, u32 n × u32 parameter }
 * ```
 * `str` is u32 byte length + UTF-8, `floats` is u32 count + f32s, `bytes` is u32 length + bytes, references
 * are u32 indices (i32 -1 for none). A grid is `u16 axes × { u32 parameter, floats keys }`, then
 * `u32 cells × { u16 coordinate per axis, form }`; a missing grid is `u16 0xFFFF`. Channels are
 * `u8 n × { u8 channel, u8 kind (0 scalar, 1 color, 2 flag), grid of values }`. Blend bindings are
 * `u16 n × { u32 parameter, floats keys, u32 neutral, u8 present + shape per key, u16 n × limit }`.
 * The reader rejects any other version.
 */
public object P2lrt {
	/** The version [write] produces by default. */
	public const val VERSION: Int = 2

	/**
	 * How to write: [version] 1 or 2; in version 2, [compression] packs the chunks that shrink (never the PNG
	 * pages), [stripNames] leaves every display name empty, keeping the ids, and [advanced] adds what the runtime's
	 * advanced mode reads.
	 */
	public data class Options(
		val version: Int = VERSION, val compression: Compression = Compression.NONE, val stripNames: Boolean = false,
		/** Version 2 only: the chunks the runtime's advanced mode reads (bones, arcs, skins). */
		val advanced: Boolean = true,
	) {
		init { require(version == 1 || version == 2) { "Unsupported .p2lrt version $version" } }
	}

	/** How version 2 stores its chunks: as they are, as zlib streams, or as zstd frames. */
	public enum class Compression { NONE, DEFLATE, ZSTD }

	/** Whether [ir]'s physics travels vertically anywhere, which needs version 2 of the physics chunk. */
	internal fun usesVertical(ir: RigIR): Boolean =
		ir.physics.groups.any { g -> g.inputs.any { it.source == PhysicsSource.Y } || g.outputs.any { it.source == PhysicsSource.Y } }

	/** Whether [clip] carries what only CEXT holds. */
	internal fun extends(clip: Clip): Boolean =
		clip.events.isNotEmpty() || clip.targetCurves.isNotEmpty() || clip.curves.any { it.fadeIn != null || it.fadeOut != null }

	/** What version 1 cannot hold of [ir]: vertical physics and the clips' extensions. */
	public fun version1Losses(ir: RigIR): List<LossEntry> = buildList {
		if (usesVertical(ir)) add(LossEntry("*", Feature.PHYSICS, Handling.DROPPED, note = "Version 1 has no vertical physics travel; its inputs and outputs are left out"))
		for (c in ir.clips.filter(::extends)) {
			add(LossEntry(c.id, Feature.TIMELINE, Handling.DROPPED, note = "Version 1 has no clip events, part or rig opacity curves, blink or lip sync effects or curve fades"))
		}
	}

	public fun write(ir: RigIR, options: Options = Options()): ByteArray =
		if (options.version == 1) Writer(ir, Out(null), false).v1() else Writer(ir, Out(Strings()), options.stripNames).v2(options)

	/** Version 2's string table: each distinct string once, in first-use order; empty strings are not stored. */
	private class Strings {
		val index = LinkedHashMap<String, Int>()
		fun ref(v: String): Int = if (v.isEmpty()) -1 else index.getOrPut(v) { index.size }
	}

	/** Bytes being written: one version 1 stream, or one version 2 chunk when [strings] is set. */
	private class Out(val strings: Strings?) {
		private val buffer = ByteArrayOutputStream()
		private val scratch = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
		fun u8(v: Int) { buffer.write(v) }
		fun u16(v: Int) { require(v in 0..0xFFFF) { "Value $v exceeds u16" }; scratch.clear(); scratch.putShort(v.toShort()); buffer.write(scratch.array(), 0, 2) }
		fun u32(v: Int) { scratch.clear(); scratch.putInt(v); buffer.write(scratch.array(), 0, 4) }
		fun u64(v: Long) { scratch.clear(); scratch.putLong(v); buffer.write(scratch.array(), 0, 8) }
		fun i32(v: Int) = u32(v)
		fun f32(v: Float) { require(v.isFinite()) { "Non-finite value in the rig" }; scratch.clear(); scratch.putFloat(v); buffer.write(scratch.array(), 0, 4) }
		fun bool(v: Boolean) = u8(if (v) 1 else 0)
		fun str(v: String) {
			if (strings != null) { u32(strings.ref(v)); return }
			val b = v.encodeToByteArray(); u32(b.size); buffer.write(b)
		}
		/** A version 2 array header: zero padding to four bytes, the codec, three reserved bytes, the count. */
		private fun array(codec: Int, count: Int) {
			while (buffer.size() % 4 != 0) buffer.write(0)
			u8(codec); u8(0); u8(0); u8(0); u32(count)
		}
		fun floats(v: Floats) { if (strings != null) array(F32, v.size) else u32(v.size); for (i in 0 until v.size) f32(v[i]) }
		fun floats(v: FloatArray) { if (strings != null) array(F32, v.size) else u32(v.size); v.forEach(::f32) }
		fun indices(v: Ints) {
			val short = strings != null && (0 until v.size).all { v[it] in 0..0xFFFF }
			if (strings != null) array(if (short) U16 else U32, v.size) else u32(v.size)
			for (i in 0 until v.size) if (short) u16(v[i]) else u32(v[i])
		}
		fun bytes(v: ByteArray) { if (strings != null) array(U8, v.size) else u32(v.size); buffer.write(v) }
		fun raw(v: ByteArray) { buffer.write(v) }
		fun pad(alignment: Int) { while (buffer.size() % alignment != 0) buffer.write(0) }
		fun size(): Int = buffer.size()
		fun rgb(c: Rgb) { f32(c.red); f32(c.green); f32(c.blue) }
		fun result(): ByteArray = buffer.toByteArray()
	}

	private const val F32 = 0
	private const val U16 = 16
	private const val U32 = 17
	private const val U8 = 32
	private const val REQUIRED = 1
	private const val HAS_CRC = 2
	private const val DEFLATED = 1 shl 2
	private const val ZSTD_FRAME = 2 shl 2
	private const val NAMES_STRIPPED = 1
	// Advanced features and the evaluation hooks extension chunks declare.
	private const val SKIN = 1
	private const val EXACT_LINKS = 2
	private const val SIM = 4
	private const val COLLISION = 8
	private const val H1 = 1
	private const val H2 = 2
	private const val H3 = 4
	private const val H4 = 8
	private const val H6 = 32

	private class Chunk(val tag: String, val required: Boolean, val data: ByteArray, val compressible: Boolean = true, val version: Int = 1)

	private class Writer(private val ir: RigIR, private var out: Out, private val stripNames: Boolean) {
		/** A display name: empty when names are stripped. */
		private fun name(v: String) = out.str(if (stripNames) "" else v)
		private val parameterIndex = ir.parameters.withIndex().associate { it.value.id to it.index }
		private val deformers = parentsFirst(ir.deformers)
		private val deformerIndex = deformers.withIndex().associate { it.value.id to it.index }
		private val partIndex = ir.parts.withIndex().associate { it.value.id to it.index }
		private val meshIndex = ir.meshes.withIndex().associate { it.value.id to it.index }

		private fun parameter(id: String) = parameterIndex[id] ?: throw IllegalArgumentException("Unknown parameter: $id")
		private fun deformer(id: String?) = id?.let { deformerIndex[it] ?: throw IllegalArgumentException("Unknown deformer: $it") } ?: -1
		private fun part(id: String?) = id?.let { partIndex[it] ?: throw IllegalArgumentException("Unknown part: $it") } ?: -1
		private fun mesh(id: String) = meshIndex[id] ?: throw IllegalArgumentException("Unknown mesh: $id")

		private fun magic() { "P2LRT".forEach { out.u8(it.code) }; out.u8(0); out.u8(0); out.u8(0) }
		private fun canvas() = with(ir.canvas) { out.f32(width); out.f32(height); out.f32(originX); out.f32(originY); out.f32(pixelsPerUnit ?: -1f) }
		private fun parameters() {
			out.u32(ir.parameters.size)
			for (p in ir.parameters) { out.str(p.id); name(p.name); out.f32(p.min); out.f32(p.max); out.f32(p.default); out.u8((if (p.blend) 1 else 0) or (if (p.repeat) 2 else 0)) }
		}
		/** Version 1 has no vertical travel: its physics leaves Y inputs and outputs out. */
		private var vertical = true
		private fun physics() { out.f32(ir.physics.fps ?: 0f); out.u32(ir.physics.groups.size); ir.physics.groups.forEach(::physics) }
		private fun roles() {
			out.u32(ir.parameterRoles.size)
			for (role in ir.parameterRoles) {
				val present = role.parameters.filter { it in parameterIndex }
				out.str(role.role); out.u32(present.size); present.forEach { out.u32(parameter(it)) }
			}
		}

		fun v1(): ByteArray {
			vertical = false
			magic(); out.u32(1)
			canvas(); parameters()
			out.u32(deformers.size); deformers.forEach(::deformer)
			out.u32(ir.parts.size); ir.parts.forEach(::part)
			out.u32(ir.meshes.size); ir.meshes.forEach(::mesh)
			out.u32(ir.glues.size); ir.glues.forEach(::glue)
			group(ir.renderRoot)
			out.u32(ir.textures.pages.size)
			for (page in ir.textures.pages) { out.u32(page.width); out.u32(page.height); out.bytes(page.png.shared()) }
			physics()
			out.u32(ir.clips.size); ir.clips.forEach(::clip)
			roles()
			return out.result()
		}

		fun v2(options: Options): ByteArray {
			val strings = out.strings!!
			fun chunk(tag: String, required: Boolean = true, compressible: Boolean = true, body: () -> Unit): Chunk {
				out = Out(strings); body(); return Chunk(tag, required, out.result(), compressible)
			}
			val chunks = ArrayList<Chunk>()
			chunks += chunk("CANV") { canvas() }
			chunks += chunk("PARM") { parameters() }
			chunks += chunk("DEFM") { out.u32(deformers.size); deformers.forEach(::deformer) }
			chunks += chunk("PART") { out.u32(ir.parts.size); ir.parts.forEach(::part) }
			chunks += chunk("MESH") { out.u32(ir.meshes.size); ir.meshes.forEach(::mesh) }
			if (ir.glues.isNotEmpty()) chunks += chunk("GLUE") { out.u32(ir.glues.size); ir.glues.forEach(::glue) }
			chunks += chunk("DRAW") { group(ir.renderRoot) }
			if (ir.textures.pages.isNotEmpty()) chunks += chunk("TEXR", compressible = false) {
				out.u32(ir.textures.pages.size)
				for (page in ir.textures.pages) { out.u8(0); out.u32(page.width); out.u32(page.height); out.bytes(page.png.shared()) }
			}
			if (ir.physics.groups.isNotEmpty() || ir.physics.fps != null) {
				// Vertical travel needs version 2 of the chunk, which a runtime that predates it refuses by name.
				val version = if (P2lrt.usesVertical(ir)) 2 else 1
				chunks += chunk("PHYS") { physics() }.let { Chunk(it.tag, it.required, it.data, it.compressible, version) }
			}
			if (ir.clips.isNotEmpty()) chunks += chunk("CLIP") { out.u32(ir.clips.size); ir.clips.forEach(::clip) }
			// Events, curves on parts and the rig, and curve fades: optional, so an older runtime plays the clips' parameters.
			val extended = ir.clips.withIndex().filter { (_, c) -> P2lrt.extends(c) }
			if (extended.isNotEmpty()) chunks += chunk("CEXT", required = false) {
				out.u32(extended.size)
				for ((i, c) in extended) clipExtras(i, c)
			}
			if (ir.parameterRoles.isNotEmpty()) chunks += chunk("ROLE") { roles() }
			if (options.advanced) advanced().forEach { chunks += chunk(it.first, required = false) { it.second() } }
			// What a host builds interaction on: expressions, hit areas and the meshes' user data.
			val expressions = ir.advanced.expressions.map { e -> e to e.parameters.filter { it.parameter in parameterIndex } }.filter { it.second.isNotEmpty() }
			if (expressions.isNotEmpty()) chunks += chunk("EXPR", required = false) {
				out.u32(expressions.size)
				for ((e, parameters) in expressions) {
					out.str(e.id); name(e.name); out.f32(e.fadeIn); out.f32(e.fadeOut)
					out.u32(parameters.size); parameters.forEach { out.u32(parameter(it.parameter)); out.u8(it.blend.ordinal); out.f32(it.value) }
				}
			}
			val hitAreas = ir.advanced.hitAreas.map { h -> h to h.meshes.filter { it in meshIndex } }.filter { it.second.isNotEmpty() }
			if (hitAreas.isNotEmpty()) chunks += chunk("HITA", required = false) {
				out.u32(hitAreas.size)
				for ((h, meshes) in hitAreas) { out.str(h.id); name(h.name); out.u32(meshes.size); meshes.forEach { out.u32(mesh(it)) } }
			}
			// Pose groups of parts the file has; a group left with one part has nothing to switch.
			val poseGroups = ir.advanced.pose?.groups.orEmpty().map { g -> g.filter { it.part in partIndex } }.filter { it.size > 1 }
			if (poseGroups.isNotEmpty()) chunks += chunk("POSE", required = false) {
				out.f32(ir.advanced.pose!!.fadeIn)
				out.u32(poseGroups.size)
				for (g in poseGroups) {
					out.u32(g.size)
					for (e in g) { out.u32(part(e.part)); val links = e.links.filter { it in partIndex }; out.u32(links.size); links.forEach { out.u32(part(it)) } }
				}
			}
			val userData = ir.meshes.filter { it.userData.isNotEmpty() }
			if (userData.isNotEmpty()) chunks += chunk("UDAT", required = false) {
				out.u32(userData.size)
				userData.forEach { out.u8(0); out.u32(mesh(it.id)); out.str(it.userData) }
			}
			val gui = Gui(ir, parameterIndex)
			if (!gui.isEmpty) chunks += chunk("PGUI", required = false) { gui.write() }
			chunks += chunk("META", required = false) {
				val meta = listOf("generator" to "psd2live", "generator_version" to Compiler.version)
				out.u32(meta.size); meta.forEach { (k, v) -> out.str(k); out.str(v) }
			}
			// The string table goes first; every other chunk has added its strings by now.
			out = Out(null)
			out.u32(strings.index.size)
			val utf8 = strings.index.keys.map { it.encodeToByteArray() }
			var at = 0
			out.u32(0); utf8.forEach { at += it.size; out.u32(at) }
			utf8.forEach(out::raw)
			chunks.add(0, Chunk("STRS", true, out.result()))
			return container(chunks, options)
		}

		/** The advanced chunks the rig offers, each a tag and its body: BONE (bones and arcs), SKIN, COLL, SIMS. */
		private fun advanced(): List<Pair<String, () -> Unit>> {
			val arcs = AdvancedData.arcs(ir)
			val skins = AdvancedData.skins(ir)
			val bones = (skins.flatMap { it.bones } + arcs.map { it.deformer }).distinct()
			// The virtual bones the skins and arcs use, and the ones above them.
			val byId = ir.advanced.virtualBones.associateBy { it.id }
			val needed = HashSet<String>()
			fun need(id: String?) { if (id != null && id in byId && needed.add(id)) need(byId.getValue(id).parent) }
			bones.forEach(::need)
			val virtual = ir.advanced.virtualBones.filter { it.id in needed }
			val virtualIndex = virtual.withIndex().associate { it.value.id to deformers.size + it.index }
			fun bone(id: String?): Int = id?.let { virtualIndex[it] } ?: deformer(id)
			// Simulations whose meshes and parameters all made it into the file, and the colliders they use.
			val sims = ir.advanced.simulations.filter { sim ->
				sim.targets.all { it.mesh in meshIndex } && sim.particles.anchorMesh.all { it.isEmpty() || it in meshIndex } &&
					sim.statics.all { st -> st.parameter in parameterIndex && st.offsets.keys.all { it in meshIndex } }
			}
			val colliders = ir.advanced.colliders.filter { c ->
				sims.any { c.id in it.colliders } && (c.mesh == null || c.mesh in meshIndex) && (c.deformer == null || c.deformer in deformerIndex)
			}
			val colliderIndex = colliders.withIndex().associate { it.value.id to it.index }
			val groupIndex = ir.physics.groups.withIndex().associate { it.value.id to it.index }
			return buildList {
				if (bones.isNotEmpty()) add("BONE" to {
					extensionHeader(EXACT_LINKS, H2 or H3, arcs.map { bone(it.deformer) })
					out.u32(virtual.size); virtual.forEach { deformer(it, bone(it.parent)) }
					out.u32(bones.size); bones.forEach { out.u32(bone(it)); out.str(it) }
					out.u32(arcs.size)
					for (a in arcs) { out.u32(bone(a.deformer)); out.u32(parameter(a.parameter)); out.f32(a.centerX); out.f32(a.centerY) }
				})
				if (skins.isNotEmpty()) add("SKIN" to {
					extensionHeader(SKIN, H4)
					out.u32(skins.size)
					for (s in skins) {
						out.u32(mesh(s.mesh))
						out.u32(s.axes.size); s.axes.forEach { (p, keys) -> out.u32(parameter(p)); out.floats(keys) }
						out.u32(s.bones.size); s.bones.forEach { out.u32(bone(it)) }
						// No weights: the runtime fits them to the keyforms.
						out.indices(Ints.Empty); out.indices(Ints.Empty); out.floats(FloatArray(0))
					}
				})
				if (colliders.isNotEmpty()) add("COLL" to {
					extensionHeader(COLLISION, H6)
					out.u32(colliders.size)
					for (c in colliders) {
						out.str(c.id); out.bool(c.capsule); out.i32(c.deformer?.let(::bone) ?: -1); out.i32(c.mesh?.let(::mesh) ?: -1)
						out.u32(c.vertexA); out.u32(c.vertexB)
						out.f32(c.ax); out.f32(c.ay); out.f32(c.bx); out.f32(c.by); out.f32(c.radiusA); out.f32(c.radiusB); out.f32(c.friction)
					}
				})
				if (sims.isNotEmpty()) add("SIMS" to {
					val parameters = sims.flatMap { it.parameters }.filter { it in parameterIndex }.distinct()
					val groups = sims.flatMap { it.physicsGroups }.mapNotNull(groupIndex::get).distinct()
					extensionHeader(SIM, H1 or H4 or H6, parameters = parameters.map(::parameter), groups = groups)
					out.u32(sims.size)
					sims.forEach { simulation(it, ::mesh, groupIndex, colliderIndex) }
				})
			}
		}

		/** One simulation's scene; an infinite compliance (no constraint) is written as -1. */
		private fun simulation(sim: SimulationIR, mesh: (String) -> Int, groups: Map<String, Int>, colliders: Map<String, Int>) {
			fun compliances(values: Floats) = out.floats(FloatArray(values.size) { values[it].takeIf(Float::isFinite) ?: -1f })
			out.str(sim.id); out.f32(sim.fps); out.u32(sim.substeps)
			out.f32(sim.gravityX); out.f32(sim.gravityY); out.f32(sim.windX); out.f32(sim.windY); out.f32(sim.pinCompliance)
			out.u32(sim.targets.size); sim.targets.forEach { out.u32(mesh(it.mesh)); out.u32(it.vertexCount) }
			with(sim.particles) {
				out.floats(invMass); out.floats(damping); out.floats(windFactor); out.floats(pinWeight); compliances(goalCompliance)
				out.floats(goalOffsetX); out.floats(goalOffsetY)
				out.indices(Ints.wrap(IntArray(anchorMesh.size) { if (anchorMesh[it].isEmpty()) -1 else mesh(anchorMesh[it]) })); out.indices(anchorVertex)
			}
			with(sim.stretch) { out.indices(a); out.indices(b); out.floats(rest); out.floats(compliance); out.floats(compressionCompliance) }
			with(sim.triangles) { out.indices(a); out.indices(b); out.indices(c); compliances(areaCompliance) }
			with(sim.bends) { out.indices(t1); out.indices(t2); out.floats(compliance) }
			with(sim.welds) { out.indices(a); out.indices(b); out.floats(weightA); out.floats(weightB); out.floats(compliance) }
			with(sim.longRange) { out.indices(particle); out.indices(root); out.floats(maxDistance) }
			val parameters = sim.parameters.filter { it in parameterIndex }
			out.u32(parameters.size); parameters.forEach { out.u32(parameter(it)) }
			val own = sim.physicsGroups.mapNotNull(groups::get)
			out.u32(own.size); own.forEach(out::u32)
			out.u32(sim.statics.size)
			for (st in sim.statics) {
				out.u32(parameter(st.parameter)); out.floats(st.keys)
				out.u32(st.offsets.size)
				for ((m, per) in st.offsets) { out.u32(mesh(m)); require(per.size == st.keys.size) { "Static offsets must match their keys" }; per.forEach(out::floats) }
			}
			val used = sim.colliders.mapNotNull(colliders::get)
			out.u32(used.size); used.forEach(out::u32)
		}

		/** An extension chunk's opening: its feature bit and hooks, then what it overrides. */
		private fun extensionHeader(feature: Int, hooks: Int, deformers: List<Int> = emptyList(), parameters: List<Int> = emptyList(), groups: List<Int> = emptyList()) {
			out.u16(feature); out.u16(hooks)
			out.u32(parameters.size); parameters.forEach(out::u32)
			out.u32(groups.size); groups.forEach(out::u32)
			out.u32(deformers.size); deformers.forEach(out::u32)
			out.u32(0); out.u32(0)
		}

		/** The parameter panel: snap values, two-dimensional pads and the group tree, for parameters the file has. */
		private inner class Gui(ir: RigIR, known: Map<String, Int>) {
			val snaps = ir.parameters.filter { it.keys != null && it.keys!!.size > 0 }
			val joysticks = ir.parameterLinks.filter { it.horizontal in known && it.vertical in known }
			val tree: List<ParameterNode> = prune(ir.parameterTree, known)
			val isEmpty get() = snaps.isEmpty() && joysticks.isEmpty() && tree.isEmpty()

			private fun prune(nodes: List<ParameterNode>, known: Map<String, Int>): List<ParameterNode> = nodes.mapNotNull { node ->
				when (node) {
					is ParameterNode.Param -> node.takeIf { it.id in known }
					is ParameterNode.Group -> node.copy(children = prune(node.children, known))
				}
			}

			fun write() {
				out.u32(snaps.size)
				for (p in snaps) { out.u32(parameter(p.id)); out.floats(p.keys!!) }
				out.u32(joysticks.size)
				for (link in joysticks) { out.u32(parameter(link.horizontal)); out.u32(parameter(link.vertical)) }
				out.u32(count(tree))
				tree.forEach(::node)
			}

			private fun count(nodes: List<ParameterNode>): Int = nodes.sumOf { 1 + if (it is ParameterNode.Group) count(it.children) else 0 }

			private fun node(n: ParameterNode) {
				when (n) {
					is ParameterNode.Param -> {
						out.u8(0); out.u8(0); out.u8(0); out.u8(0); out.i32(parameter(n.id))
						out.str(""); out.str(""); out.str(""); out.u32(0); out.u32(0)
					}
					is ParameterNode.Group -> {
						val label = n.label
						out.u8(1); out.bool(n.open); out.u8(when (label) { GroupLabel.None -> 0; is GroupLabel.Preset -> 1; is GroupLabel.Custom -> 2 }); out.u8(0)
						out.i32(-1); out.str(n.id); name(n.name)
						out.str((label as? GroupLabel.Preset)?.name ?: ""); out.u32((label as? GroupLabel.Custom)?.argb ?: 0)
						out.u32(n.children.size)
						n.children.forEach(::node)
					}
				}
			}
		}

		/** The header, the chunk table and the chunks, each 16-byte aligned. */
		private fun container(chunks: List<Chunk>, options: Options): ByteArray {
			val stored = chunks.map { c ->
				if (options.compression == Compression.NONE || !c.compressible) return@map c.data to 0
				val (packed, flag) = if (options.compression == Compression.ZSTD) zstd(c.data) to ZSTD_FRAME else deflate(c.data) to DEFLATED
				if (packed.size < c.data.size) packed to flag else c.data to 0
			}
			val file = Out(null)
			"P2LRT".forEach { file.u8(it.code) }; file.u8(0); file.u8(0); file.u8(0)
			file.u16(2); file.u16(0); file.u32(if (options.stripNames) NAMES_STRIPPED else 0)
			file.u32(chunks.size); file.u32(32); file.u32(0); file.u32(0)
			var offset = 32 + chunks.size * 40
			chunks.forEachIndexed { i, c ->
				val (bytes, compression) = stored[i]
				offset = (offset + 15) / 16 * 16
				val crc = CRC32().apply { update(bytes) }.value.toInt()
				c.tag.forEach { file.u8(it.code) }
				file.u16(c.version); file.u16((if (c.required) REQUIRED else 0) or HAS_CRC or compression)
				file.u64(offset.toLong()); file.u64(bytes.size.toLong()); file.u64(c.data.size.toLong())
				file.u32(crc); file.u32(0)
				offset += bytes.size
			}
			for ((bytes, _) in stored) { file.pad(16); file.raw(bytes) }
			return file.result()
		}

		/** One zstd frame of [data] (aircompressor's pure-Java encoder). */
		private fun zstd(data: ByteArray): ByteArray {
			val compressor = io.airlift.compress.zstd.ZstdCompressor()
			val out = ByteArray(compressor.maxCompressedLength(data.size))
			val n = compressor.compress(data, 0, data.size, out, 0, out.size)
			return out.copyOf(n)
		}

		private fun deflate(data: ByteArray): ByteArray {
			val deflater = Deflater(Deflater.BEST_COMPRESSION)
			try {
				deflater.setInput(data); deflater.finish()
				val packed = ByteArrayOutputStream()
				val buffer = ByteArray(65536)
				while (!deflater.finished()) packed.write(buffer, 0, deflater.deflate(buffer))
				return packed.toByteArray()
			} finally {
				deflater.end()
			}
		}

		private fun <F> grid(grid: KeyGrid<F>?, form: (F) -> Unit) {
			if (grid == null) { out.u16(0xFFFF); return }
			out.u16(grid.axes.size)
			for (axis in grid.axes) { out.u32(parameter(axis.parameter)); out.floats(axis.keys) }
			out.u32(grid.cells.size)
			for (cell in grid.cells) {
				require(cell.coordinate.size == grid.axes.size) { "Keyform cell coordinate does not match its axes" }
				for (i in 0 until cell.coordinate.size) out.u16(cell.coordinate[i])
				form(cell.form)
			}
		}

		private fun channels(channels: Channels) {
			out.u8(channels.size)
			for ((channel, grid) in channels.entries.sortedBy { it.key.ordinal }) {
				out.u8(channel.ordinal)
				val kind = when (grid.cells.firstOrNull()?.form) { is ChannelValue.Color -> 1; is ChannelValue.Flag -> 2; else -> 0 }
				out.u8(kind)
				grid(grid) { value ->
					when (value) {
						is ChannelValue.Scalar -> { check(kind == 0); out.f32(value.value) }
						is ChannelValue.Color -> { check(kind == 1); out.rgb(value.color) }
						is ChannelValue.Flag -> { check(kind == 2); out.bool(value.value) }
					}
				}
			}
		}

		private fun <S> bindings(bindings: List<BlendBinding<S>>, shape: (S & Any) -> Unit) {
			out.u16(bindings.size)
			for (binding in bindings) {
				out.u32(parameter(binding.parameter)); out.floats(binding.keys); out.u32(binding.neutralIndex)
				require(binding.shapes.size == binding.keys.size) { "Blend shapes must match their keys" }
				for (s in binding.shapes) { out.bool(s != null); if (s != null) shape(s) }
				out.u16(binding.limits.size)
				for (limit in binding.limits) {
					out.u32(parameter(limit.parameter)); out.u16(limit.points.size)
					for (point in limit.points) { out.f32(point.value); out.f32(point.weight) }
				}
			}
		}

		/** A deformer record; [parent] is its parent's index, given apart for a virtual bone. */
		private fun deformer(d: Deformer, parent: Int = deformer(d.parent)) {
			out.u8(if (d is Deformer.Warp) 0 else 1); out.str(d.id); out.i32(parent); out.i32(part(d.part))
			val flags = (if (d.visible) 1 else 0) or (if (d.enabled) 2 else 0) or
				(if (d is Deformer.Rotation && d.flipX) 4 else 0) or (if (d is Deformer.Rotation && d.flipY) 8 else 0) or
				(if (d is Deformer.Warp && d.bilinear) 16 else 0)
			out.u8(flags); out.f32(d.opacity); out.rgb(d.multiply); out.rgb(d.screen)
			when (d) {
				is Deformer.Warp -> {
					out.u32(d.columns); out.u32(d.rows)
					grid(d.lattice) { out.floats(it.points) }
					channels(d.channels)
					bindings(d.shapes) { s -> out.floats(s.points); out.f32(s.opacity); out.rgb(s.multiply); out.rgb(s.screen) }
				}
				is Deformer.Rotation -> {
					out.f32(d.baseAngle)
					grid(d.pivot) { out.f32(it.x); out.f32(it.y); out.f32(it.angle); out.f32(it.scale) }
					channels(d.channels)
					bindings(d.shapes) { s -> out.f32(s.x); out.f32(s.y); out.f32(s.angle); out.f32(s.scale); out.bool(s.flipX); out.bool(s.flipY)
						out.f32(s.opacity); out.rgb(s.multiply); out.rgb(s.screen) }
				}
			}
		}

		private fun child(ref: ChildRef) = when (ref) {
			is ChildRef.PartRef -> { out.u8(0); out.u32(part(ref.id)) }
			is ChildRef.MeshRef -> { out.u8(1); out.u32(mesh(ref.id)) }
		}

		private fun composite(c: Composite) {
			out.u8(c.blend.ordinal); out.u8(c.alphaBlend.ordinal)
			out.u32(c.maskedBy.size); c.maskedBy.forEach { out.u32(mesh(it)) }
			out.u32(c.maskedByParts.size); c.maskedByParts.forEach { out.u32(part(it)) }
			out.bool(c.invertMask); out.f32(c.opacity); out.rgb(c.multiply); out.rgb(c.screen)
		}

		private fun part(p: Part) {
			out.str(p.id); name(p.name)
			out.u8((if (p.visible) 1 else 0) or (if (p.sketch) 2 else 0)); out.u8(p.groupMode.ordinal); out.i32(p.drawOrder)
			out.u32(p.children.size); p.children.forEach(::child)
			channels(p.channels); composite(p.composite)
			bindings(p.shapes) { s -> out.f32(s.drawOrder); out.f32(s.opacity); out.rgb(s.multiply); out.rgb(s.screen) }
		}

		private fun mesh(m: Mesh) {
			out.str(m.id); name(m.name); out.i32(deformer(m.parent)); out.u8(m.blend.ordinal); out.u8(m.alphaBlend.ordinal)
			out.u32(m.maskedBy.size); m.maskedBy.forEach { out.u32(mesh(it)) }
			out.u8((if (m.invertMask) 1 else 0) or (if (m.culling) 2 else 0) or (if (m.visible) 4 else 0) or (if (m.geometry != null) 8 else 0))
			out.i32(ir.textures.bindings[m.id] ?: m.page)
			m.geometry?.let { g ->
				out.floats(g.positions); out.floats(g.uvs); out.indices(g.indices)
			}
			grid(m.offsets) { out.floats(it.deltas) }
			channels(m.channels)
			out.f32(m.drawOrder); out.f32(m.opacity); out.rgb(m.multiply); out.rgb(m.screen)
			bindings(m.shapes) { s -> out.floats(s.deltas); out.f32(s.drawOrder); out.f32(s.opacity); out.rgb(s.multiply); out.rgb(s.screen) }
		}

		private fun glue(g: Glue) {
			out.str(g.id ?: ""); out.u32(mesh(g.meshA)); out.u32(mesh(g.meshB)); out.f32(g.intensity)
			out.u32(g.pairs.size); for (p in g.pairs) { out.u32(p.a); out.u32(p.b); out.f32(p.weightA); out.f32(p.weightB) }
			channels(g.channels)
		}

		private fun group(g: RenderGroup) {
			out.i32(part(g.part)); out.i32(g.drawOrder); channels(g.channels)
			out.bool(g.composite != null); g.composite?.let(::composite)
			out.u32(g.children.size)
			for (child in g.children) when (child) {
				is RenderMesh -> { out.u8(1); out.u32(mesh(child.id)) }
				is RenderGroup -> { out.u8(0); group(child) }
			}
		}

		private fun physics(g: PhysicsGroup) {
			out.str(g.id); name(g.name)
			val inputs = g.inputs.filter { it.parameter in parameterIndex && (vertical || it.source != PhysicsSource.Y) }
			val outputs = g.outputs.filter { it.parameter in parameterIndex && (vertical || it.source != PhysicsSource.Y) }
			out.u32(inputs.size); for (i in inputs) { out.u32(parameter(i.parameter)); out.f32(i.weight); out.u8(i.source.ordinal); out.bool(i.reflect) }
			out.u32(outputs.size); for (o in outputs) { out.u32(parameter(o.parameter)); out.u32(o.vertex); out.f32(o.scale); out.f32(o.weight); out.u8(o.source.ordinal); out.bool(o.reflect) }
			out.u32(g.segments.size); for (s in g.segments) { out.f32(s.length); out.f32(s.mobility); out.f32(s.delay); out.f32(s.acceleration) }
			with(g.normalization) { out.f32(positionMin); out.f32(positionDefault); out.f32(positionMax); out.f32(angleMin); out.f32(angleDefault); out.f32(angleMax) }
		}

		private fun clip(c: Clip) {
			out.str(c.id); name(c.name); out.str(c.group); out.f32(c.duration); out.f32(c.fps); out.bool(c.loop)
			out.f32(c.fadeIn ?: -1f); out.f32(c.fadeOut ?: -1f)
			val curves = c.curves.filter { it.parameter in parameterIndex }
			out.u32(curves.size)
			for (curve in curves) {
				out.u32(parameter(curve.parameter)); points(curve.startTime, curve.startValue, curve.segments)
			}
		}

		/** A curve's start point and segments, as CLIP and CEXT store them. */
		private fun points(startTime: Float, startValue: Float, segments: List<CurveSegment>) {
			out.f32(startTime); out.f32(startValue); out.u32(segments.size)
			for (s in segments) {
				when (s) {
					is CurveSegment.Linear -> out.u8(0)
					is CurveSegment.Bezier -> { out.u8(1); out.f32(s.c1Time); out.f32(s.c1Value); out.f32(s.c2Time); out.f32(s.c2Value) }
					is CurveSegment.Stepped -> out.u8(2)
					is CurveSegment.InverseStepped -> out.u8(3)
				}
				out.f32(s.time); out.f32(s.value)
			}
		}

		/** Clip [index]'s CEXT record: its events, its curves on other targets and its own curves' fades. */
		private fun clipExtras(index: Int, c: Clip) {
			out.u32(index)
			val events = c.events.sortedBy { it.time }
			out.u32(events.size); events.forEach { out.f32(it.time); out.str(it.value) }
			val targets = c.targetCurves.filter { t -> (t.target as? CurveTarget.PartOpacity)?.let { it.part in partIndex } ?: true }
			out.u32(targets.size)
			for (t in targets) {
				when (val target = t.target) {
					is CurveTarget.PartOpacity -> { out.u8(1); out.i32(part(target.part)) }
					CurveTarget.ModelOpacity -> { out.u8(2); out.i32(-1) }
					CurveTarget.EyeBlink -> { out.u8(3); out.i32(-1) }
					CurveTarget.LipSync -> { out.u8(4); out.i32(-1) }
				}
				out.f32(t.fadeIn ?: -1f); out.f32(t.fadeOut ?: -1f)
				points(t.startTime, t.startValue, t.segments)
			}
			// Fades refer to the curves as CLIP wrote them: those on parameters the rig has.
			val faded = c.curves.filter { it.parameter in parameterIndex }.withIndex().filter { (_, k) -> k.fadeIn != null || k.fadeOut != null }
			out.u32(faded.size)
			for ((i, k) in faded) { out.u32(i); out.f32(k.fadeIn ?: -1f); out.f32(k.fadeOut ?: -1f) }
		}

		private companion object {
			fun parentsFirst(deformers: List<Deformer>): List<Deformer> {
				val byId = deformers.associateBy { it.id }
				val ordered = LinkedHashMap<String, Deformer>()
				fun visit(d: Deformer, path: Set<String>) {
					if (d.id in ordered) return
					require(d.id !in path) { "Deformer parents form a cycle at ${d.id}" }
					d.parent?.let { byId[it] }?.let { visit(it, path + d.id) }
					ordered[d.id] = d
				}
				deformers.forEach { visit(it, emptySet()) }
				return ordered.values.toList()
			}
		}
	}
}

/** The compiled runtime rig as an export target. */
public object P2lrtTarget : ExportTarget {
	override val id: String = "p2lrt"
	override val family: TargetFamily = TargetFamily.RIG
	override val description: String = "PSD2Live runtime rig (.p2lrt)"
	override val capabilities: CapabilityProfile = CapabilityProfile(
		warpLattice = true, parameterGrid = 8, blendShapes = true, timeline = true, physics = PhysicsSupport.PARAMETER_PENDULUM,
		blendModes = ColorBlend.entries.toSet(), masks = MaskSupport.TEXTURE_ALPHA, keyedDrawOrder = true, glue = true,
	)

	/**
	 * `v1` writes version 1 for players that predate version 2; `compress` (deflate, or zstd with `zstd`),
	 * `strip_names` and `advanced` (the data the runtime's advanced mode reads) apply to version 2.
	 */
	override val settings: List<TargetSetting> = listOf(
		TargetSetting.Flag("v1", false), TargetSetting.Flag("compress", false), TargetSetting.Flag("zstd", false),
		TargetSetting.Flag("strip_names", false), TargetSetting.Text("pose_groups", "PartArmA|PartArmB; PartHatA|PartHatB"),
		TargetSetting.Flag("advanced", true),
	)

	/**
	 * Pose groups written as `part|part; part|part`: each group's parts by id, one shown at a time, half-second fades.
	 * A part followed by `+linked` carries that part along (`PartArmA+PartHandA|PartArmB+PartHandB`).
	 */
	public fun poseGroups(text: String): PoseIR? {
		val groups = text.split(';').map { group ->
			group.split('|').map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
				val parts = entry.split('+').map { it.trim() }.filter { it.isNotEmpty() }
				PoseEntry(parts.first(), parts.drop(1))
			}
		}.filter { it.size > 1 }
		return if (groups.isEmpty()) null else PoseIR(0.5f, groups)
	}

	override fun plan(ir: RigIR, options: ExportOptions): LoweredExport {
		val posed = options.setting("pose_groups")?.let(::poseGroups)
		val ir = if (posed == null) ir else ir.copy(advanced = ir.advanced.copy(pose = posed))
		val v1 = options.flag("v1", false)
		val bytes = P2lrt.write(ir, P2lrt.Options(
			version = if (v1) 1 else 2,
			compression = when {
				!options.flag("compress", false) -> P2lrt.Compression.NONE
				options.flag("zstd", false) -> P2lrt.Compression.ZSTD
				else -> P2lrt.Compression.DEFLATE
			},
			stripNames = options.flag("strip_names", false),
			advanced = options.flag("advanced", true),
		))
		return object : LoweredExport {
			override val losses: List<LossEntry> = CapabilityScan.scan(ir, capabilities, options) + if (v1) P2lrt.version1Losses(ir) else emptyList()
			override fun write(sink: OutputSink) = sink.write("${options.baseName}.p2lrt", bytes)
		}
	}
}
