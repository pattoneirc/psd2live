package io.github.psd2live.targets.runtime

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `.p2lrt`, the compiled rig the PSD2Live runtime plays. Version 1, little-endian:
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
	public const val VERSION: Int = 1

	public fun write(ir: RigIR): ByteArray = Writer(ir).bytes()

	private class Out {
		private val buffer = ByteArrayOutputStream()
		private val scratch = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
		fun u8(v: Int) { buffer.write(v) }
		fun u16(v: Int) { require(v in 0..0xFFFF) { "Value $v exceeds u16" }; scratch.clear(); scratch.putShort(v.toShort()); buffer.write(scratch.array(), 0, 2) }
		fun u32(v: Int) { scratch.clear(); scratch.putInt(v); buffer.write(scratch.array(), 0, 4) }
		fun i32(v: Int) = u32(v)
		fun f32(v: Float) { require(v.isFinite()) { "Non-finite value in the rig" }; scratch.clear(); scratch.putFloat(v); buffer.write(scratch.array(), 0, 4) }
		fun bool(v: Boolean) = u8(if (v) 1 else 0)
		fun str(v: String) { val b = v.encodeToByteArray(); u32(b.size); buffer.write(b) }
		fun floats(v: Floats) { u32(v.size); for (i in 0 until v.size) f32(v[i]) }
		fun floats(v: FloatArray) { u32(v.size); v.forEach(::f32) }
		fun bytes(v: ByteArray) { u32(v.size); buffer.write(v) }
		fun rgb(c: Rgb) { f32(c.red); f32(c.green); f32(c.blue) }
		fun result(): ByteArray = buffer.toByteArray()
	}

	private class Writer(private val ir: RigIR) {
		private val out = Out()
		private val parameterIndex = ir.parameters.withIndex().associate { it.value.id to it.index }
		private val deformers = parentsFirst(ir.deformers)
		private val deformerIndex = deformers.withIndex().associate { it.value.id to it.index }
		private val partIndex = ir.parts.withIndex().associate { it.value.id to it.index }
		private val meshIndex = ir.meshes.withIndex().associate { it.value.id to it.index }

		private fun parameter(id: String) = parameterIndex[id] ?: throw IllegalArgumentException("Unknown parameter: $id")
		private fun deformer(id: String?) = id?.let { deformerIndex[it] ?: throw IllegalArgumentException("Unknown deformer: $it") } ?: -1
		private fun part(id: String?) = id?.let { partIndex[it] ?: throw IllegalArgumentException("Unknown part: $it") } ?: -1
		private fun mesh(id: String) = meshIndex[id] ?: throw IllegalArgumentException("Unknown mesh: $id")

		fun bytes(): ByteArray {
			out.u8('P'.code); out.u8('2'.code); out.u8('L'.code); out.u8('R'.code); out.u8('T'.code); out.u8(0); out.u8(0); out.u8(0)
			out.u32(VERSION)
			with(ir.canvas) { out.f32(width); out.f32(height); out.f32(originX); out.f32(originY); out.f32(pixelsPerUnit ?: -1f) }
			out.u32(ir.parameters.size)
			for (p in ir.parameters) { out.str(p.id); out.str(p.name); out.f32(p.min); out.f32(p.max); out.f32(p.default); out.u8((if (p.blend) 1 else 0) or (if (p.repeat) 2 else 0)) }
			out.u32(deformers.size); deformers.forEach(::deformer)
			out.u32(ir.parts.size); ir.parts.forEach(::part)
			out.u32(ir.meshes.size); ir.meshes.forEach(::mesh)
			out.u32(ir.glues.size); ir.glues.forEach(::glue)
			group(ir.renderRoot)
			out.u32(ir.textures.pages.size)
			for (page in ir.textures.pages) { out.u32(page.width); out.u32(page.height); out.bytes(page.png.shared()) }
			out.f32(ir.physics.fps ?: 0f); out.u32(ir.physics.groups.size); ir.physics.groups.forEach(::physics)
			out.u32(ir.clips.size); ir.clips.forEach(::clip)
			out.u32(ir.parameterRoles.size)
			for (role in ir.parameterRoles) {
				val present = role.parameters.filter { it in parameterIndex }
				out.str(role.role); out.u32(present.size); present.forEach { out.u32(parameter(it)) }
			}
			return out.result()
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

		private fun deformer(d: Deformer) {
			out.u8(if (d is Deformer.Warp) 0 else 1); out.str(d.id); out.i32(deformer(d.parent)); out.i32(part(d.part))
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
			out.str(p.id); out.str(p.name)
			out.u8((if (p.visible) 1 else 0) or (if (p.sketch) 2 else 0)); out.u8(p.groupMode.ordinal); out.i32(p.drawOrder)
			out.u32(p.children.size); p.children.forEach(::child)
			channels(p.channels); composite(p.composite)
			bindings(p.shapes) { s -> out.f32(s.drawOrder); out.f32(s.opacity); out.rgb(s.multiply); out.rgb(s.screen) }
		}

		private fun mesh(m: Mesh) {
			out.str(m.id); out.str(m.name); out.i32(deformer(m.parent)); out.u8(m.blend.ordinal); out.u8(m.alphaBlend.ordinal)
			out.u32(m.maskedBy.size); m.maskedBy.forEach { out.u32(mesh(it)) }
			out.u8((if (m.invertMask) 1 else 0) or (if (m.culling) 2 else 0) or (if (m.visible) 4 else 0) or (if (m.geometry != null) 8 else 0))
			out.i32(ir.textures.bindings[m.id] ?: m.page)
			m.geometry?.let { g ->
				out.floats(g.positions); out.floats(g.uvs); out.u32(g.indices.size); for (i in 0 until g.indices.size) out.u32(g.indices[i])
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
			out.str(g.id); out.str(g.name)
			val inputs = g.inputs.filter { it.parameter in parameterIndex }; val outputs = g.outputs.filter { it.parameter in parameterIndex }
			out.u32(inputs.size); for (i in inputs) { out.u32(parameter(i.parameter)); out.f32(i.weight); out.u8(i.source.ordinal); out.bool(i.reflect) }
			out.u32(outputs.size); for (o in outputs) { out.u32(parameter(o.parameter)); out.u32(o.vertex); out.f32(o.scale); out.f32(o.weight); out.u8(o.source.ordinal); out.bool(o.reflect) }
			out.u32(g.segments.size); for (s in g.segments) { out.f32(s.length); out.f32(s.mobility); out.f32(s.delay); out.f32(s.acceleration) }
			with(g.normalization) { out.f32(positionMin); out.f32(positionDefault); out.f32(positionMax); out.f32(angleMin); out.f32(angleDefault); out.f32(angleMax) }
		}

		private fun clip(c: Clip) {
			out.str(c.id); out.str(c.name); out.str(c.group); out.f32(c.duration); out.f32(c.fps); out.bool(c.loop)
			out.f32(c.fadeIn ?: -1f); out.f32(c.fadeOut ?: -1f)
			val curves = c.curves.filter { it.parameter in parameterIndex }
			out.u32(curves.size)
			for (curve in curves) {
				out.u32(parameter(curve.parameter)); out.f32(curve.startTime); out.f32(curve.startValue); out.u32(curve.segments.size)
				for (s in curve.segments) {
					when (s) {
						is CurveSegment.Linear -> out.u8(0)
						is CurveSegment.Bezier -> { out.u8(1); out.f32(s.c1Time); out.f32(s.c1Value); out.f32(s.c2Time); out.f32(s.c2Value) }
						is CurveSegment.Stepped -> out.u8(2)
						is CurveSegment.InverseStepped -> out.u8(3)
					}
					out.f32(s.time); out.f32(s.value)
				}
			}
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

	override fun plan(ir: RigIR, options: ExportOptions): LoweredExport {
		val bytes = P2lrt.write(ir)
		return object : LoweredExport {
			override val losses: List<LossEntry> = CapabilityScan.scan(ir, capabilities, options)
			override fun write(sink: OutputSink) = sink.write("${options.baseName}.p2lrt", bytes)
		}
	}
}
