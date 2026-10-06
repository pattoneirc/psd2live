package io.github.psd2live.targets.spine

/**
 * The skeleton an export writes, independent of its encoding: [SkeletonJsonWriter] and [SkeletonBinaryWriter]
 * write the same data. Colors are RGBA8888; indices refer to [Skeleton.bones] and [Skeleton.slots].
 */
internal class Skeleton(
	val hash: String, val x: Float, val y: Float, val width: Float, val height: Float, val fps: Float, val images: String,
	val bones: List<SkBone>, val slots: List<SkSlot>, val physics: List<SkPhysics>,
	/** Default skin attachments: one per slot that has one, named like the slot. */
	val attachments: List<SkAttachment>,
	val animations: List<SkAnimation>,
)

/** A bone; [parent] is null only for the root, which comes first. Parents precede children. */
internal class SkBone(
	val name: String, val parent: Int?, val x: Float, val y: Float, val rotation: Float, val scaleX: Float, val scaleY: Float, val length: Float,
)

internal enum class SkBlend { NORMAL, ADDITIVE, MULTIPLY, SCREEN }

internal class SkSlot(val name: String, val bone: Int, val color: Int, val attachment: String?, val blend: SkBlend)

internal sealed interface SkAttachment {
	val slot: Int
	val name: String

	/** An unweighted mesh: [vertices] in its slot bone's space. */
	class Mesh(override val slot: Int, override val name: String, val path: String, val uvs: FloatArray, val triangles: IntArray, val vertices: FloatArray) : SkAttachment
	class Clipping(override val slot: Int, override val name: String, val end: Int, val vertices: FloatArray) : SkAttachment
}

internal class SkPhysics(
	val name: String, val bone: Int, val rotate: Float, val fps: Int,
	val inertia: Float, val strength: Float, val damping: Float, val mass: Float, val mix: Float,
)

/** A key of a one-value bone timeline (rotate). */
internal class Key1(val time: Float, val value: Float)
/** A key of a two-value bone timeline (translate, scale). */
internal class Key2(val time: Float, val x: Float, val y: Float)

internal class BoneTimelines(val bone: Int, val rotate: List<Key1>?, val translate: List<Key2>?, val scale: List<Key2>?) {
	val count: Int get() = listOfNotNull(rotate, translate, scale).size
}

internal class SlotColorKey(val time: Float, val color: Int)

/** Offsets from the setup vertices starting at float [offset]; empty means the setup pose. */
internal class DeformKey(val time: Float, val offset: Int, val vertices: FloatArray)

internal class DeformTimeline(val slot: Int, val attachment: String, val keys: List<DeformKey>)

/** Slots that move from their setup index, by Spine's offset algorithm; sorted by slot index. */
internal class DrawOrderKey(val time: Float, val offsets: List<Pair<Int, Int>>)

internal class SkAnimation(
	val name: String,
	val slotColors: List<Pair<Int, List<SlotColorKey>>>,
	val bones: List<BoneTimelines>,
	val deforms: List<DeformTimeline>,
	val drawOrder: List<DrawOrderKey>?,
)
