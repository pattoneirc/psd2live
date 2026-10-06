package io.github.psd2live.targets.spine

import java.io.ByteArrayOutputStream

/**
 * Writes a [Skeleton] in the Spine 4.2 binary format (`.skel`), following the public layout the Spine
 * runtimes read (spine-core SkeletonBinary): big-endian numbers, variable-length positive ints, strings as
 * a byte count plus one followed by UTF-8, and a string table for attachment names and paths. Nonessential
 * data (editor colors, mesh edges) is left out.
 *
 * A binary mesh stores no triangle count: readers take `2 * vertices - hull - 2` triangles, so the hull
 * length is set to fit the mesh's triangles. A mesh with more triangles than that allows (only possible with
 * repeated triangles) gets unused copies of its first vertex.
 */
internal object SkeletonBinaryWriter {
	private const val BONE_ROTATE = 0
	private const val BONE_TRANSLATE = 1
	private const val BONE_SCALE = 4
	private const val SLOT_RGBA = 1
	private const val ATTACHMENT_DEFORM = 0
	private const val CURVE_LINEAR = 0
	private const val TYPE_MESH = 2
	private const val TYPE_CLIPPING = 6

	fun write(skeleton: Skeleton): ByteArray {
		val out = Output()
		val strings = LinkedHashMap<String, Int>()
		fun ref(name: String) = strings.getOrPut(name) { strings.size + 1 }
		// Every string written by reference, in first-use order.
		skeleton.slots.forEach { slot -> slot.attachment?.let(::ref) }
		skeleton.attachments.forEach { ref(it.name); if (it is SkAttachment.Mesh) ref(it.path) }
		skeleton.animations.forEach { animation -> animation.deforms.forEach { ref(it.attachment) } }

		out.long(skeleton.hash.toULongOrNull(16)?.toLong() ?: 0L)
		out.string(SkeletonJsonWriter.SPINE_VERSION)
		out.float(skeleton.x); out.float(skeleton.y); out.float(skeleton.width); out.float(skeleton.height)
		out.float(100f) // reference scale
		out.boolean(false) // no nonessential data
		out.varint(strings.size); strings.keys.forEach(out::string)

		out.varint(skeleton.bones.size)
		skeleton.bones.forEachIndexed { i, bone ->
			out.string(bone.name)
			if (i > 0) out.varint(bone.parent!!)
			out.float(bone.rotation); out.float(bone.x); out.float(bone.y); out.float(bone.scaleX); out.float(bone.scaleY)
			out.float(0f); out.float(0f) // shear
			out.float(bone.length)
			out.byte(0) // inherit: normal
			out.boolean(false) // skin required
		}
		out.varint(skeleton.slots.size)
		for (slot in skeleton.slots) {
			out.string(slot.name); out.varint(slot.bone); out.int(slot.color); out.int(-1) // no dark color
			out.varint(slot.attachment?.let(::ref) ?: 0); out.varint(slot.blend.ordinal)
		}
		out.varint(0); out.varint(0); out.varint(0) // IK, transform and path constraints
		out.varint(skeleton.physics.size)
		for (p in skeleton.physics) {
			out.string(p.name); out.varint(0); out.varint(p.bone)
			// Rotate is written; mass only when it is not 1.
			out.byte(8 or (if (p.mass != 1f) 128 else 0))
			out.float(p.rotate)
			out.byte(p.fps.coerceIn(1, 255))
			out.float(p.inertia); out.float(p.strength); out.float(p.damping)
			if (p.mass != 1f) out.float(1f / p.mass)
			out.float(0f); out.float(0f) // wind, gravity
			out.byte(if (p.mix != 1f) 128 else 0)
			if (p.mix != 1f) out.float(p.mix)
		}

		// Default skin.
		val bySlot = skeleton.attachments.groupBy { it.slot }.toSortedMap()
		out.varint(bySlot.size)
		for ((slot, attachments) in bySlot) {
			out.varint(slot); out.varint(attachments.size)
			for (attachment in attachments) { out.varint(ref(attachment.name)); attachment(out, attachment, ::ref) }
		}
		out.varint(0) // other skins
		out.varint(0) // events

		out.varint(skeleton.animations.size)
		for (animation in skeleton.animations) {
			out.string(animation.name)
			out.varint(animation.slotColors.size + animation.bones.sumOf { it.count } + animation.deforms.size + (if (animation.drawOrder != null) 1 else 0))
			out.varint(animation.slotColors.size)
			for ((slot, keys) in animation.slotColors) {
				out.varint(slot); out.varint(1); out.byte(SLOT_RGBA); out.varint(keys.size); out.varint(0)
				keys.forEachIndexed { i, key ->
					out.float(key.time); out.int(key.color)
					if (i > 0) out.byte(CURVE_LINEAR)
				}
			}
			out.varint(animation.bones.size)
			for (timelines in animation.bones) {
				out.varint(timelines.bone); out.varint(timelines.count)
				timelines.rotate?.let { keys ->
					out.byte(BONE_ROTATE); out.varint(keys.size); out.varint(0)
					keys.forEachIndexed { i, key -> out.float(key.time); out.float(key.value); if (i > 0) out.byte(CURVE_LINEAR) }
				}
				for ((type, keys) in listOf(BONE_TRANSLATE to timelines.translate, BONE_SCALE to timelines.scale)) {
					if (keys == null) continue
					out.byte(type); out.varint(keys.size); out.varint(0)
					keys.forEachIndexed { i, key -> out.float(key.time); out.float(key.x); out.float(key.y); if (i > 0) out.byte(CURVE_LINEAR) }
				}
			}
			out.varint(0); out.varint(0); out.varint(0); out.varint(0) // IK, transform, path and physics timelines
			if (animation.deforms.isEmpty()) out.varint(0)
			else {
				out.varint(1); out.varint(0) // the default skin
				val bySlotDeforms = animation.deforms.groupBy { it.slot }.toSortedMap()
				out.varint(bySlotDeforms.size)
				for ((slot, timelines) in bySlotDeforms) {
					out.varint(slot); out.varint(timelines.size)
					for (timeline in timelines) {
						out.varint(ref(timeline.attachment)); out.byte(ATTACHMENT_DEFORM); out.varint(timeline.keys.size); out.varint(0)
						timeline.keys.forEachIndexed { i, key ->
							if (i == 0) out.float(key.time) else { out.float(key.time); out.byte(CURVE_LINEAR) }
							out.varint(key.vertices.size)
							if (key.vertices.isNotEmpty()) { out.varint(key.offset); key.vertices.forEach(out::float) }
						}
					}
				}
			}
			val drawOrder = animation.drawOrder.orEmpty()
			out.varint(drawOrder.size)
			for (key in drawOrder) {
				out.float(key.time); out.varint(key.offsets.size)
				for ((slot, offset) in key.offsets) { out.varint(slot); out.varint(offset) }
			}
			out.varint(0) // events
		}
		return out.bytes()
	}

	/** Unused vertices a mesh needs so that its triangle count fits the binary layout. */
	private fun padding(mesh: SkAttachment.Mesh): Int {
		val vertices = mesh.vertices.size / 2
		val triangles = mesh.triangles.size / 3
		return maxOf(0, (triangles + 2 - 2 * vertices + 1) / 2)
	}

	private fun attachment(out: Output, attachment: SkAttachment, ref: (String) -> Int) {
		when (attachment) {
			is SkAttachment.Mesh -> {
				val pad = padding(attachment)
				val count = attachment.vertices.size / 2 + pad
				val triangles = attachment.triangles.size / 3
				// The path is always written (it names the atlas region), so flag 16.
				out.byte(TYPE_MESH or 16)
				out.varint(ref(attachment.path))
				out.varint(2 * count - 2 - triangles)
				out.varint(count)
				attachment.vertices.forEach(out::float)
				repeat(pad) { out.float(attachment.vertices[0]); out.float(attachment.vertices[1]) }
				attachment.uvs.forEach(out::float)
				repeat(pad) { out.float(attachment.uvs[0]); out.float(attachment.uvs[1]) }
				attachment.triangles.forEach(out::varint)
			}
			is SkAttachment.Clipping -> {
				out.byte(TYPE_CLIPPING)
				out.varint(attachment.end)
				out.varint(attachment.vertices.size / 2)
				attachment.vertices.forEach(out::float)
			}
		}
	}

	private class Output {
		private val buffer = ByteArrayOutputStream()
		fun bytes(): ByteArray = buffer.toByteArray()
		fun byte(value: Int) = buffer.write(value and 0xff)
		fun boolean(value: Boolean) = byte(if (value) 1 else 0)
		fun int(value: Int) { for (shift in intArrayOf(24, 16, 8, 0)) byte(value ushr shift) }
		fun long(value: Long) { int((value ushr 32).toInt()); int(value.toInt()) }
		fun float(value: Float) { require(value.isFinite()) { "Non-finite number in the skeleton" }; int(java.lang.Float.floatToIntBits(value)) }
		/** Seven bits per byte, low first; negative values take five bytes, as Spine's readInt(true) reads them. */
		fun varint(value: Int) {
			var v = value
			while (true) {
				if (v and 0x7f.inv() == 0) { byte(v); return }
				byte((v and 0x7f) or 0x80)
				v = v ushr 7
			}
		}
		/** UTF-16 units one by one in 1–3 bytes, as Spine's readString decodes them. */
		fun string(value: String) {
			val bytes = ByteArrayOutputStream()
			for (c in value) {
				val code = c.code
				when {
					code <= 0x7f -> bytes.write(code)
					code <= 0x7ff -> { bytes.write(0xc0 or (code shr 6)); bytes.write(0x80 or (code and 0x3f)) }
					else -> { bytes.write(0xe0 or (code shr 12)); bytes.write(0x80 or ((code shr 6) and 0x3f)); bytes.write(0x80 or (code and 0x3f)) }
				}
			}
			varint(bytes.size() + 1)
			buffer.write(bytes.toByteArray())
		}
	}
}
