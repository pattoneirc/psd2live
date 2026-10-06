package io.github.psd2live.targets.spine

import java.io.DataInputStream

/**
 * A minimal Spine 4.2 binary skeleton reader for the tests, written from the public format layout. It reads
 * the subset the exporter writes (no nonessential data, no IK, transform or path constraints, no events) and
 * returns the same map structure the JSON has, so both encodings can be posed and compared alike.
 */
public class MiniSkel(bytes: ByteArray) {
	private val input = DataInputStream(bytes.inputStream())
	private var strings: List<String> = emptyList()

	private fun byte() = input.readUnsignedByte()
	private fun int() = input.readInt()
	private fun float() = input.readFloat().toDouble()
	private fun varint(): Int {
		var result = 0
		for (shift in 0 until 35 step 7) {
			val b = byte()
			result = result or ((b and 0x7f) shl shift)
			if (b and 0x80 == 0) return result
		}
		error("Varint too long")
	}
	private fun string(): String? {
		val count = varint()
		if (count == 0) return null
		val out = StringBuilder()
		var i = 0
		while (i < count - 1) {
			val b = byte()
			when (b shr 4) {
				12, 13 -> { out.append(((b and 0x1f) shl 6 or (byte() and 0x3f)).toChar()); i += 2 }
				14 -> { out.append(((b and 0x0f) shl 12 or ((byte() and 0x3f) shl 6) or (byte() and 0x3f)).toChar()); i += 3 }
				else -> { out.append(b.toChar()); i++ }
			}
		}
		return out.toString()
	}
	private fun ref(): String? = varint().let { if (it == 0) null else strings[it - 1] }
	private fun hex(rgba: Int) = "%08x".format(rgba)
	private fun floats(n: Int) = List(n) { float() }

	public fun read(): Map<String, Any?> {
		val root = LinkedHashMap<String, Any?>()
		val hash = input.readLong()
		val skeleton = linkedMapOf<String, Any?>("hash" to "%016x".format(hash), "spine" to string(),
			"x" to float(), "y" to float(), "width" to float(), "height" to float(), "referenceScale" to float())
		check(byte() == 0) { "Nonessential data is not expected" }
		root["skeleton"] = skeleton
		strings = List(varint()) { string()!! }
		val bones = ArrayList<Map<String, Any?>>()
		repeat(varint()) { i ->
			val name = string()!!
			val parent = if (i == 0) null else bones[varint()]["name"]
			val rotation = float(); val x = float(); val y = float(); val scaleX = float(); val scaleY = float()
			val shearX = float(); val shearY = float(); val length = float()
			check(byte() == 0) { "Inherit other than normal" }
			check(byte() == 0) { "Skin-required bone" }
			check(shearX == 0.0 && shearY == 0.0)
			bones += linkedMapOf("name" to name, "parent" to parent, "x" to x, "y" to y, "rotation" to rotation, "scaleX" to scaleX, "scaleY" to scaleY, "length" to length)
		}
		root["bones"] = bones
		val slots = ArrayList<Map<String, Any?>>()
		repeat(varint()) {
			val name = string()!!
			val bone = bones[varint()]["name"]
			val color = hex(int())
			check(int() == -1) { "Dark color" }
			slots += linkedMapOf("name" to name, "bone" to bone, "color" to color, "attachment" to ref(),
				"blend" to listOf("normal", "additive", "multiply", "screen")[varint()])
		}
		root["slots"] = slots
		repeat(3) { check(varint() == 0) { "IK, transform or path constraints" } }
		root["physics"] = List(varint()) {
			val p = linkedMapOf<String, Any?>("name" to string(), "order" to varint(), "bone" to bones[varint()]["name"])
			val flags = byte()
			for ((bit, key) in listOf(2 to "x", 4 to "y", 8 to "rotate", 16 to "scaleX", 32 to "shearX", 64 to "limit")) if (flags and bit != 0) p[key] = float()
			p["fps"] = byte()
			p["inertia"] = float(); p["strength"] = float(); p["damping"] = float()
			p["mass"] = if (flags and 128 != 0) 1 / float() else 1.0
			p["wind"] = float(); p["gravity"] = float()
			val global = byte()
			check(global and 127 == 0) { "Global physics flags" }
			p["mix"] = if (global and 128 != 0) float() else 1.0
			p
		}
		val attachments = LinkedHashMap<String, Any?>()
		val vertexCounts = HashMap<Pair<Int, String>, Int>()
		repeat(varint()) {
			val slot = varint()
			val entries = LinkedHashMap<String, Any?>()
			repeat(varint()) {
				val key = ref()!!
				val flags = byte()
				val name = if (flags and 8 != 0) ref()!! else key
				entries[name] = when (flags and 7) {
					2 -> {
						val path = if (flags and 16 != 0) ref() else name
						check(flags and (32 or 64 or 128) == 0) { "Mesh color, sequence or weights" }
						val hull = varint()
						val count = varint()
						vertexCounts[slot to name] = count
						val vertices = floats(count * 2)
						val uvs = floats(count * 2)
						val triangles = List((2 * count - hull - 2) * 3) { varint().toDouble() }
						linkedMapOf("type" to "mesh", "path" to path, "uvs" to uvs, "triangles" to triangles, "vertices" to vertices, "hull" to hull)
					}
					6 -> {
						val end = slots[varint()]["name"]
						check(flags and 16 == 0) { "Weighted clipping" }
						val count = varint()
						linkedMapOf("type" to "clipping", "end" to end, "vertexCount" to count.toDouble(), "vertices" to floats(count * 2))
					}
					else -> error("Attachment type ${flags and 7}")
				}
			}
			attachments[slots[slot]["name"] as String] = entries
		}
		root["skins"] = listOf(linkedMapOf("name" to "default", "attachments" to attachments))
		check(varint() == 0) { "Other skins" }
		check(varint() == 0) { "Events" }
		val animations = LinkedHashMap<String, Any?>()
		repeat(varint()) {
			val name = string()!!
			val animation = LinkedHashMap<String, Any?>()
			val timelines = varint()
			var counted = 0
			val slotTimelines = LinkedHashMap<String, Any?>()
			repeat(varint()) {
				val slot = slots[varint()]["name"] as String
				repeat(varint()) {
					check(byte() == 1) { "Only RGBA slot timelines are expected" }
					val frames = varint(); check(varint() == 0) { "Bezier curves" }
					slotTimelines[slot] = mapOf("rgba" to List(frames) { i ->
						val time = float(); val color = hex(int())
						if (i > 0) check(byte() == 0) { "Linear curve" }
						mapOf("time" to time, "color" to color)
					})
					counted++
				}
			}
			if (slotTimelines.isNotEmpty()) animation["slots"] = slotTimelines
			val boneTimelines = LinkedHashMap<String, Any?>()
			repeat(varint()) {
				val bone = bones[varint()]["name"] as String
				val entry = LinkedHashMap<String, Any?>()
				repeat(varint()) {
					val type = byte(); val frames = varint(); check(varint() == 0) { "Bezier curves" }
					entry[when (type) { 0 -> "rotate"; 1 -> "translate"; 4 -> "scale"; else -> error("Bone timeline $type") }] = List(frames) { i ->
						val key = if (type == 0) mapOf("time" to float(), "value" to float()) else mapOf("time" to float(), "x" to float(), "y" to float())
						if (i > 0) check(byte() == 0) { "Linear curve" }
						key
					}
					counted++
				}
				boneTimelines[bone] = entry
			}
			if (boneTimelines.isNotEmpty()) animation["bones"] = boneTimelines
			repeat(4) { check(varint() == 0) { "Constraint timelines" } }
			val deforms = LinkedHashMap<String, Any?>()
			repeat(varint()) {
				check(varint() == 0) { "Default skin only" }
				repeat(varint()) {
					val slotIndex = varint()
					val slot = slots[slotIndex]["name"] as String
					val entry = LinkedHashMap<String, Any?>()
					repeat(varint()) {
						val attachment = ref()!!
						check(byte() == 0) { "Deform timeline" }
						val frames = varint(); check(varint() == 0) { "Bezier curves" }
						var time = float()
						entry[attachment] = mapOf("deform" to List(frames) { i ->
							if (i > 0) { time = float(); check(byte() == 0) { "Linear curve" } }
							val end = varint()
							if (end == 0) mapOf("time" to time)
							else mapOf("time" to time, "offset" to varint().toDouble(), "vertices" to floats(end))
						})
						counted++
					}
					deforms[slot] = entry
				}
			}
			if (deforms.isNotEmpty()) animation["attachments"] = mapOf("default" to deforms)
			val drawOrder = List(varint()) {
				val time = float()
				val offsets = List(varint()) { mapOf("slot" to slots[varint()]["name"], "offset" to varint().toDouble()) }
				if (offsets.isEmpty()) mapOf("time" to time) else mapOf("time" to time, "offsets" to offsets)
			}
			if (drawOrder.isNotEmpty()) { animation["drawOrder"] = drawOrder; counted++ }
			check(varint() == 0) { "Events" }
			check(counted == timelines) { "Timeline count $timelines, read $counted" }
			animations[name] = animation
		}
		root["animations"] = animations
		check(input.read() == -1) { "Trailing bytes" }
		return root
	}
}
