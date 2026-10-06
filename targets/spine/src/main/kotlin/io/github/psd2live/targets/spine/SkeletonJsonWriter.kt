package io.github.psd2live.targets.spine

/** Writes a [Skeleton] as Spine 4.2 skeleton JSON; values at Spine's defaults are left out. */
internal object SkeletonJsonWriter {
	fun write(skeleton: Skeleton): String {
		val bones = skeleton.bones
		val slots = skeleton.slots
		val root = ArrayList<Pair<String, J>>()
		root += "skeleton" to J.obj("hash" to J.Str(skeleton.hash), "spine" to J.Str(SPINE_VERSION),
			"x" to J.Num(skeleton.x), "y" to J.Num(skeleton.y), "width" to J.Num(skeleton.width), "height" to J.Num(skeleton.height),
			"fps" to J.Num(skeleton.fps), "images" to J.Str(skeleton.images))
		root += "bones" to J.arr(bones.map { bone ->
			J.Obj(buildList {
				add("name" to J.Str(bone.name))
				bone.parent?.let { add("parent" to J.Str(bones[it].name)) }
				if (bone.length != 0f) add("length" to J.Num(bone.length))
				if (bone.x != 0f) add("x" to J.Num(bone.x))
				if (bone.y != 0f) add("y" to J.Num(bone.y))
				if (bone.rotation != 0f) add("rotation" to J.Num(bone.rotation))
				if (bone.scaleX != 1f) add("scaleX" to J.Num(bone.scaleX))
				if (bone.scaleY != 1f) add("scaleY" to J.Num(bone.scaleY))
			})
		})
		root += "slots" to J.arr(slots.map { slot ->
			J.Obj(buildList {
				add("name" to J.Str(slot.name)); add("bone" to J.Str(bones[slot.bone].name))
				slot.attachment?.let { add("attachment" to J.Str(it)) }
				if (slot.color != WHITE) add("color" to J.Str(hex(slot.color)))
				if (slot.blend != SkBlend.NORMAL) add("blend" to J.Str(slot.blend.name.lowercase()))
			})
		})
		if (skeleton.physics.isNotEmpty()) root += "physics" to J.arr(skeleton.physics.map { p ->
			J.obj("name" to J.Str(p.name), "bone" to J.Str(bones[p.bone].name), "rotate" to J.Num(p.rotate), "fps" to J.Num(p.fps),
				"inertia" to J.Num(p.inertia), "strength" to J.Num(p.strength), "damping" to J.Num(p.damping), "mass" to J.Num(p.mass), "mix" to J.Num(p.mix))
		})
		root += "skins" to J.arr(listOf(J.obj("name" to J.Str("default"), "attachments" to J.Obj(
			skeleton.attachments.groupBy { it.slot }.toSortedMap().map { (slot, list) ->
				slots[slot].name to J.Obj(list.map { it.name to attachment(it, slots) })
			}))))
		root += "animations" to J.Obj(skeleton.animations.map { it.name to animation(it, skeleton) })
		return StringBuilder().also { J.Obj(root).write(it) }.toString()
	}

	private fun attachment(attachment: SkAttachment, slots: List<SkSlot>): J = when (attachment) {
		is SkAttachment.Mesh -> J.obj("type" to J.Str("mesh"), "path" to J.Str(attachment.path), "uvs" to J.floats(attachment.uvs),
			"triangles" to J.arr(attachment.triangles.map { J.Num(it) }), "vertices" to J.floats(attachment.vertices))
		is SkAttachment.Clipping -> J.obj("type" to J.Str("clipping"), "end" to J.Str(slots[attachment.end].name),
			"vertexCount" to J.Num(attachment.vertices.size / 2), "vertices" to J.floats(attachment.vertices))
	}

	private fun animation(animation: SkAnimation, skeleton: Skeleton): J {
		val entries = ArrayList<Pair<String, J>>()
		if (animation.slotColors.isNotEmpty()) entries += "slots" to J.Obj(animation.slotColors.map { (slot, keys) ->
			skeleton.slots[slot].name to J.obj("rgba" to J.arr(keys.map { J.obj("time" to J.Num(it.time), "color" to J.Str(hex(it.color))) }))
		})
		if (animation.bones.isNotEmpty()) entries += "bones" to J.Obj(animation.bones.map { timelines ->
			skeleton.bones[timelines.bone].name to J.Obj(buildList {
				timelines.rotate?.let { keys -> add("rotate" to J.arr(keys.map { J.obj("time" to J.Num(it.time), "value" to J.Num(it.value)) })) }
				timelines.translate?.let { keys -> add("translate" to J.arr(keys.map { J.obj("time" to J.Num(it.time), "x" to J.Num(it.x), "y" to J.Num(it.y)) })) }
				timelines.scale?.let { keys -> add("scale" to J.arr(keys.map { J.obj("time" to J.Num(it.time), "x" to J.Num(it.x), "y" to J.Num(it.y)) })) }
			})
		})
		if (animation.deforms.isNotEmpty()) entries += "attachments" to J.obj("default" to J.Obj(
			animation.deforms.groupBy { it.slot }.toSortedMap().map { (slot, timelines) ->
				skeleton.slots[slot].name to J.Obj(timelines.map { timeline ->
					timeline.attachment to J.obj("deform" to J.arr(timeline.keys.map { key ->
						if (key.vertices.isEmpty()) J.obj("time" to J.Num(key.time))
						else J.obj("time" to J.Num(key.time), "offset" to J.Num(key.offset), "vertices" to J.floats(key.vertices))
					}))
				})
			}))
		animation.drawOrder?.let { keys ->
			entries += "drawOrder" to J.arr(keys.map { key ->
				if (key.offsets.isEmpty()) J.obj("time" to J.Num(key.time))
				else J.obj("time" to J.Num(key.time), "offsets" to J.arr(key.offsets.map { (slot, offset) ->
					J.obj("slot" to J.Str(skeleton.slots[slot].name), "offset" to J.Num(offset))
				}))
			})
		}
		return J.Obj(entries)
	}

	private fun hex(rgba: Int) = "%08x".format(rgba)

	const val SPINE_VERSION = "4.2.0"
	const val WHITE = -1
}
