package io.github.psd2live.targets.spine

import kotlin.math.cos
import kotlin.math.sin

/**
 * Poses a parsed Spine skeleton JSON the way a Spine 4.2 runtime does, written from the format's documented
 * semantics for these tests: animations applied on additive tracks over the setup pose (rotate and translate
 * keys add their value, scale keys add (value - 1) times the setup scale, unweighted deform keys add their
 * offsets), linear interpolation between keys, bones composed parent first under normal inheritance.
 */
@Suppress("UNCHECKED_CAST")
public class SpinePlayer(private val root: Map<String, Any?>) {
	private class Bone(val name: String, val parent: Int?, val x: Double, val y: Double, val rotation: Double, val scaleX: Double, val scaleY: Double)

	private val bones: List<Bone>
	private val boneIndex: Map<String, Int>

	init {
		val list = root["bones"] as List<Map<String, Any?>>
		val names = HashMap<String, Int>()
		bones = list.mapIndexed { i, b ->
			names[b["name"] as String] = i
			Bone(b["name"] as String, (b["parent"] as String?)?.let { names.getValue(it) }, num(b, "x", 0.0), num(b, "y", 0.0),
				num(b, "rotation", 0.0), num(b, "scaleX", 1.0), num(b, "scaleY", 1.0))
		}
		boneIndex = names
	}

	private fun num(map: Map<*, *>, key: String, default: Double) = (map[key] as Number?)?.toDouble() ?: default

	/** Linear interpolation of a keyed value; null before the first key (an additive track then adds nothing). */
	private fun sample(keys: List<Map<String, Any?>>, time: Double, value: (Map<String, Any?>) -> DoubleArray): DoubleArray? {
		val t0 = num(keys.first(), "time", 0.0)
		if (time < t0) return null
		val after = keys.indexOfFirst { num(it, "time", 0.0) > time }
		if (after < 0) return value(keys.last())
		val a = keys[after - 1]; val b = keys[after]
		val ta = num(a, "time", 0.0); val tb = num(b, "time", 0.0)
		val u = (time - ta) / (tb - ta)
		val va = value(a); val vb = value(b)
		return DoubleArray(va.size) { va[it] + (vb[it] - va[it]) * u }
	}

	/** A parameter that keys nothing has no animation. */
	private fun animation(name: String) = (root["animations"] as Map<String, Any?>)[name] as Map<String, Any?>?

	/** World vertices (skeleton space) of every mesh slot with [tracks] (animation name, time) applied additively. */
	public fun pose(tracks: List<Pair<String, Double>>): Map<String, DoubleArray> {
		val local = bones.map { doubleArrayOf(it.x, it.y, it.rotation, it.scaleX, it.scaleY) }
		for ((name, time) in tracks) {
			val bonesMap = animation(name)?.get("bones") as Map<String, Map<String, Any?>>? ?: continue
			for ((boneName, timelines) in bonesMap) {
				val i = boneIndex.getValue(boneName); val b = bones[i]
				(timelines["rotate"] as List<Map<String, Any?>>?)?.let { keys -> sample(keys, time) { doubleArrayOf(num(it, "value", 0.0)) }?.let { local[i][2] += it[0] } }
				(timelines["translate"] as List<Map<String, Any?>>?)?.let { keys ->
					sample(keys, time) { doubleArrayOf(num(it, "x", 0.0), num(it, "y", 0.0)) }?.let { local[i][0] += it[0]; local[i][1] += it[1] }
				}
				(timelines["scale"] as List<Map<String, Any?>>?)?.let { keys ->
					sample(keys, time) { doubleArrayOf(num(it, "x", 1.0), num(it, "y", 1.0)) }?.let {
						local[i][3] += (it[0] - 1) * b.scaleX; local[i][4] += (it[1] - 1) * b.scaleY
					}
				}
			}
		}
		val world = ArrayList<DoubleArray>()
		for ((i, bone) in bones.withIndex()) {
			val (x, y, rotation, sx, sy) = local[i].toList()
			val r = Math.toRadians(rotation)
			val m = doubleArrayOf(cos(r) * sx, -sin(r) * sy, sin(r) * sx, cos(r) * sy, x, y)
			world += bone.parent?.let { compose(world[it], m) } ?: m
		}
		val skins = root["skins"] as List<Map<String, Any?>>
		val attachments = skins.single()["attachments"] as Map<String, Map<String, Map<String, Any?>>>
		val out = LinkedHashMap<String, DoubleArray>()
		for (slot in root["slots"] as List<Map<String, Any?>>) {
			val name = slot["name"] as String
			val attachment = attachments[name]?.get(slot["attachment"]) ?: continue
			if (attachment["type"] != "mesh" && attachment["type"] != "clipping") continue
			val vertices = (attachment["vertices"] as List<Number>).map { it.toDouble() }.toDoubleArray()
			for ((animationName, time) in tracks) {
				val deform = (((animation(animationName)?.get("attachments") as Map<String, Any?>?)?.get("default") as Map<String, Any?>?)
					?.get(name) as Map<String, Any?>?)?.get(slot["attachment"]) as Map<String, Any?>? ?: continue
				val keys = deform["deform"] as List<Map<String, Any?>>
				sample(keys, time) { key ->
					val values = DoubleArray(vertices.size)
					val start = num(key, "offset", 0.0).toInt()
					(key["vertices"] as List<Number>?)?.forEachIndexed { k, v -> values[start + k] = v.toDouble() }
					values
				}?.let { d -> for (k in vertices.indices) vertices[k] += d[k] }
			}
			val m = world[boneIndex.getValue(slot["bone"] as String)]
			out[name] = DoubleArray(vertices.size) { k ->
				if (k % 2 == 0) m[0] * vertices[k] + m[1] * vertices[k + 1] + m[4] else m[2] * vertices[k - 1] + m[3] * vertices[k] + m[5]
			}
		}
		return out
	}

	private fun compose(p: DoubleArray, l: DoubleArray) = doubleArrayOf(
		p[0] * l[0] + p[1] * l[2], p[0] * l[1] + p[1] * l[3], p[2] * l[0] + p[3] * l[2], p[2] * l[1] + p[3] * l[3],
		p[0] * l[4] + p[1] * l[5] + p[4], p[2] * l[4] + p[3] * l[5] + p[5])
}
